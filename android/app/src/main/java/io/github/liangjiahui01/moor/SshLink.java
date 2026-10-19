package io.github.liangjiahui01.moor;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.termux.terminal.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * One SSH connection. Carries at most one terminal channel at a time (attached to a session), swappable at any
 * time, plus one-shot exec channels for commands (list sessions, create a session, ...). Not reused after it
 * drops; reconnecting makes a new one.
 */
final class SshLink implements TerminalSession.Transport {

    interface Listener {
        /**
         * The terminal channel ended, or the connection dropped. sessionAlive=false: the whole connection is gone
         * (channel may be null when no terminal was attached); true: only this channel exited (session ended or swapped).
         */
        void onChannelClosed(SshLink link, Channel channel, boolean sessionAlive, String reason);
    }

    private final Session session;
    private final Listener listener;
    private volatile ChannelExec channel;
    private volatile OutputStream out;
    private volatile boolean closed;

    private SshLink(Session session, Listener listener) {
        this.session = session;
        this.listener = listener;
    }

    /** Blocking; call off the main thread. Connects without opening a terminal. */
    static SshLink open(android.content.Context c, Prefs prefs, byte[] prv, byte[] pub, File knownHosts, Listener listener) throws Exception {
        JschSetup.init();
        JSch jsch = new JSch();
        jsch.addIdentity("moor", prv, pub, null);

        Session s = jsch.getSession(prefs.user, prefs.host, prefs.port);
        Properties cfg = new Properties();
        // Host keys are checked here (trust on first use, then reject changes); JSch's "no" mode only skips its own prompt
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "publickey");
        // No compression: on a LAN it only costs phone CPU
        cfg.put("compression.s2c", "none");
        cfg.put("compression.c2s", "none");
        // With a saved host key, prefer the same key type so a different algorithm is not mistaken for a changed key (pairing saves ed25519)
        String savedType = savedHostKeyType(knownHosts, prefs.host + ":" + prefs.port);
        if (savedType != null) cfg.put("server_host_key", preferHostKeyType(savedType, false));
        s.setConfig(cfg);
        Rtc.Proxy proxy = null;
        if (Rtc.usable(c, prefs.rtcSignal, prefs.rtcRoom, prefs.rtcKey)) {
            proxy = new Rtc.Proxy(c, prefs.rtcSignal, prefs.rtcRoom, prefs.rtcKey);
            s.setProxy(proxy);
        }
        s.setServerAliveInterval(10_000);
        s.setServerAliveCountMax(3);
        // A direct connection first looks for a path (up to 10 s) before falling back
        s.connect(proxy != null ? 25_000 : 8_000);
        checkHostKey(s, knownHosts);
        SshLink l = new SshLink(s, listener);
        l.proxy = proxy;
        return l;
    }

    private Rtc.Proxy proxy;

    /** "direct" (peer to peer), "relay" (direct failed, went to the address) or "" (plain SSH). */
    String route() {
        Rtc.Proxy p = proxy;
        return p == null ? "" : p.route;
    }

    /** The direct connection's proxy, null for plain SSH. */
    Rtc.Proxy proxy() {
        return proxy;
    }

    /** True when a direct connection's path is known to be dead (no need to probe). */
    boolean pathDown() {
        Rtc.Proxy p = proxy;
        return p != null && p.pathDown;
    }

    /**
     * Blocking: opens a pty channel running command (e.g. attach to a session), replacing the current terminal
     * channel. The main thread must have cleared the screen and loaded history before; from here remote output
     * feeds the terminal.
     */
    void attach(TerminalSession term, String command) throws Exception {
        detach();
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setPty(true);
        ch.setPtyType("xterm-256color");
        ch.setPtySize(term.getColumns(), term.getRows(),
            term.getColumns() * term.getCellWidth(), term.getRows() * term.getCellHeight());
        ch.setEnv("LANG", "en_US.UTF-8");
        ch.setEnv("COLORTERM", "truecolor");
        InputStream in = ch.getInputStream();
        OutputStream o = ch.getOutputStream();
        ch.connect(5_000);
        out = o;
        channel = ch;
        Thread reader = new Thread("SshReader") {
            @Override
            public void run() {
                String reason = REASON_CLOSED;
                byte[] buf = new byte[16 * 1024];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (n > 0) term.feed(buf, 0, n);
                    }
                    reason = REASON_ENDED;
                } catch (IOException e) {
                    reason = REASON_NETWORK;
                    Diag.i("SshLink", "read ended: " + e);
                }
                if (closed || !session.isConnected()) {
                    close(); // reports the dropped connection
                    return;
                }
                listener.onChannelClosed(SshLink.this, ch, true, reason);
            }
        };
        reader.setDaemon(true);
        reader.start();
    }

    static final String REASON_CLOSED = "closed", REASON_ENDED = "ended", REASON_NETWORK = "network";

    interface LineHandler {
        void onLine(String line);
    }

    /** Runs a long-lived command (no terminal) and hands every output line to the handler, on its own thread. */
    void stream(String command, LineHandler handler) {
        Thread t = new Thread("SshStream") {
            @Override
            public void run() {
                ChannelExec ch = null;
                try {
                    ch = (ChannelExec) session.openChannel("exec");
                    ch.setCommand(command);
                    ch.setErrStream(new ByteArrayOutputStream());
                    java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(ch.getInputStream(), StandardCharsets.UTF_8));
                    ch.connect(5_000);
                    String line;
                    while ((line = r.readLine()) != null) handler.onLine(line);
                } catch (Exception e) {
                    Diag.i("SshLink", "stream ended: " + e);
                } finally {
                    if (ch != null) ch.disconnect();
                }
                // The events stream outlives every screen, so it is what notices a dead connection on the session list
                if (!closed && !session.isConnected()) close();
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** Detaches the terminal channel (the remote session keeps running); the connection stays. */
    void detach() {
        ChannelExec ch = channel;
        channel = null;
        out = null;
        if (ch != null) {
            try { ch.disconnect(); } catch (Exception ignored) { }
        }
    }

    Channel currentChannel() { return channel; }

    /** Blocking: runs one command and returns stdout. stdin may be null. */
    String exec(String command, byte[] stdin, int timeoutMs) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setEnv("LANG", "en_US.UTF-8");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ch.setErrStream(err);
        InputStream in = ch.getInputStream();
        OutputStream o = ch.getOutputStream();
        ch.connect(timeoutMs);
        try {
            if (stdin != null) o.write(stdin);
            o.close();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long until = System.currentTimeMillis() + timeoutMs;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    got.write(buf, 0, n);
                }
                if (ch.isClosed() && in.available() <= 0) break;
                if (System.currentTimeMillis() > until) throw new IOException("command timed out");
                Thread.sleep(15);
            }
            if (ch.getExitStatus() != 0) {
                throw new IOException(err.toString("UTF-8").trim().isEmpty()
                    ? "command failed (" + ch.getExitStatus() + ")" : err.toString("UTF-8").trim());
            }
            return got.toString("UTF-8");
        } finally {
            ch.disconnect();
        }
    }

    /** Saves the host key on first connect; disconnects if it changes later, so an impostor host can't take over. */
    /**
     * Host key algorithms putting those of one key type first (only those when exclusive). An "ssh-rsa" key is
     * signed with rsa-sha2-512/256 by current servers, so all three RSA names count as that type.
     */
    static String preferHostKeyType(String type, boolean exclusive) {
        String[] own = type.equals("ssh-rsa") ? new String[] {"rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"} : new String[] {type};
        StringBuilder algs = new StringBuilder(String.join(",", own));
        if (!exclusive) {
            for (String a : JSch.getConfig("server_host_key").split(",")) {
                if (!java.util.Arrays.asList(own).contains(a)) algs.append(',').append(a);
            }
        }
        return algs.toString();
    }

    private static String savedHostKeyType(File store, String id) {
        if (!store.exists()) return null;
        java.util.Properties known = new java.util.Properties();
        try (java.io.FileInputStream f = new java.io.FileInputStream(store)) {
            known.load(f);
        } catch (IOException e) {
            return null;
        }
        String saved = known.getProperty(id);
        return saved == null ? null : saved.split(" ")[0];
    }

    private static void checkHostKey(Session s, File store) throws Exception {
        String fp = s.getHostKey().getType() + " " + s.getHostKey().getKey();
        String id = s.getHost() + ":" + s.getPort();
        java.util.Properties known = new java.util.Properties();
        if (store.exists()) {
            try (java.io.FileInputStream f = new java.io.FileInputStream(store)) { known.load(f); }
        }
        String saved = known.getProperty(id);
        if (saved == null) {
            known.setProperty(id, fp);
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(store)) { known.store(f, null); }
        } else if (!saved.equals(fp)) {
            s.disconnect();
            throw new Exception("HostKey changed for " + id);
        }
    }

    /** Size changes come from the main thread; Android forbids network I/O there (it throws and corrupts the SSH stream), so send from a background thread. */
    private final java.util.concurrent.ExecutorService ctl = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void resize(int columns, int rows, int cellW, int cellH) {
        if (closed) return;
        ctl.execute(() -> {
            ChannelExec ch = channel;
            if (closed || ch == null) return;
            try {
                ch.setPtySize(columns, rows, columns * cellW, rows * cellH);
            } catch (Exception e) {
                android.util.Log.w("SshLink", "resize failed: " + e);
            }
        });
    }

    @Override
    public void write(byte[] data, int offset, int count) throws IOException {
        OutputStream o = out;
        if (closed || o == null) return;
        o.write(data, offset, count);
        o.flush();
    }

    /** Liveness check: opens a channel running `true`; if that doesn't open within 1.5 s the connection is dead. */
    boolean probe() {
        return probe(1_500);
    }

    /** True when the machine answers within timeoutMs (opening a channel takes two round trips). */
    boolean probe(int timeoutMs) {
        if (closed || !session.isConnected()) return false;
        ChannelExec exec = null;
        try {
            exec = (ChannelExec) session.openChannel("exec");
            exec.setCommand("true");
            exec.connect(timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (exec != null) exec.disconnect();
        }
    }

    boolean isClosed() { return closed; }

    /** The host's public key as "type base64". */
    String hostKey() {
        return session.getHostKey().getType() + " " + session.getHostKey().getKey();
    }

    /** Ends the connection and reports it once as dropped (the owner ignores links it already let go of). */
    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        ctl.shutdown();
        detach();
        try { session.disconnect(); } catch (Exception ignored) { }
        listener.onChannelClosed(this, null, false, REASON_NETWORK);
    }
}
