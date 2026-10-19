package io.github.liangjiahui01.moor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sessions are kept by the server helper's session daemon (see server/moor): it owns each session's
 * terminal, so programs keep running when the phone disconnects, and replays recent output when the
 * phone attaches again, which the phone can then scroll locally. Needs only python3 on the machine.
 */
final class Sessions {
    private Sessions() {}

    /** Server helper (see server/moor), installed by the app on every connect. */
    static final String HELPER = "$HOME/.local/share/moor/bin/moor";
    private static final String PY = "python3 \"" + HELPER + "\"";

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Prints python-ok when the machine can run the helper. */
    static String setupCommand() {
        return "command -v python3 >/dev/null 2>&1 && echo python-ok; true";
    }

    /** Like setupCommand, and also prints helper-ok when the installed helper's SHA-256 is sha (no upload needed). */
    static String setupCommand(String sha) {
        return "command -v python3 >/dev/null 2>&1 || exit 0; echo python-ok; python3 -c 'import hashlib,sys\n"
            + "try: print(\"helper-ok\" if hashlib.sha256(open(sys.argv[1],\"rb\").read()).hexdigest()==sys.argv[2] else \"\")\n"
            + "except OSError: pass' \"" + HELPER + "\" " + sha + "; true";
    }

    /** Installs the server helper from stdin. */
    static String installHelperCommand() {
        return "mkdir -p \"$HOME/.local/share/moor/bin\" && cat > \"" + HELPER + ".new\" && chmod 755 \"" + HELPER
            + ".new\" && mv -f \"" + HELPER + ".new\" \"" + HELPER + "\"";
    }

    /** Follows the event log; prints events newer than since, then "ready", then new events as they come. */
    static String watchCommand(long since) {
        return "exec " + PY + " watch --since " + since;
    }

    static String hooksCommand(String action) {
        return PY + " hooks " + action;
    }

    static String attachCommand(String name) {
        return "exec " + PY + " attach " + q(name);
    }

    static String createCommand(String name, String startup, String dir, int cols, int rows) {
        String d = dir.trim().isEmpty() ? "~" : dir.trim();
        String c = PY + " new " + q(name) + " --cwd " + q(d) + " --cols " + cols + " --rows " + rows;
        // The command is typed into the session's login shell: the session survives it exiting
        if (!startup.trim().isEmpty()) c += " --cmd " + q(startup.trim());
        return c;
    }

    static String killCommand(String name) {
        return PY + " kill " + q(name);
    }

    static String renameCommand(String from, String to) {
        return PY + " rename " + q(from) + " " + q(to);
    }

    /** One line per session: name, created, last output, clients, foreground program, cwd, last two non-empty lines (\037-separated). */
    static String dirsCommand(String args) {
        return PY + " dirs" + (args.isEmpty() ? "" : " " + args);
    }

    /** Read-only file preview: `moor file open|changes|diff|raw ...` (see server/moor). */
    static String fileCommand(String args) {
        return PY + " file " + args;
    }

    static String listCommand() {
        return PY + " ls";
    }

    static final class Info {
        String name, command, path, preview;
        long created, activity;
        int attached;
    }

    static final class Listing {
        long now;
        final List<Info> items = new ArrayList<>();
    }

    static Listing parse(String out) {
        Listing l = new Listing();
        String home = "", realHome = "";
        for (String line : out.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f[0].equals("NOW") && f.length >= 3) {
                l.now = parseLong(f[1]);
                home = f[2];
                realHome = f.length > 3 ? f[3] : "";
            } else if (f[0].equals("S") && f.length >= 8) {
                Info i = new Info();
                i.name = f[1];
                i.created = parseLong(f[2]);
                i.activity = parseLong(f[3]);
                i.attached = (int) parseLong(f[4]);
                i.command = f[5];
                i.path = shortPath(f[6], home, realHome);
                i.preview = f.length > 7 ? f[7].replace('\037', '\n').trim() : "";
                l.items.add(i);
            }
        }
        // Most recent output first
        Collections.sort(l.items, (a, b) -> Long.compare(b.activity, a.activity));
        return l;
    }

    /** Single spaces, no control characters, at most 40 characters. */
    static String cleanName(String raw) {
        String s = raw.trim().replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String shortPath(String p, String home, String realHome) {
        for (String h : new String[]{realHome, home}) {
            if (!h.isEmpty() && (p.equals(h) || p.startsWith(h + "/"))) return "~" + p.substring(h.length());
        }
        return p;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
