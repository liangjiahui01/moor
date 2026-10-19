package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;

/**
 * Read-only preview of files on the machine, full screen over the terminal: a path tapped in the terminal, the
 * folders around it, and the uncommitted changes of the projects being worked on with each file's diff.
 * Text and code, rendered Markdown, and images. Everything comes from `moor file` (server/moor) over the SSH link.
 */
final class FileViewer {
    interface Source {
        /** Runs `moor file <args>` on the machine; the result is its JSON output. */
        void files(String args, TermService.Result<String> cb);

        /** progress hears how many bytes have arrived so far. */
        void bytes(String path, SshLink.Progress progress, TermService.Result<byte[]> cb);

        /** The session on screen: its folders are searched first. */
        String session();

        /** Types the path into the session. */
        void insert(String path);
    }

    private final Activity a;
    private final Theme t;
    private final Source src;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private TextView title, subtitle;
    private LinearLayout actions;
    private FrameLayout body;
    private Markwon markwon;

    /** One screen of the viewer; Back returns to the one before, as it was left. */
    private static final class Page {
        String title, sub, path;
        View body;
        final List<View> actions = new ArrayList<>();
    }

    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private Page cur;

    FileViewer(Activity a, Theme t, Source src) {
        this.a = a;
        this.t = t;
        this.src = src;
    }

    // ---- entry points ----

    /** A tap on text that may name a file: opens it if the machine has it. */
    void openTapped(PathTap.Hit h) {
        StringBuilder args = new StringBuilder("open --session ").append(Sessions.q(n(src.session())));
        for (String c : h.cands) args.append(' ').append(Sessions.q(c));
        Toast hint = Toast.makeText(a, a.getString(R.string.files_opening, h.label), Toast.LENGTH_SHORT);
        Runnable showHint = hint::show;
        // Only a slow answer gets a word; a quick one just opens
        main.postDelayed(showHint, 400);
        src.files(args.toString(), (out, err) -> {
            main.removeCallbacks(showHint);
            hint.cancel();
            JSONObject o = parse(out);
            if (err != null || o == null) {
                Toast.makeText(a, a.getString(R.string.files_failed, err != null ? err : "?"), Toast.LENGTH_LONG).show();
                return;
            }
            if (o.has("error") && !o.has("path")) {
                // Plain words that only look like a file name stay quiet; a path with a slash was meant
                if (h.label.contains("/")) Toast.makeText(a, a.getString(R.string.files_not_found, h.label), Toast.LENGTH_SHORT).show();
                return;
            }
            open();
            Page p = page(o.optString("path"));
            push(p);
            render(p, o);
        });
    }

    /** Uncommitted changes in the projects open on the machine. */
    void showChanges() {
        open();
        Page p = new Page();
        p.title = a.getString(R.string.files_changes_title);
        p.sub = "";
        p.body = note(a.getString(R.string.files_loading));
        p.actions.add(headerButton(a.getString(R.string.files_refresh), false, () -> loadChanges(p)));
        push(p);
        loadChanges(p);
    }

    /**
     * Browse the session's files: straight into its folder when it has one; a multiplexer session (herdr) sits in
     * home, so it lists the projects coding agents work in instead, and home.
     */
    void showFiles() {
        open();
        Page p = new Page();
        p.title = a.getString(R.string.files_browse_title);
        p.sub = "";
        p.body = note(a.getString(R.string.files_loading));
        push(p);
        src.files("roots --session " + Sessions.q(n(src.session())), (out, err) -> {
            JSONObject o = parse(out);
            JSONArray dirs = o == null ? null : o.optJSONArray("dirs");
            if (err != null || dirs == null) {
                fill(p, note(a.getString(R.string.files_failed, err != null ? err : "?")));
                return;
            }
            String own = o.optString("open");
            if (!own.isEmpty() && p == cur) {
                // The session's own folder replaces the waiting page, so Back closes the viewer
                cur = null;
                openPath(own, 0);
                return;
            }
            fill(p, list(dirs.length(), i -> {
                JSONObject d = dirs.optJSONObject(i);
                String show = d.optString("show");
                LinearLayout r = Ui.row(a);
                r.setPadding(dp(16), 0, dp(16), 0);
                r.addView(Ui.icon(a, R.drawable.ic_folder, t.accent, 18));
                TextView nm = Ui.oneLine(Ui.monoText(a, show, 13.5f, t.text), TextUtils.TruncateAt.START);
                nm.setPadding(dp(12), 0, dp(8), 0);
                r.addView(nm, Ui.weight(1));
                r.addView(Ui.icon(a, R.drawable.ic_chevron_right, t.faint, 16));
                r.setBackground(Ui.pressable(t, null, 0));
                r.setOnClickListener(v -> openPath(d.optString("path"), 0));
                r.setLayoutParams(new ListView.LayoutParams(-1, dp(52)));
                return r;
            }));
        });
    }

    // ---- the window and its pages ----

    private void open() {
        if (dialog != null && dialog.isShowing()) return;
        history.clear();
        cur = null;
        dialog = new Dialog(a, t.dark ? android.R.style.Theme_DeviceDefault_NoActionBar
            : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = Ui.column(a);
        root.setBackgroundColor(t.bg);

        LinearLayout bar = Ui.row(a);
        bar.setPadding(dp(2), 0, dp(10), 0);
        bar.addView(Ui.iconButton(a, t, R.drawable.ic_back, t.text, a.getString(R.string.files_back), v -> back()));
        LinearLayout names = Ui.column(a);
        title = Ui.oneLine(Ui.text(a, "", 15.5f, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.MIDDLE);
        subtitle = Ui.oneLine(Ui.monoText(a, "", 11, t.faint), TextUtils.TruncateAt.START);
        subtitle.setPadding(0, dp(3), 0, 0);
        names.addView(title);
        names.addView(subtitle);
        bar.addView(names, Ui.weight(1));
        actions = Ui.row(a);
        bar.addView(actions, Ui.lp(-2, -1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));
        View line = new View(a);
        line.setBackgroundColor(t.divider);
        root.addView(line, new LinearLayout.LayoutParams(-1, 1));
        body = new FrameLayout(a);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(t.bg));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.setStatusBarColor(t.bg);
        w.setNavigationBarColor(t.bg);
        if (!t.dark) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        dialog.setOnKeyListener((d, code, e) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        });
        dialog.show();
    }

    private void push(Page p) {
        if (cur != null) history.push(cur);
        show(p);
    }

    private void back() {
        if (history.isEmpty()) {
            dialog.dismiss();
            return;
        }
        show(history.pop());
    }

    private void show(Page p) {
        cur = p;
        title.setText(p.title);
        subtitle.setText(p.sub);
        subtitle.setVisibility(p.sub.isEmpty() ? View.GONE : View.VISIBLE);
        // The folder line opens that folder: what else is next to this file
        String parent = p.path == null ? null : new File(p.path).getParent();
        subtitle.setOnClickListener(parent == null ? null : v -> openPath(parent, 0));
        actions.removeAllViews();
        for (View v : p.actions) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.leftMargin = dp(6);
            actions.addView(v, lp);
        }
        body.removeAllViews();
        if (p.body.getParent() != null) ((ViewGroup) p.body.getParent()).removeView(p.body);
        body.addView(p.body, new FrameLayout.LayoutParams(-1, -1));
    }

    /** The page's content arrived (the page may have been left meanwhile). */
    private void fill(Page p, View v) {
        p.body = v;
        if (p == cur) show(p);
    }

    private Page page(String path) {
        Page p = new Page();
        p.path = path;
        p.title = new File(path).getName();
        p.sub = parentShown(path);
        p.body = note(a.getString(R.string.files_loading));
        return p;
    }

    /** Opens a path from inside the viewer (a folder entry, a link in Markdown, a changed file's whole text). */
    private void openPath(String path, int line) {
        Page p = page(path);
        push(p);
        src.files("open " + Sessions.q(path), (out, err) -> {
            JSONObject o = parse(out);
            if (err != null || o == null) {
                fill(p, note(a.getString(R.string.files_failed, err != null ? err : "?")));
                return;
            }
            if (line > 0) try {
                o.put("line", line);
            } catch (Exception ignored) {
            }
            render(p, o);
        });
    }

    private void render(Page p, JSONObject o) {
        String path = o.optString("path");
        p.path = path;
        p.title = new File(path).getName();
        p.sub = parentShown(o.optString("show", path));
        if (o.has("error")) {
            fill(p, note(o.optString("error")));
            return;
        }
        String kind = o.optString("kind");
        long size = o.optLong("size");
        String show = o.optString("show", path);
        if (!kind.equals("dir")) p.actions.add(headerButton(a.getString(R.string.files_insert), false, () -> {
            dialog.dismiss();
            // ~/… is shorter, and the shell expands it as long as it needs no quotes
            src.insert(show.startsWith("~/") && show.matches("[\\w./~+@:%-]+") ? show : path);
        }));
        switch (kind) {
            case "dir":
                fill(p, dirBody(path, o.optJSONArray("entries")));
                break;
            case "text": {
                String text = o.optString("text");
                boolean md = path.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|mdx)$");
                textPage(p, text, o.optInt("line"), md, o.optBoolean("truncated"));
                break;
            }
            case "image":
                TextView loading = note(a.getString(R.string.files_loading));
                fill(p, loading);
                src.bytes(path, got -> loading.setText(a.getString(R.string.files_loading_bytes, human(got), human(size))), (bytes, err) -> {
                    if (err != null || bytes == null) fill(p, note(a.getString(R.string.files_failed, err)));
                    else decodeImage(p, bytes);
                });
                break;
            case "large":
                fill(p, note(a.getString(R.string.files_too_large, human(size))));
                break;
            default:
                fill(p, note(a.getString(R.string.files_binary, human(size))));
        }
    }

    // ---- text, code and Markdown ----

    private void textPage(Page p, String text, int line, boolean md, boolean truncated) {
        boolean[] rendered = {md && line <= 0};
        boolean[] wrap = {Prefs.raw(a).getBoolean("file_wrap", true)};
        List<String> lines = splitLines(text);
        Runnable[] redraw = new Runnable[1];
        TextView mode = headerButton(a.getString(R.string.files_source), false, null);
        TextView wrapBtn = headerButton(a.getString(R.string.files_wrap), wrap[0], null);
        redraw[0] = () -> {
            View v = rendered[0] ? markdown(text) : lineList(lines, null, null, line, wrap[0]);
            if (truncated) v = withBanner(v, a.getString(R.string.files_truncated));
            mode.setText(rendered[0] ? R.string.files_source : R.string.files_rendered);
            wrapBtn.setVisibility(rendered[0] ? View.GONE : View.VISIBLE);
            wrapBtn.setTextColor(wrap[0] ? t.accent : t.text);
            fill(p, v);
        };
        mode.setOnClickListener(v -> {
            rendered[0] = !rendered[0];
            redraw[0].run();
        });
        wrapBtn.setOnClickListener(v -> {
            wrap[0] = !wrap[0];
            Prefs.put(a, "file_wrap", wrap[0]);
            redraw[0].run();
        });
        p.actions.add(0, wrapBtn);
        if (md) p.actions.add(0, mode);
        redraw[0].run();
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int s = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(s, i > s && text.charAt(i - 1) == '\r' ? i - 1 : i));
                s = i + 1;
            }
        }
        if (s < text.length()) out.add(text.substring(s));
        return out;
    }

    private static final int PLAIN = 0, ADDED = 1, REMOVED = 2, HUNK = 3, META = 4;
    /** Code shows what is typed: JetBrains Mono would draw != as ≠ and >= as ≥ (the terminal doesn't either). */
    private static final String NO_LIGATURES = "'calt' 0, 'liga' 0";

    /**
     * Lines with numbers in a gutter, built as they scroll into view (a long file stays smooth). Not wrapped,
     * the whole list scrolls sideways as one. kinds colors diff lines; nums overrides the numbering.
     */
    private View lineList(List<String> lines, int[] nums, int[] kinds, int jump, boolean wrap) {
        float sp = 12.5f;
        Paint paint = new Paint();
        paint.setTypeface(Ui.mono(a));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, a.getResources().getDisplayMetrics()));
        int maxNum = lines.size();
        if (nums != null) for (int v : nums) maxNum = Math.max(maxNum, v);
        int gutter = (int) paint.measureText(String.valueOf(Math.max(maxNum, 9))) + dp(14);
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        int width = screen;
        if (!wrap) {
            // Measure only the longer lines: a wide (CJK) line is at least half as many characters as the longest
            int maxLen = 0;
            for (String l : lines) maxLen = Math.max(maxLen, l.length());
            float longest = 0;
            for (String l : lines) if (l.length() * 2 >= maxLen) longest = Math.max(longest, paint.measureText(tabs(l)));
            width = Math.max(screen, Math.min((int) longest + gutter + dp(24), 40_000));
        }
        int hl = (t.accent & 0x00ffffff) | 0x30000000;
        int addBg = (t.ok & 0x00ffffff) | 0x26000000, delBg = (t.red & 0x00ffffff) | 0x26000000;
        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setSelector(new ColorDrawable(0));
        lv.setClipToPadding(false);
        lv.setPadding(0, dp(6), 0, dp(40));
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return lines.size(); }
            @Override public Object getItem(int i) { return lines.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout row = (LinearLayout) cv;
                if (row == null) {
                    row = Ui.row(a);
                    row.setGravity(Gravity.TOP);
                    TextView num = Ui.monoText(a, "", sp, t.faint);
                    num.setGravity(Gravity.END);
                    num.setPadding(0, dp(1), dp(8), dp(1));
                    row.addView(num, new LinearLayout.LayoutParams(gutter, -2));
                    TextView txt = Ui.monoText(a, "", sp, t.text);
                    txt.setFontFeatureSettings(NO_LIGATURES);
                    txt.setPadding(dp(2), dp(1), dp(10), dp(1));
                    txt.setLineSpacing(dp(1), 1f);
                    row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
                }
                TextView num = (TextView) row.getChildAt(0), txt = (TextView) row.getChildAt(1);
                int kind = kinds == null ? PLAIN : kinds[i];
                int n = nums == null ? i + 1 : nums[i];
                num.setText(n > 0 ? String.valueOf(n) : "");
                txt.setText(tabs(lines.get(i)));
                txt.setTextColor(kind == HUNK || kind == META ? t.faint : t.text);
                row.setBackgroundColor(kind == ADDED ? addBg : kind == REMOVED ? delBg
                    : kind == HUNK ? t.well : (jump > 0 && i == jump - 1) ? hl : 0);
                return row;
            }
        });
        lv.setOnItemLongClickListener((parent, v, i, id) -> {
            a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("line", lines.get(i)));
            Toast.makeText(a, R.string.files_line_copied, Toast.LENGTH_SHORT).show();
            return true;
        });
        if (jump > 0) lv.setSelectionFromTop(Math.max(0, jump - 1), dp(80));
        if (wrap) {
            lv.setFastScrollEnabled(lines.size() > 300);
            return lv;
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setFillViewport(true);
        // The scroller measures its child without a width limit; a frame in between gives the list its width
        FrameLayout wide = new FrameLayout(a);
        wide.addView(lv, new FrameLayout.LayoutParams(width, -1));
        hs.addView(wide, new FrameLayout.LayoutParams(-2, -1));
        return hs;
    }

    private static String tabs(String s) {
        return s.indexOf('\t') < 0 ? s : s.replace("\t", "    ");
    }

    private View markdown(String text) {
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
                            .headingBreakColor(t.divider).thematicBreakColor(t.divider)
                            .blockMargin(dp(18));
                    }

                    @Override
                    public void configureConfiguration(MarkwonConfiguration.Builder b) {
                        b.linkResolver((view, link) -> openLink(link));
                    }
                })
                .build();
        }
        TextView tv = Ui.text(a, "", 15, t.text, Ui.REGULAR);
        tv.setFontFeatureSettings(NO_LIGATURES);
        tv.setLineSpacing(dp(4), 1f);
        tv.setPadding(dp(18), dp(14), dp(18), dp(48));
        // Links take taps (selectable text would swallow them); copying goes through Source
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        markwon.setMarkdown(tv, text);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    /** A link in Markdown: the web in the browser, a file next to this one in the viewer. */
    private void openLink(String link) {
        if (link.matches("(?i)^(https?|mailto):.*")) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
            } catch (Exception e) {
                Toast.makeText(a, a.getString(R.string.files_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String target = link.replaceAll("#.*$", "");
        if (target.isEmpty() || cur == null || cur.path == null) return;
        try {
            target = java.net.URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String base = new File(cur.path).getParent();
        String path = target.startsWith("/") ? target : new File(base, target).toPath().normalize().toString();
        openPath(path, 0);
    }

    // ---- images ----

    private void decodeImage(Page p, byte[] bytes) {
        new Thread(() -> {
            Bitmap bm = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / s > 4096) s *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
                bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            } catch (OutOfMemoryError | RuntimeException ignored) {
            }
            Bitmap b = bm;
            main.post(() -> {
                if (b == null) {
                    fill(p, note(a.getString(R.string.files_binary, human(bytes.length))));
                    return;
                }
                ZoomImage iv = new ZoomImage(a);
                iv.setBackgroundColor(t.well);
                iv.setImageBitmap(b);
                p.sub = p.sub + "  ·  " + b.getWidth() + "×" + b.getHeight() + "  ·  " + human(bytes.length);
                fill(p, iv);
            });
        }).start();
    }

    // ---- folders ----

    private View dirBody(String path, JSONArray entries) {
        int n = entries == null ? 0 : entries.length();
        if (n == 0) return note(a.getString(R.string.files_empty_dir));
        return list(n, i -> {
            JSONObject e = entries.optJSONObject(i);
            String name = e.optString("n");
            boolean dir = e.optBoolean("d");
            LinearLayout r = Ui.row(a);
            r.setPadding(dp(16), 0, dp(16), 0);
            r.addView(Ui.icon(a, dir ? R.drawable.ic_folder : R.drawable.ic_file, dir ? t.accent : t.sub, 18));
            TextView nm = Ui.oneLine(Ui.monoText(a, name, 13.5f, name.startsWith(".") ? t.faint : t.text), TextUtils.TruncateAt.MIDDLE);
            nm.setPadding(dp(12), 0, dp(8), 0);
            r.addView(nm, Ui.weight(1));
            if (dir) r.addView(Ui.icon(a, R.drawable.ic_chevron_right, t.faint, 16));
            else r.addView(Ui.monoText(a, human(e.optLong("s")), 11, t.faint));
            r.setBackground(Ui.pressable(t, null, 0));
            r.setOnClickListener(v -> openPath(path.equals("/") ? "/" + name : path + "/" + name, 0));
            r.setLayoutParams(new ListView.LayoutParams(-1, dp(46)));
            return r;
        });
    }

    // ---- uncommitted changes and diffs ----

    private void loadChanges(Page p) {
        fill(p, note(a.getString(R.string.files_loading)));
        src.files("changes --session " + Sessions.q(n(src.session())), (out, err) -> {
            JSONObject o = parse(out);
            JSONArray repos = o == null ? null : o.optJSONArray("repos");
            if (err != null || repos == null) {
                fill(p, note(a.getString(R.string.files_failed, err != null ? err : "?")));
                return;
            }
            if (repos.length() == 0) {
                fill(p, note(a.getString(R.string.files_no_changes)));
                return;
            }
            // One flat list: each project's header, then its files
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < repos.length(); i++) {
                JSONObject r = repos.optJSONObject(i);
                rows.add(new Object[]{r});
                JSONArray f = r.optJSONArray("files");
                for (int j = 0; f != null && j < f.length(); j++) rows.add(new Object[]{r, f.optJSONObject(j)});
                int more = r.optInt("total") - (f == null ? 0 : f.length());
                if (more > 0) rows.add(new Object[]{r, null, more});
            }
            fill(p, list(rows.size(), i -> changeRow(rows.get(i))));
        });
    }

    private View changeRow(Object[] row) {
        JSONObject repo = (JSONObject) row[0];
        if (row.length == 1) {
            LinearLayout h = Ui.column(a);
            h.setPadding(dp(16), dp(18), dp(16), dp(6));
            String show = repo.optString("show");
            LinearLayout top = Ui.row(a);
            top.addView(Ui.oneLine(Ui.text(a, new File(show).getName(), 15, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END));
            String branch = repo.optString("branch");
            if (!branch.isEmpty()) {
                TextView b = Ui.oneLine(Ui.monoText(a, branch, 11, t.sub), TextUtils.TruncateAt.END);
                b.setPadding(dp(7), dp(2), dp(7), dp(2));
                b.setBackground(Ui.round(t.chip, dp(6)));
                LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
                lp.leftMargin = dp(8);
                top.addView(b, lp);
            }
            TextView count = Ui.text(a, String.valueOf(repo.optInt("total")), 12, t.faint, Ui.MEDIUM);
            count.setGravity(Gravity.END);
            top.addView(count, Ui.weight(1));
            h.addView(top);
            TextView path = Ui.oneLine(Ui.monoText(a, show, 11, t.faint), TextUtils.TruncateAt.START);
            path.setPadding(0, dp(4), 0, 0);
            h.addView(path);
            h.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return h;
        }
        if (row[1] == null) {
            TextView more = note(a.getString(R.string.files_more, (Integer) row[2]));
            more.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return more;
        }
        JSONObject f = (JSONObject) row[1];
        String rel = f.optString("p"), xy = f.optString("x");
        String mark = xy.equals("??") ? "+" : xy.contains("D") ? "D" : xy.contains("A") ? "A" : xy.contains("M") ? "M" : xy.trim();
        int color = mark.equals("+") || mark.equals("A") ? t.ok : mark.equals("D") ? t.red : t.amber;
        LinearLayout r = Ui.row(a);
        r.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView m = Ui.monoText(a, mark, 12, color);
        m.setGravity(Gravity.CENTER);
        m.setBackground(Ui.round((color & 0x00ffffff) | 0x26000000, dp(6)));
        r.addView(m, Ui.lp(dp(24), dp(24)));
        LinearLayout names = Ui.column(a);
        names.setPadding(dp(12), 0, dp(8), 0);
        int slash = rel.lastIndexOf('/');
        names.addView(Ui.oneLine(Ui.monoText(a, rel.substring(slash + 1), 13.5f, xy.contains("D") ? t.faint : t.text), TextUtils.TruncateAt.MIDDLE));
        if (slash > 0) {
            TextView d = Ui.oneLine(Ui.monoText(a, rel.substring(0, slash), 11, t.faint), TextUtils.TruncateAt.START);
            d.setPadding(0, dp(3), 0, 0);
            names.addView(d);
        }
        r.addView(names, Ui.weight(1));
        String add = f.optString("a"), del = f.optString("d");
        if (!add.isEmpty() && !add.equals("-")) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (!add.equals("0")) append(sb, "+" + add, t.ok);
            if (!del.equals("0") && !del.isEmpty()) append(sb, (sb.length() > 0 ? " " : "") + "−" + del, t.red);
            r.addView(Ui.monoText(a, sb, 11.5f, t.sub));
        }
        r.setBackground(Ui.pressable(t, null, 0));
        r.setOnClickListener(v -> showDiff(repo.optString("root"), rel, xy));
        r.setLayoutParams(new ListView.LayoutParams(-1, -2));
        return r;
    }

    private static void append(android.text.SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(), 0);
    }

    private void showDiff(String root, String rel, String xy) {
        String full = root + "/" + rel;
        Page p = page(full);
        if (!xy.contains("D")) p.actions.add(headerButton(a.getString(R.string.files_full), false, () -> openPath(full, 0)));
        push(p);
        src.files("diff " + Sessions.q(root) + " " + Sessions.q(rel), (out, err) -> {
            JSONObject o = parse(out);
            if (err != null || o == null) {
                fill(p, note(a.getString(R.string.files_failed, err != null ? err : "?")));
                return;
            }
            p.sub = parentShown(o.optString("show", full));
            diffPage(p, o.optString("diff"), o.optBoolean("truncated"));
        });
    }

    private void diffPage(Page p, String diff, boolean truncated) {
        List<String> raw = splitLines(diff);
        List<String> lines = new ArrayList<>();
        List<Integer> nums = new ArrayList<>(), kinds = new ArrayList<>();
        int oldN = 0, newN = 0;
        boolean inHunk = false;
        for (String l : raw) {
            if (l.startsWith("@@")) {
                inHunk = true;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)").matcher(l);
                if (m.find()) {
                    oldN = Integer.parseInt(m.group(1));
                    newN = Integer.parseInt(m.group(2));
                }
                lines.add(l);
                nums.add(0);
                kinds.add(HUNK);
                continue;
            }
            if (!inHunk) {
                // The header (diff --git, index, ---/+++) says nothing new here; "Binary files differ" does
                if (l.startsWith("Binary")) {
                    lines.add(l);
                    nums.add(0);
                    kinds.add(META);
                }
                continue;
            }
            char c = l.isEmpty() ? ' ' : l.charAt(0);
            if (c == '+') {
                lines.add(l);
                nums.add(newN++);
                kinds.add(ADDED);
            } else if (c == '-') {
                lines.add(l);
                nums.add(oldN++);
                kinds.add(REMOVED);
            } else if (c == '\\') {
                lines.add(l);
                nums.add(0);
                kinds.add(META);
            } else if (c == 'd' && l.startsWith("diff ")) {
                inHunk = false;
            } else {
                lines.add(l);
                nums.add(newN++);
                oldN++;
                kinds.add(PLAIN);
            }
        }
        if (lines.isEmpty()) {
            fill(p, note(a.getString(R.string.files_no_diff)));
            return;
        }
        int[] n = new int[nums.size()], k = new int[kinds.size()];
        for (int i = 0; i < n.length; i++) {
            n[i] = nums.get(i);
            k[i] = kinds.get(i);
        }
        boolean[] wrap = {Prefs.raw(a).getBoolean("file_wrap", true)};
        TextView wrapBtn = headerButton(a.getString(R.string.files_wrap), wrap[0], null);
        Runnable redraw = () -> {
            View v = lineList(lines, n, k, 0, wrap[0]);
            fill(p, truncated ? withBanner(v, a.getString(R.string.files_truncated)) : v);
            wrapBtn.setTextColor(wrap[0] ? t.accent : t.text);
        };
        wrapBtn.setOnClickListener(v -> {
            wrap[0] = !wrap[0];
            Prefs.put(a, "file_wrap", wrap[0]);
            redraw.run();
        });
        p.actions.add(0, wrapBtn);
        redraw.run();
    }

    // ---- small parts ----

    private interface Rows {
        View make(int i);
    }

    /** A list that builds its rows as they scroll into view. */
    private View list(int n, Rows rows) {
        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setSelector(new ColorDrawable(0));
        lv.setClipToPadding(false);
        lv.setPadding(0, dp(4), 0, dp(40));
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return n; }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) { return rows.make(i); }
        });
        lv.setFastScrollEnabled(n > 60);
        return lv;
    }

    private View withBanner(View v, String text) {
        LinearLayout box = Ui.column(a);
        TextView b = Ui.text(a, text, 12, t.amber, Ui.MEDIUM);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setBackgroundColor(t.amberBg);
        box.addView(b, new LinearLayout.LayoutParams(-1, -2));
        box.addView(v, new LinearLayout.LayoutParams(-1, 0, 1f));
        return box;
    }

    private TextView headerButton(String s, boolean on, Runnable r) {
        TextView b = Ui.text(a, s, 13, on ? t.accent : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(11), 0, dp(11), 0);
        b.setBackground(Ui.pressable(t, Ui.round(t.chip, dp(9)), dp(9)));
        if (r != null) b.setOnClickListener(v -> r.run());
        return b;
    }

    private TextView note(String s) {
        TextView l = Ui.text(a, s, 14, t.faint, Ui.REGULAR);
        l.setLineSpacing(dp(3), 1f);
        l.setPadding(dp(20), dp(24), dp(20), dp(24));
        return l;
    }

    private static String parentShown(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : i == 0 ? "/" : "";
    }

    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", n / 1048576f);
    }

    private static JSONObject parse(String out) {
        if (out == null) return null;
        try {
            return new JSONObject(out);
        } catch (Exception e) {
            return null;
        }
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private int dp(float v) {
        return Ui.dp(a, v);
    }
}
