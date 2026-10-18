package io.github.liangjiahui01.moor;

import com.jcraft.jsch.JSch;

/** Android lacks the JDK 15+ Ed25519/X25519 providers, so JSch uses BouncyCastle for them. */
final class JschSetup {
    private static boolean done;

    private JschSetup() {}

    static synchronized void init() {
        if (done) return;
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH");
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448");
        JSch.setLogger(new com.jcraft.jsch.Logger() {
            @Override public boolean isEnabled(int level) { return level >= WARN; }
            @Override public void log(int level, String message) { android.util.Log.w("JSch", message); }
        });
        done = true;
    }
}
