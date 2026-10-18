package io.github.liangjiahui01.moor;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Images sent to a session: copied to the machine (~/.cache/moor/uploads) and the path typed into the terminal,
 * which is how Claude Code and other tools take an image from a terminal.
 */
final class Images {
    private static final int MAX_SIDE = 2560;
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    static final class Prepared {
        final byte[] bytes;
        final String ext;

        Prepared(byte[] bytes, String ext) {
            this.bytes = bytes;
            this.ext = ext;
        }
    }

    /** Reads the image; keeps it as is when it is a common format and small, else re-encodes it as JPEG. */
    static Prepared prepare(Context c, Uri uri) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String type = cr.getType(uri);
        byte[] raw = read(cr, uri);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        String mime = type != null ? type : o.outMimeType;
        String ext = mime == null ? null : mime.equals("image/png") ? "png" : mime.equals("image/jpeg") ? "jpg"
            : mime.equals("image/webp") ? "webp" : mime.equals("image/gif") ? "gif" : null;
        int side = Math.max(o.outWidth, o.outHeight);
        if (ext != null && raw.length <= MAX_BYTES && side <= MAX_SIDE) return new Prepared(raw, ext);

        Bitmap bmp = decode(c, uri, raw, side);
        if (bmp == null) throw new IllegalArgumentException("not an image");
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
        if (scale < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        bmp.recycle();
        return new Prepared(out.toByteArray(), "jpg");
    }

    private static Bitmap decode(Context c, Uri uri, byte[] raw, int side) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            // Handles HEIC and applies the photo's rotation
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                int big = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (big > MAX_SIDE) {
                    float f = MAX_SIDE / (float) big;
                    d.setTargetSize(Math.round(info.getSize().getWidth() * f), Math.round(info.getSize().getHeight() * f));
                }
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (side / o.inSampleSize > MAX_SIDE * 2) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private static byte[] read(ContentResolver cr, Uri uri) throws Exception {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("can't open " + uri);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 64 * 1024 * 1024) throw new IllegalArgumentException("image too large");
            }
            return b.toByteArray();
        }
    }

    /** Saves stdin under a new name in the uploads folder, drops uploads older than a week, prints the full path. */
    static String uploadCommand(String ext) {
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
            + "-" + Integer.toHexString((int) (Math.random() * 0xfffff)) + "." + ext;
        return "d=\"$HOME/.cache/moor/uploads\"; mkdir -p \"$d\" && chmod 700 \"$d\""
            + " && (find \"$d\" -type f -mtime +7 -delete 2>/dev/null; true)"
            + " && cat > \"$d/" + name + "\" && printf '%s' \"$d/" + name + "\"";
    }
}
