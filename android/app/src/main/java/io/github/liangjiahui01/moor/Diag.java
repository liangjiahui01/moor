package io.github.liangjiahui01.moor;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A connection log that survives vendor log filtering (ColorOS drops app logs): kept in a file on the
 * device and copied to the machine (~/.cache/moor/device-MODEL.log) after each connect, so slow
 * reconnects can be read back without a USB cable.
 */
final class Diag {
    private static final Object lock = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static File file;

    private Diag() {}

    static void init(Context c) {
        synchronized (lock) {
            if (file == null) file = new File(c.getFilesDir(), "diag.log");
        }
    }

    static void i(String tag, String msg) {
        Log.i(tag, msg);
        add(tag, msg);
    }

    static void w(String tag, String msg) {
        Log.w(tag, msg);
        add(tag, "W " + msg);
    }

    static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        add(tag, "W " + msg + ": " + t);
    }

    private static void add(String tag, String msg) {
        String line;
        synchronized (fmt) {
            line = fmt.format(new Date()) + " " + tag + " " + msg + "\n";
        }
        synchronized (lock) {
            pending.add(line);
            if (pending.size() > 3000) pending.remove(0);
            if (file == null) return;
            try {
                if (file.length() > 512 * 1024) file.renameTo(new File(file.getPath() + ".1"));
                try (FileOutputStream o = new FileOutputStream(file, true)) {
                    o.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Lines not yet copied to the machine; give them back with {@link #restore} if the copy fails. */
    static List<String> take() {
        synchronized (lock) {
            List<String> l = new ArrayList<>(pending);
            pending.clear();
            return l;
        }
    }

    static void restore(List<String> lines) {
        synchronized (lock) {
            pending.addAll(0, lines);
            while (pending.size() > 3000) pending.remove(0);
        }
    }

    static byte[] join(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String s : lines) b.append(s);
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
}
