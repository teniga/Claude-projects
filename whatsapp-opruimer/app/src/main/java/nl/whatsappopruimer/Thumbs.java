package nl.whatsappopruimer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Laadt miniaturen op de achtergrond met een geheugencache. */
final class Thumbs {

    private final ContentResolver cr;
    private final int size;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    Thumbs(ContentResolver cr, int size) {
        this.cr = cr;
        this.size = size;
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
    }

    void load(String uri, ImageView view) {
        view.setTag(uri);
        Bitmap cached = cache.get(uri);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            if (!uri.equals(view.getTag())) return; // al hergebruikt voor een andere tegel
            Bitmap b = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    b = cr.loadThumbnail(Uri.parse(uri), new Size(size, size), null);
                }
            } catch (Exception ignored) { }
            if (b == null) {
                try {
                    ImageLoader.Loaded l = ImageLoader.load(cr, Uri.parse(uri), size);
                    if (l != null) b = l.bitmap;
                } catch (Exception | OutOfMemoryError ignored) { }
            }
            if (b == null) return;
            cache.put(uri, b);
            Bitmap fb = b;
            main.post(() -> { if (uri.equals(view.getTag())) view.setImageBitmap(fb); });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
