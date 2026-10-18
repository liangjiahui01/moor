package io.github.liangjiahui01.moor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.jcraft.jsch.JSchException;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that owns the terminal and the SSH connection, so rotating the screen or
 * leaving the app doesn't drop anything. Reconnects on its own and reattaches the last session.
 * Also follows the remote event log (server/moor) and turns events into notifications.
 */
public class TermService extends Service {

    enum State { IDLE, CONNECTING, CONNECTED, WAITING, FAILED }

    interface Screen {
        void onState(State state, String message);

        /** Probing the connection after coming back to the foreground. */
        void onProbe(boolean probing);

        /** Attached to a session (null name = no python3 on the machine, a plain throwaway shell). */
        void onAttached(String name);

        /** The user needs to pick a session; note is an optional one-line explanation. */
        void onShowSessions(String note);

        /** Something changed that the session list shows (an event arrived). */
        void onSessionsChanged();

        /** The terminal (not the session list) is on screen. */
        boolean terminalVisible();
    }

    interface Result<T> {
        void done(T value, String error);
    }

    /** One line from the remote event log. */
    static final class Event {
        long ts;
        String kind, session, source, cwd, body;

        static Event parse(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) return null;
            Event e = new Event();
            try {
                e.ts = Long.parseLong(f[0]);
            } catch (NumberFormatException x) {
                return null;
            }
            e.kind = f[1];
            e.session = f[2];
            e.source = f[3];
            e.cwd = f[4];
            e.body = f[5];
            return e;
        }

        String label() {
            return !session.isEmpty() ? session : cwd;
        }
    }

    static final String ACTION_STOP = "moor.STOP";
    static final String EXTRA_SESSION = "session";
    private static final String TAG = "TermService";
    private static final String CHANNEL_CONN = "conn";
    private static final String CHANNEL_EVENTS = "events";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ClientProxy clientProxy = new ClientProxy();

    TerminalSession term;
    private SshLink link;
    /** Attached session; null = on the session list. */
    private String current;
    private boolean pythonOk = true;
    private State state = State.IDLE;
    private String message = "";
    private int attempt;
    private Screen screen;
    private long backgroundSince;
    private final Runnable reconnect = this::connect;
    private ConnectivityManager.NetworkCallback netCallback;
    /** Sessions waiting for the user (latest attention event per session). */
    private final Map<String, Event> attention = new HashMap<>();
    /** The machine this service's state (session, attention) belongs to. */
    private String machineId;

    class LocalBinder extends Binder {
        TermService service() { return TermService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        term = new TerminalSession(5000, clientProxy);
        current = Prefs.load(this).session;
        machineId = Prefs.currentId(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_CONN, getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events),
            NotificationManager.IMPORTANCE_HIGH));
        startInForeground();
        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
        }
        return START_NOT_STICKY;
    }

    // ---- screen ----

    void attachScreen(Screen s, TerminalSessionClient client) {
        this.screen = s;
        clientProxy.target = client;
        main.removeCallbacks(idleDisconnect);
        s.onState(state, message);
    }

    void detachScreen() {
        if (screen == null) return;
        screen = null;
        clientProxy.target = null;
        backgroundSince = SystemClock.elapsedRealtime();
        main.removeCallbacks(idleDisconnect);
        // With notifications on, stay connected in the background so events keep arriving
        if (!Prefs.load(this).anyNotifications()) main.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS);
    }

    boolean foreground() { return screen != null; }

    /** Left in the background for a long time: disconnect to save battery; reconnect on return. */
    private static final long IDLE_DISCONNECT_MS = 10 * 60_000L;
    private final Runnable idleDisconnect = () -> {
        if (screen != null) return;
        Log.i(TAG, "idle in background, disconnecting");
        main.removeCallbacks(reconnect);
        dropLink();
        attempt = 0;
        setState(State.WAITING, getString(R.string.state_idle_disconnected));
    };

    /** Back in the foreground: reconnect if waiting; after 15s away, probe the connection first. */
    void onScreenResumed() {
        long away = backgroundSince == 0 ? 0 : SystemClock.elapsedRealtime() - backgroundSince;
        backgroundSince = 0;
        if (state == State.FAILED && away > 0) {
            // e.g. back from adding the public key elsewhere: try again right away
            retryNow();
        } else if (state == State.WAITING) {
            main.removeCallbacks(reconnect);
            attempt = 0;
            connect();
        } else if (state == State.CONNECTED && away > 15_000) {
            final SshLink l = link;
            if (screen != null) screen.onProbe(true);
            io.execute(() -> {
                boolean ok = l.probe();
                if (!ok) {
                    Log.i(TAG, "probe failed after resume");
                    l.close();
                }
                main.post(() -> { if (ok && screen != null) screen.onProbe(false); });
            });
        }
    }

    // ---- connection ----

    /** Called once the terminal knows its size, and after settings change. */
    void connect() {
        if (!term.hasSize()) return;
        if (state == State.CONNECTING || state == State.CONNECTED) return;
        final Prefs prefs = Prefs.load(this);
        if (!prefs.configured()) {
            setState(State.FAILED, getString(R.string.state_not_configured));
            return;
        }
        main.removeCallbacks(reconnect);
        setState(State.CONNECTING, attempt == 0 ? getString(R.string.state_connecting)
            : getString(R.string.state_reconnecting, attempt));
        final File known = new File(getFilesDir(), "known_hosts");
        io.execute(() -> {
            try {
                byte[] prv = Keys.privateKey(this);
                byte[] pub = Keys.publicKeyBytes(this);
                SshLink l = SshLink.open(prefs, prv, pub, known, this::onChannelClosed);
                boolean python = false;
                try {
                    String out = l.exec(Sessions.setupCommand(), null, 8_000);
                    python = out.contains("python-ok");
                    if (python) l.exec(Sessions.installHelperCommand(), helperBytes(), 8_000);
                } catch (Exception e) {
                    Log.w(TAG, "session setup failed", e);
                }
                final boolean py = python;
                main.post(() -> onConnected(l, prefs, py));
            } catch (Exception e) {
                main.post(() -> onConnectFailed(e));
            }
        });
    }

    private byte[] helperBytes() throws java.io.IOException {
        try (InputStream in = getAssets().open("server/moor")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    /** User tapped retry. */
    void retryNow() {
        if (state != State.FAILED && state != State.WAITING) return;
        attempt = 0;
        main.removeCallbacks(reconnect);
        state = State.IDLE;
        connect();
    }

    State state() { return state; }

    String stateMessage() { return message; }

    /** Makes another machine current: drops this connection and connects there, back to its last session. */
    void switchMachine(String id) {
        Prefs.select(this, id);
        reconnectWithNewSettings();
    }

    /** Reconnects with the current settings; if the current machine changed, starts from its own state. */
    void reconnectWithNewSettings() {
        String id = Prefs.currentId(this);
        if (!id.equals(machineId)) {
            machineId = id;
            attention.clear();
            current = Prefs.load(this).session;
            if (screen != null) screen.onSessionsChanged();
        }
        attempt = 0;
        main.removeCallbacks(reconnect);
        dropLink();
        state = State.IDLE;
        connect();
    }

    private void dropLink() {
        if (link != null) {
            SshLink old = link;
            link = null;
            term.setTransport(null);
            io.execute(old::close);
        }
    }

    private void onConnected(SshLink l, Prefs prefs, boolean python) {
        Log.i(TAG, "connected, python=" + python);
        link = l;
        attempt = 0;
        pythonOk = python;
        setState(State.CONNECTED, prefs.user + "@" + prefs.host);
        if (python) startEvents(l);
        if (!python) {
            // No python3 for the session daemon: fall back to one throwaway shell
            attachCommand(null, "exec \"$SHELL\" -l", prefs.startup.trim());
        } else if (current != null) {
            openSession(current);
        } else {
            term.setTransport(null);
            if (screen != null) screen.onShowSessions(null);
        }
    }

    private void onConnectFailed(Exception e) {
        String msg = describe(e);
        Log.w(TAG, "connect failed: " + e);
        if (e instanceof JSchException && String.valueOf(e.getMessage()).contains("Auth")) {
            // Key not authorized yet: retrying won't help until the user fixes it
            setState(State.FAILED, getString(R.string.state_auth_rejected));
            return;
        }
        if (String.valueOf(e.getMessage()).contains("HostKey")) {
            setState(State.FAILED, getString(R.string.state_host_key_changed));
            return;
        }
        scheduleReconnect(msg);
    }

    private void onChannelClosed(SshLink l, com.jcraft.jsch.Channel ch, boolean alive, String reason) {
        Log.i(TAG, "channel closed: " + reason + " alive=" + alive);
        main.post(() -> {
            if (l != link) return;
            if (!alive) {
                link = null;
                term.setTransport(null);
                scheduleReconnect(reason.equals(SshLink.REASON_NETWORK) ? getString(R.string.err_network)
                    : getString(R.string.err_closed));
                return;
            }
            // Connection still up: an old channel we switched away from, or the session ended
            if (ch != l.currentChannel()) return;
            String name = current;
            term.setTransport(null);
            l.detach();
            setCurrent(null);
            if (screen != null) screen.onShowSessions(name == null ? getString(R.string.note_session_ended_plain)
                : getString(R.string.note_session_ended, name));
        });
    }

    private boolean idleInBackground() {
        return screen == null && backgroundSince != 0 && !Prefs.load(this).anyNotifications()
            && SystemClock.elapsedRealtime() - backgroundSince >= IDLE_DISCONNECT_MS;
    }

    private void scheduleReconnect(String reason) {
        if (idleInBackground()) {
            attempt = 0;
            setState(State.WAITING, getString(R.string.state_wait_foreground, reason));
            return;
        }
        long delay = Math.min(500L << Math.min(attempt, 5), 10_000L);
        attempt++;
        String sec = delay % 1000 == 0 ? String.valueOf(delay / 1000) : String.valueOf(delay / 1000.0);
        setState(State.WAITING, getString(R.string.state_retry_in, reason, sec));
        main.postDelayed(reconnect, delay);
    }

    private String describe(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("timeout") || m.contains("timed out")) return getString(R.string.err_timeout);
        if (m.contains("UnknownHost")) return getString(R.string.err_unknown_host);
        if (m.contains("refused")) return getString(R.string.err_refused);
        if (m.contains("unreachable")) return getString(R.string.err_unreachable);
        return getString(R.string.err_failed);
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg;
        if (screen != null) screen.onState(s, msg);
        updateNotification();
    }

    // ---- sessions ----

    /** Persistent sessions need python3 on the machine for the session daemon. */
    boolean sessionsAvailable() { return pythonOk; }

    boolean helperAvailable() { return pythonOk; }

    String currentSession() { return current; }

    Event attentionFor(String session) { return attention.get(session); }

    void openSession(String name) {
        final SshLink l = link;
        if (l == null) return;
        setCurrent(name);
        clearAttention(name);
        io.execute(() -> {
            // The daemon replays the session's recent output; replies the terminal makes to it are stale
            term.setTransport(null);
            feedReset();
            term.replaying = true;
            attachOnIo(l, name, Sessions.attachCommand(name), null);
        });
    }

    private void attachCommand(String name, String command, String startup) {
        final SshLink l = link;
        if (l == null) return;
        io.execute(() -> {
            term.setTransport(null);
            feedReset();
            attachOnIo(l, name, command, startup);
        });
    }

    /** Clears screen and scroll-back through the output queue, so it stays ordered with what follows. */
    private void feedReset() {
        byte[] b = "\033c\033[2J\033[3J\033[H".getBytes(StandardCharsets.US_ASCII);
        term.feed(b, 0, b.length);
    }

    private void attachOnIo(SshLink l, String name, String command, String startup) {
        try {
            l.attach(term, command);
            main.post(() -> {
                if (l != link) return;
                term.setTransport(l);
                // The size may have changed while connecting
                l.resize(term.getColumns(), term.getRows(), term.getCellWidth(), term.getCellHeight());
                if (startup != null && !startup.isEmpty()) {
                    byte[] cmd = (startup + "\r").getBytes(StandardCharsets.UTF_8);
                    term.write(cmd, 0, cmd.length);
                }
                if (screen != null) screen.onAttached(name);
            });
        } catch (Exception e) {
            Log.w(TAG, "attach failed", e);
            if (!l.isClosed() && l.probe()) {
                main.post(() -> {
                    setCurrent(null);
                    if (screen != null) screen.onShowSessions(getString(R.string.note_open_failed, name));
                });
            } else {
                l.close();
                main.post(() -> {
                    if (l != link) return;
                    link = null;
                    term.setTransport(null);
                    scheduleReconnect(getString(R.string.err_network));
                });
            }
        }
    }

    /** Back to the list: detach the terminal channel; the session keeps running remotely. */
    void leaveSession() {
        final SshLink l = link;
        setCurrent(null);
        term.setTransport(null);
        if (l != null) io.execute(l::detach);
    }

    void listSessions(Result<Sessions.Listing> cb) {
        final SshLink l = link;
        if (l == null || !pythonOk) {
            cb.done(null, l == null ? message : getString(R.string.err_no_python));
            return;
        }
        io.execute(() -> {
            try {
                Sessions.Listing r = Sessions.parse(l.exec(Sessions.listCommand(), null, 8_000));
                main.post(() -> cb.done(r, null));
            } catch (Exception e) {
                main.post(() -> cb.done(null, getString(R.string.err_list_failed, e.getMessage())));
            }
        });
    }

    void createSession(String name, String startup, String dir, Result<Void> cb) {
        run(Sessions.createCommand(name, startup, dir, term.getColumns(), term.getRows()), (out, err) -> {
            if (err == null) openSession(name);
            cb.done(null, err);
        });
    }

    void killSession(String name, Result<Void> cb) {
        if (name.equals(current)) leaveSession();
        clearAttention(name);
        run(Sessions.killCommand(name), (out, err) -> cb.done(null, err));
    }

    void renameSession(String from, String to, Result<Void> cb) {
        run(Sessions.renameCommand(from, to), (out, err) -> {
            if (err == null && from.equals(current)) setCurrent(to);
            Event e = attention.remove(from);
            if (e != null) attention.put(to, e);
            cb.done(null, err);
        });
    }

    /** Runs a command on the machine; result is its stdout. */
    void run(String cmd, Result<String> cb) {
        final SshLink l = link;
        if (l == null) {
            cb.done(null, getString(R.string.err_not_connected));
            return;
        }
        io.execute(() -> {
            String out = null, err = null;
            try {
                out = l.exec(cmd, null, 10_000);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String o = out, m = err;
            main.post(() -> cb.done(o, m));
        });
    }

    private void setCurrent(String name) {
        current = name;
        Prefs.saveSession(this, name);
        updateNotification();
    }

    // ---- events and notifications ----

    private void startEvents(SshLink l) {
        long since = Prefs.lastEvent(this);
        String cmd = since > 0 ? Sessions.watchCommand(since) : "exec python3 \"" + Sessions.HELPER + "\" watch";
        l.stream(cmd, line -> {
            if (line.equals("ready")) return;
            Event e = Event.parse(line);
            if (e != null) main.post(() -> onEvent(e));
        });
    }

    /** When a Claude notification was last shown, by notification id. */
    private final java.util.Map<Integer, Long> richShown = new java.util.HashMap<>();

    private void onEvent(Event e) {
        if (e.ts <= Prefs.lastEvent(this)) return;
        Prefs.saveLastEvent(this, e.ts);
        boolean mine = e.source.equals("moor") && !e.session.isEmpty();
        if (mine) {
            if (e.kind.equals("attention")) attention.put(e.session, e);
            else if (e.kind.equals("done")) attention.remove(e.session);
        }
        if (screen != null) screen.onSessionsChanged();
        Prefs p = Prefs.load(this);
        boolean wanted = (e.kind.equals("attention") && p.notifyAttention) || (e.kind.equals("done") && p.notifyDone)
            || (e.kind.equals("bell") && p.notifyBell);
        if (!wanted) return;
        // The user is looking at that session right now
        if (screen != null && screen.terminalVisible() && mine && e.session.equals(current)) {
            if (e.kind.equals("attention")) attention.remove(e.session);
            return;
        }
        String title;
        String label = e.label();
        switch (e.kind) {
            case "attention": title = getString(R.string.notif_attention, label); break;
            case "done": title = getString(R.string.notif_done, label); break;
            default: title = getString(R.string.notif_bell, label);
        }
        String body = e.body.isEmpty() ? e.cwd : e.body;
        if (e.source.equals("herdr")) body = "herdr · " + body;
        Intent open = new Intent(this, TermActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (mine) open.putExtra(EXTRA_SESSION, e.session);
        int id = 100 + (e.source + "/" + label).hashCode() % 100_000;
        // One notification per session: a bell right after a Claude notification would replace the more useful one
        long now = SystemClock.elapsedRealtime();
        if (e.kind.equals("bell")) {
            Long rich = richShown.get(id);
            if (rich != null && now - rich < 30_000) return;
        } else {
            richShown.put(id, now);
        }
        PendingIntent pi = PendingIntent.getActivity(this, id, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(e.ts < 100_000_000_000L ? e.ts * 1000 : e.ts)
            .setShowWhen(true)
            .build();
        getSystemService(NotificationManager.class).notify(id, n);
    }

    private void clearAttention(String session) {
        if (attention.remove(session) != null && screen != null) screen.onSessionsChanged();
        int id = 100 + ("moor/" + session).hashCode() % 100_000;
        getSystemService(NotificationManager.class).cancel(id);
    }

    // ---- network changes: reconnect when the default network changes ----

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            private Network currentNet;

            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    Network prev = currentNet;
                    currentNet = network;
                    if (idleInBackground()) return;
                    if (prev == null || prev.equals(network)) {
                        if (state == State.WAITING) {
                            attempt = 0;
                            main.removeCallbacks(reconnect);
                            connect();
                        }
                        return;
                    }
                    Log.i(TAG, "default network changed");
                    if (link != null) {
                        SshLink l = link;
                        io.execute(l::close); // the reader ends and triggers a reconnect
                    } else if (state == State.WAITING) {
                        attempt = 0;
                        main.removeCallbacks(reconnect);
                        connect();
                    }
                });
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    // ---- foreground notification ----

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, TermActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, TermService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String text;
        switch (state) {
            case CONNECTED:
                text = current != null ? getString(R.string.fg_in_session, current, message) : getString(R.string.fg_connected, message);
                break;
            case IDLE: text = getString(R.string.fg_idle); break;
            default: text = message;
        }
        return new Notification.Builder(this, CHANNEL_CONN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, getString(R.string.fg_disconnect), stop).build())
            .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(1, buildNotification());
    }

    void shutdown() {
        main.removeCallbacks(reconnect);
        if (link != null) {
            SshLink l = link;
            link = null;
            io.execute(l::close);
        }
        term.setTransport(null);
        state = State.IDLE;
        if (screen instanceof TermActivity) ((TermActivity) screen).finishAndRemoveTask();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (netCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        }
        if (link != null) { SshLink l = link; io.execute(l::close); }
        term.finish();
        io.shutdown();
        super.onDestroy();
    }

    /** Forwards terminal callbacks to the current screen; dropped while none is attached. */
    private static final class ClientProxy implements TerminalSessionClient {
        volatile TerminalSessionClient target;

        @Override public void onTextChanged(TerminalSession s) { if (target != null) target.onTextChanged(s); }
        @Override public void onTitleChanged(TerminalSession s) { if (target != null) target.onTitleChanged(s); }
        @Override public void onSessionFinished(TerminalSession s) { if (target != null) target.onSessionFinished(s); }
        @Override public void onCopyTextToClipboard(TerminalSession s, String t) { if (target != null) target.onCopyTextToClipboard(s, t); }
        @Override public void onPasteTextFromClipboard(TerminalSession s) { if (target != null) target.onPasteTextFromClipboard(s); }
        @Override public void onBell(TerminalSession s) { if (target != null) target.onBell(s); }
        @Override public void onColorsChanged(TerminalSession s) { if (target != null) target.onColorsChanged(s); }
        @Override public void onTerminalCursorStateChange(boolean st) { if (target != null) target.onTerminalCursorStateChange(st); }
        @Override public Integer getTerminalCursorStyle() { return target != null ? target.getTerminalCursorStyle() : null; }
        @Override public void logError(String tag, String m) { Log.e(tag, m); }
        @Override public void logWarn(String tag, String m) { Log.w(tag, m); }
        @Override public void logInfo(String tag, String m) { Log.i(tag, m); }
        @Override public void logDebug(String tag, String m) { Log.d(tag, m); }
        @Override public void logVerbose(String tag, String m) { Log.v(tag, m); }
        @Override public void logStackTraceWithMessage(String tag, String m, Exception e) { Log.e(tag, m, e); }
        @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "", e); }
    }
}
