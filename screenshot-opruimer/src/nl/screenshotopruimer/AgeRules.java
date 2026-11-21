package nl.screenshotopruimer;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java rules for deciding how old a file is. No Android dependencies, so it can be unit tested.
 *
 * Screenshot names almost always contain the capture date (e.g. "Screenshot_20261001-120000.png",
 * "Screenshot_2026-10-01-12-00-00-123_com.app.jpg"). The file's last-modified time is not reliable:
 * restoring a backup, copying or syncing resets it to "now", which made old screenshots look new.
 * So we use the oldest trustworthy date we can find.
 */
final class AgeRules {

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    // A year 2000-2099, month and day, optionally separated by - _ or . and not preceded by a digit.
    private static final Pattern DATE_IN_NAME = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])");

    private AgeRules() {}

    /** Start of the day (local time) of the date found in the file name, or -1 if there is none. */
    static long dateFromName(String name, TimeZone tz) {
        if (name == null) return -1;
        Matcher m = DATE_IN_NAME.matcher(name);
        if (!m.find()) return -1;
        int y = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(2));
        int d = Integer.parseInt(m.group(3));
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.setLenient(false);
        try {
            c.set(y, mo - 1, d, 0, 0, 0);
            return c.getTimeInMillis();
        } catch (IllegalArgumentException e) {
            return -1; // e.g. 31 February
        }
    }

    /**
     * Best estimate of when the file was created, or -1 if unknown.
     * Uses the oldest of last-modified and the date in the name; if both point at the same day,
     * the (more precise) last-modified time is used. Dates in the future are ignored.
     */
    static long fileTime(String name, long lastModified, long now, TimeZone tz) {
        long fromName = dateFromName(name, tz);
        if (fromName > now) fromName = -1;
        long lm = (lastModified > 0 && lastModified <= now + DAY_MS) ? lastModified : -1;
        if (fromName < 0) return lm;
        if (lm < 0) return fromName;
        if (lm >= fromName && lm < fromName + DAY_MS) return lm;
        return Math.min(lm, fromName);
    }

    /** True when the file is older than the given number of days. */
    static boolean isOld(String name, long lastModified, int days, long now, TimeZone tz) {
        long t = fileTime(name, lastModified, now, tz);
        return t > 0 && t < now - days * DAY_MS;
    }
}
