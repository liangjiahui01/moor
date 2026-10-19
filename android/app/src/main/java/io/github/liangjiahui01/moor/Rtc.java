package io.github.liangjiahui01.moor;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Direct (peer-to-peer) connections over WebRTC. The app ships moor-rtc (Go) as libmoorrtc.so; each SSH
 * connection runs one `moor-rtc dial`, and SSH goes over its stdin/stdout. It finds a direct path through
 * the relay named in the pairing link and, when there is none, connects to the machine's address as usual.
 */
final class Rtc {
    private static final String TAG = "MoorRtc";

    /** The bundled binary, or null on a device ABI it wasn't built for. */
    static File binary(Context c) {
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "libmoorrtc.so");
        return f.exists() ? f : null;
    }

    /** Direct connections are set up for this machine and possible on this device. */
    static boolean usable(Context c, String signal, String room, String key) {
        return !signal.isEmpty() && !room.isEmpty() && !key.isEmpty() && binary(c) != null;
    }

    /** Which paths a direct connection may take: "auto" (first that works), "lan" or "wan". */
    static String route(Context c) {
        String r = Prefs.raw(c).getString("route", "auto");
        return "lan".equals(r) || "wan".equals(r) ? r : "auto";
    }

    /**
     * "lan" or "wan" from a "route direct <type> <addr>:<port> <-> <type> <addr>:<port>" line: private when
     * the machine's address is private, or ours is unique-local IPv6 (an office network's IPv6 reaching the
     * machine's company IPv6 address never leaves the company).
     */
    static String pathOf(String line) {
        String[] f = line.trim().split("\\s+");
        int arrow = -1;
        for (int i = 0; i < f.length; i++) if (f[i].equals("<->")) arrow = i;
        if (arrow < 1 || arrow + 2 >= f.length) return "";
        String local = hostOf(f[arrow - 1]), remote = hostOf(f[arrow + 2]);
        return isPrivate(remote) || (local.contains(":") && isPrivate(local)) ? "lan" : "wan";
    }

    private static String hostOf(String addrPort) {
        int colon = addrPort.lastIndexOf(':');
        return colon > 0 ? addrPort.substring(0, colon) : addrPort;
    }

    static boolean isPrivate(String a) {
        try {
            java.net.InetAddress ip = java.net.InetAddress.getByName(a);
            byte[] b = ip.getAddress();
            if (ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) return true;
            if (b.length == 4) {
                int x = b[0] & 0xff, y = b[1] & 0xff;
                return (x == 100 && y >= 64 && y < 128) || (x == 198 && (y == 18 || y == 19));
            }
            return (b[0] & 0xfe) == 0xfc; // IPv6 unique local
        } catch (Exception e) {
            return false;
        }
    }

    private static int rttOf(String line, int old) {
        try {
            String[] f = line.trim().split("\\s+");
            for (int i = 0; i + 1 < f.length; i++) if (f[i].equals("rtt")) return Integer.parseInt(f[i + 1]);
        } catch (NumberFormatException ignored) {
        }
        return old;
    }

    /** JSch proxy: the SSH stream is moor-rtc's stdin/stdout; host:port is where it falls back to. */
    static final class Proxy implements com.jcraft.jsch.Proxy {
        private final File bin;
        private final String signal, room, key, routePref;
        private Process proc;
        /** "direct", "relay" (fell back to host:port) or "" while connecting. */
        volatile String route = "";
        /** The direct path stopped answering (ICE disconnected or failed) and hasn't come back. */
        volatile boolean pathDown;
        /** The direct path in use: "lan" (private addresses), "wan" (public) or "" before it opens. */
        volatile String path = "";
        /** Round-trip time of the direct path in ms, -1 before the first measurement. */
        volatile int rttMs = -1;

        Proxy(Context c, String signal, String room, String key) {
            this.bin = binary(c);
            this.signal = signal;
            this.room = room;
            this.key = key;
            this.routePref = route(c);
        }

        @Override
        public void connect(com.jcraft.jsch.SocketFactory sf, String host, int port, int timeout) throws Exception {
            String fallback = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "dial", "--signal", signal, "--room", room,
                "--fallback", fallback, "--route", routePref);
            pb.environment().put("MOOR_RTC_KEY", key);
            pb.environment().put("MOOR_ANDROID_SDK", String.valueOf(android.os.Build.VERSION.SDK_INT));
            proc = pb.start();
            final InputStream err = proc.getErrorStream();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Diag.i(TAG, line);
                        if (line.contains("route direct")) {
                            route = "direct";
                            path = pathOf(line);
                        } else if (line.contains(" rtt ")) rttMs = rttOf(line, rttMs);
                        else if (line.contains("route relay")) route = "relay";
                        else if (line.endsWith("ice disconnected") || line.endsWith("ice failed")) pathDown = true;
                        else if (line.endsWith("ice connected")) pathDown = false;
                    }
                } catch (Exception ignored) {
                }
            }, "MoorRtcLog");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public InputStream getInputStream() { return proc.getInputStream(); }

        @Override
        public OutputStream getOutputStream() { return proc.getOutputStream(); }

        @Override
        public Socket getSocket() { return null; }

        @Override
        public void close() {
            if (proc != null) proc.destroy();
        }
    }
}
