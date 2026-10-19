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
import java.util.HashSet;
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
    private TermService service;
    private int fontSize;
    private boolean ctrlOn, altOn, volCtrl;
    private TextView ctrlKey, altKey;
    private View moreKey;
    private LinearLayout keyArea, morePanel;
    private QuickCommands quick;
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
        topBar = buildTopBar();
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(40)));
        // Hidden unless turned on in the sheet: the session list is a key in the strip (and the back gesture)
        showTopBar = Prefs.raw(this).getBoolean("top_bar", false);
        topBar.setVisibility(showTopBar ? View.VISIBLE : View.GONE);

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

        Prefs.raw(this).edit().remove("fullscreen").apply(); // the fullscreen mode is gone
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(t.bg));
        keyArea = Ui.column(this);
        keyArea.setClipChildren(false);
        keyArea.setBackgroundColor(t.keyBar);
        buildKeys();
        root.addView(keyArea, new LinearLayout.LayoutParams(-1, -2));
        buildBubble();
        minimal = Prefs.raw(this).getBoolean("minimal", false);
        applyMinimal();

        FrameLayout top = new FrameLayout(this);
        top.addView(root, new FrameLayout.LayoutParams(-1, -1));
        panel = new SessionPanel(this, this, t);
        top.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(top);
        // No previous session: open straight into the session list
        if (Prefs.load(this).session == null) panel.show(null);

        fontSize = Prefs.load(this).fontSize;
        if (fontSize <= 0) fontSize = evenPx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
            getResources().getDisplayMetrics()));
        // setTextSize first: setTypeface needs the renderer it creates
        view.setTextSize(fontSize);
        view.setTypeface(Ui.mono(this), Ui.monoBold(this));

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
        Updates.checkDaily(this);
        // The setting may have changed in Settings
        if (service != null) view.setKeepScreenOn(service.currentSession() != null && Prefs.load(this).keepScreenOn);
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
        if (service != null) service.detachScreen(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.detachScreen(this);
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

    /** One slim row: back, session name and status dot, what runs there and where, the session count. */
    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, dp(8), 0);
        bar.setBackgroundColor(t.bg);
        View back = Ui.iconButton(this, t, R.drawable.ic_back, t.sub, getString(R.string.a11y_sessions), v -> panel.show(null));
        bar.addView(back, Ui.lp(dp(40), dp(40)));

        title = Ui.oneLine(Ui.text(this, "", 14.5f, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(150));
        bar.addView(title, Ui.lp(-2, -2));
        titleDot = Ui.dot(this, t.ok, 6);
        LinearLayout.LayoutParams dlp = Ui.lp(dp(6), dp(6));
        dlp.setMargins(dp(6), 0, dp(8), 0);
        bar.addView(titleDot, dlp);
        subtitle = Ui.oneLine(Ui.monoText(this, "", 11, t.faint), TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        slp.rightMargin = dp(8);
        bar.addView(subtitle, slp);

        LinearLayout chip = Ui.row(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(9), 0, dp(10), 0);
        chip.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(13)), dp(13)));
        chip.addView(Ui.icon(this, R.drawable.ic_sessions, t.sub, 14));
        countLabel = Ui.text(this, "", 12, t.text, Ui.MEDIUM);
        countLabel.setPadding(dp(5), 0, 0, 0);
        chip.addView(countLabel);
        chip.setContentDescription(getString(R.string.a11y_sessions));
        chip.setOnClickListener(v -> panel.show(null));
        bar.addView(chip, Ui.lp(-2, dp(26)));
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
                    titleDot.setBackground(Ui.round(idle < 15 ? t.ok : t.faint, dp(3)));
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

    // ---- bottom: a row of keys that scrolls sideways, and an input box (as in Orca) ----
    // Keys: keyboard straight into the terminal, the sheet (symbols, Ctrl combos, font), then named keys, most used
    // first. Input box: type or dictate a whole line with the normal keyboard, then send it with Enter.

    /** Label on the key → key pressed. */
    private static final String[][] STRIP = {
        {"Paste", "PASTE"}, {"Esc", "ESC"}, {"Tab", "TAB"}, {"Enter", "⏎"}, {"Shift+Tab", "⇧TAB"},
        {"↑", "↑"}, {"↓", "↓"}, {"←", "←"}, {"→", "→"}, {"Ctrl", "CTRL"}, {"Alt", "ALT"}, {"^C", "^C"},
        {"Home", "HOME"}, {"End", "END"}, {"PgUp", "PGUP"}, {"PgDn", "PGDN"},
    };
    private static final String[][] SYMBOLS = {
        {"/", "-", "_", "|", "~", "`", "=", "\\", "$", "&"},
        {"'", "\"", "<", ">", "(", ")", "[", "]", "{", "}"},
    };
    private static final String[] CTRL = {"^C", "^D", "^R", "^L", "^Z", "^A", "^E", "^U", "^W", "^O"};
    private static final int[] CTRL_LABELS = {R.string.k_interrupt, R.string.k_eof, R.string.k_history, R.string.k_clear,
        R.string.k_suspend, R.string.k_line_start, R.string.k_line_end, R.string.k_kill_line, R.string.k_kill_word,
        R.string.k_details};
    private static final Set<String> REPEAT = new HashSet<>(Arrays.asList("←", "↓", "↑", "→", "PGUP", "PGDN"));

    private EditText input;
    private boolean showTopBar;
    private long inputBlurAt;
    private View sendBtn;

    private void buildKeys() {
        String typed = input != null ? input.getText().toString() : "";
        keyArea.removeAllViews();
        ctrlKey = altKey = null;
        moreKey = null;
        morePanel = buildMorePanel();
        morePanel.setVisibility(View.GONE);
        keyArea.addView(morePanel);

        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setHorizontalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(dp(18));
        LinearLayout strip = Ui.row(this);
        strip.setPadding(dp(6), dp(5), dp(6), dp(2));
        strip.addView(pillKey("»", "⋯"), pillLp(dp(40)));
        for (String[] k : STRIP) strip.addView(pillKey(k[0], k[1]), pillLp(-2));
        scroller.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        keyArea.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        // The key row folds away (the default): typing and dictation need only the input bar
        keyStrip = scroller;
        keyStrip.setVisibility(Prefs.raw(this).getBoolean("key_strip", false) ? View.VISIBLE : View.GONE);

        keyArea.addView(buildInputBar(typed));
        refreshToggles();
    }

    private LinearLayout.LayoutParams pillLp(int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(30));
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private android.graphics.drawable.Drawable keyBg(boolean on) {
        return on ? Ui.round(t.accent, dp(8)) : t.dark ? Ui.round(t.key, dp(8)) : Ui.outline(t.key, t.line, dp(1), dp(8));
    }

    /** A key with a name on it, as wide as its name. */
    private View pillKey(String label, String key) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackground(keyBg(false));
        TextView b = Ui.text(this, label, 12.5f, t.text, Ui.REGULAR);
        b.setTypeface(Ui.mono(this));
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setPadding(dp(11), 0, dp(11), 0);
        cell.addView(b, new FrameLayout.LayoutParams(-2, -1));
        if (key.equals("CTRL")) ctrlKey = b;
        if (key.equals("ALT")) altKey = b;
        if (key.equals("⋯")) {
            moreKey = cell;
            cell.setContentDescription(getString(R.string.a11y_more_keys));
        }
        bindKey(cell, null, key, null, false);
        return cell;
    }

    /** Symbols in the sheet: plain square keys. */
    private View keyCell(String k, boolean inPanel) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackground(keyBg(false));
        TextView b = Ui.text(this, k, keySize(k), t.text, Ui.MEDIUM);
        b.setTypeface(Ui.mono(this));
        b.setGravity(Gravity.CENTER);
        cell.addView(b, new FrameLayout.LayoutParams(-1, -1));
        bindKey(cell, b, k, null, inPanel);
        return cell;
    }

    /** The input bar: key-row toggle, image, the input box (hold it to talk) and send. */
    private View keyStrip, stripToggle;

    /** Shows or folds away the row of keys above the input bar; remembered. */
    private void toggleKeyStrip() {
        boolean show = keyStrip.getVisibility() != View.VISIBLE;
        Prefs.put(this, "key_strip", show);
        keyStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) setMore(false);
        styleStripToggle();
    }

    private void styleStripToggle() {
        if (stripToggle == null || keyStrip == null) return;
        // Chevron up: there are keys to unfold; down: fold them away
        ((FrameLayout) stripToggle).getChildAt(0).setRotation(keyStrip.getVisibility() == View.VISIBLE ? 0 : 180);
    }

    private View buildInputBar(String typed) {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(8));
        // Send swells with your voice while dictating: let it grow past its slot instead of being cut off
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        stripToggle = roundButton(R.drawable.ic_chevron_down, R.string.a11y_key_strip, false, v -> toggleKeyStrip());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(36), dp(44));
        tlp.rightMargin = dp(6);
        bar.addView(stripToggle, tlp);
        bar.post(this::styleStripToggle);
        View image = roundButton(R.drawable.ic_image, R.string.k_image, false, v -> showImageSource());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(8);
        bar.addView(image, ilp);
        input = new EditText(this);
        input.setText(typed);
        input.setSelection(typed.length());
        input.setHint(R.string.input_hint);
        input.setTextColor(t.text);
        input.setHintTextColor(t.faint);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTypeface(Ui.mono(this));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setBackground(t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)));
        input.setOnTouchListener(this::onInputTouch);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { styleBubble(); }
        });
        // A single line that scrolls sideways as text grows (dictation included), so the bar never
        // changes height; Send on the keyboard's Enter key
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setHorizontallyScrolling(true);
        input.setMaxLines(1);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setMinHeight(dp(44));
        input.setOnFocusChangeListener((v, has) -> {
            if (!has) inputBlurAt = android.os.SystemClock.uptimeMillis();
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
                sendInput();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        sendBtn = roundButton(R.drawable.ic_arrow_up, R.string.input_send, true, v -> sendInput());
        bar.addView(sendBtn, roundLp());
        return bar;
    }

    private LinearLayout.LayoutParams roundLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(8);
        return lp;
    }

    private View roundButton(int icon, int a11y, boolean primary, View.OnClickListener l) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(Ui.pressable(t, primary ? Ui.round(t.accent, dp(22))
            : t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)), dp(22)));
        b.addView(Ui.icon(this, icon, primary ? t.onAccent : t.text, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        b.setContentDescription(getString(a11y));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        return b;
    }

    /** Sends the box's text and Enter; with an empty box, just Enter. */
    private void sendInput() {
        if (voice != null) {
            // Still dictating (locked): let the last words land, then send
            sendWhenDone = true;
            setInputHint(R.string.voice_finishing);
            voice.stop();
            return;
        }
        String text = input.getText().toString();
        input.setText("");
        if (text.trim().isEmpty()) {
            pressKey("⏎");
        } else {
            sendText(text, true);
        }
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

        p.addView(sectionLabel(R.string.k_quick));
        quick = new QuickCommands(this, t, this::sendText, () -> setMore(false));
        p.addView(quick.view());

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
        actions.addView(actionTile(R.drawable.ic_topbar, showTopBar ? R.string.k_hide_bar : R.string.k_show_bar,
            this::toggleTopBar), tileLp());
        actions.addView(actionTile(R.drawable.ic_minimal, R.string.k_minimal, this::toggleMinimal), tileLp());
        actions.addView(actionTile(R.drawable.ic_link, R.string.k_route, this::showRoute), tileLp());
        actions.addView(actionTile(R.drawable.ic_redraw, R.string.k_redraw, () -> service.redraw()), tileLp());
        actions.addView(actionTile(R.drawable.ic_refresh, R.string.k_reconnect, () -> service.reconnectWithNewSettings()), tileLp());
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

    private void toggleTopBar() {
        showTopBar = !showTopBar;
        Prefs.raw(this).edit().putBoolean("top_bar", showTopBar).apply();
        topBar.setVisibility(showTopBar ? View.VISIBLE : View.GONE);
        if (showTopBar) refreshTitle();
        main.post(this::buildKeys); // the strip's session key and the tile's label follow
    }

    private TextView sectionLabel(int res) {
        TextView l = Ui.text(this, getString(res), 11.5f, t.faint, Ui.MEDIUM);
        l.setPadding(dp(4), dp(6), 0, dp(5));
        return l;
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
        // The list may have changed in the bubble's menu
        if (open && quick != null) quick.render();
        if (open && fontLabel != null) fontLabel.setText(String.valueOf(Math.round(fontSize / getResources().getDisplayMetrics().scaledDensity)));
        refreshToggles();
    }

    private void pressKey(String k) {
        if (service == null) return;
        TerminalSession s = service.term;
        switch (k) {
            case "CTRL": ctrlOn = !ctrlOn; refreshToggles(); return;
            case "ALT": altOn = !altOn; refreshToggles(); return;
            case "⋯": setMore(morePanel.getVisibility() != View.VISIBLE); return;
            case "PASTE": onPasteTextFromClipboard(s); return;
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
            case "⏎": keyCode = KeyEvent.KEYCODE_ENTER; break;
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
        if (moreKey != null) {
            boolean open = morePanel != null && morePanel.getVisibility() == View.VISIBLE;
            moreKey.setBackground(keyBg(open));
            View label = ((FrameLayout) moreKey).getChildAt(0);
            if (label instanceof TextView) ((TextView) label).setTextColor(open ? t.onAccent : t.text);
        }
    }

    private void styleToggle(TextView key, boolean on) {
        if (key == null) return;
        View cell = (View) key.getParent();
        cell.setBackground(keyBg(on));
        key.setTextColor(on ? t.onAccent : t.text);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (keyArea != null) buildKeys();
        if (Theme.get(this).dark != themeDark) recreate();
    }

    private void sendText(String text, boolean enter) {
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

    // ---- voice input: speech to text without the keyboard ----

    private static final int REQ_PICK = 3, REQ_CAMERA = 4, REQ_MIC_HOLD = 5;
    private VoiceInput voice;
    /** The box's text when dictation started; the transcript goes after it. */
    private String voiceBase = "";

    // ---- hold to talk: press and hold the empty input box, release to send, slide up to cancel,
    // slide right to lock (keeps listening hands-free; tap send to finish, tap the box to stop and edit) ----

    private boolean holding, holdCancel, holdLock, locked, sendWhenDone;
    private float holdX, holdY;
    private final Runnable holdStart = this::startHold;

    private boolean onInputTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (locked && voice != null) {
                    // Tap while locked: stop listening, keep the text for editing
                    setInputHint(R.string.voice_finishing);
                    voice.stop();
                    return false;
                }
                holding = false;
                holdCancel = false;
                holdLock = false;
                holdX = e.getRawX();
                holdY = e.getRawY();
                if (input.getText().length() == 0 && voice == null) main.postDelayed(holdStart, 350);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!holding) {
                    if (Math.hypot(e.getRawX() - holdX, e.getRawY() - holdY) > dp(10)) main.removeCallbacks(holdStart);
                    return false;
                }
                boolean c = holdY - e.getRawY() > dp(60);
                boolean l = !c && e.getRawX() - holdX > dp(60);
                if (c != holdCancel || l != holdLock) {
                    holdCancel = c;
                    holdLock = l;
                    styleHold();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(holdStart);
                if (!holding) return false;
                holding = false;
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (up && holdLock && voice != null) {
                    locked = true;
                    styleHold();
                } else {
                    endHold(up && !holdCancel);
                }
                return true;
            default:
                return holding;
        }
    }

    private void startHold() {
        if (!VoiceInput.Config.load(this).usable()) {
            showVoiceSetup();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_HOLD);
            return;
        }
        holding = true;
        // Take the gesture from the text box, so no selection menu or keyboard comes up
        input.cancelLongPress();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        input.onTouchEvent(cancel);
        cancel.recycle();
        input.setCursorVisible(false); // no cursor or its drag handle while talking
        input.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        startVoice();
        styleHold();
    }

    private void styleHold() {
        styleBubble();
        if (voice == null) return;
        int hint = locked ? (minimal ? R.string.hold_locked_bubble : R.string.hold_locked) : holdCancel ? R.string.hold_cancel
            : holdLock ? R.string.hold_lock : R.string.hold_send;
        if (holdLock && !locked) input.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        setInputHint(hint);
        input.setBackground(Ui.outline(t.key, holdCancel ? t.red : t.accent, dp(2), dp(22)));
    }

    private void endHold(boolean send) {
        if (voice == null) {
            resetInputLook();
            return;
        }
        if (send) {
            sendWhenDone = true;
            setInputHint(R.string.voice_finishing);
            voice.stop();
        } else {
            voice.cancel();
            endVoice();
            input.setText("");
        }
    }

    private void resetInputLook() {
        input.setCursorVisible(true);
        input.setBackground(t.dark ? Ui.round(t.key, dp(22)) : Ui.outline(t.key, t.line, dp(1), dp(22)));
    }

    private void startVoice() {
        voiceBase = input.getText().toString();
        setInputHint(R.string.voice_listening);
        voice = new VoiceInput(this, new VoiceInput.Listener() {
            @Override
            public void onText(String text) {
                showTranscript(text);
            }

            @Override
            public void onLevel(float level) {
                float s = 1f + level * 0.18f;
                View pulse = minimal ? bubble : sendBtn;
                if (pulse != null) pulse.animate().scaleX(s).scaleY(s).setDuration(90).start();
            }

            @Override
            public void onDone(String text) {
                showTranscript(text);
                boolean send = sendWhenDone, review = reviewWhenDone;
                endVoice();
                if (review) enterReview();
                else if (send && !input.getText().toString().trim().isEmpty()) sendInput();
            }

            @Override
            public void onError(String message) {
                boolean review = reviewWhenDone;
                endVoice();
                if (review) enterReview();
                Toast.makeText(TermActivity.this, getString(R.string.voice_failed, message), Toast.LENGTH_LONG).show();
            }
        });
        voice.start();
        refreshToggles();
    }

    private void showTranscript(String text) {
        String s = voiceBase + text;
        input.setText(s);
        input.setSelection(s.length());
    }

    /** Stops dictation; what was recognised so far stays in the box. */
    private void cancelVoice() {
        if (voice == null) return;
        voice.cancel();
        endVoice();
    }

    private void endVoice() {
        voice = null;
        sendWhenDone = false;
        reviewWhenDone = false;
        locked = false;
        holdLock = false;
        if (sendBtn != null) {
            sendBtn.animate().cancel();
            sendBtn.setScaleX(1f);
            sendBtn.setScaleY(1f);
        }
        resetInputLook();
        setInputHint(R.string.input_hint);
        if (bubble != null) {
            bubble.animate().cancel();
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
        }
        refreshToggles();
    }

    private static final String[] ROUTES = {"auto", "lan", "wan"};
    private static final int[] ROUTE_LABELS = {R.string.route_auto, R.string.route_lan, R.string.route_wan};
    private static final int[] ROUTE_HINTS = {R.string.route_auto_hint, R.string.route_lan_hint, R.string.route_wan_hint};

    /** Which path a direct connection takes: first that works, company/home network, or the internet. */
    private void showRoute() {
        LinearLayout box = Ui.column(this);
        TextView title = Ui.text(this, getString(R.string.route_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(dp(4), 0, 0, dp(6));
        box.addView(title);
        TextView now = Ui.text(this, routeNow(), 13.5f, t.sub, Ui.REGULAR);
        now.setPadding(dp(4), 0, 0, dp(10));
        box.addView(now);
        Dialog d = Ui.bottomSheet(this, t, box);
        String cur = Rtc.route(this);
        for (int i = 0; i < ROUTES.length; i++) {
            String key = ROUTES[i];
            boolean on = key.equals(cur);
            LinearLayout item = Ui.column(this);
            item.setPadding(dp(16), dp(10), dp(16), dp(10));
            item.setBackground(Ui.pressable(t, null, dp(12)));
            item.addView(Ui.text(this, (on ? "✓  " : "") + getString(ROUTE_LABELS[i]), 16, on ? t.accent : t.text, Ui.MEDIUM));
            TextView hint = Ui.text(this, getString(ROUTE_HINTS[i]), 12.5f, t.faint, Ui.REGULAR);
            hint.setPadding(0, dp(3), 0, 0);
            item.addView(hint);
            item.setOnClickListener(v -> {
                d.dismiss();
                if (on || service == null) return;
                Prefs.put(this, "route", key);
                Diag.i("TermActivity", "route set to " + key);
                service.reconnectWithNewSettings();
            });
            box.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        d.show();
    }

    private String routeNow() {
        Rtc.Proxy p = service == null ? null : service.directProxy();
        if (p == null || p.path.isEmpty()) return getString(R.string.route_now_none);
        String path = getString("lan".equals(p.path) ? R.string.route_lan : R.string.route_wan);
        return p.rttMs > 0 ? getString(R.string.route_now_rtt, path, p.rttMs) : getString(R.string.route_now, path);
    }

    /**
     * Once: ask to be left out of battery optimization. Without it some systems (Xiaomi) freeze the app
     * about 10 s after the screen locks, the machine drops the connection, and every unlock reconnects.
     */
    private void maybeAskBattery() {
        if (isFinishing() || Prefs.raw(this).getBoolean("asked_battery", false)) return;
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        Prefs.put(this, "asked_battery", true);
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Diag.w("TermActivity", "battery request: " + e);
        }
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
        if (req != REQ_MIC_HOLD) return;
        // Granted: hold the box again to talk
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.voice_no_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void setInputHint(int res) {
        input.setHint(res);
        styleBubble();
    }

    // ---- minimal mode: no bars, the whole screen is terminal; a mic bubble that can sit anywhere.
    // Hold it to talk (same gestures as the input box), press and move to drag it, tap it for a menu. ----

    private boolean minimal, bubbleDrag;
    private FrameLayout bubble;
    private TextView bubblePreview;
    /** The card beside the bubble: live words while talking, then the result with cancel / edit / send. */
    private LinearLayout bubbleCard, bubbleActions;
    /** Dictation stopped from the bubble: the words wait on the card until sent, edited or dropped. */
    private boolean reviewing, reviewWhenDone;
    private float bubbleX0, bubbleY0;

    private void buildBubble() {
        bubble = new FrameLayout(this);
        bubble.addView(Ui.icon(this, R.drawable.ic_mic, t.text, 24), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        bubble.setContentDescription(getString(R.string.a11y_bubble));
        bubble.setElevation(dp(6));
        bubble.setOnTouchListener(this::onBubbleTouch);
        termBox.addView(bubble, new FrameLayout.LayoutParams(dp(56), dp(56)));
        bubbleCard = Ui.column(this);
        bubbleCard.setBackground(Ui.outline(t.sheet, t.accent, dp(1), dp(14)));
        bubbleCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubbleCard.setElevation(dp(6));
        bubbleCard.setVisibility(View.GONE);
        bubbleCard.setOnClickListener(v -> cancelBubbleVoice());
        bubblePreview = Ui.text(this, "", 14, t.text, Ui.REGULAR);
        bubblePreview.setLineSpacing(dp(3), 1f);
        bubblePreview.setMaxLines(12);
        bubblePreview.setEllipsize(android.text.TextUtils.TruncateAt.START);
        bubbleCard.addView(bubblePreview);
        bubbleActions = Ui.row(this);
        bubbleActions.setPadding(0, dp(10), 0, 0);
        TextView cancel = smallButton(getString(R.string.bubble_cancel), this::cancelReview);
        TextView edit = smallButton(getString(R.string.bubble_edit), this::editReview);
        TextView send = smallButton(getString(R.string.bubble_send), this::sendReview);
        send.setTextColor(t.onAccent);
        send.setBackground(Ui.pressable(t, Ui.round(t.accent, dp(9)), dp(9)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(72), dp(36));
        alp.rightMargin = dp(8);
        bubbleActions.addView(cancel, alp);
        bubbleActions.addView(edit, new LinearLayout.LayoutParams(alp));
        bubbleActions.addView(send, new LinearLayout.LayoutParams(dp(72), dp(36)));
        bubbleCard.addView(bubbleActions);
        termBox.addView(bubbleCard, new FrameLayout.LayoutParams(-2, -2));
        // Keep its spot (as a share of the free space) through rotation and the keyboard opening
        termBox.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - tp != ob - ot) placeBubble();
        });
        styleBubble();
    }

    private void toggleMinimal() {
        minimal = !minimal;
        reviewing = false;
        Prefs.put(this, "minimal", minimal);
        setMore(false);
        if (minimal) {
            cancelVoice();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        applyMinimal();
    }

    private void applyMinimal() {
        keyArea.setVisibility(minimal ? View.GONE : View.VISIBLE);
        bubble.setVisibility(minimal ? View.VISIBLE : View.GONE);
        getWindow().setNavigationBarColor(minimal ? t.terminalBg() : t.keyBar);
        styleBubble();
        wakeBubble();
        termBox.post(this::placeBubble);
    }

    // Left alone, the bubble fades so the text under it shows; a touch brings it back and still counts.
    private static final long BUBBLE_IDLE_MS = 3000;
    private static final float BUBBLE_IDLE_ALPHA = 0.55f;
    private android.animation.ObjectAnimator bubbleFade;
    private boolean bubbleWasOn;
    private final Runnable bubbleIdle = () -> {
        if (bubble == null || !minimal || voice != null || reviewing || bubbleDrag) return;
        if (bubbleFade != null) bubbleFade.cancel();
        bubbleFade = android.animation.ObjectAnimator.ofFloat(bubble, View.ALPHA, bubble.getAlpha(), BUBBLE_IDLE_ALPHA);
        bubbleFade.setDuration(800);
        bubbleFade.start();
    };

    /** Full strength now; fades again after a while unless dictation is on. */
    private void wakeBubble() {
        if (bubble == null) return;
        main.removeCallbacks(bubbleIdle);
        if (bubbleFade != null) bubbleFade.cancel();
        bubble.setAlpha(1f);
        if (minimal && voice == null && !reviewing) main.postDelayed(bubbleIdle, BUBBLE_IDLE_MS);
    }

    private void placeBubble() {
        if (bubble == null) return;
        int w = termBox.getWidth(), h = termBox.getHeight(), s = dp(56), m = dp(10);
        if (w == 0 || h == 0) return;
        float fx = Prefs.raw(this).getFloat("bubble_x", 0.92f), fy = Prefs.raw(this).getFloat("bubble_y", 0.86f);
        bubble.setX(m + fx * Math.max(0, w - s - 2 * m));
        bubble.setY(m + fy * Math.max(0, h - s - 2 * m));
        placePreview();
    }

    private void moveBubble(float x, float y) {
        int w = termBox.getWidth(), h = termBox.getHeight(), s = dp(56), m = dp(10);
        bubble.setX(Math.max(m, Math.min(x, w - s - m)));
        bubble.setY(Math.max(m, Math.min(y, h - s - m)));
        placePreview();
    }

    private void saveBubble() {
        int w = termBox.getWidth(), h = termBox.getHeight(), s = dp(56), m = dp(10);
        float fx = w - s - 2 * m > 0 ? (bubble.getX() - m) / (w - s - 2 * m) : 0.92f;
        float fy = h - s - 2 * m > 0 ? (bubble.getY() - m) / (h - s - 2 * m) : 0.86f;
        Prefs.raw(this).edit().putFloat("bubble_x", Math.max(0, Math.min(1, fx)))
            .putFloat("bubble_y", Math.max(0, Math.min(1, fy))).apply();
    }

    /** The bubble's look follows dictation; while talking, a card next to it shows the hint and the words. */
    private void styleBubble() {
        if (bubble == null || input == null) return;
        boolean on = voice != null;
        if ((on || reviewing) != bubbleWasOn) {
            bubbleWasOn = on || reviewing;
            wakeBubble();
        }
        int fill = holdCancel && holding ? t.red : on ? t.accent : (t.key & 0x00ffffff) | 0xe6000000;
        // A light ring, so the bubble stays findable on a black screen even when faded
        bubble.setBackground(on ? Ui.round(fill, dp(28)) : Ui.outline(fill, t.sub, dp(2), dp(28)));
        View icon = bubble.getChildAt(0);
        if (icon instanceof android.widget.ImageView) {
            ((android.widget.ImageView) icon).setImageTintList(android.content.res.ColorStateList.valueOf(on ? t.onAccent : t.text));
        }
        if (!minimal || !(on || reviewing)) {
            bubbleCard.setVisibility(View.GONE);
            return;
        }
        bubbleActions.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        bubbleCard.setClickable(!reviewing);
        String hint = reviewing ? getString(R.string.bubble_review)
            : input.getHint() == null ? "" : input.getHint().toString();
        String text = input.getText().toString();
        // While talking the latest words matter most: keep the tail of a long transcript
        if (!reviewing && text.length() > 140) text = "…" + text.substring(text.length() - 140);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(hint);
        sb.setSpan(new android.text.style.ForegroundColorSpan(holdCancel && holding ? t.red : t.faint), 0, hint.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), 0, hint.length(), 0);
        if (!text.isEmpty()) sb.append("\n").append(text);
        bubblePreview.setText(sb);
        bubbleCard.setVisibility(View.VISIBLE);
        placePreview();
    }

    private void placePreview() {
        if (bubbleCard == null || bubbleCard.getVisibility() != View.VISIBLE) return;
        int w = termBox.getWidth(), gap = dp(10), m = dp(12);
        if (w == 0) return;
        bubbleCard.measure(View.MeasureSpec.makeMeasureSpec(Math.min(w - 2 * m, dp(420)), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED);
        int pw = bubbleCard.getMeasuredWidth(), ph = bubbleCard.getMeasuredHeight();
        float cx = bubble.getX() + dp(28);
        bubbleCard.setX(Math.max(m, Math.min(cx - pw / 2f, w - m - pw)));
        // Above the bubble when there is room, else below it
        float above = bubble.getY() - gap - ph;
        bubbleCard.setY(above >= m ? above : bubble.getY() + dp(56) + gap);
    }

    private boolean bubbleLongDone;
    private final Runnable bubbleLongPress = () -> {
        bubbleLongDone = true;
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showBubbleMenu();
    };

    // Tap to start talking, tap again to send; the card beside it cancels. Long press: the menu. Press and move: drag.
    private boolean onBubbleTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                wakeBubble();
                bubbleDrag = false;
                bubbleLongDone = false;
                holdX = e.getRawX();
                holdY = e.getRawY();
                bubbleX0 = bubble.getX();
                bubbleY0 = bubble.getY();
                if (voice == null) main.postDelayed(bubbleLongPress, 450);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - holdX, dy = e.getRawY() - holdY;
                if (bubbleLongDone) return true;
                if (!bubbleDrag && Math.hypot(dx, dy) > dp(8)) {
                    bubbleDrag = true;
                    main.removeCallbacks(bubbleLongPress);
                }
                if (bubbleDrag) moveBubble(bubbleX0 + dx, bubbleY0 + dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                main.removeCallbacks(bubbleLongPress);
                wakeBubble();
                if (bubbleDrag) {
                    bubbleDrag = false;
                    saveBubble();
                    return true;
                }
                if (bubbleLongDone || e.getActionMasked() != MotionEvent.ACTION_UP) return true;
                bubble.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (voice == null) {
                    if (reviewing) sendReview();
                    else startBubbleVoice();
                } else if (locked) {
                    // Stop, and let the words wait on the card: a wrong word is caught before it goes out
                    reviewWhenDone = true;
                    setInputHint(R.string.voice_finishing);
                    voice.stop();
                    styleBubble();
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void startBubbleVoice() {
        if (!VoiceInput.Config.load(this).usable()) {
            showVoiceSetup();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_HOLD);
            return;
        }
        input.setText(""); // the box is hidden here: send only what is said now
        holding = false;
        holdCancel = false;
        startVoice();
        locked = true;
        styleHold();
    }

    private void enterReview() {
        reviewing = minimal && !input.getText().toString().trim().isEmpty();
        if (!reviewing) input.setText("");
        styleBubble();
    }

    private void sendReview() {
        String text = input.getText().toString();
        reviewing = false;
        input.setText("");
        if (!text.trim().isEmpty()) sendText(text, true);
        styleBubble();
    }

    private void cancelReview() {
        reviewing = false;
        input.setText("");
        styleBubble();
    }

    /** Fix a misheard word with the keyboard, then send; closing the sheet keeps the words on the card. */
    private void editReview() {
        LinearLayout box = Ui.column(this);
        TextView title = Ui.text(this, getString(R.string.bubble_edit_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, dp(12));
        box.addView(title);
        android.widget.EditText field = NewSessionSheet.field(this, t, input.getText().toString(), "");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(2);
        field.setMaxLines(8);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        Dialog d = Ui.bottomSheet(this, t, box);
        boolean[] sent = {false};
        TextView send = Ui.primaryButton(this, t, getString(R.string.bubble_send), v -> {
            sent[0] = true;
            input.setText(field.getText().toString());
            d.dismiss();
            sendReview();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(16);
        box.addView(send, lp);
        d.setOnDismissListener(x -> {
            if (sent[0]) return;
            input.setText(field.getText().toString());
            enterReview();
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        field.requestFocus();
        field.setSelection(field.getText().length());
    }

    private void cancelBubbleVoice() {
        if (voice == null) return;
        voice.cancel();
        endVoice();
        input.setText("");
    }

    /** The bubble's menu: keyboard, image, sessions, a few keys, and the way back to the bars. */
    private void showBubbleMenu() {
        if (service == null) return;
        LinearLayout box = Ui.column(this);
        Dialog[] d = new Dialog[1];
        LinearLayout tiles = Ui.row(this);
        tiles.addView(actionTile(R.drawable.ic_image, R.string.k_image, () -> {
            d[0].dismiss();
            showImageSource();
        }), tileLp());
        tiles.addView(actionTile(R.drawable.ic_minimal, R.string.k_exit_minimal, () -> {
            d[0].dismiss();
            toggleMinimal();
        }), tileLp());
        box.addView(tiles);
        QuickCommands q = new QuickCommands(this, t, this::sendText, () -> d[0].dismiss());
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(10);
        box.addView(q.view(), qlp);
        // Keys stay in the sheet, so several can be pressed in a row
        String[][] keys = {{"Esc", "ESC"}, {"Tab", "TAB"}, {"⇧Tab", "⇧TAB"}, {"Enter", "⏎"},
            {"↑", "↑"}, {"↓", "↓"}, {"^C", "^C"}, {"Paste", "PASTE"}};
        LinearLayout row = Ui.row(this);
        row.setPadding(0, dp(10), 0, 0);
        for (String[] k : keys) {
            TextView b = smallButton(k[0], () -> pressKey(k[1]));
            b.setTypeface(Ui.mono(this));
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                pressKey(k[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.setMargins(dp(2.5f), 0, dp(2.5f), 0);
            row.addView(b, lp);
        }
        box.addView(row);
        d[0] = Ui.bottomSheet(this, t, box);
        d[0].getWindow().setDimAmount(0.15f); // the terminal stays readable while pressing keys
        d[0].show();
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
        status.setTextColor(t.dark ? 0xff11111b : 0xffffffff);
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
                main.postDelayed(this::maybeAskBattery, 1500);
                // The message ends with " · direct" / " · via relay" for WebRTC connections
                int dot = message == null ? -1 : message.lastIndexOf(" · ");
                showStatus(getString(R.string.state_connected) + (dot < 0 ? "" : message.substring(dot)), t.ok);
                main.postDelayed(hideStatus, dot < 0 ? 1200 : 2000);
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
        // In a session the screen stays on (only while Moor is in front)
        view.setKeepScreenOn(Prefs.load(this).keepScreenOn);
        main.postDelayed(this::sendPendingImage, 300);
        view.requestFocus();
        refreshTitle();
        main.removeCallbacks(titlePoll);
        main.postDelayed(titlePoll, 10_000);
    }

    @Override
    public void onShowSessions(String note) {
        panel.show(note);
        view.setKeepScreenOn(false);
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
        // Tapping doesn't open the keyboard, so reading isn't interrupted; the keyboard key does.
        // A tap while typing in the input box puts the keyboard away. The terminal has already taken the focus
        // by now, so look at when the box lost it.
        if (android.os.SystemClock.uptimeMillis() - inputBlurAt < 500) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
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
