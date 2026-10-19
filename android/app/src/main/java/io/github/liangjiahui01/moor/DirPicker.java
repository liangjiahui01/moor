package io.github.liangjiahui01.moor;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a new session starts, without typing a path: recent folders and the machine's git projects to tap,
 * a search by folder name, and browsing folder by folder. Lists come from `moor dirs` on the machine.
 */
final class DirPicker {
    interface Helper {
        /** Runs `moor dirs <args>` on the machine; the result is its JSON output. */
        void dirs(String args, TermService.Result<String> cb);
    }

    interface Picked {
        void pick(String dir);
    }

    private final Context c;
    private final Theme t;
    private final Helper helper;
    private final List<String> recent;
    private final Picked picked;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private LinearLayout list;
    private EditText search;
    private ScrollView scroll;
    /** The "Browse" header, so stepping into a folder keeps it in view. */
    private View browseHeader;
    private List<String> projects;
    private String browsePath = "~";
    private List<String> browseDirs;
    private String browseError;
    /** Bumped on every search and every browse, so a slow answer to an older one is dropped. */
    private int searchGen, browseGen;

    DirPicker(Context c, Theme t, Helper helper, List<String> recent, String start, Picked picked) {
        this.c = c;
        this.t = t;
        this.helper = helper;
        this.recent = recent;
        this.picked = picked;
        if (start != null && start.startsWith("~")) browsePath = start;
    }

    void show() {
        LinearLayout box = Ui.column(c);
        TextView title = Ui.text(c, c.getString(R.string.dir_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(12));
        box.addView(title);

        search = NewSessionSheet.field(c, t, "", c.getString(R.string.dir_search_hint));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView sv = scroll = new ScrollView(c);
        list = Ui.column(c);
        list.setPadding(0, dp(6), 0, dp(6));
        sv.addView(list);
        int h = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, h));

        dialog = Ui.bottomSheet(c, t, box);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        Runnable runSearch = this::searchNow;
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) {
                main.removeCallbacks(runSearch);
                if (e.toString().trim().isEmpty()) render();
                else main.postDelayed(runSearch, 250);
            }
        });

        render();
        helper.dirs("", (out, err) -> {
            projects = parse(out);
            if (query().isEmpty()) render();
        });
        browse(browsePath, false);
        dialog.show();
    }

    private String query() {
        return search.getText().toString().trim();
    }

    private void searchNow() {
        String q = query();
        if (q.isEmpty()) return;
        int g = ++searchGen;
        list.removeAllViews();
        list.addView(note(c.getString(R.string.dir_searching)));
        helper.dirs("--search " + Sessions.q(q), (out, err) -> {
            if (g != searchGen || !q.equals(query())) return;
            List<String> found = parse(out);
            list.removeAllViews();
            if (err != null) list.addView(note(err));
            else if (found == null || found.isEmpty()) list.addView(note(c.getString(R.string.dir_none)));
            else for (String d : found) list.addView(pickRow(d));
        });
    }

    private void browse(String path, boolean follow) {
        int g = ++browseGen;
        browsePath = path;
        browseDirs = null;
        browseError = null;
        helper.dirs("--ls " + Sessions.q(path), (out, err) -> {
            if (g != browseGen) return;
            if (err != null) browseError = err;
            else try {
                JSONObject o = new JSONObject(out == null ? "{}" : out);
                if (o.has("error")) browseError = o.getString("error");
                else {
                    browsePath = o.optString("path", path);
                    browseDirs = new ArrayList<>();
                    JSONArray a = o.optJSONArray("dirs");
                    for (int i = 0; a != null && i < a.length(); i++) browseDirs.add(a.getString(i));
                }
            } catch (Exception e) {
                browseError = String.valueOf(e.getMessage());
            }
            if (query().isEmpty()) render(follow);
        });
        if (query().isEmpty()) render(follow);
    }

    /** Without a search: recent folders, projects, then the folder being browsed. */
    private void render() {
        render(false);
    }

    private void render(boolean follow) {
        if (list == null) return;
        list.removeAllViews();
        if (!recent.isEmpty()) {
            list.addView(section(c.getString(R.string.dir_recent)));
            for (String d : recent) list.addView(pickRow(d));
        }
        list.addView(section(c.getString(R.string.dir_projects)));
        if (projects == null) list.addView(note(c.getString(R.string.dir_loading)));
        else if (projects.isEmpty()) list.addView(note(c.getString(R.string.dir_no_projects)));
        else {
            // A few at the top; search finds the rest
            int shown = 0;
            for (String d : projects) {
                if (recent.contains(d)) continue;
                if (++shown > 8) break;
                list.addView(pickRow(d));
            }
        }

        browseHeader = section(c.getString(R.string.dir_browse));
        list.addView(browseHeader);
        list.addView(breadcrumbs());
        if (browseError != null) list.addView(note(browseError));
        else if (browseDirs == null) list.addView(note(c.getString(R.string.dir_loading)));
        else {
            if (browseDirs.isEmpty()) list.addView(note(c.getString(R.string.dir_empty)));
            for (String name : browseDirs) list.addView(browseRow(name));
        }
        TextView here = Ui.primaryButton(c, t, c.getString(R.string.dir_pick_here, browsePath), v -> choose(browsePath));
        Ui.oneLine(here, TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(10);
        list.addView(here, lp);
        if (follow) {
            View h = browseHeader;
            scroll.post(() -> scroll.scrollTo(0, h.getTop()));
        }
    }

    /** ~ / project / moor: each part goes back up to that folder. */
    private View breadcrumbs() {
        LinearLayout r = Ui.row(c);
        r.setPadding(dp(4), 0, 0, dp(4));
        String[] parts = browsePath.split("/");
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() && i > 0) continue;
            if (acc.length() > 0 || i > 0) acc.append(i == 0 ? "" : "/");
            acc.append(parts[i].isEmpty() ? "/" : parts[i]);
            final String target = acc.toString().replace("//", "/");
            boolean last = i == parts.length - 1;
            TextView p = Ui.monoText(c, parts[i].isEmpty() ? "/" : parts[i], 13, last ? t.text : t.accent);
            p.setPadding(dp(4), dp(6), dp(4), dp(6));
            if (!last) p.setOnClickListener(v -> browse(target, true));
            r.addView(p);
            if (!last) r.addView(Ui.monoText(c, "/", 13, t.faint));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(r);
        hs.post(() -> hs.fullScroll(View.FOCUS_RIGHT));
        return hs;
    }

    private View pickRow(String dir) {
        TextView v = Ui.monoText(c, dir, 13.5f, t.text);
        Ui.oneLine(v, TextUtils.TruncateAt.START);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(Ui.pressable(t, null, dp(10)));
        v.setOnClickListener(x -> choose(dir));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
        return v;
    }

    private View browseRow(String name) {
        LinearLayout r = Ui.row(c);
        r.setPadding(dp(12), 0, dp(10), 0);
        r.addView(Ui.icon(c, R.drawable.ic_folder, t.sub, 17));
        TextView n = Ui.monoText(c, name, 13.5f, t.text);
        Ui.oneLine(n, TextUtils.TruncateAt.END);
        n.setPadding(dp(10), 0, 0, 0);
        r.addView(n, Ui.weight(1));
        r.addView(Ui.icon(c, R.drawable.ic_chevron_right, t.faint, 16));
        r.setBackground(Ui.pressable(t, null, dp(10)));
        r.setOnClickListener(x -> browse(browsePath.equals("/") ? "/" + name : browsePath + "/" + name, true));
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
        return r;
    }

    private TextView section(String s) {
        TextView l = Ui.text(c, s, 12, t.faint, Ui.MEDIUM);
        l.setPadding(dp(4), dp(14), 0, dp(4));
        return l;
    }

    private TextView note(String s) {
        TextView l = Ui.text(c, s, 13, t.faint, Ui.REGULAR);
        l.setPadding(dp(12), dp(8), dp(12), dp(8));
        return l;
    }

    private void choose(String dir) {
        dialog.dismiss();
        picked.pick(dir);
    }

    private static List<String> parse(String out) {
        if (out == null) return new ArrayList<>();
        try {
            JSONArray a = new JSONObject(out).optJSONArray("dirs");
            List<String> r = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) r.add(a.getString(i));
            return r;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }
}
