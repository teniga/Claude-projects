package nl.whatsappopruimer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.util.ArrayList;
import java.util.List;

/** Somt afbeeldingen op in een via de mapkiezer gekozen map (Storage Access Framework). */
final class FileLister {

    static final String TRASH_DIR = "_Opruimer_prullenbak";

    static final class Item {
        Uri uri;
        String docId;
        String parentDocId;
        String name;
        String mime;
        long size;
        long modified;
    }

    interface Cancel { boolean isCancelled(); }

    static String rootDocId(Uri tree) {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    static List<Item> list(ContentResolver cr, Uri tree, boolean subfolders, Cancel cancel) {
        List<Item> out = new ArrayList<>();
        walk(cr, tree, rootDocId(tree), subfolders, out, cancel);
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String dirId, boolean subfolders,
                             List<Item> out, Cancel cancel) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        List<String> dirs = new ArrayList<>();
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                if (cancel.isCancelled()) return;
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    if (subfolders && !TRASH_DIR.equals(name)) dirs.add(id);
                    continue;
                }
                if (!Rules.isImageName(name, mime)) continue;
                Item it = new Item();
                it.docId = id;
                it.parentDocId = dirId;
                it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                it.name = name;
                it.mime = mime;
                it.size = c.isNull(3) ? 0 : c.getLong(3);
                it.modified = c.isNull(4) ? 0 : c.getLong(4);
                out.add(it);
            }
        }
        for (String d : dirs) walk(cr, tree, d, true, out, cancel);
    }
}
