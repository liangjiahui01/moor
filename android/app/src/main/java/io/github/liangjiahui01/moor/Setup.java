package io.github.liangjiahui01.moor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Setting up another device from this one: moor://setup?d=<deflated JSON, base64url>
 * {"v":1, "m":[pairing links], "cur":i, "p":{app settings}}. Each pairing link is a one-time code
 * this device makes on the machine with its own login (helper `invite`), so the other device
 * registers its own key and this device's private key never leaves it.
 */
final class Setup {
    static final String PREFIX = "moor://setup?";
    static final int MINUTES = 10;

    /** App-wide settings carried over; voice keys only when asked for. */
    private static final String[] BOOLS = {"notify_attention", "notify_done", "notify_bell", "ime_compat", "top_bar"};
    private static final String[] STRINGS = {"theme"};
    static final String[] VOICE = {"voice_api_key", "voice_app_id", "voice_access_token", "voice_model"};

    static final class Invites {
        final List<String> links = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        int current = 0;
        long expiresAt;
    }

    /** Blocking. One pairing link per machine this device can reach. */
    static Invites makeInvites(Context c, byte[] helper) {
        Invites out = new Invites();
        String cur = Prefs.currentId(c);
        File known = new File(c.getFilesDir(), "known_hosts");
        SecureRandom rnd = new SecureRandom();
        for (Prefs.Machine m : Prefs.machines(c)) {
            if (!m.configured) continue;
            Prefs p = Prefs.of(c, m.id);
            SshLink l = null;
            try {
                l = SshLink.open(p, Keys.privateKey(c), Keys.publicKeyBytes(c), known, (a, b, d, e) -> { });
                String hk = l.hostKey();
                if (!hk.startsWith("ssh-ed25519 ")) throw new Pairing.PairException(c.getString(R.string.setup_no_ed25519));
                if (!l.exec(Sessions.setupCommand(), null, 8_000).contains("python-ok")) {
                    throw new Pairing.PairException(c.getString(R.string.setup_need_python));
                }
                // The helper there may predate `invite`
                l.exec(Sessions.installHelperCommand(), helper, 8_000);
                byte[] seed = new byte[32];
                rnd.nextBytes(seed);
                byte[] idb = new byte[4];
                rnd.nextBytes(idb);
                StringBuilder id = new StringBuilder();
                for (byte b : idb) id.append(String.format("%02x", b));
                OpenSshKey tmp = OpenSshKey.fromSeed(seed, "pair");
                String pub = tmp.publicLine.split(" ")[0] + " " + tmp.publicLine.split(" ")[1];
                String reply = l.exec("python3 \"" + Sessions.HELPER + "\" invite " + id + " " + MINUTES,
                    (pub + "\n").getBytes(StandardCharsets.US_ASCII), 10_000).trim();
                if (!reply.startsWith("ok ")) throw new Pairing.PairException(reply);
                long exp = Long.parseLong(reply.substring(3).trim());
                out.expiresAt = out.expiresAt == 0 ? exp * 1000 : Math.min(out.expiresAt, exp * 1000);
                if (m.id.equals(cur)) out.current = out.links.size();
                out.links.add(Pairing.PREFIX + "v=1&h=" + Uri.encode(p.host) + "&p=" + p.port + "&u=" + Uri.encode(p.user)
                    + "&c=" + Uri.encode(p.startup) + "&n=" + Uri.encode(p.name) + "&hk=" + Uri.encode(hk.substring(12))
                    + "&k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(seed) + "&id=" + id + "&exp=" + exp);
            } catch (Exception e) {
                android.util.Log.w("Moor", "invite failed for " + m.address(), e);
                out.skipped.add(m.label(c) + " (" + Pairing.explain(c, e) + ")");
            } finally {
                if (l != null) l.close();
            }
        }
        return out;
    }

    static boolean hasVoice(Context c) {
        return VoiceInput.Config.load(c).usable();
    }

    static String bundle(Context c, Invites inv, boolean withVoice) {
        try {
            JSONObject o = new JSONObject().put("v", 1).put("cur", inv.current);
            JSONArray m = new JSONArray();
            for (String l : inv.links) m.put(l);
            o.put("m", m);
            SharedPreferences g = Prefs.raw(c);
            JSONObject p = new JSONObject();
            for (String k : BOOLS) if (g.contains(k)) p.put(k, g.getBoolean(k, false));
            for (String k : STRINGS) if (g.contains(k)) p.put(k, g.getString(k, ""));
            if (withVoice) {
                for (String k : VOICE) {
                    String v = g.getString(k, "");
                    if (!v.isEmpty()) p.put(k, v);
                }
            }
            o.put("p", p);
            byte[] raw = o.toString().getBytes(StandardCharsets.UTF_8);
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            return PREFIX + "v=1&d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(z.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final class Parsed {
        final List<Pairing> machines = new ArrayList<>();
        int current;
        JSONObject prefs;

        boolean hasVoice() {
            return prefs != null && (prefs.has("voice_api_key") || prefs.has("voice_access_token"));
        }
    }

    /** null when the text holds no setup link. */
    static Parsed parse(String text) {
        if (text == null) return null;
        int at = text.indexOf(PREFIX);
        if (at < 0) return null;
        try {
            String d = Uri.parse(text.substring(at).split("\\s")[0]).getQueryParameter("d");
            if (d == null) return null;
            byte[] z = Base64.getUrlDecoder().decode(d);
            Inflater inf = new Inflater(true);
            inf.setInput(z);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                raw.write(buf, 0, n);
                if (raw.size() > 64 * 1024) return null;
            }
            inf.end();
            JSONObject o = new JSONObject(raw.toString("UTF-8"));
            Parsed out = new Parsed();
            JSONArray m = o.getJSONArray("m");
            for (int i = 0; i < m.length(); i++) {
                Pairing p = Pairing.parse(m.getString(i));
                if (p != null) out.machines.add(p);
            }
            out.current = o.optInt("cur", 0);
            out.prefs = o.optJSONObject("p");
            return out.machines.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes the carried-over settings; only known keys. */
    static void applyPrefs(Context c, JSONObject p) {
        if (p == null) return;
        SharedPreferences.Editor e = Prefs.raw(c).edit();
        for (String k : BOOLS) if (p.has(k)) e.putBoolean(k, p.optBoolean(k));
        for (String k : STRINGS) if (p.has(k)) e.putString(k, p.optString(k));
        for (String k : VOICE) if (p.has(k)) e.putString(k, p.optString(k));
        e.apply();
    }
}
