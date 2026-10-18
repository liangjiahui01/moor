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

    /** JSch proxy: the SSH stream is moor-rtc's stdin/stdout; host:port is where it falls back to. */
    static final class Proxy implements com.jcraft.jsch.Proxy {
        private final File bin;
        private final String signal, room, key;
        private Process proc;
        /** "direct", "relay" (fell back to host:port) or "" while connecting. */
        volatile String route = "";
        /** The direct path stopped answering (ICE disconnected or failed) and hasn't come back. */
        volatile boolean pathDown;

        Proxy(Context c, String signal, String room, String key) {
            this.bin = binary(c);
            this.signal = signal;
            this.room = room;
            this.key = key;
        }

        @Override
        public void connect(com.jcraft.jsch.SocketFactory sf, String host, int port, int timeout) throws Exception {
            String fallback = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "dial", "--signal", signal, "--room", room,
                "--fallback", fallback);
            pb.environment().put("MOOR_RTC_KEY", key);
            pb.environment().put("MOOR_ANDROID_SDK", String.valueOf(android.os.Build.VERSION.SDK_INT));
            proc = pb.start();
            final InputStream err = proc.getErrorStream();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Diag.i(TAG, line);
                        if (line.contains("route direct")) route = "direct";
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
