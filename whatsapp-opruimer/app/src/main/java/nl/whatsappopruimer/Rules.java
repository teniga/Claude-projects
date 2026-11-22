package nl.whatsappopruimer;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure beslisregels (zonder Android-afhankelijkheden), zodat ze met gewone unittests te testen zijn. */
public final class Rules {

    private Rules() {}

    /** Uitkomst van de persoonsherkenning. */
    public enum Verdict { NO_PERSON, DOUBT, PERSON }

    /** Gevoeligheid: index 0 = voorzichtig (standaard), 1 = normaal, 2 = streng. */
    public static final String[] LEVEL_NAMES = {
            "Voorzichtig (aanbevolen)", "Normaal", "Streng"
    };
    /** Vanaf deze persoonsscore telt een foto als "met persoon". */
    static final float[] KEEP_FROM = {0.35f, 0.50f, 0.60f};
    /** Vanaf deze persoonsscore (en onder KEEP_FROM) is het twijfel. */
    static final float[] DOUBT_FROM = {0.15f, 0.25f, 0.40f};

    /** Verhouding lang:kort vanaf waar een afbeelding als telefoon-screenshot telt (19,5:9 ≈ 2,17; 16:9-foto's = 1,78). */
    static final float SCREENSHOT_RATIO = 1.95f;

    public static Verdict verdict(int faces, float personScore, int level) {
        int l = Math.max(0, Math.min(level, KEEP_FROM.length - 1));
        if (faces > 0 || personScore >= KEEP_FROM[l]) return Verdict.PERSON;
        if (personScore >= DOUBT_FROM[l]) return Verdict.DOUBT;
        return Verdict.NO_PERSON;
    }

    public static boolean isScreenshot(String name, int width, int height) {
        if (name != null && name.toLowerCase(Locale.ROOT).contains("screenshot")) return true;
        if (width <= 0 || height <= 0) return false;
        float ratio = (float) Math.max(width, height) / Math.min(width, height);
        return ratio >= SCREENSHOT_RATIO;
    }

    /** Moet de afbeelding standaard aangevinkt staan om naar de prullenbak te gaan? */
    public static boolean selectedByDefault(Verdict v, boolean screenshot, boolean screenshotsAlwaysDelete) {
        if (screenshot && screenshotsAlwaysDelete) return true;
        return v == Verdict.NO_PERSON;
    }

    private static final Pattern NAME_DATE = Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)");

    /** Datum uit een bestandsnaam zoals IMG-20240131-WA0001.jpg (lokale middernacht), of -1. */
    public static long dateFromName(String name) {
        if (name == null) return -1;
        Matcher m = NAME_DATE.matcher(name);
        while (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (mo < 1 || mo > 12 || d < 1 || d > 31) continue;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(y, mo - 1, d);
            return c.getTimeInMillis();
        }
        return -1;
    }

    /** Is het bestand jonger dan {@code days} dagen? Datum in de naam gaat voor de bestandsdatum. */
    public static boolean isRecent(String name, long lastModified, long now, int days) {
        if (days <= 0) return false;
        long date = dateFromName(name);
        if (date < 0) date = lastModified;
        if (date <= 0) return false;
        // Datum uit de naam is middernacht: tel de hele dag mee.
        long dayMs = 24L * 60 * 60 * 1000;
        return now - date < days * dayMs;
    }

    public static boolean isImageName(String name, String mime) {
        if (name == null || name.startsWith(".")) return false;
        if (mime != null && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp"))) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }
}
