package io.github.liangjiahui01.moor;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Generates ed25519 keys in OpenSSH format (private key unencrypted, kept in app-private storage). JSch can read but not write this format. */
final class OpenSshKey {
    final String privatePem;
    final String publicLine;

    private OpenSshKey(String privatePem, String publicLine) {
        this.privatePem = privatePem;
        this.publicLine = publicLine;
    }

    static OpenSshKey generate(String comment) throws IOException {
        SecureRandom rnd = new SecureRandom();
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(rnd));
        AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        return fromSeed(((Ed25519PrivateKeyParameters) kp.getPrivate()).getEncoded(), comment);
    }

    /** Rebuilds a key pair from a 32-byte seed (the throwaway key in a pairing link). */
    static OpenSshKey fromSeed(byte[] seed, String comment) throws IOException {
        SecureRandom rnd = new SecureRandom();
        byte[] pub = new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();

        byte[] pubBlob = blob(out -> {
            str(out, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
            str(out, pub);
        });

        int check = rnd.nextInt();
        byte[] priv64 = new byte[64];
        System.arraycopy(seed, 0, priv64, 0, 32);
        System.arraycopy(pub, 0, priv64, 32, 32);
        ByteArrayOutputStream pb = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pb);
        p.writeInt(check);
        p.writeInt(check);
        str(p, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        str(p, pub);
        str(p, priv64);
        str(p, comment.getBytes(StandardCharsets.UTF_8));
        for (int i = 1; pb.size() % 8 != 0; i++) p.write(i);
        byte[] privBlock = pb.toByteArray();

        byte[] whole = blob(out -> {
            out.write("openssh-key-v1".getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            str(out, "none".getBytes(StandardCharsets.US_ASCII));
            str(out, "none".getBytes(StandardCharsets.US_ASCII));
            str(out, new byte[0]);
            out.writeInt(1);
            str(out, pubBlob);
            str(out, privBlock);
        });

        String b64 = Base64.getEncoder().encodeToString(whole);
        StringBuilder pem = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < b64.length(); i += 70) pem.append(b64, i, Math.min(b64.length(), i + 70)).append('\n');
        pem.append("-----END OPENSSH PRIVATE KEY-----\n");
        String line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(pubBlob) + " " + comment + "\n";
        return new OpenSshKey(pem.toString(), line);
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] blob(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        w.write(new DataOutputStream(b));
        return b.toByteArray();
    }

    private static void str(DataOutputStream out, byte[] v) throws IOException {
        out.writeInt(v.length);
        out.write(v);
    }
}
