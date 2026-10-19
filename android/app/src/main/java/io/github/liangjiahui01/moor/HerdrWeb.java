package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * herdr web ui (github.com/devswha/herdr-web-ui, MIT), a herdr plugin on the machine, shown in a web view.
 * It reads the agents' own conversations in herdr's panes as chat, answers their prompts with cards and attaches
 * to a pane's live terminal. It listens on the machine's loopback only; the page reaches it through this app's
 * SSH connection (a local port forward), which the plugin lets in without pairing, like any SSH tunnel.
 * The window is hidden, not closed, on the way out, so the page keeps its state between visits.
 */
final class HerdrWeb {
    interface Host {
        TermService service();

        /** Lets the page pick files: the activity starts the chooser and hands back the result. */
        void pickFiles(Intent chooser, ValueCallback<Uri[]> result);

        /** Asks for the microphone, then runs granted (only if allowed). */
        void needMic(Runnable granted);
    }

    private final Activity a;
    private final Theme t;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private WebView web;
    private TextView banner;
    private int loadedPort = -1;
    private boolean failed;

    HerdrWeb(Activity a, Theme t, Host host) {
        this.a = a;
        this.t = t;
        this.host = host;
    }

    void show() {
        if (dialog == null) build();
        dialog.show();
        connect();
    }

    /** Called when the app's connection to the machine comes back: the page's link to the plugin went with it. */
    void reconnected() {
        if (dialog != null && dialog.isShowing()) connect();
    }

    private void connect() {
        TermService s = host.service();
        if (s == null) return;
        if (loadedPort < 0 || failed) showBanner(a.getString(R.string.herdr_starting), false);
        s.herdrWeb((port, err) -> {
            if (dialog == null || !dialog.isShowing()) return;
            if (err != null) {
                String why = "not_installed".equals(err) ? a.getString(R.string.herdr_not_installed)
                    : "not_running".equals(err) ? a.getString(R.string.herdr_not_running) : a.getString(R.string.herdr_failed, err);
                showBanner(why, true);
                return;
            }
            if (port != loadedPort || failed) {
                loadedPort = port;
                failed = false;
                web.loadUrl("http://127.0.0.1:" + port + "/");
            } else {
                hideBanner();
            }
        });
    }

    private void build() {
        dialog = new Dialog(a, t.dark ? android.R.style.Theme_DeviceDefault_NoActionBar
            : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(t.bg);
        web = new WebView(dialog.getContext());
        web.setBackgroundColor(t.bg);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setTextZoom(100);
        ws.setSupportZoom(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (local(u)) return false;
                // Links out of the app (docs, GitHub) open in the browser
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!failed) hideBanner();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError e) {
                if (!req.isForMainFrame()) return;
                failed = true;
                showBanner(a.getString(R.string.herdr_failed, String.valueOf(e.getDescription())), true);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                Intent i;
                try {
                    i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                } catch (Exception e) {
                    cb.onReceiveValue(null);
                    return true;
                }
                host.pickFiles(i, cb);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                boolean audio = false;
                for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                if (!audio || !local(req.getOrigin())) {
                    req.deny();
                    return;
                }
                String[] grant = {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
                if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    req.grant(grant);
                } else {
                    host.needMic(() -> req.grant(grant));
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        banner = Ui.text(a, "", 13, t.text, Ui.MEDIUM);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(18), dp(12), dp(18), dp(12));
        banner.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(14)), dp(14)));
        banner.setOnClickListener(v -> connect());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        blp.leftMargin = blp.rightMargin = dp(24);
        root.addView(banner, blp);
        banner.setVisibility(View.GONE);

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
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                if (web.canGoBack()) web.goBack();
                else dialog.hide();
            }
            return true;
        });
    }

    private static boolean local(Uri u) {
        String h = u == null ? null : u.getHost();
        return "127.0.0.1".equals(h) || "localhost".equals(h);
    }

    private void showBanner(String s, boolean retry) {
        banner.setText(retry ? s + "\n" + a.getString(R.string.herdr_retry) : s);
        banner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        banner.setVisibility(View.GONE);
    }

    private int dp(float v) {
        return Ui.dp(a, v);
    }
}
