package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;

/**
 * Chat mode: Claude Code conversations as chat, not a terminal. The machine's Paseo daemon runs them (see
 * PaseoClient); this screen lists them, starts new ones and shows one as messages, rendered on the phone so
 * scrolling never waits for the network. Runs of tool calls fold into one line, as in Paseo's and Orca's apps.
 */
final class Chat {
    interface Host {
        TermService service();

        /** Opens a file on the machine in the preview. */
        void openFile(String path);
    }

    private final Activity a;
    private final Theme t;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private FrameLayout page;
    private TextView banner;
    private PaseoClient client;
    private String home = "";
    private int attempt;
    private Markwon markwon;

    /** The chat on screen, or null for the list. */
    private Conversation conv;
    private ListPage list;

    Chat(Activity a, Theme t, Host host) {
        this.a = a;
        this.t = t;
        this.host = host;
    }

    // ---- window and connection ----

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new Dialog(a, t.dark ? android.R.style.Theme_DeviceDefault_NoActionBar
            : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = Ui.column(a);
        root.setBackgroundColor(t.bg);
        banner = Ui.text(a, "", 12.5f, t.amber, Ui.MEDIUM);
        banner.setPadding(dp(16), dp(7), dp(16), dp(7));
        banner.setBackgroundColor(t.amberBg);
        banner.setVisibility(View.GONE);
        page = new FrameLayout(a);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(banner, 0, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(t.bg));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.setStatusBarColor(t.bg);
        w.setNavigationBarColor(t.bg);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        if (!t.dark) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        dialog.setOnKeyListener((d, code, e) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        });
        dialog.setOnDismissListener(d -> {
            stopVoice();
            main.removeCallbacksAndMessages(null);
            if (client != null) client.close();
            client = null;
            conv = null;
            list = null;
        });
        list = new ListPage();
        setPage(list.view);
        dialog.show();
        connect();
    }

    private void back() {
        if (conv != null) {
            stopVoice();
            conv = null;
            if (client != null && client.ready()) subscribe(new String[0]);
            setPage(list.view);
            list.load();
        } else {
            dialog.dismiss();
        }
    }

    private void setPage(View v) {
        page.removeAllViews();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        page.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void connect() {
        TermService s = host.service();
        if (dialog == null || !dialog.isShowing()) return;
        if (s == null || s.state() != TermService.State.CONNECTED) {
            showBanner(a.getString(R.string.chat_reconnecting));
            retryLater();
            return;
        }
        showBanner(a.getString(R.string.chat_connecting));
        s.paseo((ep, err) -> {
            if (dialog == null || !dialog.isShowing()) return;
            if (err != null) {
                if (err.equals("no_paseo") || err.equals("not_running")) {
                    hideBanner();
                    list.setNote(a.getString(err.equals("no_paseo") ? R.string.chat_no_paseo : R.string.chat_not_running));
                } else {
                    showBanner(a.getString(R.string.chat_failed, err));
                }
                retryLater();
                return;
            }
            home = ep.length > 2 ? ep[2] : "";
            client = new PaseoClient(Integer.parseInt(ep[0]), ep[1], new PaseoClient.Events() {
                @Override
                public void onReady() {
                    attempt = 0;
                    hideBanner();
                    if (conv != null) conv.load();
                    else list.load();
                }

                @Override
                public void onClosed(String why) {
                    Diag.w("Chat", "paseo closed: " + why);
                    client = null;
                    if (dialog == null || !dialog.isShowing()) return;
                    showBanner(a.getString(R.string.chat_reconnecting) + "  (" + why + ")");
                    retryLater();
                }

                @Override
                public void onMessage(JSONObject m) {
                    route(m);
                }
            });
        });
    }

    private void retryLater() {
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, Math.min(10_000, 1500 + 1500L * attempt++));
    }

    private final Runnable reconnect = this::connect;

    private void showBanner(String s) {
        banner.setText(s);
        banner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        banner.setVisibility(View.GONE);
    }

    private void route(JSONObject m) {
        String type = m.optString("type");
        JSONObject p = m.optJSONObject("payload");
        if (p == null) return;
        if (type.equals("agent_update") && list != null) list.onUpdate(p);
        if (conv == null) return;
        if (type.equals("agent_stream") && conv.id.equals(p.optString("agentId"))) conv.onStream(p);
        else if (type.equals("agent_update") && p.optJSONObject("agent") != null
            && conv.id.equals(p.optJSONObject("agent").optString("id"))) conv.onAgent(p.optJSONObject("agent"));
        else if (type.startsWith("agent.timeline.replacement") && conv.id.equals(p.optString("agentId"))) conv.load();
    }

    private void subscribe(String[] ids) {
        if (client == null) return;
        try {
            JSONObject m = PaseoClient.msg("agent.timeline.set_subscription.request");
            m.put("agentIds", new JSONArray(java.util.Arrays.asList(ids)));
            client.request(m, null);
        } catch (Exception ignored) {
        }
    }

    // ---- the list of chats ----

    private final class ListPage {
        final LinearLayout view = Ui.column(a);
        final List<JSONObject> agents = new ArrayList<>();
        final ListView lv = new ListView(a);
        final TextView note;
        final BaseAdapter adapter;

        ListPage() {
            view.addView(header(a.getString(R.string.chat_title), "", () -> dialog.dismiss(),
                headerButton(a.getString(R.string.chat_new), true, Chat.this::newChat)), new LinearLayout.LayoutParams(-1, dp(54)));
            note = Ui.text(a, a.getString(R.string.chat_loading), 14, t.faint, Ui.REGULAR);
            note.setLineSpacing(dp(3), 1f);
            note.setPadding(dp(20), dp(24), dp(20), dp(24));
            note.setTextIsSelectable(true);
            view.addView(note, new LinearLayout.LayoutParams(-1, -2));
            lv.setDivider(null);
            lv.setSelector(new ColorDrawable(0));
            lv.setClipToPadding(false);
            lv.setPadding(0, dp(4), 0, dp(40));
            adapter = new BaseAdapter() {
                @Override public int getCount() { return agents.size(); }
                @Override public Object getItem(int i) { return agents.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View cv, ViewGroup parent) { return row(agents.get(i)); }
            };
            lv.setAdapter(adapter);
            view.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        void setNote(String s) {
            note.setText(s);
            note.setVisibility(View.VISIBLE);
        }

        void load() {
            if (client == null || !client.ready()) return;
            try {
                JSONObject m = PaseoClient.msg("fetch_agents_request");
                m.put("sort", new JSONArray().put(new JSONObject().put("key", "updated_at").put("direction", "desc")));
                m.put("page", new JSONObject().put("limit", 60));
                m.put("subscribe", new JSONObject());
                client.request(m, (p, err) -> {
                    if (err != null || p == null) {
                        setNote(a.getString(R.string.chat_failed, err));
                        return;
                    }
                    agents.clear();
                    JSONArray e = p.optJSONArray("entries");
                    for (int i = 0; e != null && i < e.length(); i++) {
                        JSONObject ag = e.optJSONObject(i).optJSONObject("agent");
                        if (ag != null && ag.isNull("archivedAt")) agents.add(withProject(ag, e.optJSONObject(i)));
                    }
                    refresh();
                });
            } catch (Exception ignored) {
            }
        }

        private JSONObject withProject(JSONObject ag, JSONObject entry) {
            JSONObject pr = entry == null ? null : entry.optJSONObject("project");
            if (pr != null) try {
                ag.put("_workspace", pr.optString("workspaceName"));
            } catch (Exception ignored) {
            }
            return ag;
        }

        void refresh() {
            if (agents.isEmpty()) setNote(a.getString(R.string.chat_empty));
            else note.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        }

        void onUpdate(JSONObject p) {
            String kind = p.optString("kind");
            String id = kind.equals("remove") ? p.optString("agentId") : p.optJSONObject("agent") == null ? "" : p.optJSONObject("agent").optString("id");
            int at = -1;
            for (int i = 0; i < agents.size(); i++) if (agents.get(i).optString("id").equals(id)) at = i;
            if (kind.equals("upsert")) {
                JSONObject ag = p.optJSONObject("agent");
                if (ag.optBoolean("internal") || !ag.isNull("archivedAt")) {
                    if (at >= 0) agents.remove(at);
                } else {
                    if (at >= 0) {
                        String ws = agents.get(at).optString("_workspace");
                        agents.remove(at);
                        try {
                            if (!ws.isEmpty()) ag.put("_workspace", ws);
                        } catch (Exception ignored) {
                        }
                    }
                    agents.add(0, withProject(ag, p));
                }
            } else if (at >= 0) {
                agents.remove(at);
            }
            refresh();
        }

        View row(JSONObject ag) {
            LinearLayout r = Ui.column(a);
            r.setPadding(dp(18), dp(12), dp(18), dp(12));
            LinearLayout top = Ui.row(a);
            String state = ag.optString("status");
            JSONArray perms = ag.optJSONArray("pendingPermissions");
            boolean waiting = perms != null && perms.length() > 0;
            boolean running = state.equals("running") || state.equals("initializing");
            top.addView(Ui.dot(a, waiting ? t.amber : running ? t.accent : t.line, 8));
            TextView title = Ui.oneLine(Ui.text(a, titleOf(ag), 15.5f, t.text, Ui.MEDIUM), TextUtils.TruncateAt.END);
            title.setPadding(dp(10), 0, dp(8), 0);
            top.addView(title, Ui.weight(1));
            top.addView(Ui.text(a, ago(ag.optString("updatedAt")), 12, t.faint, Ui.REGULAR));
            r.addView(top);
            String sub = shortPath(ag.optString("cwd"));
            if (waiting) sub = a.getString(R.string.chat_waiting) + " · " + sub;
            else if (running) sub = a.getString(R.string.chat_running) + " · " + sub;
            TextView s = Ui.oneLine(Ui.monoText(a, sub, 11.5f, waiting ? t.amber : t.faint), TextUtils.TruncateAt.START);
            s.setPadding(dp(18), dp(5), 0, 0);
            r.addView(s);
            r.setBackground(Ui.pressable(t, null, 0));
            r.setOnClickListener(v -> openChat(ag));
            r.setOnLongClickListener(v -> {
                confirmArchive(ag);
                return true;
            });
            r.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return r;
        }
    }

    private String titleOf(JSONObject ag) {
        String title = ag.isNull("title") ? "" : ag.optString("title");
        if (title.isEmpty()) title = ag.optString("_workspace");
        return title.isEmpty() ? a.getString(R.string.chat_untitled) : title;
    }

    private void confirmArchive(JSONObject ag) {
        LinearLayout box = Ui.column(a);
        TextView q = Ui.text(a, a.getString(R.string.chat_archive_q), 18, t.text, Ui.SEMIBOLD);
        q.setPadding(0, 0, 0, dp(6));
        box.addView(q);
        TextView name = Ui.text(a, titleOf(ag), 14, t.sub, Ui.REGULAR);
        name.setPadding(0, 0, 0, dp(16));
        box.addView(name);
        Dialog d = Ui.bottomSheet(a, t, box);
        TextView go = Ui.primaryButton(a, t, a.getString(R.string.chat_archive), v -> {
            d.dismiss();
            if (client == null) return;
            try {
                JSONObject m = PaseoClient.msg("archive_agent_request").put("agentId", ag.optString("id"));
                client.request(m, (p, err) -> {
                    if (err != null) Toast.makeText(a, a.getString(R.string.chat_failed, err), Toast.LENGTH_SHORT).show();
                    else if (list != null) list.load();
                });
            } catch (Exception ignored) {
            }
        });
        box.addView(go, new LinearLayout.LayoutParams(-1, dp(50)));
        d.show();
    }

    // ---- starting a chat ----

    private void newChat() {
        if (client == null || !client.ready()) return;
        LinearLayout box = Ui.column(a);
        TextView title = Ui.text(a, a.getString(R.string.chat_new), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(12));
        box.addView(title);
        String[] dir = {Prefs.raw(a).getString("chat_dir", "~")};
        LinearLayout dirRow = Ui.row(a);
        dirRow.setPadding(dp(14), 0, dp(10), 0);
        dirRow.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(12)), dp(12)));
        dirRow.addView(Ui.icon(a, R.drawable.ic_folder, t.accent, 18));
        TextView dirText = Ui.oneLine(Ui.monoText(a, dir[0], 13.5f, t.text), TextUtils.TruncateAt.START);
        dirText.setPadding(dp(10), 0, dp(8), 0);
        dirRow.addView(dirText, Ui.weight(1));
        dirRow.addView(Ui.icon(a, R.drawable.ic_chevron_right, t.faint, 16));
        dirRow.setOnClickListener(v -> {
            TermService s = host.service();
            if (s == null) return;
            new DirPicker(a, t, s::dirs, recentDirs(), dir[0], picked -> {
                dir[0] = picked;
                dirText.setText(picked);
            }).show();
        });
        box.addView(dirRow, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText text = NewSessionSheet.field(a, t, "", a.getString(R.string.chat_first_hint));
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(3);
        text.setMaxLines(8);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        box.addView(text, tlp);
        Dialog d = Ui.bottomSheet(a, t, box);
        LinearLayout buttons = Ui.row(a);
        View mic = micButton(text);
        buttons.addView(mic, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView go = Ui.primaryButton(a, t, a.getString(R.string.chat_start), v -> {
            String prompt = text.getText().toString().trim();
            if (prompt.isEmpty()) {
                text.setError(a.getString(R.string.chat_first_hint));
                return;
            }
            stopVoice();
            d.dismiss();
            Prefs.put(a, "chat_dir", dir[0]);
            rememberDir(dir[0]);
            start(dir[0], prompt);
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        glp.leftMargin = dp(10);
        buttons.addView(go, glp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(14);
        box.addView(buttons, blp);
        d.setOnDismissListener(x -> stopVoice());
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        text.requestFocus();
    }

    private List<String> recentDirs() {
        List<String> out = new ArrayList<>();
        for (String s : Prefs.raw(a).getString("chat_dirs", "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void rememberDir(String d) {
        List<String> r = recentDirs();
        r.remove(d);
        r.add(0, d);
        while (r.size() > 5) r.remove(r.size() - 1);
        Prefs.put(a, "chat_dirs", TextUtils.join("\n", r));
    }

    private String absolute(String dir) {
        if (dir.equals("~")) return home.isEmpty() ? dir : home;
        if (dir.startsWith("~/") && !home.isEmpty()) return home + dir.substring(1);
        return dir;
    }

    private void start(String dir, String prompt) {
        if (client == null) return;
        String clientId = UUID.randomUUID().toString();
        try {
            JSONObject cfg = new JSONObject().put("provider", "claude").put("cwd", absolute(dir)).put("modeId", "auto");
            JSONObject m = PaseoClient.msg("create_agent_request").put("config", cfg).put("initialPrompt", prompt)
                .put("clientMessageId", clientId).put("labels", new JSONObject());
            showBanner(a.getString(R.string.chat_starting));
            client.request(m, (p, err) -> {
                hideBanner();
                JSONObject ag = p == null ? null : p.optJSONObject("agent");
                if (err != null || ag == null) {
                    Toast.makeText(a, a.getString(R.string.chat_failed, err != null ? err : "?"), Toast.LENGTH_LONG).show();
                    return;
                }
                openChat(ag);
            });
        } catch (Exception ignored) {
        }
    }

    private void openChat(JSONObject ag) {
        conv = new Conversation(ag);
        setPage(conv.view);
        conv.load();
    }

    // ---- one chat ----

    private static final int USER = 0, TEXT = 1, TOOLS = 3, NOTE = 4, TODO = 5, WORKING = 6;

    /** A row on screen: one item, or a run of tool calls. version changes when what it shows changes. */
    private static final class Row {
        int kind;
        String key;
        long version;
        final List<JSONObject> items = new ArrayList<>();
    }

    private final class Conversation {
        final String id;
        JSONObject agent;
        final LinearLayout view = Ui.column(a);
        final TextView title, subtitle;
        final ListView lv = new ListView(a);
        final LinearLayout permBox = Ui.column(a);
        final EditText input;
        final FrameLayout sendBtn;
        final android.widget.ImageView sendIcon;

        /** Timeline items in order; keyed ones (messages, tool calls) are updated in place. */
        final List<JSONObject> items = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        final Map<JSONObject, Long> versions = new HashMap<>();
        final List<Row> rows = new ArrayList<>();
        final Map<String, View> views = new HashMap<>();
        final Map<String, Long> viewVersions = new HashMap<>();
        final java.util.Set<String> expanded = new java.util.HashSet<>();
        final Map<String, JSONObject> permissions = new LinkedHashMap<>();
        boolean running, loading, hasOlder;
        String epoch;
        long lastSeq;
        /** Stream events that arrive while the timeline loads, applied after it. */
        List<JSONObject> early;
        long tick;

        Conversation(JSONObject ag) {
            id = ag.optString("id");
            agent = ag;
            LinearLayout bar = header(titleOf(ag), shortPath(ag.optString("cwd")), Chat.this::back);
            title = (TextView) ((LinearLayout) bar.getChildAt(1)).getChildAt(0);
            subtitle = (TextView) ((LinearLayout) bar.getChildAt(1)).getChildAt(1);
            view.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));
            lv.setDivider(null);
            lv.setSelector(new ColorDrawable(0));
            lv.setClipToPadding(false);
            lv.setPadding(0, dp(8), 0, dp(12));
            lv.setStackFromBottom(true);
            lv.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
            lv.setAdapter(adapter);
            view.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
            // Cards above the input; a long question scrolls instead of pushing the chat away
            android.widget.ScrollView permScroll = new android.widget.ScrollView(a) {
                @Override
                protected void onMeasure(int w, int h) {
                    int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            permScroll.addView(permBox);
            view.addView(permScroll, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout bar2 = Ui.row(a);
            bar2.setGravity(Gravity.BOTTOM);
            bar2.setPadding(dp(8), dp(6), dp(8), dp(8));
            bar2.setBackgroundColor(t.keyBar);
            input = new EditText(a);
            input.setHint(R.string.chat_input_hint);
            input.setTextColor(t.text);
            input.setHintTextColor(t.faint);
            input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            input.setTypeface(Ui.face(a, Ui.REGULAR));
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMaxLines(6);
            input.setMinHeight(dp(44));
            input.setPadding(dp(16), dp(10), dp(16), dp(10));
            input.setBackground(t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)));
            View mic = micButton(input);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(44), dp(44));
            mlp.rightMargin = dp(8);
            bar2.addView(mic, mlp);
            bar2.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
            sendBtn = new FrameLayout(a);
            sendIcon = Ui.icon(a, R.drawable.ic_arrow_up, t.onAccent, 20);
            sendBtn.addView(sendIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            sendBtn.setBackground(Ui.pressable(t, Ui.round(t.accent, dp(22)), dp(22)));
            sendBtn.setOnClickListener(v -> onSend());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
            slp.leftMargin = dp(8);
            bar2.addView(sendBtn, slp);
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int s, int b, int n) { }
                @Override public void onTextChanged(CharSequence c, int s, int b, int n) { }
                @Override public void afterTextChanged(android.text.Editable e) { styleSend(); }
            });
            view.addView(bar2, new LinearLayout.LayoutParams(-1, -2));
            onAgent(ag);
        }

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                Row r = rows.get(i);
                View v = views.get(r.key);
                Long ver = viewVersions.get(r.key);
                if (v == null || ver == null || ver != r.version) {
                    v = render(r);
                    views.put(r.key, v);
                    viewVersions.put(r.key, r.version);
                }
                if (v.getParent() != null && v.getParent() != parent) ((ViewGroup) v.getParent()).removeView(v);
                return v;
            }
        };

        void load() {
            if (client == null || !client.ready()) return;
            loading = true;
            early = new ArrayList<>();
            subscribe(new String[]{id});
            try {
                JSONObject m = PaseoClient.msg("fetch_agent_timeline_request").put("agentId", id)
                    .put("direction", "tail").put("limit", 300).put("projection", "projected");
                client.request(m, (p, err) -> {
                    if (conv != this) return;
                    loading = false;
                    if (err != null || p == null) {
                        Toast.makeText(a, a.getString(R.string.chat_failed, err), Toast.LENGTH_LONG).show();
                        return;
                    }
                    items.clear();
                    index.clear();
                    epoch = p.optString("epoch");
                    JSONObject end = p.optJSONObject("endCursor");
                    lastSeq = end == null ? 0 : end.optLong("seq");
                    hasOlder = p.optBoolean("hasOlder");
                    JSONArray e = p.optJSONArray("entries");
                    for (int i = 0; e != null && i < e.length(); i++) {
                        JSONObject it = e.optJSONObject(i).optJSONObject("item");
                        if (it != null) apply(it, true);
                    }
                    if (p.optJSONObject("agent") != null) onAgent(p.optJSONObject("agent"));
                    List<JSONObject> later = early;
                    early = null;
                    for (JSONObject ev : later) onStream(ev);
                    rebuild();
                    lv.post(() -> lv.setSelection(rows.size() - 1));
                });
            } catch (Exception ignored) {
            }
        }

        void onAgent(JSONObject ag) {
            agent = ag;
            title.setText(titleOf(ag));
            String state = ag.optString("status");
            running = state.equals("running") || state.equals("initializing");
            subtitle.setText(shortPath(ag.optString("cwd")));
            subtitle.setVisibility(View.VISIBLE);
            JSONArray perms = ag.optJSONArray("pendingPermissions");
            if (perms != null) {
                permissions.clear();
                for (int i = 0; i < perms.length(); i++) {
                    JSONObject r = perms.optJSONObject(i);
                    if (r != null) permissions.put(r.optString("id"), r);
                }
                renderPermissions();
            }
            styleSend();
            if (!loading) rebuild();
        }

        void onStream(JSONObject p) {
            if (early != null) {
                early.add(p);
                return;
            }
            JSONObject ev = p.optJSONObject("event");
            if (ev == null) return;
            long seq = p.optLong("seq", -1);
            if (seq >= 0) {
                String ep = p.optString("epoch");
                if (ep.equals(epoch) && seq <= lastSeq) return; // already in the loaded timeline
                if (!ep.isEmpty() && epoch != null && !ep.equals(epoch)) {
                    load();
                    return;
                }
                lastSeq = seq;
            }
            switch (ev.optString("type")) {
                case "timeline":
                    JSONObject it = ev.optJSONObject("item");
                    if (it != null) apply(it, false);
                    break;
                case "turn_started":
                    running = true;
                    break;
                case "turn_completed":
                    running = false;
                    break;
                case "turn_failed":
                    running = false;
                    add(obj("type", "error", "message", ev.optString("error")));
                    break;
                case "turn_canceled":
                    running = false;
                    add(obj("type", "notification", "level", "info", "message", a.getString(R.string.chat_stopped)));
                    break;
                case "permission_requested":
                    JSONObject r = ev.optJSONObject("request");
                    if (r != null) permissions.put(r.optString("id"), r);
                    renderPermissions();
                    break;
                case "permission_resolved":
                    permissions.remove(ev.optString("requestId"));
                    renderPermissions();
                    break;
                default:
                    return;
            }
            styleSend();
            rebuild();
        }

        /** Merges one timeline item: message chunks join their message, tool call updates replace the call. */
        void apply(JSONObject it, boolean loaded) {
            String type = it.optString("type");
            String key = null;
            switch (type) {
                case "assistant_message":
                    key = "a:" + it.optString("messageId", "x" + items.size());
                    Integer at = index.get(key);
                    if (at != null && !loaded) {
                        JSONObject old = items.get(at);
                        put(old, "text", old.optString("text") + it.optString("text"));
                        touch(old);
                        return;
                    }
                    break;
                case "reasoning":
                    if (!items.isEmpty() && "reasoning".equals(items.get(items.size() - 1).optString("type")) && !loaded) {
                        JSONObject old = items.get(items.size() - 1);
                        put(old, "text", old.optString("text") + it.optString("text"));
                        touch(old);
                        return;
                    }
                    break;
                case "tool_call":
                    // Stopping a turn leaves a nameless "tool" result with no input behind: not a step of the work
                    JSONObject dd = it.optJSONObject("detail");
                    if ("tool".equals(it.optString("name")) && (dd == null || dd.isNull("input"))) return;
                    key = "t:" + it.optString("callId");
                    break;
                case "user_message":
                    String cm = it.optString("clientMessageId", "");
                    if (!cm.isEmpty() && index.containsKey("u:" + cm)) key = "u:" + cm;
                    else key = "u:" + it.optString("messageId", "x" + items.size());
                    break;
                case "todo":
                    key = "todo";
                    break;
                case "plugin":
                    return;
            }
            if (key != null && index.containsKey(key)) {
                int i = index.get(key);
                if (type.equals("todo")) {
                    // The plan moves to where it was last updated
                    items.remove(i);
                    reindex();
                } else {
                    items.set(i, it);
                    touch(it);
                    return;
                }
            }
            add(it);
            if (key != null) index.put(key, items.size() - 1);
        }

        void add(JSONObject it) {
            items.add(it);
            touch(it);
        }

        void reindex() {
            index.clear();
            for (int i = 0; i < items.size(); i++) {
                JSONObject it = items.get(i);
                String k = keyOf(it);
                if (k != null) index.put(k, i);
            }
        }

        String keyOf(JSONObject it) {
            switch (it.optString("type")) {
                case "assistant_message": return "a:" + it.optString("messageId");
                case "tool_call": return "t:" + it.optString("callId");
                case "user_message": return "u:" + it.optString("clientMessageId", it.optString("messageId"));
                case "todo": return "todo";
                default: return null;
            }
        }

        void touch(JSONObject it) {
            versions.put(it, ++tick);
        }

        long ver(JSONObject it) {
            Long v = versions.get(it);
            return v == null ? 0 : v;
        }

        /** Items to rows: a run of tool calls is one row. */
        void rebuild() {
            rows.clear();
            if (hasOlder) {
                Row r = new Row();
                r.kind = NOTE;
                r.key = "older";
                r.items.add(obj("type", "notification", "level", "info", "message", a.getString(R.string.chat_older)));
                rows.add(r);
            }
            Row run = null;
            for (int i = 0; i < items.size(); i++) {
                JSONObject it = items.get(i);
                String type = it.optString("type");
                // Thinking and tool calls between two messages are one row of work, as in Paseo's app
                if (type.equals("tool_call") || type.equals("reasoning")) {
                    if (run == null) {
                        run = new Row();
                        run.kind = TOOLS;
                        run.key = "run:" + i;
                        rows.add(run);
                    }
                    run.items.add(it);
                    run.version = run.version * 31 + ver(it);
                    continue;
                }
                run = null;
                Row r = new Row();
                r.items.add(it);
                r.version = ver(it);
                r.key = "i:" + i + ":" + type;
                switch (type) {
                    case "user_message": r.kind = USER; break;
                    case "assistant_message": r.kind = TEXT; break;
                    case "todo": r.kind = TODO; break;
                    default: r.kind = NOTE;
                }
                rows.add(r);
            }
            for (Row r : rows) if (r.kind == TOOLS) r.version = r.version * 2 + (expanded.contains(r.key) ? 1 : 0);
            JSONObject last = items.isEmpty() ? null : items.get(items.size() - 1);
            boolean streaming = last != null && "assistant_message".equals(last.optString("type"));
            if (running && !streaming && permissions.isEmpty()) {
                Row w = new Row();
                w.kind = WORKING;
                w.key = "working";
                rows.add(w);
            }
            adapter.notifyDataSetChanged();
        }

        void styleSend() {
            boolean stop = running && input.getText().toString().trim().isEmpty();
            sendIcon.setImageResource(stop ? R.drawable.ic_stop : R.drawable.ic_arrow_up);
            sendBtn.setBackground(Ui.pressable(t, Ui.round(stop ? t.red : t.accent, dp(22)), dp(22)));
            sendBtn.setContentDescription(a.getString(stop ? R.string.chat_stop : R.string.chat_send));
        }

        void onSend() {
            String text = input.getText().toString().trim();
            if (client == null || !client.ready()) return;
            try {
                if (text.isEmpty()) {
                    if (running) client.request(PaseoClient.msg("cancel_agent_request").put("agentId", id), null);
                    return;
                }
                stopVoice();
                String mid = UUID.randomUUID().toString();
                JSONObject m = PaseoClient.msg("send_agent_message_request").put("agentId", id).put("text", text)
                    .put("messageId", mid);
                if (running) m.put("activeTurnBehavior", "interrupt");
                input.setText("");
                JSONObject local = obj("type", "user_message", "text", text, "clientMessageId", mid, "messageId", mid);
                add(local);
                index.put("u:" + mid, items.size() - 1);
                running = true;
                styleSend();
                rebuild();
                lv.post(() -> lv.setSelection(rows.size() - 1));
                client.request(m, (p, err) -> {
                    if (err != null || (p != null && !p.optBoolean("accepted", true))) {
                        Toast.makeText(a, a.getString(R.string.chat_send_failed, err != null ? err : p.optString("error")), Toast.LENGTH_LONG).show();
                        if (input.getText().length() == 0) input.setText(text);
                    }
                });
            } catch (Exception ignored) {
            }
        }

        /** Cards by request id, kept while the request is open so a half-answered question survives updates. */
        final Map<String, View> permViews = new HashMap<>();

        void renderPermissions() {
            permViews.keySet().retainAll(permissions.keySet());
            permBox.removeAllViews();
            for (JSONObject r : permissions.values()) {
                String rid = r.optString("id");
                View v = permViews.get(rid);
                if (v == null) {
                    v = isQuestion(r) ? questionCard(r) : permissionCard(r);
                    permViews.put(rid, v);
                }
                if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                permBox.addView(v);
            }
            if (!loading) rebuild();
        }

        boolean isQuestion(JSONObject r) {
            JSONObject in = r.optJSONObject("input");
            return "question".equals(r.optString("kind")) && in != null && in.optJSONArray("questions") != null;
        }

        /** Claude asks with choices (AskUserQuestion): pick options, or write an answer, then send. */
        View questionCard(JSONObject r) {
            LinearLayout c = Ui.column(a);
            c.setPadding(dp(16), dp(12), dp(16), dp(12));
            c.setBackground(Ui.outline(t.amberBg, t.amberLine, dp(1), dp(14)));
            JSONArray qs = r.optJSONObject("input").optJSONArray("questions");
            int n = qs.length();
            List<java.util.Set<Integer>> sel = new ArrayList<>();
            EditText[] others = new EditText[n];
            TextView[] submit = new TextView[1];
            Runnable check = () -> {
                boolean all = true;
                for (int i = 0; i < n; i++) {
                    boolean other = others[i] != null && !others[i].getText().toString().trim().isEmpty();
                    if (sel.get(i).isEmpty() && !other) all = false;
                }
                submit[0].setEnabled(all);
                submit[0].setAlpha(all ? 1f : 0.4f);
            };
            for (int qi = 0; qi < n; qi++) {
                JSONObject q = qs.optJSONObject(qi);
                java.util.Set<Integer> chosen = new java.util.LinkedHashSet<>();
                sel.add(chosen);
                if (q == null) continue;
                boolean multi = q.optBoolean("multiSelect");
                if (qi > 0) c.addView(new View(a), new LinearLayout.LayoutParams(1, dp(14)));
                String head = q.optString("header", "");
                if (!head.isEmpty()) {
                    TextView h = Ui.text(a, multi ? a.getString(R.string.chat_q_multi, head) : head, 12, t.sub, Ui.MEDIUM);
                    h.setPadding(0, 0, 0, dp(4));
                    c.addView(h);
                }
                c.addView(Ui.text(a, q.optString("question"), 15, t.text, Ui.SEMIBOLD));
                JSONArray opts = q.optJSONArray("options");
                List<View> optViews = new ArrayList<>();
                final int qIndex = qi;
                for (int oi = 0; opts != null && oi < opts.length(); oi++) {
                    JSONObject o = opts.optJSONObject(oi);
                    if (o == null) continue;
                    LinearLayout ov = Ui.column(a);
                    ov.setPadding(dp(12), dp(9), dp(12), dp(9));
                    ov.addView(Ui.text(a, o.optString("label"), 14.5f, t.text, Ui.MEDIUM));
                    String desc = o.optString("description", "");
                    if (!desc.isEmpty()) {
                        TextView d = Ui.text(a, desc, 12.5f, t.sub, Ui.REGULAR);
                        d.setPadding(0, dp(2), 0, 0);
                        ov.addView(d);
                    }
                    final int optIndex = oi;
                    ov.setBackground(optionBg(false));
                    ov.setOnClickListener(v -> {
                        if (multi) {
                            if (!chosen.remove(optIndex)) chosen.add(optIndex);
                        } else {
                            chosen.clear();
                            chosen.add(optIndex);
                            if (others[qIndex] != null) others[qIndex].setText("");
                        }
                        for (int k = 0; k < optViews.size(); k++) optViews.get(k).setBackground(optionBg(chosen.contains(k)));
                        check.run();
                    });
                    optViews.add(ov);
                    LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
                    olp.topMargin = dp(8);
                    c.addView(ov, olp);
                }
                if (q.optBoolean("allowOther", true)) {
                    EditText e = NewSessionSheet.field(a, t, "", a.getString(R.string.chat_q_other));
                    e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
                    e.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence x, int s, int b, int k) { }
                        @Override public void onTextChanged(CharSequence x, int s, int b, int k) { }
                        @Override public void afterTextChanged(android.text.Editable ed) {
                            // A written answer replaces the picked one when only one may be picked
                            if (!multi && ed.length() > 0 && !chosen.isEmpty()) {
                                chosen.clear();
                                for (View ov : optViews) ov.setBackground(optionBg(false));
                            }
                            check.run();
                        }
                    });
                    others[qi] = e;
                    LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(42));
                    elp.topMargin = dp(8);
                    c.addView(e, elp);
                }
            }
            LinearLayout b = Ui.row(a);
            b.setPadding(0, dp(12), 0, 0);
            TextView skip = Ui.secondaryButton(a, t, a.getString(R.string.chat_q_skip), v -> {
                try {
                    respond(r, new JSONObject().put("behavior", "deny").put("message", "Dismissed by user"));
                } catch (Exception ignored) {
                }
            });
            submit[0] = Ui.primaryButton(a, t, a.getString(R.string.chat_q_send), v -> {
                try {
                    JSONObject answers = new JSONObject();
                    for (int i = 0; i < n; i++) {
                        JSONObject q = qs.optJSONObject(i);
                        if (q == null) continue;
                        JSONArray opts = q.optJSONArray("options");
                        List<String> picked = new ArrayList<>();
                        for (int k : sel.get(i)) picked.add(opts.optJSONObject(k).optString("label"));
                        String other = others[i] == null ? "" : others[i].getText().toString().trim();
                        if (!other.isEmpty()) {
                            if (!q.optBoolean("multiSelect")) picked.clear();
                            picked.add(other);
                        }
                        answers.put(q.optString("question"), TextUtils.join(", ", picked));
                    }
                    JSONObject in = new JSONObject(r.optJSONObject("input").toString()).put("answers", answers);
                    respond(r, new JSONObject().put("behavior", "allow").put("updatedInput", in));
                } catch (Exception ignored) {
                }
            });
            submit[0].setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            b.addView(skip, new LinearLayout.LayoutParams(0, dp(42), 1f));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            slp.leftMargin = dp(10);
            b.addView(submit[0], slp);
            c.addView(b);
            check.run();
            FrameLayout wrap = new FrameLayout(a);
            wrap.setPadding(dp(10), dp(6), dp(10), dp(4));
            wrap.addView(c);
            return wrap;
        }

        android.graphics.drawable.Drawable optionBg(boolean on) {
            return Ui.pressable(t, on ? Ui.outline(t.accentSoft, t.accent, dp(1.5f), dp(12))
                : Ui.outline(t.surface, t.line, dp(1), dp(12)), dp(12));
        }

        void respond(JSONObject r, JSONObject response) throws Exception {
            if (client == null) return;
            client.request(PaseoClient.msg("agent_permission_response").put("agentId", id)
                .put("requestId", r.optString("id")).put("response", response), (p, err) -> {
                    if (err == null || err.equals("timed out")) return;
                    Toast.makeText(a, a.getString(R.string.chat_send_failed, err), Toast.LENGTH_LONG).show();
                    // Still open on the machine: show the card again
                    if (!err.equals("disconnected") && !permissions.containsKey(r.optString("id"))) {
                        permissions.put(r.optString("id"), r);
                        renderPermissions();
                    }
                });
            permissions.remove(r.optString("id"));
            renderPermissions();
        }

        View permissionCard(JSONObject r) {
            LinearLayout c = Ui.column(a);
            c.setPadding(dp(16), dp(12), dp(16), dp(12));
            c.setBackground(Ui.outline(t.amberBg, t.amberLine, dp(1), dp(14)));
            JSONObject detail = r.optJSONObject("detail");
            String what = r.optString("title", "");
            if (what.isEmpty()) what = r.optString("name");
            c.addView(Ui.text(a, a.getString(R.string.chat_permission, what), 14.5f, t.text, Ui.SEMIBOLD));
            String target = detail == null ? "" : toolLabel(r.optString("name"), detail);
            if (!target.isEmpty()) {
                TextView tv = Ui.monoText(a, target, 12, t.sub);
                tv.setMaxLines(4);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(0, dp(6), 0, 0);
                c.addView(tv);
            }
            LinearLayout b = Ui.row(a);
            b.setPadding(0, dp(10), 0, 0);
            TextView deny = Ui.secondaryButton(a, t, a.getString(R.string.chat_deny), v -> answer(r, false));
            TextView allow = Ui.primaryButton(a, t, a.getString(R.string.chat_allow), v -> answer(r, true));
            allow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            b.addView(deny, new LinearLayout.LayoutParams(0, dp(42), 1f));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            alp.leftMargin = dp(10);
            b.addView(allow, alp);
            c.addView(b);
            FrameLayout wrap = new FrameLayout(a);
            wrap.setPadding(dp(10), dp(6), dp(10), dp(4));
            wrap.addView(c);
            return wrap;
        }

        void answer(JSONObject r, boolean allow) {
            if (client == null) return;
            try {
                JSONObject resp = new JSONObject().put("behavior", allow ? "allow" : "deny");
                JSONArray acts = r.optJSONArray("actions");
                for (int i = 0; acts != null && i < acts.length(); i++) {
                    JSONObject ac = acts.optJSONObject(i);
                    if (ac != null && ac.optString("behavior").equals(allow ? "allow" : "deny")) {
                        resp.put("selectedActionId", ac.optString("id"));
                        break;
                    }
                }
                respond(r, resp);
            } catch (Exception ignored) {
            }
        }

        View render(Row r) {
            switch (r.kind) {
                case USER: return userRow(r.items.get(0));
                case TEXT: return textRow(r.items.get(0));
                case TOOLS: return toolsRow(r);
                case TODO: return todoRow(r.items.get(0));
                case WORKING: return noteRow(a.getString(R.string.chat_working), t.faint);
                default: {
                    JSONObject it = r.items.get(0);
                    boolean err = it.optString("type").equals("error") || "error".equals(it.optString("level"));
                    String msg = it.optString("message");
                    if (it.optString("type").equals("compaction")) msg = a.getString(R.string.chat_compacted);
                    return noteRow(msg, err ? t.red : t.faint);
                }
            }
        }

        View userRow(JSONObject it) {
            FrameLayout f = new FrameLayout(a);
            f.setPadding(dp(56), dp(8), dp(14), dp(8));
            TextView tv = Ui.text(a, it.optString("text"), 15, t.text, Ui.REGULAR);
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setBackground(Ui.round(t.accentSoft, dp(16)));
            tv.setTextIsSelectable(true);
            f.addView(tv, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
            f.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return f;
        }

        View textRow(JSONObject it) {
            TextView tv = Ui.text(a, "", 15, t.text, Ui.REGULAR);
            tv.setLineSpacing(dp(4), 1f);
            tv.setPadding(dp(18), dp(8), dp(18), dp(8));
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            markwon().setMarkdown(tv, it.optString("text"));
            tv.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return tv;
        }

        View todoRow(JSONObject it) {
            LinearLayout c = Ui.column(a);
            c.setPadding(dp(18), dp(6), dp(18), dp(6));
            JSONArray list = it.optJSONArray("items");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject x = list.optJSONObject(i);
                boolean done = x.optBoolean("completed") || "completed".equals(x.optString("status"));
                boolean doing = "in_progress".equals(x.optString("status"));
                TextView tv = Ui.text(a, (done ? "✓  " : doing ? "▸  " : "○  ") + x.optString("text"), 13.5f,
                    done ? t.faint : doing ? t.text : t.sub, doing ? Ui.MEDIUM : Ui.REGULAR);
                tv.setPadding(0, dp(2), 0, dp(2));
                c.addView(tv);
            }
            c.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return c;
        }

        View noteRow(String s, int color) {
            TextView tv = Ui.text(a, s, 13, color, Ui.REGULAR);
            tv.setPadding(dp(18), dp(6), dp(18), dp(6));
            tv.setTextIsSelectable(color == t.red);
            tv.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return tv;
        }

        View toolsRow(Row r) {
            boolean open = expanded.contains(r.key);
            LinearLayout c = Ui.column(a);
            c.setPadding(dp(14), dp(4), dp(14), dp(4));
            LinearLayout head = Ui.row(a);
            head.setPadding(dp(10), dp(8), dp(10), dp(8));
            head.setBackground(Ui.pressable(t, Ui.round(t.surface, dp(12)), dp(12)));
            JSONObject live = null;
            boolean failed = false;
            for (JSONObject it : r.items) {
                if ("running".equals(it.optString("status"))) live = it;
                if (failed(it)) failed = true;
            }
            String summary = live != null ? toolLabel(live.optString("name"), live.optJSONObject("detail")) : runSummary(r.items);
            if (summary.isEmpty()) summary = a.getString(R.string.chat_thinking);
            TextView s = Ui.oneLine(Ui.text(a, (live != null ? "… " : "") + summary, 13, live != null ? t.text : t.sub, Ui.REGULAR),
                TextUtils.TruncateAt.END);
            if (live != null) s.setTypeface(Ui.mono(a));
            head.addView(s, Ui.weight(1));
            if (failed) {
                TextView f = Ui.text(a, a.getString(R.string.chat_t_failed), 12, t.red, Ui.MEDIUM);
                f.setPadding(dp(6), 0, dp(4), 0);
                head.addView(f);
            }
            android.widget.ImageView chev = Ui.icon(a, R.drawable.ic_chevron_down, t.faint, 16);
            chev.setRotation(open ? 180 : 0);
            head.addView(chev);
            head.setOnClickListener(v -> toggle(r.key));
            c.addView(head);
            if (open) for (JSONObject it : r.items) c.addView(it.optString("type").equals("reasoning") ? thought(it) : callDetail(it));
            c.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return c;
        }

        View callDetail(JSONObject it) {
            LinearLayout c = Ui.column(a);
            c.setPadding(dp(10), dp(8), dp(4), dp(4));
            JSONObject d = it.optJSONObject("detail");
            String dt = d == null ? "" : d.optString("type");
            String status = it.optString("status");
            TextView label = Ui.monoText(a, toolLabel(it.optString("name"), d), 12.5f,
                failed(it) ? t.red : t.text);
            label.setMaxLines(3);
            label.setEllipsize(TextUtils.TruncateAt.END);
            String path = d == null ? "" : d.optString("filePath");
            if (!path.isEmpty()) {
                label.setTextColor(t.accent);
                label.setOnClickListener(v -> host.openFile(path));
            }
            c.addView(label);
            String body = "";
            if (dt.equals("shell")) body = d.optString("output");
            else if (dt.equals("write")) body = d.optString("content");
            else if (dt.equals("search")) body = d.optString("content");
            else if (dt.equals("fetch")) body = d.optString("result");
            else if (dt.equals("sub_agent")) body = d.optString("log");
            else if (dt.equals("plan")) body = d.optString("text");
            if ("failed".equals(status) && !it.isNull("error")) body = String.valueOf(it.opt("error"));
            if (dt.equals("edit") && !d.optString("unifiedDiff").isEmpty()) c.addView(diffView(d.optString("unifiedDiff")));
            else if (!body.isEmpty()) c.addView(codeBlock(body));
            return c;
        }

        View thought(JSONObject it) {
            TextView tv = Ui.text(a, it.optString("text").trim(), 13, t.faint, Ui.REGULAR);
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(10), dp(8), dp(4), dp(2));
            tv.setTextIsSelectable(true);
            return tv;
        }

        View codeBlock(String text) {
            if (text.length() > 4000) text = text.substring(0, 4000) + "\n…";
            TextView tv = Ui.monoText(a, text, 11.5f, t.sub);
            tv.setFontFeatureSettings("'calt' 0, 'liga' 0");
            tv.setMaxLines(30);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setBackground(Ui.round(t.well, dp(8)));
            tv.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            tv.setLayoutParams(lp);
            return tv;
        }

        View diffView(String diff) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            int addBg = (t.ok & 0x00ffffff) | 0x26000000, delBg = (t.red & 0x00ffffff) | 0x26000000;
            int shown = 0;
            for (String l : diff.split("\n")) {
                if (l.startsWith("---") || l.startsWith("+++") || l.startsWith("diff ") || l.startsWith("index ")) continue;
                if (++shown > 80) {
                    sb.append("…\n");
                    break;
                }
                int s = sb.length();
                sb.append(l).append('\n');
                if (l.startsWith("+")) sb.setSpan(new android.text.style.BackgroundColorSpan(addBg), s, sb.length(), 0);
                else if (l.startsWith("-")) sb.setSpan(new android.text.style.BackgroundColorSpan(delBg), s, sb.length(), 0);
                else if (l.startsWith("@@")) sb.setSpan(new android.text.style.ForegroundColorSpan(t.faint), s, sb.length(), 0);
            }
            TextView tv = Ui.monoText(a, sb, 11.5f, t.text);
            tv.setFontFeatureSettings("'calt' 0, 'liga' 0");
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setBackground(Ui.round(t.well, dp(8)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            tv.setLayoutParams(lp);
            return tv;
        }

        void toggle(String key) {
            if (!expanded.remove(key)) expanded.add(key);
            rebuild();
        }
    }

    /** One line for a tool call: the main argument (command, file, query, url), as in Paseo's tool-call display. */
    /** Failed on its own, not refused by the person or cut off by a stop. */
    private static boolean failed(JSONObject it) {
        if (!"failed".equals(it.optString("status"))) return false;
        String e = String.valueOf(it.opt("error"));
        return !e.contains("doesn't want to proceed") && !e.contains("was rejected");
    }

    private String toolLabel(String name, JSONObject d) {
        String dt = d == null ? "" : d.optString("type");
        switch (dt) {
            case "shell": return "$ " + firstLine(d.optString("command"));
            case "read": return a.getString(R.string.chat_l_read, shortPath(d.optString("filePath")));
            case "edit": return a.getString(R.string.chat_l_edit, shortPath(d.optString("filePath")));
            case "write": return a.getString(R.string.chat_l_write, shortPath(d.optString("filePath")));
            case "search": return a.getString(R.string.chat_l_search, d.optString("query"));
            case "fetch": return a.getString(R.string.chat_l_fetch, d.optString("url"));
            case "sub_agent": return a.getString(R.string.chat_l_agent, d.optString("description", d.optString("subAgentType")));
            case "plain_text": return d.optString("label", name);
            default: return "AskUserQuestion".equals(name) ? a.getString(R.string.chat_l_ask) : name;
        }
    }

    private String runSummary(List<JSONObject> calls) {
        int cmd = 0, read = 0, edit = 0, search = 0, web = 0, agent = 0, other = 0;
        for (JSONObject it : calls) {
            if (!it.optString("type").equals("tool_call")) continue;
            JSONObject d = it.optJSONObject("detail");
            switch (d == null ? "" : d.optString("type")) {
                case "shell": cmd++; break;
                case "read": read++; break;
                case "edit": case "write": edit++; break;
                case "search": search++; break;
                case "fetch": web++; break;
                case "sub_agent": agent++; break;
                default: other++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (cmd > 0) parts.add(a.getString(R.string.chat_t_cmd, cmd));
        if (read > 0) parts.add(a.getString(R.string.chat_t_read, read));
        if (edit > 0) parts.add(a.getString(R.string.chat_t_edit, edit));
        if (search > 0) parts.add(a.getString(R.string.chat_t_search, search));
        if (web > 0) parts.add(a.getString(R.string.chat_t_web, web));
        if (agent > 0) parts.add(a.getString(R.string.chat_t_agent, agent));
        if (other > 0) parts.add(parts.isEmpty() && other == 1 ? firstTool(calls).optString("name")
            : a.getString(R.string.chat_t_other, other));
        return TextUtils.join(a.getString(R.string.chat_t_sep), parts);
    }

    private static JSONObject firstTool(List<JSONObject> items) {
        for (JSONObject it : items) if (it.optString("type").equals("tool_call")) return it;
        return items.get(0);
    }

    // ---- voice: tap the mic to dictate into the box, tap again to stop; the text waits there to be sent ----

    private VoiceInput voice;
    private View voiceButton;

    private View micButton(EditText target) {
        FrameLayout b = new FrameLayout(a);
        b.addView(Ui.icon(a, R.drawable.ic_mic, t.text, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        b.setBackground(Ui.pressable(t, t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)), dp(22)));
        b.setContentDescription(a.getString(R.string.a11y_bubble));
        b.setOnClickListener(v -> {
            if (voice != null) {
                voice.stop();
                return;
            }
            if (!VoiceInput.Config.load(a).usable()) {
                Toast.makeText(a, R.string.voice_setup_title, Toast.LENGTH_SHORT).show();
                return;
            }
            if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 5);
                return;
            }
            String base = target.getText().toString();
            String sep = base.isEmpty() || base.endsWith(" ") || base.endsWith("\n") ? "" : " ";
            voiceButton = b;
            b.setBackground(Ui.round(t.accent, dp(22)));
            ((android.widget.ImageView) b.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(t.onAccent));
            voice = new VoiceInput(a, new VoiceInput.Listener() {
                @Override public void onText(String text) { setTo(target, base + sep + text); }
                @Override public void onLevel(float level) {
                    float s = 1f + level * 0.15f;
                    b.animate().scaleX(s).scaleY(s).setDuration(90).start();
                }
                @Override public void onDone(String text) {
                    setTo(target, base + sep + text);
                    endVoice();
                }
                @Override public void onError(String message) {
                    endVoice();
                    Toast.makeText(a, a.getString(R.string.voice_failed, message), Toast.LENGTH_LONG).show();
                }
            });
            voice.start();
        });
        return b;
    }

    private static void setTo(EditText e, String s) {
        e.setText(s);
        e.setSelection(s.length());
    }

    private void stopVoice() {
        if (voice != null) voice.cancel();
        endVoice();
    }

    private void endVoice() {
        voice = null;
        View b = voiceButton;
        voiceButton = null;
        if (b == null) return;
        b.animate().cancel();
        b.setScaleX(1f);
        b.setScaleY(1f);
        b.setBackground(Ui.pressable(t, t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)), dp(22)));
        ((android.widget.ImageView) ((FrameLayout) b).getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(t.text));
    }

    // ---- small parts ----

    private LinearLayout header(String title, String sub, Runnable back, View... actions) {
        LinearLayout bar = Ui.row(a);
        bar.setPadding(dp(2), 0, dp(10), 0);
        bar.addView(Ui.iconButton(a, t, R.drawable.ic_back, t.text, a.getString(R.string.files_back), v -> back.run()));
        LinearLayout names = Ui.column(a);
        TextView tt = Ui.oneLine(Ui.text(a, title, 16, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END);
        TextView st = Ui.oneLine(Ui.monoText(a, sub, 11, t.faint), TextUtils.TruncateAt.START);
        st.setPadding(0, dp(3), 0, 0);
        st.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        names.addView(tt);
        names.addView(st);
        bar.addView(names, Ui.weight(1));
        for (View v : actions) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.leftMargin = dp(6);
            bar.addView(v, lp);
        }
        return bar;
    }

    private TextView headerButton(String s, boolean primary, Runnable r) {
        TextView b = Ui.text(a, s, 13.5f, primary ? t.onAccent : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(Ui.pressable(t, Ui.round(primary ? t.accent : t.chip, dp(10)), dp(10)));
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private Markwon markwon() {
        if (markwon == null) {
            TableTheme table = TableTheme.buildWithDefaults(a).tableBorderColor(t.line).tableBorderWidth(1)
                .tableHeaderRowBackgroundColor(t.well).tableOddRowBackgroundColor(0).tableEvenRowBackgroundColor(0)
                .tableCellPadding(dp(6)).build();
            markwon = Markwon.builder(a)
                .usePlugin(TablePlugin.create(table))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(MarkwonTheme.Builder b) {
                        b.codeTypeface(Ui.mono(a)).codeBlockTypeface(Ui.mono(a))
                            .codeTextColor(t.text).codeBlockTextColor(t.text)
                            .codeBackgroundColor(t.well).codeBlockBackgroundColor(t.well)
                            .codeTextSize(dp(12.5f)).codeBlockTextSize(dp(12.5f))
                            .linkColor(t.accent).blockQuoteColor(t.line).listItemColor(t.sub)
                            .headingBreakColor(t.divider).thematicBreakColor(t.divider).blockMargin(dp(18));
                    }

                    @Override
                    public void configureConfiguration(MarkwonConfiguration.Builder b) {
                        b.linkResolver((view, link) -> {
                            if (!link.matches("(?i)^(https?|mailto):.*")) return;
                            try {
                                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                            } catch (Exception ignored) {
                            }
                        });
                    }
                })
                .build();
        }
        return markwon;
    }

    private String shortPath(String p) {
        if (!home.isEmpty() && (p.equals(home) || p.startsWith(home + "/"))) return "~" + p.substring(home.length());
        return p;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i) + " …";
    }

    private String ago(String iso) {
        try {
            long ms = java.time.Instant.parse(iso).toEpochMilli();
            if (System.currentTimeMillis() - ms < DateUtils.MINUTE_IN_MILLIS) return a.getString(R.string.ago_now);
            return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, kv[i], kv[i + 1]);
        return o;
    }

    private int dp(float v) {
        return Ui.dp(a, v);
    }
}
