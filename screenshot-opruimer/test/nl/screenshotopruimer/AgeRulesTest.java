package nl.screenshotopruimer;

import java.util.Calendar;
import java.util.TimeZone;

/** Plain-Java tests for AgeRules. Run with ./test.sh. */
public class AgeRulesTest {
    static final TimeZone TZ = TimeZone.getTimeZone("Europe/Amsterdam");
    static final long DAY = AgeRules.DAY_MS;
    static int failures = 0, passed = 0;

    static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    static void check(String label, boolean ok) {
        if (ok) { passed++; } else { failures++; System.out.println("FAIL: " + label); }
    }

    public static void main(String[] a) {
        long now = at(2026, 11, 21, 12, 19);

        // Date parsing from typical screenshot names (Pixel, Samsung, Xiaomi, OnePlus, Huawei/Honor, iOS-style)
        String[] names = {
            "Screenshot_20261001-120000.png",
            "Screenshot_20261001-120000_Chrome.jpg",
            "Screenshot_2026-10-01-12-00-00-123_com.whatsapp.jpg",
            "Screenshot_20261001_120000_com.android.chrome.jpg",
            "Screenshot 2026-10-01 at 12.00.00.png",
            "IMG_20261001_120000.jpg",
            "Schermafbeelding_2026.10.01.png",
        };
        for (String n : names) {
            check("parse " + n, AgeRules.dateFromName(n, TZ) == at(2026, 10, 1, 0, 0));
        }
        check("no date", AgeRules.dateFromName("photo.png", TZ) == -1);
        check("invalid date 31 feb", AgeRules.dateFromName("Screenshot_20260231-1200.png", TZ) == -1);
        check("not preceded by digit", AgeRules.dateFromName("x120261001.png", TZ) == -1);
        check("null name", AgeRules.dateFromName(null, TZ) == -1);

        // THE BUG: old screenshot whose last-modified was reset to today (restore / copy / sync)
        long resetToday = at(2026, 11, 21, 9, 0);
        check("old by name, modified today -> old (30d)",
                AgeRules.isOld("Screenshot_20261001-120000.png", resetToday, 30, now, TZ));
        check("old by name, modified today -> old (1d)",
                AgeRules.isOld("Screenshot_20261001-120000.png", resetToday, 1, now, TZ));

        // Normal cases with correct last-modified
        check("40 days old, 30d -> old",
                AgeRules.isOld("Screenshot_20261012-100000.png", at(2026, 10, 12, 10, 0), 30, now, TZ));
        check("10 days old, 30d -> keep",
                AgeRules.isOld("Screenshot_20261111-100000.png", at(2026, 11, 11, 10, 0), 30, now, TZ) == false);
        check("2 days old, 1d -> old",
                AgeRules.isOld("Screenshot_20261119-100000.png", at(2026, 11, 19, 10, 0), 1, now, TZ));
        check("today, 1d -> keep",
                AgeRules.isOld("Screenshot_20261121-080000.png", at(2026, 11, 21, 8, 0), 1, now, TZ) == false);
        // Taken yesterday 23:00: same day as name, so precise time is used -> only 13h old -> keep with 1 day
        check("yesterday 23:00, 1d -> keep (precise time used)",
                AgeRules.isOld("Screenshot_20261120-230000.png", at(2026, 11, 20, 23, 0), 1, now, TZ) == false);
        check("same-day uses last-modified",
                AgeRules.fileTime("Screenshot_20261120-230000.png", at(2026, 11, 20, 23, 0), now, TZ) == at(2026, 11, 20, 23, 0));

        // No date in name: falls back to last-modified
        check("no name date, old modified -> old",
                AgeRules.isOld("random.png", now - 40 * DAY, 30, now, TZ));
        check("no name date, new modified -> keep",
                AgeRules.isOld("random.png", now - 5 * DAY, 30, now, TZ) == false);
        // Neither known -> never delete
        check("no info -> keep", AgeRules.isOld("random.png", -1, 1, now, TZ) == false);
        check("zero modified -> keep", AgeRules.isOld("random.png", 0, 1, now, TZ) == false);
        // Date in name in the future is ignored
        check("future name date ignored",
                AgeRules.isOld("Screenshot_20301001-120000.png", now - 5 * DAY, 30, now, TZ) == false);
        // Name date missing, last-modified missing but name has date -> use name
        check("modified missing, name old -> old",
                AgeRules.isOld("Screenshot_20260101-120000.png", -1, 30, now, TZ));
        // Boundary: exactly at cutoff is not older
        check("exactly 30 days -> keep", AgeRules.isOld("x.png", now - 30 * DAY, 30, now, TZ) == false);
        check("30 days + 1 min -> old", AgeRules.isOld("x.png", now - 30 * DAY - 60000, 30, now, TZ));

        System.out.println(passed + " geslaagd, " + failures + " mislukt");
        if (failures > 0) System.exit(1);
    }
}
