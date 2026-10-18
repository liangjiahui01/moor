package io.github.liangjiahui01.moor;

import android.content.Context;
import android.net.Uri;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/**
 * Pairing link: moor://pair?v=1&h=&p=&u=&c=&hk=&k=&id=&exp=
 * Printed by `moor pair` on the host. k seeds a throwaway key that can only run `claim`:
 * the phone logs in with it once and hands over its own public key; the host stores
 * that key and removes the throwaway one.
 */
final class Pairing {
    static final String PREFIX = "moor://pair?";

    final String host, user, cmd, hostKey, id;
    final int port;
    final long expiresAt;
    private final byte[] seed;

    private Pairing(String host, int port, String user, String cmd, String hostKey, byte[] seed, String id, long exp) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.cmd = cmd;
        this.hostKey = hostKey;
        this.seed = seed;
        this.id = id;
        this.expiresAt = exp;
    }

    /** Returns null when the text holds no pairing link. Tolerates surrounding whitespace from terminal copies. */
    static Pairing parse(String text) {
        if (text == null) return null;
        int at = text.indexOf(PREFIX);
        if (at < 0) return null;
        String s = text.substring(at).split("\\s")[0];
        try {
            Uri u = Uri.parse(s);
            String host = u.getQueryParameter("h");
            String user = u.getQueryParameter("u");
            String hk = u.getQueryParameter("hk");
            String k = u.getQueryParameter("k");
            if (host == null || user == null || hk == null || k == null) return null;
            byte[] seed = Base64.getUrlDecoder().decode(k);
            if (seed.length != 32) return null;
            String p = u.getQueryParameter("p");
            String c = u.getQueryParameter("c");
            String e = u.getQueryParameter("exp");
            String id = u.getQueryParameter("id");
            return new Pairing(host, p == null ? 22 : Integer.parseInt(p), user, c == null ? "" : c, hk, seed,
                id == null ? "" : id, e == null ? 0 : Long.parseLong(e) * 1000);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    boolean expired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /** Blocking; call off the main thread. On success the phone's key is on the host. */
    void claim(Context c) throws Exception {
        JschSetup.init();
        OpenSshKey tmp = OpenSshKey.fromSeed(seed, "pair");
        JSch jsch = new JSch();
        jsch.addIdentity("pair", tmp.privatePem.getBytes(StandardCharsets.US_ASCII),
            tmp.publicLine.getBytes(StandardCharsets.US_ASCII), null);
        Session s = jsch.getSession(user, host, port);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "publickey");
        cfg.put("server_host_key", "ssh-ed25519");
        s.setConfig(cfg);
        s.setTimeout(10_000);
        try {
            s.connect(8_000);
            if (!hostKey.equals(s.getHostKey().getKey())) throw new PairException(c.getString(R.string.pair_err_hostkey));
            ChannelExec ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand("claim");
            InputStream in = ch.getInputStream();
            ch.setErrStream(new ByteArrayOutputStream());
            OutputStream out = ch.getOutputStream();
            ch.connect(5_000);
            out.write((Keys.publicKey(c) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) got.write(buf, 0, n);
            String reply = got.toString("UTF-8").trim();
            ch.disconnect();
            if (!reply.endsWith("ok")) throw new PairException(c.getString(R.string.pair_err_rejected, reply));
        } finally {
            s.disconnect();
        }
    }

    /** Saves the connection and pins the host key from the link instead of trusting on first use. */
    void save(Context c) throws Exception {
        Prefs.save(c, host, port, user, cmd);
        File store = new File(c.getFilesDir(), "known_hosts");
        Properties known = new Properties();
        if (store.exists()) {
            try (java.io.FileInputStream f = new java.io.FileInputStream(store)) { known.load(f); }
        }
        known.setProperty(host + ":" + port, "ssh-ed25519 " + hostKey);
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(store)) { known.store(f, null); }
    }

    /** One readable sentence for the user. */
    static String explain(Context c, Exception e) {
        if (e instanceof PairException) return e.getMessage();
        String m = String.valueOf(e.getMessage());
        if (e instanceof JSchException && m.contains("Auth fail")) {
            return c.getString(R.string.pair_err_auth);
        }
        if (m.contains("timeout") || m.contains("timed out") || m.contains("No route") || m.contains("refused")
            || m.contains("Network is unreachable") || m.contains("UnknownHost")) {
            return c.getString(R.string.pair_err_network);
        }
        return c.getString(R.string.pair_err_generic, m);
    }

    static final class PairException extends Exception {
        PairException(String m) { super(m); }
    }
}
