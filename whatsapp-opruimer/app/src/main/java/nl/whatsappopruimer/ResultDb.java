package nl.whatsappopruimer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Bewaart scanresultaten, zodat een onderbroken scan verdergaat waar hij was. */
final class ResultDb extends SQLiteOpenHelper {

    static final class Row {
        String uri;
        String folder;
        String name;
        String docId;
        String parentDocId;
        long size;
        long modified;
        boolean analyzed;
        boolean error;
        int faces;
        float person;
        float dog;
        int width;
        int height;
    }

    private static ResultDb instance;

    static synchronized ResultDb get(Context c) {
        if (instance == null) instance = new ResultDb(c.getApplicationContext());
        return instance;
    }

    private ResultDb(Context c) {
        super(c, "results.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (" +
                "uri TEXT PRIMARY KEY, folder TEXT NOT NULL, name TEXT, doc_id TEXT, parent_doc_id TEXT, " +
                "size INTEGER, modified INTEGER, analyzed INTEGER DEFAULT 0, error INTEGER DEFAULT 0, " +
                "faces INTEGER DEFAULT 0, person REAL DEFAULT 0, dog REAL DEFAULT -1, width INTEGER DEFAULT 0, height INTEGER DEFAULT 0, " +
                "seen INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX results_folder ON results(folder, seen)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // Versie 1 kende geen honden: dog = -1 betekent "nog niet op honden gecontroleerd".
            db.execSQL("ALTER TABLE results ADD COLUMN dog REAL DEFAULT -1");
        }
    }

    void markAllUnseen(String folder) {
        ContentValues v = new ContentValues();
        v.put("seen", 0);
        getWritableDatabase().update("results", v, "folder=?", new String[]{folder});
    }

    /** Registreert een gevonden bestand. Geeft true als het (opnieuw) geanalyseerd moet worden. */
    boolean markSeen(String folder, FileLister.Item it) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.query("results", new String[]{"size", "modified", "analyzed", "error", "faces", "dog"},
                "uri=?", new String[]{it.uri.toString()}, null, null, null)) {
            if (c.moveToFirst() && c.getLong(0) == it.size && c.getLong(1) == it.modified
                    && c.getInt(2) == 1 && c.getInt(3) == 0
                    // Oude resultaten zonder hondscore alleen opnieuw doen als er geen gezicht was
                    // (met gezicht blijft de foto toch staan).
                    && (c.getInt(4) > 0 || c.getFloat(5) >= 0)) {
                ContentValues v = new ContentValues();
                v.put("seen", 1);
                v.put("folder", folder);
                db.update("results", v, "uri=?", new String[]{it.uri.toString()});
                return false;
            }
        }
        ContentValues v = new ContentValues();
        v.put("uri", it.uri.toString());
        v.put("folder", folder);
        v.put("name", it.name);
        v.put("doc_id", it.docId);
        v.put("parent_doc_id", it.parentDocId);
        v.put("size", it.size);
        v.put("modified", it.modified);
        v.put("analyzed", 0);
        v.put("seen", 1);
        db.insertWithOnConflict("results", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    void saveResult(String uri, Detector.Result r) {
        ContentValues v = new ContentValues();
        v.put("analyzed", 1);
        v.put("error", r.error ? 1 : 0);
        v.put("faces", r.faces);
        v.put("person", r.personScore);
        v.put("dog", r.dogScore);
        v.put("width", r.width);
        v.put("height", r.height);
        getWritableDatabase().update("results", v, "uri=?", new String[]{uri});
    }

    void delete(String uri) {
        getWritableDatabase().delete("results", "uri=?", new String[]{uri});
    }

    List<Row> seenRows(String folder) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("results",
                new String[]{"uri", "name", "doc_id", "parent_doc_id", "size", "modified", "analyzed", "error",
                        "faces", "person", "width", "height", "dog"},
                "folder=? AND seen=1", new String[]{folder}, null, null, "name DESC")) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.uri = c.getString(0);
                r.folder = folder;
                r.name = c.getString(1);
                r.docId = c.getString(2);
                r.parentDocId = c.getString(3);
                r.size = c.getLong(4);
                r.modified = c.getLong(5);
                r.analyzed = c.getInt(6) == 1;
                r.error = c.getInt(7) == 1;
                r.faces = c.getInt(8);
                r.person = c.getFloat(9);
                r.width = c.getInt(10);
                r.height = c.getInt(11);
                r.dog = Math.max(0f, c.getFloat(12));
                out.add(r);
            }
        }
        return out;
    }
}
