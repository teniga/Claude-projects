package nl.whatsappopruimer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.InputStream;

/** Leest een verkleinde, rechtgezette versie van een afbeelding. */
final class ImageLoader {

    private ImageLoader() {}

    static final class Loaded {
        Bitmap bitmap;
        /** Oorspronkelijke afmetingen (na rotatie). */
        int width;
        int height;
    }

    /** @param maxSide langste zijde van het resultaat (ongeveer). */
    static Loaded load(ContentResolver cr, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (sample * 2) >= maxSide) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) return null;

        int rotation = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            rotation = exif.getRotationDegrees();
        } catch (Exception ignored) {
            // Geen of onleesbare EXIF: niet draaien.
        }

        float scale = Math.min(1f, (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (rotation != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap t = bmp.copy(Bitmap.Config.ARGB_8888, false);
            bmp.recycle();
            bmp = t;
        }

        Loaded l = new Loaded();
        l.bitmap = bmp;
        boolean swap = rotation == 90 || rotation == 270;
        l.width = swap ? bounds.outHeight : bounds.outWidth;
        l.height = swap ? bounds.outWidth : bounds.outHeight;
        return l;
    }
}
