package io.github.liangjiahui01.moor;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Settings. App-wide preferences live in "conn"; each machine has its own file "machine_&lt;id&gt;" holding its
 * connection, last session, recent directories and event cursor. One machine is current at a time.
 */
final class Prefs {
    /** Current machine's id. */
    final String machine;
    /** Display name; empty means "use the host". */
    final String name;
    final String host;
    final int port;
    final String user;
    /** Default command for new sessions ("" = plain shell). */
    final String startup;
    /** Last attached session; reattached after reconnecting. null = stay on the session list. */
    final String session;
    final int fontSize;
    final boolean imeCompat;
    /** "system", "dark" or "light". */
    final String theme;
    final boolean notifyAttention, notifyDone, notifyBell;
    private final boolean saved;

    private Prefs(Context c, String id) {
        SharedPreferences g = raw(c);
        machine = id;
        SharedPreferences m = file(c, machine);
        name = m.getString("name", "");
        // Optional build-time defaults from android/defaults.properties (not in git), for the first machine only
        boolean first = ids(c).size() == 1;
        host = m.getString("host", first ? BuildConfig.DEFAULT_HOST : "");
        port = m.getInt("port", 22);
        user = m.getString("user", first ? BuildConfig.DEFAULT_USER : "");
        startup = m.getString("startup", "");
        session = m.getString("session", null);
        saved = m.getBoolean("saved", false) || m.contains("host");
        fontSize = g.getInt("font_size", 0);
        imeCompat = g.getBoolean("ime_compat", false);
        theme = g.getString("theme", "system");
        notifyAttention = g.getBoolean("notify_attention", true);
        notifyDone = g.getBoolean("notify_done", true);
        notifyBell = g.getBoolean("notify_bell", false);
    }

    /** App-wide preferences. */
    static SharedPreferences raw(Context c) {
        return c.getSharedPreferences("conn", Context.MODE_PRIVATE);
    }

    /** The current machine's preferences. */
    static SharedPreferences machine(Context c) {
        return file(c, currentId(c));
    }

    private static SharedPreferences file(Context c, String id) {
        return c.getSharedPreferences("machine_" + id, Context.MODE_PRIVATE);
    }

    static Prefs load(Context c) {
        return new Prefs(c, currentId(c));
    }

    /** One machine's connection (with the app-wide settings), current or not. */
    static Prefs of(Context c, String id) {
        return new Prefs(c, id);
    }

    /** Configured once the user saved a host; prefilled defaults don't count. */
    boolean configured() {
        return saved && !host.isEmpty() && !user.isEmpty();
    }

    boolean anyNotifications() {
        return notifyAttention || notifyDone || notifyBell;
    }

    /** Name shown in the switcher: the given name, else the host. */
    String label(Context c) {
        return label(c, name, host);
    }

    static String label(Context c, String name, String host) {
        if (!name.isEmpty()) return name;
        if (!host.isEmpty()) return host;
        return c.getString(R.string.machine_new);
    }

    // ---- machines ----

    static final class Machine {
        final String id, name, host, user;
        final int port;
        final boolean configured;

        Machine(String id, SharedPreferences m) {
            this.id = id;
            name = m.getString("name", "");
            host = m.getString("host", "");
            user = m.getString("user", "");
            port = m.getInt("port", 22);
            configured = !host.isEmpty() && !user.isEmpty();
        }

        String label(Context c) {
            return Prefs.label(c, name, host);
        }

        String address() {
            return user + "@" + host + (port == 22 ? "" : ":" + port);
        }
    }

    private static List<String> ids(Context c) {
        String s = raw(c).getString("machines", "");
        List<String> out = new ArrayList<>();
        for (String id : s.split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private static void saveIds(Context c, List<String> ids) {
        raw(c).edit().putString("machines", String.join(",", ids)).apply();
    }

    static synchronized String currentId(Context c) {
        List<String> ids = ids(c);
        if (ids.isEmpty()) {
            String id = newId(c);
            migrateLegacy(c, id);
            ids.add(id);
            saveIds(c, ids);
            raw(c).edit().putString("current", id).apply();
            return id;
        }
        String cur = raw(c).getString("current", "");
        return ids.contains(cur) ? cur : ids.get(0);
    }

    static List<Machine> machines(Context c) {
        currentId(c);
        List<Machine> out = new ArrayList<>();
        for (String id : ids(c)) out.add(new Machine(id, file(c, id)));
        return out;
    }

    static void select(Context c, String id) {
        if (ids(c).contains(id)) raw(c).edit().putString("current", id).apply();
    }

    /** Adds an empty machine and makes it current. */
    static synchronized String addMachine(Context c) {
        currentId(c);
        String id = newId(c);
        List<String> ids = ids(c);
        ids.add(id);
        saveIds(c, ids);
        raw(c).edit().putString("current", id).apply();
        return id;
    }

    /** Removes a machine; if it was current, the first remaining one becomes current. Keeps at least one. */
    static synchronized void removeMachine(Context c, String id) {
        List<String> ids = ids(c);
        if (!ids.remove(id)) return;
        file(c, id).edit().clear().commit();
        c.deleteSharedPreferences("machine_" + id);
        if (ids.isEmpty()) ids.add(newId(c));
        saveIds(c, ids);
        if (id.equals(raw(c).getString("current", ""))) raw(c).edit().putString("current", ids.get(0)).apply();
    }

    /** A machine with this address, or null. */
    static String find(Context c, String host, int port, String user) {
        for (Machine m : machines(c)) {
            if (m.host.equals(host) && m.port == port && m.user.equals(user)) return m.id;
        }
        return null;
    }

    private static String newId(Context c) {
        int n = raw(c).getInt("next_machine", 1);
        raw(c).edit().putInt("next_machine", n + 1).apply();
        return "m" + n;
    }

    /** Before multi-machine support everything lived in "conn". */
    private static void migrateLegacy(Context c, String id) {
        SharedPreferences g = raw(c);
        if (!g.contains("host")) return;
        SharedPreferences.Editor m = file(c, id).edit();
        SharedPreferences.Editor ge = g.edit();
        for (Map.Entry<String, ?> e : g.getAll().entrySet()) {
            String k = e.getKey();
            if (!MACHINE_KEYS.contains(k) && !k.equals("saved") && !k.equals("session")
                && !k.equals("recent_dirs") && !k.equals("last_event")) continue;
            Object v = e.getValue();
            if (v instanceof String) m.putString(k, (String) v);
            else if (v instanceof Integer) m.putInt(k, (Integer) v);
            else if (v instanceof Long) m.putLong(k, (Long) v);
            else if (v instanceof Boolean) m.putBoolean(k, (Boolean) v);
            ge.remove(k);
        }
        m.apply();
        ge.apply();
    }

    // ---- writes ----

    /** Keys stored per machine; everything else is app-wide. */
    private static final List<String> MACHINE_KEYS = Arrays.asList("name", "host", "port", "user", "startup");

    /** Saves the current machine's connection. */
    static void save(Context c, String host, int port, String user, String startup) {
        machine(c).edit().putString("host", host).putInt("port", port).putString("user", user)
            .putString("startup", startup).putBoolean("saved", true).apply();
    }

    static void put(Context c, String key, String value) {
        (MACHINE_KEYS.contains(key) ? machine(c) : raw(c)).edit().putString(key, value).apply();
    }

    static void put(Context c, String key, boolean value) {
        raw(c).edit().putBoolean(key, value).apply();
    }

    static void putPort(Context c, int port) {
        machine(c).edit().putInt("port", port).apply();
    }

    static void saveSession(Context c, String name) {
        if (name == null) machine(c).edit().remove("session").apply();
        else machine(c).edit().putString("session", name).apply();
    }

    static void saveFontSize(Context c, int size) {
        raw(c).edit().putInt("font_size", size).apply();
    }

    /** Most recent start directories on the current machine, newest first. */
    static List<String> recentDirs(Context c) {
        String s = machine(c).getString("recent_dirs", "");
        List<String> out = new ArrayList<>();
        for (String d : s.split("\n")) if (!d.isEmpty()) out.add(d);
        return out;
    }

    static void addRecentDir(Context c, String dir) {
        List<String> l = recentDirs(c);
        l.remove(dir);
        l.add(0, dir);
        while (l.size() > 6) l.remove(l.size() - 1);
        machine(c).edit().putString("recent_dirs", String.join("\n", l)).apply();
    }

    static long lastEvent(Context c) {
        return machine(c).getLong("last_event", 0);
    }

    static void saveLastEvent(Context c, long ts) {
        machine(c).edit().putLong("last_event", ts).apply();
    }

    static final List<String> SHELLS = Arrays.asList("zsh", "bash", "sh", "fish", "dash", "ksh", "tcsh", "nu");
}
