package io.github.liangjiahui01.moor;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Hands this app's own APK to a device on the same Wi-Fi (or this phone's hotspot): a small HTTP server
 * on a random port and path, running only while the setup sheet is open. The new device gets the same
 * version and signature, so it can update from Releases later.
 */
final class ApkServer {
    private final File apk;
    private final String token, page, file;
    private ServerSocket server;
    private volatile boolean stopped;

    private ApkServer(Context c, String page) {
        this.apk = new File(c.getApplicationInfo().sourceDir);
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        this.token = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        this.page = page;
        this.file = "moor-" + BuildConfig.VERSION_NAME + ".apk";
    }

    /** Starts serving; returns null when this phone has no local network address to serve on. */
    static ApkServer start(Context c, String page) {
        String ip = lanAddress();
        if (ip == null) return null;
        ApkServer s = new ApkServer(c, page);
        try {
            s.server = new ServerSocket(0, 16);
        } catch (Exception e) {
            return null;
        }
        s.url = "http://" + ip + ":" + s.server.getLocalPort() + "/" + s.token + "/";
        Thread t = new Thread(s::acceptLoop, "ApkServer");
        t.setDaemon(true);
        t.start();
        return s;
    }

    /** Address of the landing page, for the QR code. */
    String url;

    void stop() {
        stopped = true;
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }

    /** A private IPv4 address on Wi-Fi, Ethernet or this phone's hotspot (not mobile data or a VPN). */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")
                    || n.startsWith("rndis") || n.startsWith("softap"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "ApkServerConn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (stopped) return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15_000);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0], path = parts.length > 1 ? parts[1] : "/";
            while ((line = r.readLine()) != null && !line.isEmpty()) { /* headers */ }
            OutputStream out = sock.getOutputStream();
            boolean head = method.equals("HEAD");
            String base = "/" + token + "/";
            if (path.equals(base) || path.equals("/" + token)) {
                byte[] body = page.replace("{apk}", base + file).replace("{size}",
                    String.format(java.util.Locale.US, "%.1f MB", apk.length() / 1048576.0)).getBytes(StandardCharsets.UTF_8);
                header(out, "200 OK", "text/html; charset=utf-8", body.length, null);
                if (!head) out.write(body);
            } else if (path.equals(base + file)) {
                header(out, "200 OK", "application/vnd.android.package-archive", apk.length(),
                    "attachment; filename=\"" + file + "\"");
                if (!head) {
                    try (InputStream in = new FileInputStream(apk)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } else {
                byte[] body = "not found\n".getBytes(StandardCharsets.UTF_8);
                header(out, "404 Not Found", "text/plain", body.length, null);
                if (!head) out.write(body);
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static void header(OutputStream out, String status, String type, long length, String disposition) throws Exception {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Cache-Control: no-store\r\nConnection: close\r\n");
        if (disposition != null) h.append("Content-Disposition: ").append(disposition).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
}
