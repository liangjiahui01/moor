package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TermActivity extends Activity implements TerminalViewClient, TerminalSessionClient, TermService.Screen,
    SessionPanel.Host {

    private static final String TAG = "TermActivity";

    private Theme t;
    private TerminalView view;
    private TextView status, title, subtitle, countLabel;
    private View titleDot;
    private SessionPanel panel;
    private View topBar;
    private FrameLayout termBox;
    /** The session list is up (or on its way up); it hides with an animation, so panel.shown() lags. */
    private boolean listVisible;
    /** Terminal without the title bar and the status bar; the session list still shows both. */
    private boolean fullscreen;
    private TermService service;
    private int fontSize;
    private boolean ctrlOn, altOn, volCtrl;
    private TextView ctrlKey, altKey;
    private View moreKey, micKey;
    private LinearLayout keyArea, morePanel;
    private TextView fontLabel;
    private boolean themeDark;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TermService.LocalBinder) binder).service();
            // Theme may have changed since the emulator was created
            if (service.term.getEmulator() != null) service.term.getEmulator().mColors.reset();
            view.attachSession(service.term);
            service.attachScreen(TermActivity.this, TermActivity.this);
            view.onScreenUpdated();
            service.connect();
            service.onScreenResumed();
            handleOpenIntent(getIntent());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.load(this).configured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        t = Theme.get(this);
        themeDark = t.dark;
        t.applyTerminalColors();
        getWindow().setStatusBarColor(t.bg);
        getWindow().setNavigationBarColor(t.keyBar);
        if (!t.dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        // Don't pop the keyboard when coming back to the terminal; only the keyboard key opens it
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(t.bg);
        // In fullscreen the window covers the camera-cutout strip too, so it can be painted like the terminal
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wa = getWindow().getAttributes();
            wa.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wa);
        }
        topBar = buildTopBar();
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));

        termBox = new FrameLayout(this);
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setBackgroundColor(t.terminalBg());
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        // TerminalView ignores its own padding, so the gutter lives on the container
        termBox.setBackgroundColor(t.terminalBg());
        termBox.setPadding(dp(6), dp(2), dp(4), 0);
        termBox.addView(view, new FrameLayout.LayoutParams(-1, -1));

        status = Ui.text(this, "", 12, t.onAccent, Ui.MEDIUM);
        status.setPadding(dp(10), dp(5), dp(10), dp(5));
        status.setVisibility(View.GONE);
        status.setOnClickListener(v -> {
            if (status.getText().toString().equals(getString(R.string.state_auth_rejected))) {
                openSettings();
            } else if (service != null) {
                service.retryNow();
            }
        });
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        slp.setMargins(0, dp(8), dp(8), 0);
        termBox.addView(status, slp);
        root.addView(termBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        fullscreen = Prefs.raw(this).getBoolean("fullscreen", false);
        keyArea = Ui.column(this);
        keyArea.setBackgroundColor(t.keyBar);
        buildKeys();
        root.addView(keyArea, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout top = new FrameLayout(this);
        top.addView(root, new FrameLayout.LayoutParams(-1, -1));
        panel = new SessionPanel(this, this, t);
        top.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(top);
        // No previous session: open straight into the session list
        if (Prefs.load(this).session == null) panel.show(null);
        applyBars();

        fontSize = Prefs.load(this).fontSize;
        if (fontSize <= 0) fontSize = evenPx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
            getResources().getDisplayMetrics()));
        // setTextSize first: setTypeface needs the renderer it creates
        view.setTextSize(fontSize);
        view.setTypeface(Ui.mono(this));

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Intent svc = new Intent(this, TermService.class);
        startForegroundService(svc);
        bindService(svc, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    /** A notification tap carries the session to open. */
    private void handleOpenIntent(Intent intent) {
        if (intent == null || service == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            intent.setAction(null);
            if (u != null) {
                pendingImage = u;
                sendPendingImage();
            }
            return;
        }
        String s = intent.getStringExtra(TermService.EXTRA_SESSION);
        if (s == null) return;
        intent.removeExtra(TermService.EXTRA_SESSION);
        if (service.state() == TermService.State.CONNECTED) {
            if (!s.equals(service.currentSession())) service.openSession(s);
            else panel.hide();
        } else {
            Prefs.saveSession(this, s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (topBar != null) applyBars();
        if (Theme.get(this).dark != themeDark) {
            recreate();
            return;
        }
        view.requestFocus();
        // The IME compatibility setting may have changed: let the keyboard reconnect with the new input type
        getSystemService(InputMethodManager.class).restartInput(view);
        if (service != null) {
            service.attachScreen(this, this);
            view.onScreenUpdated();
            service.onScreenResumed();
        }
        refreshTitle();
    }

    @Override
    protected void onPause() {
        cancelVoice();
        if (service != null) service.detachScreen();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.detachScreen();
            unbindService(conn);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Terminal → session list; list → back to the session, or to the background if there is none
        if (!panel.shown()) {
            panel.show(null);
        } else if (service != null && service.currentSession() != null) {
            panel.hide();
        } else {
            moveTaskToBack(true);
        }
    }

    // ---- top bar ----

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(4), 0, dp(10), 0);
        bar.setBackgroundColor(t.bg);
        bar.addView(Ui.iconButton(this, t, R.drawable.ic_back, t.sub, getString(R.string.a11y_sessions), v -> panel.show(null)));

        LinearLayout titles = Ui.column(this);
        titles.setPadding(dp(2), 0, dp(8), 0);
        LinearLayout r1 = Ui.row(this);
        title = Ui.oneLine(Ui.text(this, "", 15.5f, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END);
        r1.addView(title, new LinearLayout.LayoutParams(-2, -2, 0f));
        titleDot = Ui.dot(this, t.accent, 6);
        LinearLayout.LayoutParams dlp = Ui.lp(dp(6), dp(6));
        dlp.leftMargin = dp(7);
        r1.addView(titleDot, dlp);
        titles.addView(r1);
        subtitle = Ui.oneLine(Ui.monoText(this, "", 11.5f, t.faint), TextUtils.TruncateAt.MIDDLE);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        bar.addView(titles, Ui.weight(1));

        LinearLayout chip = Ui.row(this);
        chip.setPadding(dp(10), 0, dp(12), 0);
        chip.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(16)), dp(16)));
        chip.addView(Ui.icon(this, R.drawable.ic_sessions, t.sub, 15));
        countLabel = Ui.text(this, "", 12.5f, t.text, Ui.MEDIUM);
        countLabel.setPadding(dp(6), 0, 0, 0);
        chip.addView(countLabel);
        chip.setContentDescription(getString(R.string.a11y_sessions));
        chip.setOnClickListener(v -> panel.show(null));
        bar.addView(chip, Ui.lp(-2, dp(32)));
        return bar;
    }

    /** Fills the top bar from a fresh session listing. */
    private void refreshTitle() {
        if (service == null || title == null) return;
        String cur = service.currentSession();
        title.setText(cur != null ? cur : getString(R.string.app_name));
        if (service.state() != TermService.State.CONNECTED || !service.sessionsAvailable()) {
            subtitle.setText(service.stateMessage());
            countLabel.setText("");
            return;
        }
        service.listSessions((r, err) -> {
            if (r == null) return;
            countLabel.setText(String.valueOf(r.items.size()));
            for (Sessions.Info i : r.items) {
                if (i.name.equals(service.currentSession())) {
                    subtitle.setText((i.command.isEmpty() ? "shell" : i.command) + " · " + i.path);
                    long idle = Math.max(0, r.now - i.activity);
                    titleDot.setBackground(Ui.round(idle < 15 ? t.accent : t.faint, dp(3)));
                }
            }
        });
    }

    private final Runnable titlePoll = new Runnable() {
        @Override
        public void run() {
            if (!panel.shown()) refreshTitle();
            main.postDelayed(this, 10_000);
        }
    };

    // ---- key bar ----
    // Portrait: one row of common keys; swipe up on a key for the small label in its corner;
    // the last key opens a sheet with symbols, Ctrl combos and actions. Wide screens get more keys in the row.

    private static final String[] MAIN = {"ESC", "TAB", "CTRL", "ALT", "←", "↓", "↑", "→", "🎤", "⌨", "⋯"};
    private static final String[] WIDE = {"ESC", "TAB", "CTRL", "ALT", "←", "↓", "↑", "→",
        "HOME", "END", "PGUP", "PGDN", "/", "-", "|", "~", "🎤", "⌨", "⋯"};
    private static final String[][] SYMBOLS = {
        {"/", "-", "_", "|", "~", "`", "=", "\\", "$", "&"},
        {"'", "\"", "<", ">", "(", ")", "[", "]", "{", "}"},
    };
    private static final String[] CTRL = {"^C", "^D", "^R", "^L", "^Z", "^A", "^E", "^U", "^W", "^O"};
    private static final int[] CTRL_LABELS = {R.string.k_interrupt, R.string.k_eof, R.string.k_history, R.string.k_clear,
        R.string.k_suspend, R.string.k_line_start, R.string.k_line_end, R.string.k_kill_line, R.string.k_kill_word,
        R.string.k_details};
    private static final Map<String, String> UP = new HashMap<>();
    private static final Set<String> REPEAT = new HashSet<>(Arrays.asList("←", "↓", "↑", "→", "PGUP", "PGDN"));
    static {
        UP.put("ESC", "^C");
        UP.put("TAB", "⇧TAB");
        UP.put("←", "HOME");
        UP.put("→", "END");
        UP.put("↑", "PGUP");
        UP.put("↓", "PGDN");
    }

    private void buildKeys() {
        keyArea.removeAllViews();
        ctrlKey = altKey = null;
        moreKey = micKey = null;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        morePanel = buildMorePanel();
        morePanel.setVisibility(View.GONE);
        keyArea.addView(morePanel);
        if (voiceBar == null) buildVoiceBar();
        if (voiceBar.getParent() != null) ((android.view.ViewGroup) voiceBar.getParent()).removeView(voiceBar);
        keyArea.addView(voiceBar);
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(6), dp(4), dp(6), dp(5));
        for (String k : wide ? WIDE : MAIN) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.setMargins(dp(2.5f), 0, dp(2.5f), 0);
            row.addView(keyCell(k, false), lp);
        }
        keyArea.addView(row);
        refreshToggles();
    }

    private View keyCell(String k, boolean inPanel) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackground(Ui.round(t.key, dp(10)));
        if (!t.dark) cell.setBackground(Ui.outline(t.key, t.line, dp(1), dp(10)));
        if (k.equals("⌨") || k.equals("⋯") || k.equals("🎤")) {
            int res = k.equals("⌨") ? R.drawable.ic_keyboard : k.equals("🎤") ? R.drawable.ic_mic : R.drawable.ic_more;
            android.widget.ImageView i = Ui.icon(this, res, t.text, 18);
            cell.addView(i, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            cell.setContentDescription(getString(k.equals("⌨") ? R.string.a11y_keyboard
                : k.equals("🎤") ? R.string.a11y_voice : R.string.a11y_more_keys));
            if (k.equals("⋯")) moreKey = cell;
            if (k.equals("🎤")) micKey = cell;
            bindKey(cell, null, k, null, inPanel);
            return cell;
        }
        TextView b = Ui.text(this, k, keySize(k), t.text, Ui.MEDIUM);
        b.setTypeface(Ui.mono(this));
        b.setGravity(Gravity.CENTER);
        cell.addView(b, new FrameLayout.LayoutParams(-1, -1));
        String up = UP.get(k);
        if (up != null) {
            TextView hint = Ui.text(this, up.equals("⇧TAB") ? "⇧" : up, 8, t.faint, Ui.REGULAR);
            hint.setPadding(0, dp(3), dp(4), 0);
            cell.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        }
        if (k.equals("CTRL")) ctrlKey = b;
        if (k.equals("ALT")) altKey = b;
        bindKey(cell, b, k, up, inPanel);
        return cell;
    }

    private static float keySize(String k) {
        return k.length() > 3 ? 11 : k.length() > 1 ? 12 : 15;
    }

    private LinearLayout buildMorePanel() {
        LinearLayout p = Ui.column(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.keyBar);
        float r = dp(20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        p.setBackground(bg);
        p.setPadding(dp(10), dp(12), dp(10), 0);

        p.addView(sectionLabel(R.string.k_symbols));
        for (String[] rowKeys : SYMBOLS) {
            LinearLayout row = Ui.row(this);
            for (String k : rowKeys) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
                lp.setMargins(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f));
                row.addView(keyCell(k, true), lp);
            }
            p.addView(row);
        }

        p.addView(sectionLabel(R.string.k_ctrl));
        for (int start = 0; start < CTRL.length; start += 5) {
            LinearLayout row = Ui.row(this);
            for (int i = start; i < start + 5; i++) {
                String k = CTRL[i];
                LinearLayout cell = Ui.column(this);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(t.dark ? Ui.round(t.key, dp(9)) : Ui.outline(t.key, t.line, dp(1), dp(9)));
                TextView a = Ui.text(this, k, 12.5f, t.text, Ui.MEDIUM);
                a.setTypeface(Ui.mono(this));
                a.setGravity(Gravity.CENTER);
                cell.addView(a);
                TextView b = Ui.text(this, getString(CTRL_LABELS[i]), 9.5f, t.faint, Ui.REGULAR);
                b.setPadding(dp(2), dp(3), dp(2), 0);
                b.setGravity(Gravity.CENTER);
                Ui.oneLine(b, TextUtils.TruncateAt.END);
                cell.addView(b);
                cell.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    pressKey(k);
                    setMore(false);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                lp.setMargins(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f));
                row.addView(cell, lp);
            }
            p.addView(row);
        }

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(actionTile(R.drawable.ic_paste, R.string.k_paste, () -> onPasteTextFromClipboard(service.term)), tileLp());
        actions.addView(actionTile(R.drawable.ic_input, R.string.k_input, this::showInputBox), tileLp());
        actions.addView(actionTile(R.drawable.ic_image, R.string.k_image, this::showImageSource), tileLp());
        actions.addView(actionTile(R.drawable.ic_refresh, R.string.k_reconnect, () -> service.reconnectWithNewSettings()), tileLp());
        actions.addView(actionTile(fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen,
            fullscreen ? R.string.k_fullscreen_exit : R.string.k_fullscreen, this::toggleFullscreen), tileLp());
        actions.addView(actionTile(R.drawable.ic_settings, R.string.k_settings, this::openSettings), tileLp());
        p.addView(actions);

        LinearLayout font = Ui.row(this);
        font.setPadding(dp(14), 0, dp(6), 0);
        font.setBackground(t.dark ? Ui.round(t.key, dp(12)) : Ui.outline(t.key, t.line, dp(1), dp(12)));
        font.addView(Ui.text(this, getString(R.string.k_font_size), 12.5f, t.sub, Ui.REGULAR), Ui.weight(1));
        font.addView(smallButton("A−", () -> setFont(fontSize - 2)), Ui.lp(dp(44), dp(34)));
        fontLabel = Ui.text(this, "", 13, t.text, Ui.MEDIUM);
        fontLabel.setGravity(Gravity.CENTER);
        font.addView(fontLabel, Ui.lp(dp(40), -2));
        font.addView(smallButton("A+", () -> setFont(fontSize + 2)), Ui.lp(dp(44), dp(34)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(46));
        flp.setMargins(dp(2.5f), dp(5), dp(2.5f), 0);
        p.addView(font, flp);
        return p;
    }

    private TextView sectionLabel(int res) {
        TextView l = Ui.text(this, getString(res), 11.5f, t.faint, Ui.MEDIUM);
        l.setPadding(dp(4), dp(6), 0, dp(5));
        return l;
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        Prefs.put(this, "fullscreen", fullscreen);
        buildKeys();
        applyBars();
    }

    /** Hides the title bar and status bar in fullscreen while the terminal is in front. */
    private void applyBars() {
        boolean hide = fullscreen && !listVisible;
        topBar.setVisibility(hide ? View.GONE : View.VISIBLE);
        // The system keeps content clear of the camera cutout; that strip shows the window background
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(hide ? t.terminalBg() : t.bg));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (hide) c.hide(android.view.WindowInsets.Type.statusBars());
            else c.show(android.view.WindowInsets.Type.statusBars());
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (hide) f |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            d.setSystemUiVisibility(f);
        }
    }

    @Override
    public void onPanelVisible(boolean visible) {
        listVisible = visible;
        if (topBar != null) applyBars();
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        lp.setMargins(dp(2.5f), 0, dp(2.5f), 0);
        return lp;
    }

    private View actionTile(int icon, int label, Runnable r) {
        LinearLayout c = Ui.column(this);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.pressable(t, t.dark ? Ui.round(t.key, dp(12)) : Ui.outline(t.key, t.line, dp(1), dp(12)), dp(12)));
        c.addView(Ui.icon(this, icon, t.text, 19));
        TextView l = Ui.text(this, getString(label), 11.5f, t.text, Ui.REGULAR);
        l.setPadding(dp(3), dp(5), dp(3), 0);
        l.setGravity(Gravity.CENTER);
        l.setMaxLines(1);
        // Six tiles in a row: longer labels (English) shrink to fit rather than being cut off
        l.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        c.addView(l, new LinearLayout.LayoutParams(-1, dp(22)));
        c.setOnClickListener(v -> {
            if (service == null) return;
            setMore(false);
            r.run();
        });
        return c;
    }

    private TextView smallButton(String s, Runnable r) {
        TextView b = Ui.text(this, s, 13, t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(t, Ui.round(t.chip, dp(9)), dp(9)));
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void bindKey(View cell, TextView b, String k, String up, boolean inPanel) {
        cell.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            boolean upSel, fired;

            String current() {
                return upSel ? up : k;
            }

            final Runnable again = new Runnable() {
                @Override
                public void run() {
                    pressKey(current());
                    main.postDelayed(this, 60);
                }
            };

            // Held for 400ms: arrows and page keys repeat (after a swipe up, the swiped key repeats)
            final Runnable hold = () -> {
                if (!REPEAT.contains(current())) return;
                fired = true;
                pressKey(current());
                main.postDelayed(again, 60);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0 = e.getY();
                        upSel = false;
                        fired = false;
                        v.setAlpha(0.6f);
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        main.postDelayed(hold, 400);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (up != null && b != null && !fired) {
                            boolean sel = y0 - e.getY() > dp(14);
                            if (sel != upSel) {
                                upSel = sel;
                                b.setText(current());
                                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, keySize(current()));
                                b.setTextColor(sel ? t.accent : t.text);
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(hold);
                        main.removeCallbacks(again);
                        boolean press = e.getActionMasked() == MotionEvent.ACTION_UP && !fired;
                        String key = current();
                        v.setAlpha(1f);
                        if (b != null) {
                            b.setText(k);
                            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, keySize(k));
                            b.setTextColor(t.text);
                        }
                        upSel = false;
                        if (press) pressKey(key);
                        if (inPanel && press) setMore(false);
                        refreshToggles();
                        return true;
                }
                return false;
            }
        });
    }

    private void setMore(boolean open) {
        if (morePanel == null) return;
        morePanel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open && fontLabel != null) fontLabel.setText(String.valueOf(Math.round(fontSize / getResources().getDisplayMetrics().scaledDensity)));
        refreshToggles();
    }

    private void pressKey(String k) {
        if (service == null) return;
        TerminalSession s = service.term;
        switch (k) {
            case "CTRL": ctrlOn = !ctrlOn; refreshToggles(); return;
            case "ALT": altOn = !altOn; refreshToggles(); return;
            case "⌨": toggleKeyboard(); return;
            case "⋯": setMore(morePanel.getVisibility() != View.VISIBLE); return;
            case "🎤": toggleVoice(); return;
            case "⇧TAB": s.write(new byte[]{27, '[', 'Z'}, 0, 3); return;
        }
        if (k.length() == 2 && k.charAt(0) == '^' && k.charAt(1) >= 'A' && k.charAt(1) <= 'Z') {
            s.write(new byte[]{(byte) (k.charAt(1) - '@')}, 0, 1);
            return;
        }
        if (s.getEmulator() == null) return;
        int keyCode;
        switch (k) {
            case "ESC": keyCode = KeyEvent.KEYCODE_ESCAPE; break;
            case "TAB": keyCode = KeyEvent.KEYCODE_TAB; break;
            case "HOME": keyCode = KeyEvent.KEYCODE_MOVE_HOME; break;
            case "END": keyCode = KeyEvent.KEYCODE_MOVE_END; break;
            case "PGUP": keyCode = KeyEvent.KEYCODE_PAGE_UP; break;
            case "PGDN": keyCode = KeyEvent.KEYCODE_PAGE_DOWN; break;
            case "↑": keyCode = KeyEvent.KEYCODE_DPAD_UP; break;
            case "↓": keyCode = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "←": keyCode = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "→": keyCode = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            default:
                // A plain character: same path as the soft keyboard, so CTRL/ALT apply
                view.inputCodePoint(k.codePointAt(0), false, false);
                return;
        }
        int mod = 0;
        if (ctrlOn) mod |= com.termux.terminal.KeyHandler.KEYMOD_CTRL;
        if (altOn) mod |= com.termux.terminal.KeyHandler.KEYMOD_ALT;
        view.handleKeyCode(keyCode, mod);
        ctrlOn = false;
        altOn = false;
    }

    private void refreshToggles() {
        styleToggle(ctrlKey, ctrlOn);
        styleToggle(altKey, altOn);
        if (micKey != null) {
            boolean on = voice != null;
            micKey.setBackground(on ? Ui.round(t.accent, dp(10))
                : t.dark ? Ui.round(t.key, dp(10)) : Ui.outline(t.key, t.line, dp(1), dp(10)));
            View icon = ((FrameLayout) micKey).getChildAt(0);
            if (icon instanceof android.widget.ImageView) {
                ((android.widget.ImageView) icon).setImageTintList(android.content.res.ColorStateList.valueOf(on ? t.onAccent : t.text));
            }
        }
        if (moreKey != null) {
            boolean open = morePanel != null && morePanel.getVisibility() == View.VISIBLE;
            moreKey.setBackground(open ? Ui.round(t.accent, dp(10))
                : t.dark ? Ui.round(t.key, dp(10)) : Ui.outline(t.key, t.line, dp(1), dp(10)));
            View icon = ((FrameLayout) moreKey).getChildAt(0);
            if (icon instanceof android.widget.ImageView) {
                ((android.widget.ImageView) icon).setImageResource(open ? R.drawable.ic_chevron_down : R.drawable.ic_more);
                ((android.widget.ImageView) icon).setImageTintList(android.content.res.ColorStateList.valueOf(open ? t.onAccent : t.text));
            }
        }
    }

    private void styleToggle(TextView key, boolean on) {
        if (key == null) return;
        View cell = (View) key.getParent();
        cell.setBackground(on ? Ui.round(t.accent, dp(10))
            : t.dark ? Ui.round(t.key, dp(10)) : Ui.outline(t.key, t.line, dp(1), dp(10)));
        key.setTextColor(on ? t.onAccent : t.text);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (keyArea != null) buildKeys();
        if (Theme.get(this).dark != themeDark) recreate();
    }

    // ---- input box: type a whole message with the full keyboard (suggestions, voice), then send it ----

    private String draft = "";

    private void showInputBox() {
        LinearLayout box = Ui.column(this);
        EditText e = new EditText(this);
        e.setText(draft);
        e.setSelection(draft.length());
        e.setMinLines(3);
        e.setMaxLines(8);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setHint(R.string.input_hint);
        e.setTextColor(t.text);
        e.setHintTextColor(t.faint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(Ui.outline(t.bg, t.accent, dp(1.5f), dp(14)));
        box.addView(e, Ui.lp(-1, -2));
        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, dp(14), 0, 0);
        box.addView(buttons);
        Dialog d = Ui.bottomSheet(this, t, box);
        TextView fill = Ui.secondaryButton(this, t, getString(R.string.input_fill), v -> {
            sendText(e.getText().toString(), false);
            d.dismiss();
        });
        buttons.addView(fill, new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView send = Ui.primaryButton(this, t, getString(R.string.input_send), v -> {
            sendText(e.getText().toString(), true);
            d.dismiss();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(50), 2f);
        slp.leftMargin = dp(10);
        buttons.addView(send, slp);
        d.setOnCancelListener(x -> draft = e.getText().toString());
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        e.requestFocus();
    }

    private void sendText(String text, boolean enter) {
        draft = "";
        if (service == null) return;
        TerminalSession s = service.term;
        if (!text.isEmpty() && s.getEmulator() != null) s.getEmulator().paste(text);
        // A short pause before Enter so it isn't read as part of the paste
        if (enter) main.postDelayed(() -> s.write(new byte[]{'\r'}, 0, 1), 80);
    }

    private void setFont(int size) {
        fontSize = Math.max(16, Math.min(size, 80));
        view.setTextSize(fontSize);
        Prefs.saveFontSize(this, fontSize);
        if (fontLabel != null) fontLabel.setText(String.valueOf(Math.round(fontSize / getResources().getDisplayMetrics().scaledDensity)));
    }

    private boolean keyboardShown() {
        android.view.WindowInsets in = view.getRootWindowInsets();
        return in != null && in.isVisible(android.view.WindowInsets.Type.ime());
    }

    private void toggleKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (keyboardShown()) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } else {
            view.requestFocus();
            imm.showSoftInput(view, 0);
        }
    }

    // ---- voice input: speech to text without the keyboard ----

    private static final int REQ_MIC = 2, REQ_PICK = 3, REQ_CAMERA = 4;
    private VoiceInput voice;
    /** What happens to the transcript when recognition ends: 0 drop, 1 insert, 2 insert and press Enter. */
    private int voiceMode;
    private LinearLayout voiceBar;
    private TextView voiceText;
    private View voiceDot;

    private void buildVoiceBar() {
        voiceBar = Ui.row(this);
        voiceBar.setGravity(Gravity.CENTER_VERTICAL);
        voiceBar.setPadding(dp(14), dp(10), dp(8), dp(4));
        voiceBar.setVisibility(View.GONE);
        voiceDot = new View(this);
        voiceDot.setBackground(Ui.round(t.red, dp(6)));
        voiceBar.addView(voiceDot, Ui.lp(dp(11), dp(11)));
        voiceText = Ui.text(this, "", 15, t.text, Ui.REGULAR);
        voiceText.setHintTextColor(t.faint);
        voiceText.setMaxLines(4);
        voiceText.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMargins(dp(10), 0, dp(8), 0);
        voiceBar.addView(voiceText, tlp);
        TextView cancel = smallButton("✕", this::cancelVoice);
        cancel.setContentDescription(getString(R.string.cancel));
        voiceBar.addView(cancel, Ui.lp(dp(38), dp(36)));
        TextView insert = Ui.secondaryButton(this, t, getString(R.string.voice_insert), v -> finishVoice(1));
        LinearLayout.LayoutParams ilp = Ui.lp(-2, dp(36));
        ilp.leftMargin = dp(6);
        voiceBar.addView(insert, ilp);
        TextView send = Ui.primaryButton(this, t, getString(R.string.voice_send), v -> finishVoice(2));
        send.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams slp = Ui.lp(-2, dp(36));
        slp.leftMargin = dp(6);
        voiceBar.addView(send, slp);
    }

    private void toggleVoice() {
        if (voice != null) {
            finishVoice(1);
            return;
        }
        if (!VoiceInput.Config.load(this).usable()) {
            showVoiceSetup();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startVoice();
    }

    private void startVoice() {
        voiceMode = 0;
        voiceText.setText("");
        voiceText.setHint(R.string.voice_listening);
        voiceBar.setVisibility(View.VISIBLE);
        voice = new VoiceInput(this, new VoiceInput.Listener() {
            @Override
            public void onText(String text) {
                voiceText.setText(text);
            }

            @Override
            public void onLevel(float level) {
                float s = 1f + level * 0.9f;
                voiceDot.animate().scaleX(s).scaleY(s).setDuration(90).start();
            }

            @Override
            public void onDone(String text) {
                int mode = voiceMode;
                endVoice();
                if (mode != 0 && !text.trim().isEmpty()) sendText(text.trim(), mode == 2);
            }

            @Override
            public void onError(String message) {
                endVoice();
                Toast.makeText(TermActivity.this, getString(R.string.voice_failed, message), Toast.LENGTH_LONG).show();
            }
        });
        voice.start();
        refreshToggles();
    }

    /** Stops listening; the transcript is inserted (mode 1) or inserted and sent (mode 2) once it is final. */
    private void finishVoice(int mode) {
        if (voice == null) return;
        voiceMode = mode;
        voiceText.setHint(R.string.voice_finishing);
        voice.stop();
    }

    private void cancelVoice() {
        if (voice == null) return;
        voice.cancel();
        endVoice();
    }

    private void endVoice() {
        voice = null;
        voiceDot.animate().cancel();
        voiceBar.setVisibility(View.GONE);
        refreshToggles();
    }

    private void showVoiceSetup() {
        LinearLayout box = Ui.column(this);
        TextView title = Ui.text(this, getString(R.string.voice_setup_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(10));
        box.addView(title);
        TextView body = Ui.text(this, getString(R.string.voice_setup_body), 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(dp(4), 1f);
        box.addView(body);
        Dialog d = Ui.bottomSheet(this, t, box);
        TextView go = Ui.primaryButton(this, t, getString(R.string.voice_open_settings), v -> {
            d.dismiss();
            openSettings();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(18);
        box.addView(go, lp);
        d.show();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice();
        } else {
            Toast.makeText(this, R.string.voice_no_permission, Toast.LENGTH_LONG).show();
        }
    }

    // ---- images: copied to the machine, the path typed into the session ----

    private Uri pendingImage, cameraUri;

    private void showImageSource() {
        if (service == null || service.currentSession() == null) {
            Toast.makeText(this, R.string.image_need_session, Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = Ui.column(this);
        TextView title = Ui.text(this, getString(R.string.image_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(8));
        box.addView(title);
        TextView body = Ui.text(this, getString(R.string.image_hint), 13, t.sub, Ui.REGULAR);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, 0, 0, dp(16));
        box.addView(body);
        LinearLayout row = Ui.row(this);
        Dialog d = Ui.bottomSheet(this, t, box);
        View gallery = actionTile(R.drawable.ic_image, R.string.image_gallery, () -> {
            d.dismiss();
            pickImage();
        });
        View camera = actionTile(R.drawable.ic_camera, R.string.image_camera, () -> {
            d.dismiss();
            takePhoto();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1f);
        lp.setMargins(0, 0, dp(5), 0);
        row.addView(gallery, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(76), 1f);
        lp2.setMargins(dp(5), 0, 0, 0);
        row.addView(camera, lp2);
        box.addView(row);
        d.show();
    }

    private void pickImage() {
        Intent i = Build.VERSION.SDK_INT >= 33 ? new Intent(MediaStore.ACTION_PICK_IMAGES)
            : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.image_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void takePhoto() {
        java.io.File dir = new java.io.File(getCacheDir(), "camera");
        java.io.File[] old = dir.listFiles();
        if (old != null) for (java.io.File f : old) f.delete();
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
        cameraUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.image_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        Uri u = req == REQ_PICK ? (data != null ? data.getData() : null) : req == REQ_CAMERA ? cameraUri : null;
        if (u == null) return;
        pendingImage = u;
        // The service may still be reconnecting after the picker; it is sent once the session is attached
        main.postDelayed(this::sendPendingImage, 300);
    }

    private void sendPendingImage() {
        Uri u = pendingImage;
        if (u == null || service == null) return;
        if (service.state() != TermService.State.CONNECTED || service.currentSession() == null || panel.shown()) {
            Toast.makeText(this, R.string.image_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingImage = null;
        showStatus(getString(R.string.image_uploading), t.amber);
        service.uploadImage(u, (path, err) -> {
            status.setVisibility(View.GONE);
            if (err != null) {
                Toast.makeText(this, getString(R.string.image_failed, err), Toast.LENGTH_LONG).show();
                return;
            }
            TerminalSession s = service.term;
            if (s.getEmulator() != null) s.getEmulator().paste(path.contains(" ") ? "'" + path + "'" : path);
        });
    }

    // ---- connection state ----

    @Override
    public void onProbe(boolean probing) {
        if (probing) {
            main.removeCallbacks(hideStatus);
            showStatus(getString(R.string.state_checking), t.amber);
        } else if (lastState == TermService.State.CONNECTED) {
            status.setVisibility(View.GONE);
        }
    }

    private TermService.State lastState;
    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private void showStatus(String s, int color) {
        status.setText(s);
        status.setBackground(Ui.round(color, dp(12)));
        status.setTextColor(t.dark ? 0xff0b0d10 : 0xffffffff);
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public void onState(TermService.State state, String message) {
        panel.onState(state, message);
        TermService.State prev = lastState;
        lastState = state;
        main.removeCallbacks(hideStatus);
        if (state == TermService.State.CONNECTED) {
            if (prev != null && prev != TermService.State.CONNECTED) {
                showStatus(getString(R.string.state_connected), t.accent);
                main.postDelayed(hideStatus, 1200);
            } else {
                status.setVisibility(View.GONE);
            }
            refreshTitle();
            return;
        }
        showStatus(message, state == TermService.State.FAILED ? t.red : t.amber);
        subtitle.setText(message);
    }

    // ---- session list ----

    @Override
    public TermService service() { return service; }

    @Override
    public void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    @Override
    public void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    @Override
    public void onAttached(String name) {
        panel.hide();
        main.postDelayed(this::sendPendingImage, 300);
        view.requestFocus();
        refreshTitle();
        main.removeCallbacks(titlePoll);
        main.postDelayed(titlePoll, 10_000);
    }

    @Override
    public void onShowSessions(String note) {
        panel.show(note);
    }

    @Override
    public void onSessionsChanged() {
        panel.refreshSoon();
    }

    @Override
    public boolean terminalVisible() {
        return !panel.shown();
    }

    // ---- TerminalViewClient ----

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            setFont(fontSize + (scale > 1f ? 2 : -2));
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (service != null && service.state() != TermService.State.CONNECTED
            && service.state() != TermService.State.CONNECTING) {
            service.retryNow();
        }
        // Tapping doesn't open the keyboard, so reading isn't interrupted; the keyboard key does
        view.requestFocus();
    }

    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    /** Settings → IME compatibility: some keyboards can't type CJK or Enter in TYPE_NULL mode. */
    @Override public boolean shouldEnforceCharBasedInput() { return Prefs.load(this).imeCompat; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volCtrl = true; return true; }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volCtrl = false; return true; }
        // This also overrides Activity.onKeyUp; without handling Back here the back gesture does nothing
        if (keyCode == KeyEvent.KEYCODE_BACK && !e.isCanceled()) {
            onBackPressed();
            return true;
        }
        return false;
    }

    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return ctrlOn || volCtrl; }
    @Override public boolean readAltKey() { return altOn; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        // CTRL/ALT release after one character from the keyboard or the key bar
        if (ctrlOn || altOn) {
            main.post(() -> { ctrlOn = false; altOn = false; refreshToggles(); });
        }
        return false;
    }

    @Override public void onEmulatorSet() {
        if (service != null) service.connect();
    }

    @Override public void logError(String tag, String m) { Log.e(tag, m); }
    @Override public void logWarn(String tag, String m) { Log.w(tag, m); }
    @Override public void logInfo(String tag, String m) { Log.i(tag, m); }
    @Override public void logDebug(String tag, String m) { Log.d(tag, m); }
    @Override public void logVerbose(String tag, String m) { Log.v(tag, m); }
    @Override public void logStackTraceWithMessage(String tag, String m, Exception e) { Log.e(tag, m, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "", e); }

    // ---- TerminalSessionClient ----

    @Override public void onTextChanged(TerminalSession s) { view.onScreenUpdated(); }
    @Override public void onTitleChanged(TerminalSession s) { }
    @Override public void onSessionFinished(TerminalSession s) { }

    @Override
    public void onCopyTextToClipboard(TerminalSession s, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("terminal", text));
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession s) {
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || s.getEmulator() == null) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0) s.getEmulator().paste(text.toString());
    }

    @Override public void onBell(TerminalSession s) { }
    @Override public void onColorsChanged(TerminalSession s) { view.invalidate(); }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        view.setTerminalCursorBlinkerState(state, true);
    }

    @Override public Integer getTerminalCursorStyle() { return null; }

    // ---- helpers ----

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static int evenPx(float px) {
        int v = Math.round(px);
        return v % 2 == 0 ? v : v + 1;
    }
}
