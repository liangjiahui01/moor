package io.github.liangjiahui01.moor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sessions live in a dedicated tmux server on the remote machine (tmux -L moor), so programs keep
 * running when the phone disconnects. Its config drops the status bar and every key binding, so it
 * feels like a plain terminal. Your own tmux is untouched.
 */
final class Sessions {
    private Sessions() {}

    static final String DIR = "$HOME/.config/moor";
    /** Server helper (see server/moor), installed by the app on every connect. */
    static final String HELPER = "$HOME/.local/share/moor/bin/moor";
    static final String TMUX = "tmux -u -L moor -f " + DIR + "/tmux.conf";

    /** Written on every connect; reloaded if the tmux server is already running. */
    /** Private key that makes tmux leave scroll-back mode; sent before the first input after scrolling up. */
    static final byte[] LEAVE_SCROLL = "\033[7777~".getBytes(StandardCharsets.US_ASCII);

    static final String CONF = String.join("\n",
        "# tmux server used by the Moor app (tmux -L moor). Written by the app on connect; edits are overwritten.",
        "set -g prefix None",
        "set -g prefix2 None",
        "unbind-key -aq",
        "unbind-key -aq -T root",
        "set -g status off",
        "set -s escape-time 0",
        "set -g history-limit 20000",
        "set -g default-terminal tmux-256color",
        "set -ga terminal-overrides ',xterm-256color:RGB'",
        "set -s set-clipboard on",
        "set -g allow-passthrough on",
        "set -g window-size latest",
        "set -g destroy-unattached off",
        "set -g detach-on-destroy on",
        "set -g set-titles off",
        // Finger scroll = mouse wheel: passed to programs that want the mouse, otherwise tmux scroll-back, one line per step
        "set -g mouse on",
        "bind -n WheelUpPane if -F '#{||:#{pane_in_mode},#{mouse_any_flag}}' 'send -M' 'copy-mode -e'",
        "bind -n WheelDownPane send -M",
        "bind -n MouseDown1Pane select-pane -t = \\; send -M",
        "bind -n MouseUp1Pane send -M",
        "bind -T copy-mode WheelUpPane send -X scroll-up",
        "bind -T copy-mode WheelDownPane send -X scroll-down",
        "bind -T copy-mode-vi WheelUpPane send -X scroll-up",
        "bind -T copy-mode-vi WheelDownPane send -X scroll-down",
        // The app's private key: leaves scroll-back mode, does nothing otherwise
        "set -s user-keys[0] \"\\e[7777~\"",
        "bind -n User0 set -gq @moor_noop 1",
        "bind -T copy-mode User0 send -X cancel",
        "bind -T copy-mode-vi User0 send -X cancel",
        // A bell in any session becomes an event the app can turn into a notification
        "set -g monitor-bell on",
        "set -g bell-action any",
        "set-hook -g alert-bell \"run-shell -b '" + HELPER + " notify bell #{q:session_name} >/dev/null 2>&1'\"",
        "") ;

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Writes the tmux config. Prints tmux-ok / python-ok for what the machine has. */
    static String setupCommand() {
        return "mkdir -p " + DIR + " && cat > " + DIR + "/tmux.conf.new && mv -f " + DIR + "/tmux.conf.new " + DIR
            + "/tmux.conf && { command -v tmux >/dev/null 2>&1 && echo tmux-ok; command -v python3 >/dev/null 2>&1 && echo python-ok; "
            + "tmux -L moor source-file " + DIR + "/tmux.conf >/dev/null 2>&1; true; }";
    }

    /** Installs the server helper from stdin. */
    static String installHelperCommand() {
        return "mkdir -p \"$HOME/.local/share/moor/bin\" && cat > \"" + HELPER + ".new\" && chmod 755 \"" + HELPER
            + ".new\" && mv -f \"" + HELPER + ".new\" \"" + HELPER + "\"";
    }

    /** Follows the event log; prints events newer than since, then "ready", then new events as they come. */
    static String watchCommand(long since) {
        return "exec python3 \"" + HELPER + "\" watch --since " + since;
    }

    static String hooksCommand(String action) {
        return "python3 \"" + HELPER + "\" hooks " + action;
    }

    static byte[] confBytes() {
        return CONF.getBytes(StandardCharsets.UTF_8);
    }

    static String attachCommand(String name) {
        return "exec " + TMUX + " attach-session -t " + q("=" + name);
    }

    /** Scroll-back before the current screen. */
    static String historyCommand(String name, int lines) {
        return TMUX + " capture-pane -p -e -J -S -" + lines + " -E -1 -t " + q("=" + name + ":") + " 2>/dev/null; true";
    }

    /** dir "~" or "~/x" is relative to $HOME. */
    static String dirArg(String dir) {
        String d = dir.trim();
        if (d.isEmpty() || d.equals("~")) return "\"$HOME\"";
        if (d.startsWith("~/")) return "\"$HOME\"/" + q(d.substring(2));
        return q(d);
    }

    static String createCommand(String name, String startup, String dir, int cols, int rows) {
        StringBuilder c = new StringBuilder();
        c.append(TMUX).append(" new-session -d -s ").append(q(name)).append(" -x ").append(cols).append(" -y ").append(rows)
            .append(" -c ").append(dirArg(dir));
        if (!startup.trim().isEmpty()) {
            // Start a login shell and type the command into it: the session survives the command exiting,
            // and PATH is the same as in a normal login
            String t = q("=" + name + ":");
            c.append(" && ").append(TMUX).append(" send-keys -t ").append(t).append(" -l ").append(q(startup.trim()))
                .append(" && ").append(TMUX).append(" send-keys -t ").append(t).append(" Enter");
        }
        return c.toString();
    }

    static String killCommand(String name) {
        return TMUX + " kill-session -t " + q("=" + name);
    }

    static String renameCommand(String from, String to) {
        return TMUX + " rename-session -t " + q("=" + from) + " " + q(to);
    }

    /** One line per session: name, created, last output, clients, foreground program, cwd, last two non-empty lines (\037-separated). */
    static String listCommand() {
        String script = String.join("\n",
            "T=\"" + TMUX + "\"",
            "printf 'NOW\\t%s\\t%s\\t%s\\n' \"$(date +%s)\" \"$HOME\" \"$(cd \"$HOME\" && pwd -P)\"",
            "$T list-sessions -F '#{session_name}' 2>/dev/null | while IFS= read -r s; do",
            "  m=$($T display-message -p -t \"=$s:\" '#{session_created}\t#{session_activity}\t#{session_attached}\t#{pane_current_command}\t#{pane_current_path}')",
            "  p=$($T capture-pane -p -J -t \"=$s:\" | grep -v '^[[:space:]]*$' | tail -n 2 | tr '\\t' ' ' | tr '\\n' '\\037')",
            "  printf 'S\\t%s\\t%s\\t%s\\n' \"$s\" \"$m\" \"$p\"",
            "done");
        return "sh -c " + q(script);
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

    /** Drops characters tmux rejects in session names. */
    static String cleanName(String raw) {
        String s = raw.trim().replaceAll("[.:\\s]+", " ").trim();
        while (s.startsWith("=")) s = s.substring(1);
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
