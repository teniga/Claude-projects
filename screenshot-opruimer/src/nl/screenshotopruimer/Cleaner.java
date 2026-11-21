package nl.screenshotopruimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

/** Shared logic: settings, finding old files, deleting them, scheduling and notifications. */
final class Cleaner {

    static final String PREFS = "settings";
    static final String KEY_TREE = "tree_uri";
    static final String KEY_DAYS = "days";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LAST_RUN = "last_run";
    static final String KEY_LAST_COUNT = "last_count";
    static final String KEY_LAST_ERROR = "last_error";
    static final int DEFAULT_DAYS = 30;

    private static final int JOB_ID = 1001;
    private static final String CHANNEL_ID = "opruimen";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    static final class OldFile {
        final String docId;
        final String name;
        final long modified;

        OldFile(String docId, String name, long modified) {
            this.docId = docId;
            this.name = name;
            this.modified = modified;
        }
    }

    static final class Result {
        int deleted;
        int failed;
        String error;
    }

    private Cleaner() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri treeUri(Context c) {
        String s = prefs(c).getString(KEY_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    static int days(Context c) {
        return prefs(c).getInt(KEY_DAYS, DEFAULT_DAYS);
    }

    /** Files directly in the chosen folder whose last-modified date is older than the cutoff. */
    static List<OldFile> findOldFiles(Context c) throws Exception {
        Uri tree = treeUri(c);
        if (tree == null) throw new Exception("Geen map gekozen");
        long cutoff = System.currentTimeMillis() - days(c) * DAY_MS;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        List<OldFile> out = new ArrayList<>();
        Cursor cur;
        try {
            cur = c.getContentResolver().query(children, cols, null, null, null);
        } catch (SecurityException e) {
            throw new Exception("Geen toegang meer tot de map. Kies de map opnieuw.");
        }
        if (cur == null) throw new Exception("Map kon niet worden gelezen");
        try {
            while (cur.moveToNext()) {
                String mime = cur.getString(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                if (cur.isNull(2)) continue;
                long modified = cur.getLong(2);
                if (modified <= 0 || modified >= cutoff) continue;
                out.add(new OldFile(cur.getString(0), cur.getString(1), modified));
            }
        } finally {
            cur.close();
        }
        return out;
    }

    /** Permanently deletes old files, stores the outcome and shows a notification. */
    static Result run(Context c) {
        Result r = new Result();
        try {
            Uri tree = treeUri(c);
            ContentResolver cr = c.getContentResolver();
            for (OldFile f : findOldFiles(c)) {
                try {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, f.docId);
                    if (DocumentsContract.deleteDocument(cr, doc)) r.deleted++;
                    else r.failed++;
                } catch (Exception e) {
                    r.failed++;
                }
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        prefs(c).edit()
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putInt(KEY_LAST_COUNT, r.deleted)
                .putString(KEY_LAST_ERROR, r.error)
                .apply();
        notifyResult(c, r);
        return r;
    }

    static String describe(Result r) {
        if (r.error != null) return "Opruimen mislukt: " + r.error;
        String s = r.deleted == 1 ? "1 bestand verwijderd" : r.deleted + " bestanden verwijderd";
        if (r.failed > 0) s += " (" + r.failed + " mislukt)";
        return s;
    }

    private static void notifyResult(Context c, Result r) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Opruimen", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setContentTitle("Screenshot Opruimer")
                .setContentText(describe(r))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(1, n);
        } catch (SecurityException ignored) {
            // Notification permission not granted.
        }
    }

    /** Schedules (or cancels) the daily background job according to the settings. */
    static void applySchedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        boolean enabled = prefs(c).getBoolean(KEY_ENABLED, false) && treeUri(c) != null;
        if (!enabled) {
            js.cancel(JOB_ID);
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, CleanupJobService.class))
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }
}
