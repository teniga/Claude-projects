package nl.whatsappopruimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import org.junit.Test;

public class RulesTest {

    @Test
    public void faceAlwaysMeansPerson() {
        assertEquals(Rules.Verdict.PERSON, Rules.verdict(1, 0f, 2));
    }

    @Test
    public void scoresPerLevel() {
        assertEquals(Rules.Verdict.PERSON, Rules.verdict(0, 0.40f, 0));
        assertEquals(Rules.Verdict.DOUBT, Rules.verdict(0, 0.40f, 1));
        assertEquals(Rules.Verdict.DOUBT, Rules.verdict(0, 0.40f, 2));
        assertEquals(Rules.Verdict.DOUBT, Rules.verdict(0, 0.20f, 0));
        assertEquals(Rules.Verdict.NO_PERSON, Rules.verdict(0, 0.20f, 1));
        assertEquals(Rules.Verdict.NO_PERSON, Rules.verdict(0, 0.05f, 0));
    }

    @Test
    public void screenshotByRatioOrName() {
        assertTrue(Rules.isScreenshot("IMG-20240101-WA0001.jpg", 1080, 2400));
        assertTrue(Rules.isScreenshot("IMG-20240101-WA0001.jpg", 2340, 1080));
        assertFalse(Rules.isScreenshot("IMG-20240101-WA0001.jpg", 1080, 1920)); // 16:9-foto
        assertFalse(Rules.isScreenshot("IMG-20240101-WA0001.jpg", 1200, 1600)); // 4:3-foto
        assertTrue(Rules.isScreenshot("Screenshot_20240101.png", 1080, 1080));
        assertFalse(Rules.isScreenshot("x.jpg", 0, 0));
    }

    @Test
    public void defaultSelection() {
        assertTrue(Rules.selectedByDefault(Rules.Verdict.NO_PERSON, false, false));
        assertFalse(Rules.selectedByDefault(Rules.Verdict.DOUBT, false, false));
        assertFalse(Rules.selectedByDefault(Rules.Verdict.PERSON, false, false));
        assertFalse(Rules.selectedByDefault(Rules.Verdict.PERSON, true, false));
        assertTrue(Rules.selectedByDefault(Rules.Verdict.PERSON, true, true));
        assertTrue(Rules.selectedByDefault(Rules.Verdict.NO_PERSON, true, false));
    }

    @Test
    public void dateFromWhatsAppName() {
        long d = Rules.dateFromName("IMG-20240131-WA0001.jpg");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(d);
        assertEquals(2024, c.get(Calendar.YEAR));
        assertEquals(Calendar.JANUARY, c.get(Calendar.MONTH));
        assertEquals(31, c.get(Calendar.DAY_OF_MONTH));
        assertEquals(-1, Rules.dateFromName("IMG-WA0001.jpg"));
        assertEquals(-1, Rules.dateFromName("IMG-20241399-WA0001.jpg"));
    }

    @Test
    public void recentUsesNameDateFirst() {
        long day = 24L * 60 * 60 * 1000;
        long nameDate = Rules.dateFromName("IMG-20240110-WA0001.jpg");
        long now = nameDate + 3 * day;
        // Naam zegt 3 dagen oud, bestandsdatum zegt oud: recent.
        assertTrue(Rules.isRecent("IMG-20240110-WA0001.jpg", 1000L, now, 7));
        // Naam zegt 3 dagen oud: niet recent bij grens van 2 dagen.
        assertFalse(Rules.isRecent("IMG-20240110-WA0001.jpg", now, now, 2));
        // Zonder datum in naam: bestandsdatum.
        assertTrue(Rules.isRecent("foto.jpg", now - day, now, 7));
        assertFalse(Rules.isRecent("foto.jpg", now - 10 * day, now, 7));
        assertFalse(Rules.isRecent("foto.jpg", now, now, 0));
    }

    @Test
    public void imageNames() {
        assertTrue(Rules.isImageName("IMG-1.jpg", null));
        assertTrue(Rules.isImageName("a.WEBP", null));
        assertTrue(Rules.isImageName("a", "image/png"));
        assertFalse(Rules.isImageName(".nomedia", null));
        assertFalse(Rules.isImageName("video.mp4", "video/mp4"));
    }
}
