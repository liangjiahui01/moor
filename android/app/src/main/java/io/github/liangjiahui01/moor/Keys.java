package io.github.liangjiahui01.moor;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** This phone's ed25519 key: created on first use; the private key stays in app-private storage. */
final class Keys {
    private Keys() {}

    static File privFile(Context c) { return new File(c.getFilesDir(), "id_ed25519"); }

    static File pubFile(Context c) { return new File(c.getFilesDir(), "id_ed25519.pub"); }

    static synchronized void ensure(Context c) throws Exception {
        if (privFile(c).exists() && pubFile(c).exists()) return;
        OpenSshKey k = OpenSshKey.generate("moor@" + android.os.Build.MODEL.replace(' ', '-'));
        Files.write(privFile(c).toPath(), k.privatePem.getBytes(StandardCharsets.US_ASCII));
        Files.write(pubFile(c).toPath(), k.publicLine.getBytes(StandardCharsets.US_ASCII));
    }

    static String publicKey(Context c) throws Exception {
        ensure(c);
        return new String(Files.readAllBytes(pubFile(c).toPath()), StandardCharsets.UTF_8).trim();
    }

    static byte[] privateKey(Context c) throws Exception {
        ensure(c);
        return Files.readAllBytes(privFile(c).toPath());
    }

    static byte[] publicKeyBytes(Context c) throws IOException {
        return Files.readAllBytes(pubFile(c).toPath());
    }
}
