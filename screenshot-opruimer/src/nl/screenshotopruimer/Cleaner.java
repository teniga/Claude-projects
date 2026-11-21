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
import java.util.TimeZone;

/** Shared logic: settings, finding old files, deleting them, scheduling and notifications. */
final class Cleaner {

    static final String PREFS = "settings";
    static final String KEY_TREE = "tree_uri";
    static final String KEY_DAYS = "days";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LAST_RUN = "last_run";
    static final String KEY_LAST_COUNT = "last_count";
    static final String KEY_LAST_ERROR = "last_error";
    static final String KEY_LAST_FOUND = "last_found";
    static final String KEY_LAST_OLD = "last_old";
    static final String KEY_LAST_FAILED = "last_failed";
    static final int DEFAULT_DAYS = 30;

    private static final int JOB_ID = 1001;
    private static final String CHANNEL_ID = "opruimen";
    private static final long DAY_MS = AgeRules.DAY_MS;

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

    /** Outcome of reading the folder. */
    static final class Scan {
        int totalFiles;
        final List<OldFile> old = new ArrayList<>();
    }

    static final class Result {
        int totalFiles;
        int old;
        int deleted;
        int failed;
        String firstFailure;
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

    /** Reads the files directly in the chosen folder and picks the ones older than the set number of days. */
    static Scan scan(Context c) throws Exception {
        Uri tree = treeUri(c);
        if (tree == null) throw new Exception("Geen map gekozen");
        int days = days(c);
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Scan out = new Scan();
        Cursor cur;
        try {
            cur = c.getContentResolver().query(children, cols, null, null, null);
        } catch (SecurityException e) {
            throw new Exception("Geen toegang meer tot de map. Kies de map opnieuw.");
        }
        if (cur == null) throw new Exception("Map kon niet worden gelezen");
        try {
            int iId = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iName = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int iMod = cur.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int iMime = cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cur.moveToNext()) {
                String mime = iMime < 0 ? null : cur.getString(iMime);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                String name = iName < 0 ? null : cur.getString(iName);
                if (name != null && name.startsWith(".")) continue; // hidden files such as .nomedia
                out.totalFiles++;
                long modified = (iMod < 0 || cur.isNull(iMod)) ? -1 : cur.getLong(iMod);
                if (!AgeRules.isOld(name, modified, days, now, tz)) continue;
                long when = AgeRules.fileTime(name, modified, now, tz);
                out.old.add(new OldFile(cur.getString(iId), name, when));
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
            Scan scan = scan(c);
            r.totalFiles = scan.totalFiles;
            r.old = scan.old.size();
            for (OldFile f : scan.old) {
                try {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, f.docId);
                    if (DocumentsContract.deleteDocument(cr, doc)) {
                        r.deleted++;
                    } else {
                        r.failed++;
                        if (r.firstFailure == null) r.firstFailure = f.name + ": verwijderen geweigerd";
                    }
                } catch (Exception e) {
                    r.failed++;
                    if (r.firstFailure == null) r.firstFailure = f.name + ": " + e;
                }
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        prefs(c).edit()
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putInt(KEY_LAST_COUNT, r.deleted)
                .putString(KEY_LAST_ERROR, r.error != null ? r.error : r.firstFailure)
                .putInt(KEY_LAST_FOUND, r.totalFiles)
                .putInt(KEY_LAST_OLD, r.old)
                .putInt(KEY_LAST_FAILED, r.failed)
                .apply();
        notifyResult(c, r);
        return r;
    }

    static String describe(Result r) {
        if (r.error != null) return "Opruimen mislukt: " + r.error;
        String s = (r.deleted == 1 ? "1 bestand verwijderd" : r.deleted + " bestanden verwijderd");
        if (r.failed > 0) s += ", " + r.failed + " mislukt";
        s += " (" + r.totalFiles + " in map, " + r.old + " oud genoeg)";
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
                .setStyle(new Notification.BigTextStyle().bigText(
                        r.firstFailure == null ? describe(r) : describe(r) + "\n" + r.firstFailure))
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
