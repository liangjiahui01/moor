package io.github.liangjiahui01.moor;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Connection settings for one machine, plus a few UI preferences. */
final class Prefs {
    final String host;
    final int port;
    final String user;
    /** Default command for new sessions ("" = plain shell). */
    final String startup;
    final int fontSize;
    final boolean imeCompat;
    /** Last attached session; reattached after reconnecting. null = stay on the session list. */
    final String session;
    /** "system", "dark" or "light". */
    final String theme;
    final boolean notifyAttention, notifyDone, notifyBell;
    private final boolean saved;

    private Prefs(SharedPreferences p) {
        // Optional build-time defaults from android/defaults.properties (not in git)
        host = p.getString("host", BuildConfig.DEFAULT_HOST);
        port = p.getInt("port", 22);
        user = p.getString("user", BuildConfig.DEFAULT_USER);
        startup = p.getString("startup", "");
        fontSize = p.getInt("font_size", 0);
        imeCompat = p.getBoolean("ime_compat", false);
        session = p.getString("session", null);
        theme = p.getString("theme", "system");
        notifyAttention = p.getBoolean("notify_attention", true);
        notifyDone = p.getBoolean("notify_done", true);
        notifyBell = p.getBoolean("notify_bell", false);
        saved = p.getBoolean("saved", false) || p.contains("host");
    }

    static SharedPreferences raw(Context c) {
        return c.getSharedPreferences("conn", Context.MODE_PRIVATE);
    }

    static Prefs load(Context c) {
        return new Prefs(raw(c));
    }

    /** Configured once the user saved a host; prefilled defaults don't count. */
    boolean configured() {
        return saved && !host.isEmpty() && !user.isEmpty();
    }

    boolean anyNotifications() {
        return notifyAttention || notifyDone || notifyBell;
    }

    static void save(Context c, String host, int port, String user, String startup) {
        raw(c).edit().putString("host", host).putInt("port", port).putString("user", user)
            .putString("startup", startup).putBoolean("saved", true).apply();
    }

    static void put(Context c, String key, String value) {
        raw(c).edit().putString(key, value).apply();
    }

    static void put(Context c, String key, boolean value) {
        raw(c).edit().putBoolean(key, value).apply();
    }

    static void saveSession(Context c, String name) {
        if (name == null) raw(c).edit().remove("session").apply();
        else raw(c).edit().putString("session", name).apply();
    }

    static void saveFontSize(Context c, int size) {
        raw(c).edit().putInt("font_size", size).apply();
    }

    /** Most recent start directories, newest first. */
    static List<String> recentDirs(Context c) {
        String s = raw(c).getString("recent_dirs", "");
        List<String> out = new ArrayList<>();
        for (String d : s.split("\n")) if (!d.isEmpty()) out.add(d);
        return out;
    }

    static void addRecentDir(Context c, String dir) {
        List<String> l = recentDirs(c);
        l.remove(dir);
        l.add(0, dir);
        while (l.size() > 6) l.remove(l.size() - 1);
        raw(c).edit().putString("recent_dirs", String.join("\n", l)).apply();
    }

    static long lastEvent(Context c) {
        return raw(c).getLong("last_event", 0);
    }

    static void saveLastEvent(Context c, long ts) {
        raw(c).edit().putLong("last_event", ts).apply();
    }

    static final List<String> SHELLS = Arrays.asList("zsh", "bash", "sh", "fish", "dash", "ksh", "tcsh", "nu");
}
