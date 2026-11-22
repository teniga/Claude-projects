package nl.whatsappopruimer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class Prefs {

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static Uri tree(Context c) {
        String s = p(c).getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    static void setTree(Context c, Uri u) { p(c).edit().putString("tree", u.toString()).apply(); }

    static boolean subfolders(Context c) { return p(c).getBoolean("subfolders", false); }

    static void setSubfolders(Context c, boolean v) { p(c).edit().putBoolean("subfolders", v).apply(); }

    static int skipDays(Context c) { return p(c).getInt("skipDays", 7); }

    static void setSkipDays(Context c, int v) { p(c).edit().putInt("skipDays", v).apply(); }

    static int level(Context c) { return p(c).getInt("level", 0); }

    static void setLevel(Context c, int v) { p(c).edit().putInt("level", v).apply(); }

    static boolean shotsAlways(Context c) { return p(c).getBoolean("shotsAlways", false); }

    static void setShotsAlways(Context c, boolean v) { p(c).edit().putBoolean("shotsAlways", v).apply(); }
}
