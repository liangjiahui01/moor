package io.github.liangjiahui01.moor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.TimeUnit;

/**
 * Checks GitHub for a newer release. It reads where releases/latest redirects to (…/tag/vX.Y.Z), which
 * needs no API token and has no rate limit, and downloads go through the browser.
 */
final class Updates {
    private static final String TAG = "Moor";
    private static final String REPO = "https://github.com/liangjiahui01/moor";
    private static final long DAY = 24 * 3600 * 1000L;

    private Updates() { }

    /** Blocking: the latest released version, like "0.10.2". */
    static String latest() throws Exception {
        okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).build();
        try (okhttp3.Response r = http.newCall(new okhttp3.Request.Builder().url(REPO + "/releases/latest").head().build()).execute()) {
            String loc = r.header("Location", "");
            int i = loc.lastIndexOf("/tag/v");
            if (i < 0) throw new java.io.IOException("HTTP " + r.code());
            return loc.substring(i + 6);
        }
    }

    /** True when version a is newer than b (numeric parts compared in order). */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** At most once a day, quietly: offers a newer version once (Later skips that version). */
    static void checkDaily(Activity a) {
        android.content.SharedPreferences p = Prefs.raw(a);
        long now = System.currentTimeMillis();
        if (now - p.getLong("update_checked_at", 0) < DAY) return;
        p.edit().putLong("update_checked_at", now).apply();
        new Thread(() -> {
            try {
                String v = latest();
                if (!newer(v, BuildConfig.VERSION_NAME) || v.equals(p.getString("update_skipped", ""))) return;
                a.runOnUiThread(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) offer(a, v);
                });
            } catch (Exception e) {
                Log.i(TAG, "update check failed: " + e);
            }
        }, "UpdateCheck").start();
    }

    /** From Settings: says whether there is a newer version. */
    static void checkNow(Activity a) {
        android.widget.Toast.makeText(a, R.string.update_checking, android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String v = null;
            String err = null;
            try {
                v = latest();
                Prefs.raw(a).edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String got = v, why = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (got == null) {
                    android.widget.Toast.makeText(a, a.getString(R.string.update_failed, why), android.widget.Toast.LENGTH_LONG).show();
                } else if (newer(got, BuildConfig.VERSION_NAME)) {
                    offer(a, got);
                } else {
                    android.widget.Toast.makeText(a, a.getString(R.string.update_latest, BuildConfig.VERSION_NAME), android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }, "UpdateCheck").start();
    }

    private static void offer(Activity a, String v) {
        Theme t = Theme.get(a);
        LinearLayout box = Ui.column(a);
        box.addView(Ui.text(a, a.getString(R.string.update_title, v), 19, t.text, Ui.MEDIUM));
        TextView body = Ui.text(a, a.getString(R.string.update_body, BuildConfig.VERSION_NAME), 14, t.sub, Ui.REGULAR);
        body.setLineSpacing(Ui.dp(a, 3), 1f);
        body.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 18));
        box.addView(body);
        Dialog[] d = {null};
        box.addView(Ui.primaryButton(a, t, a.getString(R.string.update_download), x -> {
            open(a, REPO + "/releases/download/v" + v + "/moor-" + v + ".apk");
            d[0].dismiss();
        }), Ui.lp(-1, Ui.dp(a, 48)));
        LinearLayout row = Ui.row(a);
        row.setPadding(0, Ui.dp(a, 10), 0, 0);
        row.addView(Ui.secondaryButton(a, t, a.getString(R.string.update_notes), x -> open(a, REPO + "/releases/tag/v" + v)),
            weighted(a));
        LinearLayout.LayoutParams later = weighted(a);
        later.leftMargin = Ui.dp(a, 10);
        row.addView(Ui.secondaryButton(a, t, a.getString(R.string.update_later), x -> {
            Prefs.raw(a).edit().putString("update_skipped", v).apply();
            d[0].dismiss();
        }), later);
        box.addView(row);
        d[0] = Ui.bottomSheet(a, t, box);
        d[0].show();
    }

    private static LinearLayout.LayoutParams weighted(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f);
        return lp;
    }

    private static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.w(TAG, "no browser", e);
        }
    }
}
