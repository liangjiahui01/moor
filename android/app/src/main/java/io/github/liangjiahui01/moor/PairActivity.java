package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

/**
 * Pairing entry. Three sources: a moor://pair link, "Scan QR" and "Paste link" in settings.
 * Only bottom sheets, on a transparent activity over the previous screen.
 */
public class PairActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_SCAN = "scan";
    static final String MODE_PASTE = "paste";

    private Theme t;
    private Dialog sheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = Theme.get(this);
        if (savedInstanceState != null) return;
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (getIntent().getData() != null) {
            handle(getIntent().getDataString());
        } else if (MODE_SCAN.equals(mode)) {
            new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.pair_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
        } else if (MODE_PASTE.equals(mode)) {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
            handle(text == null ? null : text.toString());
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (r == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (r.getContents() == null) {
            finish();
            return;
        }
        handle(r.getContents());
    }

    @Override
    protected void onDestroy() {
        if (sheet != null) sheet.dismiss();
        super.onDestroy();
    }

    private void handle(String text) {
        Pairing p = Pairing.parse(text);
        if (p == null) {
            fail(getString(R.string.pair_not_link));
            return;
        }
        if (p.expired()) {
            fail(getString(R.string.pair_expired));
            return;
        }
        String target = p.user + "@" + p.host + (p.port == 22 ? "" : ":" + p.port);
        String cmd = p.cmd.isEmpty() ? getString(R.string.pair_shell_only) : p.cmd;
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, getString(R.string.pair_title), 20, t.text, Ui.SEMIBOLD));
        TextView body = Ui.text(this, getString(R.string.pair_body, target, cmd), 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        body.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 18));
        box.addView(body);
        buttons(box, getString(R.string.cancel), this::finish, getString(R.string.pair_confirm), () -> run(p));
        show(box);
    }

    private void run(Pairing p) {
        LinearLayout box = Ui.row(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(t.accent));
        box.addView(spin, Ui.lp(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView l = Ui.text(this, getString(R.string.pair_running), 15, t.text, Ui.MEDIUM);
        l.setPadding(Ui.dp(this, 14), 0, 0, 0);
        box.addView(l);
        show(box).setCancelable(false);
        new Thread(() -> {
            String err = null;
            try {
                p.claim(this);
                p.save(this);
            } catch (Exception e) {
                android.util.Log.w("Moor", "pair failed", e);
                err = Pairing.explain(this, e);
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (msg != null) fail(msg);
                else done();
            });
        }, "Pair").start();
    }

    private void done() {
        Toast.makeText(this, R.string.pair_ok, Toast.LENGTH_SHORT).show();
        // Reconnect with the new settings if the service is already running
        bindService(new Intent(this, TermService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                ((TermService.LocalBinder) binder).service().switchMachine(Prefs.currentId(PairActivity.this));
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) { }
        }, 0);
        startActivity(new Intent(this, TermActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void fail(String msg) {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, getString(R.string.pair_failed_title), 20, t.text, Ui.SEMIBOLD));
        TextView body = Ui.text(this, msg, 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        body.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 18));
        box.addView(body);
        TextView ok = Ui.primaryButton(this, t, getString(R.string.got_it), v -> finish());
        box.addView(ok, Ui.lp(-1, Ui.dp(this, 50)));
        show(box);
    }

    private void buttons(LinearLayout box, String no, Runnable onNo, String yes, Runnable onYes) {
        LinearLayout row = Ui.row(this);
        row.addView(Ui.secondaryButton(this, t, no, v -> onNo.run()), new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        lp.leftMargin = Ui.dp(this, 10);
        row.addView(Ui.primaryButton(this, t, yes, v -> onYes.run()), lp);
        box.addView(row);
    }

    /** Replaces the current sheet. Dismissing it by hand closes the activity. */
    private Dialog show(LinearLayout content) {
        Dialog old = sheet;
        sheet = Ui.bottomSheet(this, t, content);
        Dialog mine = sheet;
        sheet.setOnDismissListener(d -> {
            if (sheet == mine && !isFinishing()) finish();
        });
        sheet.show();
        if (old != null) old.dismiss();
        return sheet;
    }
}
