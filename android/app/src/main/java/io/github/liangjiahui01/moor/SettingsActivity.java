package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: pairing and connection, notifications, terminal look, keys. */
public class SettingsActivity extends Activity {
    /** Boolean extra: start with a fresh, empty machine (dropped again if the user leaves without connecting). */
    static final String EXTRA_NEW_MACHINE = "new_machine";

    private Theme t;
    private LinearLayout page;
    private TermService service;
    private TextView hooksValue;
    private String pub = "";

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TermService.LocalBinder) binder).service();
            checkHooks();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bindService(new Intent(this, TermService.class), conn, 0);
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_NEW_MACHINE, false)) {
            Prefs.raw(this).edit().putString("adding_from", Prefs.currentId(this)).apply();
            Prefs.addMachine(this);
        }
        build();
    }

    /** Leaving the "new machine" form without connecting: drop the empty machine, go back to the previous one. */
    @Override
    public void finish() {
        String from = Prefs.raw(this).getString("adding_from", null);
        if (from != null) {
            Prefs.raw(this).edit().remove("adding_from").apply();
            if (!Prefs.load(this).configured()) {
                Prefs.removeMachine(this, Prefs.currentId(this));
                Prefs.select(this, from);
            }
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        unbindService(conn);
        super.onDestroy();
    }

    private void build() {
        t = Theme.get(this);
        getWindow().setStatusBarColor(t.bg);
        getWindow().setNavigationBarColor(t.bg);
        getWindow().getDecorView().setSystemUiVisibility(t.dark ? 0
            : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        Prefs p = Prefs.load(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(t.bg);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(4), 0, dp(16), 0);
        bar.addView(Ui.iconButton(this, t, R.drawable.ic_back, t.sub, getString(R.string.a11y_back), v -> finish()));
        bar.addView(Ui.text(this, getString(R.string.k_settings), 18, t.text, Ui.SEMIBOLD));
        root.addView(bar, Ui.lp(-1, dp(56)));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        page = Ui.column(this);
        page.setPadding(dp(16), dp(4), dp(16), dp(32));
        sv.addView(page);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        // ---- connection ----
        int machineCount = Prefs.machines(this).size();
        if (!p.configured()) {
            TextView hello = Ui.text(this, getString(machineCount > 1 ? R.string.add_machine : R.string.welcome_title),
                26, t.text, Ui.SEMIBOLD);
            hello.setPadding(dp(4), dp(8), 0, dp(8));
            page.addView(hello);
            TextView sub = Ui.text(this, getString(R.string.welcome_body), 14, t.sub, Ui.REGULAR);
            sub.setLineSpacing(dp(4), 1f);
            sub.setPadding(dp(4), 0, dp(4), dp(6));
            page.addView(sub);
        }
        // ---- updates: first, so it's easy to find ----
        LinearLayout ab = card();
        ab.addView(valueRow(R.string.update_check, BuildConfig.VERSION_NAME, true, () -> Updates.checkNow(this)));
        page.addView(ab);

        section(R.string.sec_connection);
        LinearLayout pair = Ui.row(this);
        pair.addView(pairButton(R.drawable.ic_scan, R.string.pair_scan, PairActivity.MODE_SCAN), new LinearLayout.LayoutParams(0, dp(50), 1f));
        View paste = pairButton(R.drawable.ic_link, R.string.pair_paste, PairActivity.MODE_PASTE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        plp.leftMargin = dp(8);
        pair.addView(paste, plp);
        page.addView(pair);
        TextView pairHint = hint(getString(R.string.pair_hint));
        page.addView(pairHint);
        if (p.configured()) {
            LinearLayout setup = Ui.row(this);
            setup.setGravity(Gravity.CENTER);
            setup.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(14)), dp(14)));
            setup.addView(Ui.icon(this, R.drawable.ic_devices, t.text, 18));
            TextView sl = Ui.text(this, getString(R.string.setup_device), 14, t.text, Ui.MEDIUM);
            sl.setPadding(dp(8), 0, 0, 0);
            setup.addView(sl);
            setup.setOnClickListener(v -> showSetupDevice());
            LinearLayout.LayoutParams sdl = new LinearLayout.LayoutParams(-1, dp(50));
            sdl.topMargin = dp(12);
            page.addView(setup, sdl);
            TextView sh = hint(getString(R.string.setup_device_hint));
            sh.setPadding(sh.getPaddingLeft(), sh.getPaddingTop(), sh.getPaddingRight(), dp(14));
            page.addView(sh);
        }

        LinearLayout card = card();
        card.addView(valueRow(R.string.f_name, p.label(this), true,
            () -> editField(R.string.f_name, "name", p.name, InputType.TYPE_CLASS_TEXT)));
        card.addView(divider());
        card.addView(valueRow(R.string.f_host, p.host.isEmpty() ? getString(R.string.not_set) : p.host, true,
            () -> editField(R.string.f_host, "host", p.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)));
        card.addView(divider());
        card.addView(valueRow(R.string.f_port, String.valueOf(p.port), true,
            () -> editField(R.string.f_port, "port", String.valueOf(p.port), InputType.TYPE_CLASS_NUMBER)));
        card.addView(divider());
        card.addView(valueRow(R.string.f_user, p.user.isEmpty() ? getString(R.string.not_set) : p.user, true,
            () -> editField(R.string.f_user, "user", p.user, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)));
        card.addView(divider());
        card.addView(valueRow(R.string.f_default_cmd, p.startup.isEmpty() ? getString(R.string.run_shell) : p.startup, true,
            () -> editField(R.string.f_default_cmd, "startup", p.startup, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)));
        page.addView(card);
        if (p.configured() && machineCount > 1) {
            TextView remove = Ui.text(this, getString(R.string.remove_machine), 14.5f, t.red, Ui.REGULAR);
            remove.setGravity(Gravity.CENTER_VERTICAL);
            remove.setPadding(dp(16), 0, dp(16), 0);
            remove.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(16)), dp(16)));
            remove.setOnClickListener(v -> confirmRemove(p.label(this)));
            LinearLayout.LayoutParams rlp = Ui.lp(-1, dp(52));
            rlp.topMargin = dp(10);
            page.addView(remove, rlp);
        }
        if (!p.configured()) {
            TextView go = Ui.primaryButton(this, t, getString(R.string.connect), v -> saveAndConnect());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(52));
            glp.topMargin = dp(12);
            page.addView(go, glp);
        }

        // ---- notifications ----
        section(R.string.sec_notifications);
        LinearLayout n = card();
        n.addView(toggleRow(R.string.n_attention, R.string.n_attention_sub, p.notifyAttention, "notify_attention"));
        n.addView(divider());
        n.addView(toggleRow(R.string.n_done, R.string.n_done_sub, p.notifyDone, "notify_done"));
        n.addView(divider());
        n.addView(toggleRow(R.string.n_bell, R.string.n_bell_sub, p.notifyBell, "notify_bell"));
        n.addView(divider());
        View hooksRow = valueRow(R.string.n_hooks, getString(R.string.checking), true, this::toggleHooks);
        hooksValue = (TextView) ((LinearLayout) hooksRow).getChildAt(1);
        n.addView(hooksRow);
        page.addView(n);
        page.addView(hint(getString(R.string.n_hint)));

        // ---- terminal ----
        section(R.string.sec_terminal);
        LinearLayout term = card();
        term.addView(themeRow(p.theme));
        term.addView(divider());
        term.addView(toggleRow(R.string.t_ime, R.string.t_ime_sub, p.imeCompat, "ime_compat"));
        page.addView(term);

        // ---- voice input ----
        section(R.string.sec_voice);
        VoiceInput.Config vc = VoiceInput.Config.load(this);
        LinearLayout voice = card();
        // A masked password field: keyboards switch to plain Latin input and keep no history
        int secret = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        voice.addView(valueRow(R.string.v_api_key, vc.apiKey.isEmpty() ? getString(R.string.not_set) : mask(vc.apiKey), true,
            () -> editField(R.string.v_api_key, "voice_api_key", vc.apiKey, secret)));
        voice.addView(divider());
        voice.addView(valueRow(R.string.v_app_id, vc.appId.isEmpty() ? getString(R.string.not_set) : vc.appId, true,
            () -> editField(R.string.v_app_id, "voice_app_id", vc.appId, secret)));
        voice.addView(divider());
        voice.addView(valueRow(R.string.v_access_token, vc.accessToken.isEmpty() ? getString(R.string.not_set) : mask(vc.accessToken), true,
            () -> editField(R.string.v_access_token, "voice_access_token", vc.accessToken, secret)));
        voice.addView(divider());
        boolean v1 = vc.model.equals(VoiceInput.MODEL_1);
        voice.addView(valueRow(R.string.v_model, getString(v1 ? R.string.v_model_1 : R.string.v_model_2), true, () -> {
            Prefs.put(this, "voice_model", v1 ? VoiceInput.MODEL_2 : VoiceInput.MODEL_1);
            rebuild();
        }));
        page.addView(voice);
        page.addView(hint(getString(R.string.v_hint)));

        // ---- keys ----
        section(R.string.sec_keys);
        LinearLayout keys = card();
        LinearLayout kb = Ui.column(this);
        kb.setPadding(dp(16), dp(14), dp(16), dp(14));
        kb.addView(Ui.text(this, getString(R.string.key_title), 14.5f, t.text, Ui.REGULAR));
        TextView key = Ui.monoText(this, "", 11.5f, t.sub);
        key.setTextIsSelectable(true);
        key.setLineSpacing(dp(3), 1f);
        key.setPadding(0, dp(10), 0, dp(12));
        try {
            pub = Keys.publicKey(this);
            key.setText(pub);
        } catch (Exception e) {
            key.setText(getString(R.string.key_failed, String.valueOf(e)));
        }
        kb.addView(key);
        LinearLayout kbtn = Ui.row(this);
        kbtn.addView(Ui.secondaryButton(this, t, getString(R.string.key_copy), v -> copy(pub, R.string.copied)), Ui.lp(-2, dp(38)));
        View cmd = Ui.secondaryButton(this, t, getString(R.string.key_copy_cmd), v -> copy(
            "mkdir -p ~/.ssh && echo '" + pub + "' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys",
            R.string.copied));
        LinearLayout.LayoutParams clp = Ui.lp(-2, dp(38));
        clp.leftMargin = dp(8);
        kbtn.addView(cmd, clp);
        kb.addView(kbtn);
        keys.addView(kb);
        keys.addView(divider());
        TextView forget = Ui.text(this, getString(R.string.key_forget_host), 14.5f, t.red, Ui.REGULAR);
        forget.setGravity(Gravity.CENTER_VERTICAL);
        forget.setPadding(dp(16), 0, dp(16), 0);
        forget.setBackground(Ui.pressable(t, null, dp(16)));
        forget.setOnClickListener(v -> {
            new java.io.File(getFilesDir(), "known_hosts").delete();
            Toast.makeText(this, R.string.key_forgot, Toast.LENGTH_SHORT).show();
        });
        keys.addView(forget, Ui.lp(-1, dp(52)));
        page.addView(keys);
        page.addView(hint(getString(R.string.key_hint)));

        TextView about = Ui.text(this, "Moor " + BuildConfig.VERSION_NAME + " · github.com/liangjiahui01/moor", 12, t.faint, Ui.REGULAR);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(28), 0, 0);
        about.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/liangjiahui01/moor"))));
        page.addView(about);
    }

    // ---- actions ----

    private void saveAndConnect() {
        Prefs p = Prefs.load(this);
        if (p.host.isEmpty() || p.user.isEmpty()) {
            Toast.makeText(this, R.string.err_need_host_user, Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.save(this, p.host, p.port, p.user, p.startup);
        Prefs.raw(this).edit().remove("adding_from").apply();
        if (service != null) service.switchMachine(Prefs.currentId(this));
        startActivity(new Intent(this, TermActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void reconnect() {
        if (service != null && Prefs.load(this).configured()) service.reconnectWithNewSettings();
    }

    private void editField(int label, String key, String value, int type) {
        LinearLayout box = Ui.column(this);
        TextView title = Ui.text(this, getString(label), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(14));
        box.addView(title);
        EditText e = NewSessionSheet.field(this, t, value, null);
        e.setInputType(type);
        if ((type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            e.setImeOptions(e.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        e.selectAll();
        box.addView(e, Ui.lp(-1, dp(50)));
        Dialog d = Ui.bottomSheet(this, t, box);
        TextView ok = Ui.primaryButton(this, t, getString(R.string.ok), v -> {
            String s = e.getText().toString().trim();
            if (key.equals("port")) {
                int port;
                try {
                    port = Integer.parseInt(s);
                } catch (NumberFormatException x) {
                    port = 22;
                }
                Prefs.putPort(this, port);
            } else {
                Prefs.put(this, key, s);
            }
            d.dismiss();
            if (!key.equals("startup") && !key.equals("name") && !key.startsWith("voice_")) reconnect();
            rebuild();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(16);
        box.addView(ok, lp);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        e.requestFocus();
    }

    private void rebuild() {
        int y = page != null && page.getParent() instanceof ScrollView ? ((ScrollView) page.getParent()).getScrollY() : 0;
        build();
        checkHooks();
        page.post(() -> ((ScrollView) page.getParent()).scrollTo(0, y));
    }

    private void checkHooks() {
        if (hooksValue == null) return;
        if (service == null || service.state() != TermService.State.CONNECTED) {
            hooksValue.setText(R.string.n_hooks_offline);
            return;
        }
        if (!service.helperAvailable()) {
            hooksValue.setText(R.string.n_hooks_no_python);
            return;
        }
        service.run(Sessions.hooksCommand("status"), (out, err) -> {
            if (out == null) hooksValue.setText(R.string.n_hooks_offline);
            else hooksValue.setText(out.contains("not installed") ? R.string.n_hooks_off : R.string.n_hooks_on);
        });
    }

    private void toggleHooks() {
        if (service == null || service.state() != TermService.State.CONNECTED || !service.helperAvailable()) {
            Toast.makeText(this, R.string.n_hooks_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = hooksValue.getText().toString().equals(getString(R.string.n_hooks_on));
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, getString(on ? R.string.n_hooks_remove_title : R.string.n_hooks_add_title), 19, t.text, Ui.SEMIBOLD));
        TextView body = Ui.text(this, getString(on ? R.string.n_hooks_remove_body : R.string.n_hooks_add_body), 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(10), 0, dp(18));
        box.addView(body);
        LinearLayout buttons = Ui.row(this);
        box.addView(buttons);
        Dialog d = Ui.bottomSheet(this, t, box);
        buttons.addView(Ui.secondaryButton(this, t, getString(R.string.cancel), v -> d.dismiss()), new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView go = Ui.primaryButton(this, t, getString(on ? R.string.n_hooks_remove : R.string.n_hooks_add), v -> {
            d.dismiss();
            service.run(Sessions.hooksCommand(on ? "uninstall" : "install"), (out, err) -> {
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                checkHooks();
            });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp.leftMargin = dp(10);
        buttons.addView(go, lp);
        d.show();
    }

    private void confirmRemove(String label) {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, getString(R.string.remove_machine_title, label), 19, t.text, Ui.SEMIBOLD));
        TextView body = Ui.text(this, getString(R.string.remove_machine_body), 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(10), 0, dp(18));
        box.addView(body);
        LinearLayout buttons = Ui.row(this);
        box.addView(buttons);
        Dialog d = Ui.bottomSheet(this, t, box);
        buttons.addView(Ui.secondaryButton(this, t, getString(R.string.cancel), v -> d.dismiss()), new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView go = Ui.text(this, getString(R.string.remove), 16, 0xffffffff, Ui.SEMIBOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(Ui.pressable(t, Ui.round(t.red, dp(14)), dp(14)));
        go.setOnClickListener(v -> {
            d.dismiss();
            Prefs.removeMachine(this, Prefs.currentId(this));
            if (service != null) service.switchMachine(Prefs.currentId(this));
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp.leftMargin = dp(10);
        buttons.addView(go, lp);
        d.show();
    }

    private static String mask(String s) {
        return s.length() <= 8 ? "••••" : s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private void copy(String text, int toast) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("moor", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ---- building blocks ----

    private void section(int res) {
        TextView s = Ui.text(this, getString(res), 12.5f, t.faint, Ui.SEMIBOLD);
        s.setPadding(dp(4), dp(22), 0, dp(8));
        page.addView(s);
    }

    private TextView hint(String s) {
        TextView h = Ui.text(this, s, 12, t.faint, Ui.REGULAR);
        h.setLineSpacing(dp(3), 1f);
        h.setPadding(dp(4), dp(8), dp(4), 0);
        return h;
    }

    private LinearLayout card() {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.outline(t.surface, t.line, dp(1), dp(16)));
        c.setClipToOutline(true);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(t.divider);
        v.setLayoutParams(Ui.lp(-1, 1));
        return v;
    }

    private View valueRow(int label, String value, boolean chevron, Runnable onClick) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(16), 0, dp(12), 0);
        r.addView(Ui.text(this, getString(label), 14.5f, t.text, Ui.REGULAR), Ui.weight(1));
        TextView v = Ui.oneLine(Ui.text(this, value, 13.5f, t.sub, Ui.REGULAR), TextUtils.TruncateAt.MIDDLE);
        v.setMaxWidth(dp(200));
        r.addView(v);
        if (chevron) {
            android.widget.ImageView c = Ui.icon(this, R.drawable.ic_chevron_right, t.faint, 16);
            ((LinearLayout.LayoutParams) c.getLayoutParams()).leftMargin = dp(6);
            r.addView(c);
        }
        r.setBackground(Ui.pressable(t, null, 0));
        r.setOnClickListener(x -> onClick.run());
        r.setLayoutParams(Ui.lp(-1, dp(52)));
        return r;
    }

    private View toggleRow(int label, int sub, boolean on, String key) {
        return toggleRow(label, sub, on, v -> Prefs.put(this, key, v));
    }

    private View toggleRow(int label, int sub, boolean on, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(16), dp(10), dp(14), dp(10));
        r.setMinimumHeight(dp(62));
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, getString(label), 14.5f, t.text, Ui.REGULAR));
        TextView s = Ui.text(this, getString(sub), 12, t.faint, Ui.REGULAR);
        s.setPadding(0, dp(4), 0, 0);
        labels.addView(s);
        r.addView(labels, Ui.weight(1));
        FrameLayout sw = new FrameLayout(this);
        View knob = new View(this);
        sw.addView(knob, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL));
        final boolean[] state = {on};
        Runnable paint = () -> {
            sw.setBackground(Ui.round(state[0] ? t.accent : (t.dark ? 0xff45475a : 0xffccd0da), dp(14)));
            knob.setBackground(Ui.round(state[0] ? t.onAccent : (t.dark ? t.faint : 0xffffffff), dp(11)));
            ((FrameLayout.LayoutParams) knob.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL | (state[0] ? Gravity.END : Gravity.START);
            knob.requestLayout();
        };
        sw.setPadding(dp(3), 0, dp(3), 0);
        paint.run();
        sw.setContentDescription(getString(label));
        r.addView(sw, Ui.lp(dp(46), dp(28)));
        r.setBackground(Ui.pressable(t, null, 0));
        r.setOnClickListener(v -> {
            state[0] = !state[0];
            onChange.accept(state[0]);
            paint.run();
        });
        return r;
    }

    private View themeRow(String current) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(16), 0, dp(10), 0);
        r.addView(Ui.text(this, getString(R.string.t_theme), 14.5f, t.text, Ui.REGULAR), Ui.weight(1));
        LinearLayout seg = Ui.row(this);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(Ui.round(t.chip, dp(10)));
        String[] keys = {"system", "light", "dark"};
        int[] labels = {R.string.t_theme_system, R.string.t_theme_light, R.string.t_theme_dark};
        for (int i = 0; i < 3; i++) {
            String k = keys[i];
            boolean on = k.equals(current);
            TextView b = Ui.text(this, getString(labels[i]), 12.5f, on ? t.text : t.sub, on ? Ui.SEMIBOLD : Ui.REGULAR);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(10), 0, dp(10), 0);
            if (on) b.setBackground(Ui.round(t.surface, dp(8)));
            b.setOnClickListener(v -> {
                Prefs.put(this, "theme", k);
                rebuild();
            });
            seg.addView(b, Ui.lp(-2, dp(30)));
        }
        r.addView(seg);
        r.setLayoutParams(Ui.lp(-1, dp(56)));
        return r;
    }

    // ---- set up another device: one-time codes for every machine, shown as a QR code ----

    private void showSetupDevice() {
        LinearLayout box = Ui.row(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(8), 0, dp(12));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(t.accent));
        box.addView(spin, Ui.lp(dp(26), dp(26)));
        TextView l = Ui.text(this, getString(R.string.setup_preparing), 15, t.text, Ui.MEDIUM);
        l.setPadding(dp(14), 0, 0, 0);
        box.addView(l);
        Dialog d = Ui.bottomSheet(this, t, box);
        d.show();
        new Thread(() -> {
            byte[] helper;
            try (java.io.InputStream in = getAssets().open("server/moor")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                helper = b.toByteArray();
            } catch (java.io.IOException e) {
                helper = new byte[0];
            }
            Setup.Invites inv = Setup.makeInvites(this, helper);
            runOnUiThread(() -> {
                if (isFinishing() || !d.isShowing()) return;
                d.dismiss();
                showSetupQr(inv);
            });
        }, "Setup").start();
    }

    private void showSetupQr(Setup.Invites inv) {
        LinearLayout box = Ui.column(this);
        if (inv.links.isEmpty()) {
            box.addView(Ui.text(this, getString(R.string.setup_none), 18, t.text, Ui.SEMIBOLD));
            if (!inv.skipped.isEmpty()) box.addView(hint(TextUtils.join("\n", inv.skipped)));
            Ui.bottomSheet(this, t, box).show();
            return;
        }
        box.addView(Ui.text(this, getString(R.string.setup_qr_title), 20, t.text, Ui.SEMIBOLD));

        // Step 1 puts Moor on the new device (from this phone over Wi-Fi), step 2 sets it up
        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(0, dp(12), 0, 0);
        TextView tabInstall = tab(getString(R.string.setup_tab_install));
        TextView tabSetup = tab(getString(R.string.setup_tab_setup));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        tabs.addView(tabInstall, tlp);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        tlp2.leftMargin = dp(8);
        tabs.addView(tabSetup, tlp2);
        box.addView(tabs);

        TextView body = Ui.text(this, "", 13.5f, t.sub, Ui.REGULAR);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(12), 0, dp(14));
        box.addView(body);

        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setBackground(Ui.round(0xffffffff, dp(14)));
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        int side = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels - dp(80));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(side, side);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(qr, qlp);

        String until = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(inv.expiresAt));
        final boolean[] voice = {Setup.hasVoice(this)};
        LinearLayout voiceCard = null;
        if (Setup.hasVoice(this)) {
            voiceCard = card();
            voiceCard.addView(toggleRow(R.string.setup_voice, R.string.setup_voice_sub, voice[0], v -> {
                voice[0] = v;
                qr.setImageBitmap(qrBitmap(Setup.bundle(this, inv, voice[0]), side - dp(20)));
            }));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = dp(14);
            box.addView(voiceCard, clp);
        }
        final View voiceRow = voiceCard;
        java.util.function.Consumer<Boolean> show = install -> {
            styleTab(tabInstall, install);
            styleTab(tabSetup, !install);
            if (install) {
                body.setText(getString(R.string.setup_install_body));
                qr.setImageBitmap(qrBitmap(APK_URL, side - dp(20)));
            } else {
                body.setText(getString(R.string.setup_qr_body, until));
                qr.setImageBitmap(qrBitmap(Setup.bundle(this, inv, voice[0]), side - dp(20)));
            }
            if (voiceRow != null) voiceRow.setVisibility(install ? View.GONE : View.VISIBLE);
        };
        tabInstall.setOnClickListener(v -> show.accept(true));
        tabSetup.setOnClickListener(v -> show.accept(false));
        show.accept(true);

        if (!inv.skipped.isEmpty()) box.addView(hint(getString(R.string.setup_skipped, TextUtils.join("; ", inv.skipped))));
        Dialog d = Ui.bottomSheet(this, t, box);
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.show();
    }

    /** This version's APK on GitHub: the install code downloads it straight away. */
    private static final String APK_URL = "https://github.com/liangjiahui01/moor/releases/download/v"
        + BuildConfig.VERSION_NAME + "/moor-" + BuildConfig.VERSION_NAME + ".apk";

    private TextView tab(String label) {
        TextView v = Ui.text(this, label, 13.5f, t.text, Ui.MEDIUM);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private void styleTab(TextView v, boolean on) {
        v.setBackground(on ? Ui.round(t.accent, dp(19)) : Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(19)), dp(19)));
        v.setTextColor(on ? t.bg : t.text);
    }


    private static android.graphics.Bitmap qrBitmap(String text, int px) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = m.get(x, y) ? 0xff000000 : 0xffffffff;
            return android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.RGB_565);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private View pairButton(int icon, int label, String mode) {
        LinearLayout b = Ui.row(this);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(14)), dp(14)));
        b.addView(Ui.icon(this, icon, t.text, 18));
        TextView l = Ui.text(this, getString(label), 14, t.text, Ui.MEDIUM);
        l.setPadding(dp(8), 0, 0, 0);
        b.addView(l);
        b.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class).putExtra(PairActivity.EXTRA_MODE, mode)));
        return b;
    }

    /** Not configured yet and the clipboard holds a pairing link: offer it right away. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || Prefs.load(this).configured()) return;
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        Pairing p = Pairing.parse(text == null ? null : text.toString());
        if (p == null || p.expired()) return;
        android.content.SharedPreferences raw = Prefs.raw(this);
        if (p.id.equals(raw.getString("pair_seen", ""))) return;
        raw.edit().putString("pair_seen", p.id).apply();
        startActivity(new Intent(this, PairActivity.class).putExtra(PairActivity.EXTRA_MODE, PairActivity.MODE_PASTE));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
