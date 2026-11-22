package nl.whatsappopruimer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verplaatst afbeeldingen naar de prullenbak. Er wordt nooit iets definitief verwijderd.
 * - Android 11+: de systeemprullenbak (MediaStore.createTrashRequest). Android vraagt zelf om bevestiging;
 *   bestanden blijven daar 30 dagen staan en zijn terug te zetten vanuit de Galerij/Google Foto's.
 * - Bestanden die Android niet in de mediabibliotheek heeft (bijv. map met .nomedia) en Android 8–10:
 *   verplaatsen naar de submap "_Opruimer_prullenbak" in de gekozen map. Die map kun je later zelf legen.
 */
final class Trasher {

    private Trasher() {}

    static final int BATCH = 300;

    static final class Plan {
        final List<List<Uri>> mediaBatches = new ArrayList<>();
        final List<List<ResultDb.Row>> mediaBatchRows = new ArrayList<>();
        final List<ResultDb.Row> moveRows = new ArrayList<>();
    }

    static boolean systemTrashAvailable() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** Zoekt per bestand de bijbehorende MediaStore-uri. Draai op een achtergrondthread. */
    static Plan plan(Context ctx, List<ResultDb.Row> rows) {
        Plan p = new Plan();
        if (!systemTrashAvailable()) {
            p.moveRows.addAll(rows);
            return p;
        }
        ContentResolver cr = ctx.getContentResolver();
        Map<String, Map<String, Uri>> dirCache = new HashMap<>();
        List<Uri> batch = new ArrayList<>();
        List<ResultDb.Row> batchRows = new ArrayList<>();
        for (ResultDb.Row r : rows) {
            Uri media = findMedia(cr, r.docId, dirCache);
            if (media == null) {
                p.moveRows.add(r);
                continue;
            }
            batch.add(media);
            batchRows.add(r);
            if (batch.size() >= BATCH) {
                p.mediaBatches.add(batch);
                p.mediaBatchRows.add(batchRows);
                batch = new ArrayList<>();
                batchRows = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            p.mediaBatches.add(batch);
            p.mediaBatchRows.add(batchRows);
        }
        return p;
    }

    /** docId zoals "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-….jpg". */
    private static Uri findMedia(ContentResolver cr, String docId, Map<String, Map<String, Uri>> cache) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String volumeId = docId.substring(0, colon);
        String path = docId.substring(colon + 1);
        int slash = path.lastIndexOf('/');
        String rel = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = slash < 0 ? path : path.substring(slash + 1);
        String volume = "primary".equals(volumeId)
                ? MediaStore.VOLUME_EXTERNAL_PRIMARY : volumeId.toLowerCase(Locale.ROOT);

        String key = volume + "|" + rel;
        Map<String, Uri> names = cache.get(key);
        if (names == null) {
            names = new HashMap<>();
            Uri base;
            try {
                base = MediaStore.Images.Media.getContentUri(volume);
            } catch (Exception e) {
                cache.put(key, names);
                return null;
            }
            try (Cursor c = cr.query(base,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME},
                    MediaStore.Images.Media.RELATIVE_PATH + "=?", new String[]{rel}, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        names.put(c.getString(1), ContentUris.withAppendedId(base, c.getLong(0)));
                    }
                }
            } catch (Exception ignored) {
                // Geen toegang tot de mediabibliotheek: valt terug op verplaatsen.
            }
            cache.put(key, names);
        }
        return names.get(name);
    }

    /** Verplaatst bestanden naar de submap _Opruimer_prullenbak. Geeft de gelukte rijen terug. */
    static List<ResultDb.Row> moveToTrashFolder(Context ctx, Uri tree, List<ResultDb.Row> rows) {
        List<ResultDb.Row> ok = new ArrayList<>();
        if (rows.isEmpty()) return ok;
        ContentResolver cr = ctx.getContentResolver();
        String rootId = FileLister.rootDocId(tree);
        Uri trashDir;
        try {
            trashDir = findOrCreateTrashDir(cr, tree, rootId);
        } catch (Exception e) {
            return ok;
        }
        if (trashDir == null) return ok;
        for (ResultDb.Row r : rows) {
            try {
                Uri src = Uri.parse(r.uri);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, r.parentDocId);
                if (DocumentsContract.moveDocument(cr, src, parent, trashDir) != null) ok.add(r);
            } catch (Exception ignored) {
                // Mislukt: bestand blijft gewoon staan.
            }
        }
        return ok;
    }

    private static Uri findOrCreateTrashDir(ContentResolver cr, Uri tree, String rootId) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        try (Cursor c = cr.query(children, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (FileLister.TRASH_DIR.equals(c.getString(1)) && Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    }
                }
            }
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Uri dir = DocumentsContract.createDocument(cr, root, Document.MIME_TYPE_DIR, FileLister.TRASH_DIR);
        if (dir != null) {
            // Zorgt dat de Galerij deze map niet toont.
            try {
                DocumentsContract.createDocument(cr, dir, "application/octet-stream", ".nomedia");
            } catch (Exception ignored) { }
        }
        return dir;
    }
}
