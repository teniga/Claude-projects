package nl.whatsappopruimer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Voert de scan uit (aangeroepen vanuit {@link ScanService}) en houdt de voortgang bij. */
final class ScanManager {

    private ScanManager() {}

    interface Listener { void onScanUpdate(); }

    private static final int WORKERS = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.List<Listener> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static volatile boolean running;
    static volatile boolean cancelled;
    static volatile String status = "";
    static volatile int toAnalyze;
    static final AtomicInteger analyzed = new AtomicInteger();
    static final AtomicInteger errors = new AtomicInteger();

    static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void update(String s) {
        status = s;
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onScanUpdate(); });
    }

    static int progressPercent() {
        int t = toAnalyze;
        return t <= 0 ? 0 : (int) (100L * analyzed.get() / t);
    }

    /** Blokkerend; draai dit op een achtergrondthread. */
    static void run(Context ctx, Uri tree, boolean subfolders, int skipDays) {
        running = true;
        cancelled = false;
        analyzed.set(0);
        errors.set(0);
        toAnalyze = 0;
        try {
            doRun(ctx.getApplicationContext(), tree, subfolders, skipDays);
        } catch (Throwable t) {
            update("Fout tijdens scannen: " + t);
        } finally {
            running = false;
            update(status);
        }
    }

    private static void doRun(Context ctx, Uri tree, boolean subfolders, int skipDays) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        ResultDb db = ResultDb.get(ctx);
        String folder = tree.toString();

        update("Bestanden zoeken…");
        List<FileLister.Item> all = FileLister.list(cr, tree, subfolders, () -> cancelled);
        if (cancelled) { update("Gestopt."); return; }

        long now = System.currentTimeMillis();
        int recent = 0;
        db.markAllUnseen(folder);
        ConcurrentLinkedQueue<FileLister.Item> queue = new ConcurrentLinkedQueue<>();
        for (FileLister.Item it : all) {
            if (Rules.isRecent(it.name, it.modified, now, skipDays)) { recent++; continue; }
            if (db.markSeen(folder, it)) queue.add(it);
        }
        final int candidates = all.size() - recent;
        final int fromCache = candidates - queue.size();
        final int skippedRecent = recent;
        toAnalyze = queue.size();

        final long start = SystemClock.elapsedRealtime();
        update(progressText(candidates, fromCache, skippedRecent, start));

        List<Thread> threads = new ArrayList<>();
        final Throwable[] fatal = new Throwable[1];
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(() -> {
                try (Detector det = new Detector(ctx)) {
                    FileLister.Item it;
                    while (!cancelled && (it = queue.poll()) != null) {
                        Detector.Result r;
                        try {
                            ImageLoader.Loaded img = ImageLoader.load(cr, it.uri, Detector.ANALYSIS_SIZE);
                            if (img == null) {
                                r = new Detector.Result();
                                r.error = true;
                            } else {
                                try {
                                    r = det.analyze(img);
                                } finally {
                                    img.bitmap.recycle();
                                }
                            }
                        } catch (Exception | OutOfMemoryError e) {
                            r = new Detector.Result();
                            r.error = true;
                        }
                        if (r.error) errors.incrementAndGet();
                        db.saveResult(it.uri.toString(), r);
                        int n = analyzed.incrementAndGet();
                        if (n % 5 == 0 || n == toAnalyze) {
                            update(progressText(candidates, fromCache, skippedRecent, start));
                        }
                    }
                } catch (Throwable e) {
                    synchronized (fatal) { fatal[0] = e; }
                    cancelled = true;
                }
            }, "scan-" + i);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) t.join();

        if (fatal[0] != null) {
            update("Herkenning kon niet starten: " + fatal[0]);
        } else if (cancelled) {
            update("Gestopt na " + analyzed.get() + " van " + toAnalyze
                    + " nieuwe afbeeldingen. Druk weer op Scannen om verder te gaan.");
        } else {
            update("Klaar. " + candidates + " afbeeldingen beoordeeld"
                    + (skippedRecent > 0 ? ", " + skippedRecent + " recente overgeslagen" : "")
                    + (errors.get() > 0 ? ", " + errors.get() + " onleesbaar (blijven staan)" : "")
                    + ". Bekijk het resultaat.");
        }
    }

    private static String progressText(int candidates, int fromCache, int recent, long start) {
        int done = analyzed.get();
        int total = toAnalyze;
        StringBuilder sb = new StringBuilder();
        sb.append("Analyseren: ").append(done).append(" / ").append(total);
        if (fromCache > 0) sb.append("\n(").append(fromCache).append(" al eerder gescand)");
        if (recent > 0) sb.append("\n").append(recent).append(" recente afbeeldingen overgeslagen");
        if (done >= 5 && done < total) {
            long elapsed = SystemClock.elapsedRealtime() - start;
            long remainingSec = elapsed * (total - done) / done / 1000;
            sb.append("\nNog ongeveer ").append(formatDuration(remainingSec));
        }
        return sb.toString();
    }

    static String formatDuration(long sec) {
        if (sec < 60) return sec + " sec";
        long min = (sec + 30) / 60;
        if (min < 60) return min + " min";
        return (min / 60) + " u " + (min % 60) + " min";
    }
}
