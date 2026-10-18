package io.github.liangjiahui01.moor;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The session list: a full page over the terminal. Refreshed from the machine every 3 seconds while shown. */
final class SessionPanel extends FrameLayout {

    interface Host {
        TermService service();

        void openSettings();

        void hideKeyboard();
    }

    private final Host host;
    private final Theme t;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView connText, note;
    private final View connDot;
    private final LinearLayout list;
    private final View newButton;
    private Sessions.Listing last;
    private long lastAt;
    private boolean loading;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 3_000);
        }
    };

    SessionPanel(Context c, Host host, Theme t) {
        super(c);
        this.host = host;
        this.t = t;
        setBackgroundColor(t.bg);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout page = Ui.column(c);
        addView(page, new LayoutParams(-1, -1));

        LinearLayout head = Ui.column(c);
        head.setPadding(dp(20), dp(20), dp(12), dp(6));
        LinearLayout top = Ui.row(c);
        LinearLayout chip = Ui.row(c);
        chip.setPadding(dp(10), 0, dp(12), 0);
        chip.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(15)), dp(15)));
        connDot = Ui.dot(c, t.faint, 7);
        chip.addView(connDot);
        connText = Ui.oneLine(Ui.text(c, "", 12.5f, t.sub, Ui.REGULAR), TextUtils.TruncateAt.END);
        connText.setPadding(dp(8), 0, 0, 0);
        chip.addView(connText);
        android.widget.ImageView chev = Ui.icon(c, R.drawable.ic_chevron_down, t.faint, 14);
        ((LinearLayout.LayoutParams) chev.getLayoutParams()).leftMargin = dp(4);
        chip.addView(chev);
        chip.setContentDescription(c.getString(R.string.a11y_switch_machine));
        chip.setOnClickListener(v -> showMachines());
        LinearLayout.LayoutParams clp = Ui.lp(-2, dp(32));
        clp.weight = 0;
        top.addView(chip, clp);
        connText.setMaxWidth(dp(230));
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(Ui.iconButton(c, t, R.drawable.ic_settings, t.sub, c.getString(R.string.k_settings), v -> host.openSettings()));
        head.addView(top);
        TextView title = Ui.text(c, c.getString(R.string.sessions_title), 32, t.text, Ui.SEMIBOLD);
        title.setPadding(0, dp(14), 0, 0);
        head.addView(title);
        page.addView(head);

        note = Ui.text(c, "", 13, t.text, Ui.REGULAR);
        note.setPadding(dp(14), dp(10), dp(14), dp(10));
        note.setBackground(Ui.round(t.chip, dp(12)));
        note.setVisibility(GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(dp(16), dp(8), dp(16), 0);
        page.addView(note, nlp);

        ScrollView sv = new ScrollView(c);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        list = Ui.column(c);
        list.setPadding(dp(16), dp(4), dp(16), dp(110));
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // "New session" sits at the bottom, within thumb reach, over a fade
        FrameLayout bottom = new FrameLayout(c);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{t.bg & 0x00ffffff, t.bg, t.bg});
        bottom.setBackground(fade);
        bottom.setPadding(dp(16), dp(28), dp(16), dp(20));
        LinearLayout nb = Ui.row(c);
        nb.setGravity(Gravity.CENTER);
        nb.setBackground(Ui.pressable(t, Ui.round(t.accent, dp(18)), dp(18)));
        nb.addView(Ui.icon(c, R.drawable.ic_plus, t.onAccent, 20));
        TextView nbt = Ui.text(c, c.getString(R.string.new_session), 16, t.onAccent, Ui.SEMIBOLD);
        nbt.setPadding(dp(8), 0, 0, 0);
        nb.addView(nbt);
        nb.setOnClickListener(v -> showCreate());
        bottom.addView(nb, new FrameLayout.LayoutParams(-1, dp(54)));
        newButton = bottom;
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    // ---- show, hide, refresh ----

    boolean shown() { return getVisibility() == VISIBLE; }

    void show(String message) {
        host.hideKeyboard();
        if (message != null) {
            note.setText(message);
            note.setVisibility(VISIBLE);
            main.postDelayed(() -> note.setVisibility(GONE), 5_000);
        }
        if (!shown()) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationY(dp(24));
            animate().alpha(1f).translationY(0).setDuration(160).start();
        }
        render();
        main.removeCallbacks(poll);
        main.post(poll);
    }

    void hide() {
        main.removeCallbacks(poll);
        if (!shown()) return;
        animate().alpha(0f).translationY(dp(24)).setDuration(140).withEndAction(() -> setVisibility(GONE)).start();
    }

    void refreshSoon() {
        if (shown()) {
            render();
            refresh();
        }
    }

    void onState(TermService.State state, String message) {
        int color = state == TermService.State.CONNECTED ? t.accent : state == TermService.State.FAILED ? t.red : t.amber;
        connDot.setBackground(Ui.round(color, dp(4)));
        String label = Prefs.load(getContext()).label(getContext());
        connText.setText(state == TermService.State.CONNECTED || message == null || message.isEmpty()
            ? label : label + " · " + message);
        newButton.setVisibility(state == TermService.State.CONNECTED ? VISIBLE : GONE);
        if (shown()) {
            if (state == TermService.State.CONNECTED) refresh();
            else render();
        }
    }

    private void refresh() {
        TermService s = host.service();
        if (s == null || loading) return;
        if (s.state() != TermService.State.CONNECTED) {
            render();
            return;
        }
        loading = true;
        s.listSessions((r, err) -> {
            loading = false;
            if (r != null) {
                last = r;
                lastAt = SystemClock.elapsedRealtime();
                render();
            } else {
                last = null;
                list.removeAllViews();
                placeholder(getContext().getString(R.string.list_failed), err, false);
            }
        });
    }

    private void render() {
        Context c = getContext();
        list.removeAllViews();
        TermService s = host.service();
        if (s == null || s.state() != TermService.State.CONNECTED) {
            placeholder(c.getString(R.string.list_connecting), c.getString(R.string.list_connecting_body), false);
            return;
        }
        if (!s.sessionsAvailable()) {
            placeholder(c.getString(R.string.list_no_python), c.getString(R.string.list_no_python_body), false);
            return;
        }
        if (last == null) {
            placeholder(c.getString(R.string.list_loading), null, false);
            return;
        }
        if (last.items.isEmpty()) {
            placeholder(c.getString(R.string.list_empty), c.getString(R.string.list_empty_body), true);
            return;
        }
        long now = last.now + (SystemClock.elapsedRealtime() - lastAt) / 1000;
        List<Sessions.Info> needs = new ArrayList<>(), running = new ArrayList<>(), idle = new ArrayList<>();
        for (Sessions.Info i : last.items) {
            if (s.attentionFor(i.name) != null) needs.add(i);
            else if (now - i.activity < 30 || !Prefs.SHELLS.contains(i.command)) running.add(i);
            else idle.add(i);
        }
        String cur = s.currentSession();
        section(c.getString(R.string.group_needs_you), needs.size(), t.amber);
        for (Sessions.Info i : needs) list.addView(card(i, now, i.name.equals(cur), s.attentionFor(i.name)));
        section(c.getString(R.string.group_running), running.size(), t.sub);
        for (Sessions.Info i : running) list.addView(card(i, now, i.name.equals(cur), null));
        section(c.getString(R.string.group_idle), idle.size(), t.sub);
        for (Sessions.Info i : idle) list.addView(compactCard(i, now, i.name.equals(cur)));
    }

    private void section(String title, int count, int color) {
        if (count == 0) return;
        LinearLayout r = Ui.row(getContext());
        r.setPadding(dp(4), dp(14), 0, dp(8));
        r.addView(Ui.text(getContext(), title, 12.5f, color, Ui.SEMIBOLD));
        TextView n = Ui.text(getContext(), String.valueOf(count), 12.5f, t.faint, Ui.MEDIUM);
        n.setPadding(dp(8), 0, 0, 0);
        r.addView(n);
        list.addView(r);
    }

    private void placeholder(String title, String body, boolean action) {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(80), dp(24), dp(24));
        TextView tt = Ui.text(c, title, 18, t.text, Ui.SEMIBOLD);
        tt.setGravity(Gravity.CENTER);
        box.addView(tt);
        if (body != null) {
            TextView b = Ui.text(c, body, 13.5f, t.sub, Ui.REGULAR);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(10), 0, 0);
            b.setLineSpacing(dp(4), 1f);
            box.addView(b);
        }
        list.addView(box);
    }

    // ---- cards ----

    private LinearLayout cardShell(boolean current, boolean attention) {
        LinearLayout card = Ui.column(getContext());
        card.setPadding(dp(16), dp(14), dp(10), dp(14));
        int fill = attention ? t.amberBg : t.surface;
        int stroke = current ? t.accent : attention ? t.amberLine : t.line;
        card.setBackground(Ui.pressable(t, Ui.outline(fill, stroke, current ? dp(1.5f) : dp(1), dp(18)), dp(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private View card(Sessions.Info i, long now, boolean current, TermService.Event attention) {
        Context c = getContext();
        long idleSec = Math.max(0, now - i.activity);
        boolean busy = idleSec < 15;
        LinearLayout card = cardShell(current, attention != null);

        LinearLayout r1 = Ui.row(c);
        r1.addView(Ui.oneLine(Ui.text(c, i.name, 16.5f, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END), Ui.weight(1));
        if (current) {
            TextView tag = Ui.text(c, c.getString(R.string.tag_current), 11, t.onAccent, Ui.SEMIBOLD);
            tag.setPadding(dp(7), dp(3), dp(7), dp(3));
            tag.setBackground(Ui.round(t.accent, dp(8)));
            r1.addView(tag);
        } else {
            r1.addView(Ui.text(c, ago(c, idleSec), 12, t.faint, Ui.REGULAR));
        }
        r1.addView(moreButton(i));
        card.addView(r1);

        LinearLayout r2 = Ui.row(c);
        r2.setPadding(0, dp(8), dp(6), 0);
        r2.addView(programChip(i));
        if (attention != null) {
            TextView st = Ui.oneLine(Ui.text(c, attention.body.isEmpty() ? c.getString(R.string.status_needs_you)
                : attention.body, 12.5f, t.amber, Ui.MEDIUM), TextUtils.TruncateAt.END);
            st.setPadding(dp(8), 0, 0, 0);
            r2.addView(st, Ui.weight(1));
        } else {
            if (busy) {
                LinearLayout.LayoutParams dl = Ui.lp(dp(6), dp(6));
                dl.leftMargin = dp(8);
                r2.addView(Ui.dot(c, t.accent, 6), dl);
                TextView st = Ui.text(c, c.getString(R.string.status_output), 12.5f, t.accent, Ui.MEDIUM);
                st.setPadding(dp(6), 0, 0, 0);
                r2.addView(st);
            }
            TextView path = Ui.oneLine(Ui.monoText(c, i.path, 11.5f, t.faint), TextUtils.TruncateAt.MIDDLE);
            path.setPadding(dp(8), 0, 0, 0);
            r2.addView(path, Ui.weight(1));
        }
        card.addView(r2);

        if (!i.preview.isEmpty()) {
            TextView pv = Ui.monoText(c, i.preview, 11.5f, t.sub);
            pv.setMaxLines(2);
            pv.setEllipsize(TextUtils.TruncateAt.END);
            pv.setLineSpacing(dp(3), 1f);
            pv.setPadding(dp(11), dp(9), dp(11), dp(9));
            pv.setBackground(Ui.round(t.well, dp(10)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.setMargins(0, dp(10), dp(6), 0);
            card.addView(pv, plp);
        }
        bindCard(card, i);
        return card;
    }

    /** Idle sessions: one row. */
    private View compactCard(Sessions.Info i, long now, boolean current) {
        Context c = getContext();
        LinearLayout card = cardShell(current, false);
        card.setPadding(dp(16), dp(10), dp(10), dp(10));
        LinearLayout r = Ui.row(c);
        r.addView(Ui.oneLine(Ui.text(c, i.name, 16, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END), Ui.weight(1));
        r.addView(programChip(i));
        TextView when = Ui.text(c, current ? c.getString(R.string.tag_current) : ago(c, Math.max(0, now - i.activity)),
            12, current ? t.accent : t.faint, Ui.REGULAR);
        when.setPadding(dp(10), 0, 0, 0);
        r.addView(when);
        r.addView(moreButton(i));
        card.addView(r);
        bindCard(card, i);
        return card;
    }

    private TextView programChip(Sessions.Info i) {
        TextView chip = Ui.monoText(getContext(), i.command.isEmpty() ? "shell" : i.command, 11.5f, t.text);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setBackground(Ui.round(t.chip, dp(11)));
        return chip;
    }

    private View moreButton(Sessions.Info i) {
        FrameLayout b = Ui.iconButton(getContext(), t, R.drawable.ic_more, t.faint,
            getContext().getString(R.string.a11y_session_menu), v -> showMenu(i));
        b.setLayoutParams(Ui.lp(dp(36), dp(36)));
        return b;
    }

    private void bindCard(View card, Sessions.Info i) {
        card.setOnClickListener(v -> open(i.name));
        card.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showMenu(i);
            return true;
        });
    }

    static String ago(Context c, long sec) {
        if (sec < 15) return c.getString(R.string.ago_now);
        if (sec < 60) return c.getString(R.string.ago_sec, sec);
        if (sec < 3600) return c.getString(R.string.ago_min, sec / 60);
        if (sec < 86400) return c.getString(R.string.ago_hour, sec / 3600);
        return c.getString(R.string.ago_day, sec / 86400);
    }

    private void open(String name) {
        TermService s = host.service();
        if (s == null) return;
        if (name.equals(s.currentSession())) {
            hide();
            return;
        }
        s.openSession(name);
    }

    // ---- create, rename, close ----

    private void showCreate() {
        TermService s = host.service();
        if (s == null || s.state() != TermService.State.CONNECTED) {
            toast(getContext().getString(R.string.err_not_connected));
            return;
        }
        List<String> taken = new ArrayList<>();
        List<String> dirs = new ArrayList<>(Prefs.recentDirs(getContext()));
        if (last != null) {
            for (Sessions.Info i : last.items) {
                taken.add(i.name);
                if (!dirs.contains(i.path) && dirs.size() < 6) dirs.add(i.path);
            }
        }
        new NewSessionSheet(getContext(), t, taken, dirs, (name, cmd, dir) -> {
            TermService sv = host.service();
            if (sv == null) return;
            if (!dir.isEmpty()) Prefs.addRecentDir(getContext(), dir);
            sv.createSession(name, cmd, dir, (v, err) -> {
                if (err != null) toast(getContext().getString(R.string.err_create_failed, err));
            });
        }).show();
    }

    private void showMenu(Sessions.Info i) {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        TextView title = Ui.oneLine(Ui.text(c, i.name, 18, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, 0, dp(12));
        box.addView(title);
        Dialog d = Ui.bottomSheet(c, t, box);
        box.addView(menuItem(c.getString(R.string.menu_open), t.text, () -> { d.dismiss(); open(i.name); }));
        box.addView(menuItem(c.getString(R.string.menu_rename), t.text, () -> { d.dismiss(); showRename(i); }));
        box.addView(menuItem(c.getString(R.string.menu_close), t.red, () -> { d.dismiss(); confirmKill(i); }));
        d.show();
    }

    // ---- machines ----

    private void showMachines() {
        Context c = getContext();
        TermService s = host.service();
        LinearLayout box = Ui.column(c);
        TextView title = Ui.text(c, c.getString(R.string.machines_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(dp(4), 0, 0, dp(10));
        box.addView(title);
        Dialog d = Ui.bottomSheet(c, t, box);
        String cur = Prefs.currentId(c);
        for (Prefs.Machine m : Prefs.machines(c)) {
            boolean isCur = m.id.equals(cur);
            LinearLayout r = Ui.row(c);
            r.setPadding(dp(14), dp(10), dp(12), dp(10));
            r.setMinimumHeight(dp(62));
            int dot = !isCur ? t.line : s != null && s.state() == TermService.State.CONNECTED ? t.accent
                : s != null && s.state() == TermService.State.FAILED ? t.red : t.amber;
            r.addView(Ui.dot(c, dot, 9));
            LinearLayout labels = Ui.column(c);
            labels.setPadding(dp(14), 0, dp(8), 0);
            labels.addView(Ui.oneLine(Ui.text(c, m.label(c), 16, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END));
            TextView sub = Ui.oneLine(Ui.monoText(c, m.configured ? m.address() : c.getString(R.string.not_set), 11.5f, t.faint),
                TextUtils.TruncateAt.MIDDLE);
            sub.setPadding(0, dp(3), 0, 0);
            labels.addView(sub);
            r.addView(labels, Ui.weight(1));
            if (isCur) {
                TextView tag = Ui.text(c, c.getString(R.string.tag_current), 11, t.onAccent, Ui.SEMIBOLD);
                tag.setPadding(dp(8), dp(3), dp(8), dp(3));
                tag.setBackground(Ui.round(t.accent, dp(8)));
                r.addView(tag);
            }
            r.setBackground(Ui.pressable(t, isCur ? Ui.round(t.chip, dp(14)) : null, dp(14)));
            r.setOnClickListener(v -> {
                d.dismiss();
                TermService sv = host.service();
                if (sv == null) return;
                if (!isCur) {
                    last = null;
                    sv.switchMachine(m.id);
                    render();
                } else if (sv.state() != TermService.State.CONNECTED) {
                    sv.retryNow();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            box.addView(r, lp);
        }
        LinearLayout add = Ui.row(c);
        add.setPadding(dp(12), 0, dp(12), 0);
        add.addView(Ui.icon(c, R.drawable.ic_plus, t.accent, 20));
        TextView al = Ui.text(c, c.getString(R.string.add_machine), 15.5f, t.accent, Ui.SEMIBOLD);
        al.setPadding(dp(12), 0, 0, 0);
        add.addView(al);
        add.setBackground(Ui.pressable(t, null, dp(14)));
        add.setOnClickListener(v -> {
            d.dismiss();
            showAdd();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(52));
        alp.topMargin = dp(6);
        box.addView(add, alp);
        d.show();
    }

    private void showAdd() {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        TextView title = Ui.text(c, c.getString(R.string.add_machine), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(dp(4), 0, 0, dp(4));
        box.addView(title);
        TextView hint = Ui.text(c, c.getString(R.string.add_machine_hint), 13, t.sub, Ui.REGULAR);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(dp(4), 0, dp(4), dp(10));
        box.addView(hint);
        Dialog d = Ui.bottomSheet(c, t, box);
        box.addView(addOption(R.drawable.ic_scan, R.string.pair_scan, () -> {
            d.dismiss();
            c.startActivity(new android.content.Intent(c, PairActivity.class).putExtra(PairActivity.EXTRA_MODE, PairActivity.MODE_SCAN));
        }));
        box.addView(addOption(R.drawable.ic_link, R.string.pair_paste, () -> {
            d.dismiss();
            c.startActivity(new android.content.Intent(c, PairActivity.class).putExtra(PairActivity.EXTRA_MODE, PairActivity.MODE_PASTE));
        }));
        box.addView(addOption(R.drawable.ic_key, R.string.add_manual, () -> {
            d.dismiss();
            c.startActivity(new android.content.Intent(c, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_NEW_MACHINE, true));
        }));
        d.show();
    }

    private View addOption(int icon, int label, Runnable r) {
        Context c = getContext();
        LinearLayout o = Ui.row(c);
        o.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout ib = new LinearLayout(c);
        ib.setGravity(Gravity.CENTER);
        ib.setBackground(Ui.round(t.chip, dp(10)));
        ib.addView(Ui.icon(c, icon, t.text, 18));
        o.addView(ib, Ui.lp(dp(36), dp(36)));
        TextView l = Ui.text(c, c.getString(label), 15.5f, t.text, Ui.MEDIUM);
        l.setPadding(dp(14), 0, 0, 0);
        o.addView(l);
        o.setBackground(Ui.pressable(t, null, dp(14)));
        o.setOnClickListener(v -> r.run());
        o.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(58)));
        return o;
    }

    private View menuItem(String label, int color, Runnable r) {
        TextView v = Ui.text(getContext(), label, 16, color, Ui.MEDIUM);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(16), 0, dp(16), 0);
        v.setBackground(Ui.pressable(t, null, dp(12)));
        v.setOnClickListener(x -> r.run());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return v;
    }

    private void showRename(Sessions.Info i) {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        TextView title = Ui.text(c, c.getString(R.string.menu_rename), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(14));
        box.addView(title);
        EditText e = NewSessionSheet.field(c, t, i.name, null);
        e.selectAll();
        box.addView(e, new LinearLayout.LayoutParams(-1, dp(50)));
        Dialog d = Ui.bottomSheet(c, t, box);
        TextView ok = Ui.primaryButton(c, t, c.getString(R.string.ok), v -> {
            String n = Sessions.cleanName(e.getText().toString());
            if (n.isEmpty() || n.equals(i.name)) {
                d.dismiss();
                return;
            }
            if (exists(n)) {
                e.setError(c.getString(R.string.err_name_taken));
                return;
            }
            d.dismiss();
            TermService s = host.service();
            if (s != null) s.renameSession(i.name, n, (v2, err) -> {
                if (err != null) toast(c.getString(R.string.err_rename_failed, err));
                refresh();
            });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(16);
        box.addView(ok, lp);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        e.requestFocus();
    }

    private void confirmKill(Sessions.Info i) {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        box.addView(Ui.text(c, c.getString(R.string.kill_title, i.name), 19, t.text, Ui.SEMIBOLD));
        String prog = i.command.isEmpty() || Prefs.SHELLS.contains(i.command) ? "" : " (" + i.command + ")";
        TextView body = Ui.text(c, c.getString(R.string.kill_body, prog), 14, t.sub, Ui.REGULAR);
        body.setPadding(0, dp(10), 0, dp(18));
        body.setLineSpacing(dp(4), 1f);
        box.addView(body);
        LinearLayout buttons = Ui.row(c);
        box.addView(buttons);
        Dialog d = Ui.bottomSheet(c, t, box);
        buttons.addView(Ui.secondaryButton(c, t, c.getString(R.string.cancel), v -> d.dismiss()),
            new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView kill = Ui.text(c, c.getString(R.string.menu_close), 16, 0xffffffff, Ui.SEMIBOLD);
        kill.setGravity(Gravity.CENTER);
        kill.setBackground(Ui.pressable(t, Ui.round(t.red, dp(16)), dp(16)));
        kill.setOnClickListener(v -> {
            d.dismiss();
            TermService s = host.service();
            if (s != null) s.killSession(i.name, (v2, err) -> {
                if (err != null) toast(c.getString(R.string.err_close_failed, err));
                refresh();
            });
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        klp.leftMargin = dp(10);
        buttons.addView(kill, klp);
        d.show();
    }

    private boolean exists(String n) {
        if (last == null) return false;
        for (Sessions.Info i : last.items) if (i.name.equals(n)) return true;
        return false;
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
