package dev.mainframe.value;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The only place in MainFrame that turns a moment into text.
 *
 * <h2>The invariant</h2>
 * <b>Every timestamp MainFrame shows is in the user's local zone.</b> Nobody
 * running a shell wants to work out what a UTC stamp means for them, so a time
 * is always presented as the wall clock they can look up at.
 *
 * <p>That is a statement about <em>display</em>, not storage. What MainFrame
 * keeps and compares is an instant -- epoch milliseconds, no zone -- because
 * that is the only form that survives daylight saving, a machine moving between
 * zones, and an index built on one computer being read on another. Local time is
 * applied at the last possible moment, here.
 *
 * <h2>If you are adding support for another operating system</h2>
 * You do not have to do anything for the invariant to keep holding: the
 * operating system tells the JVM its zone, and every path to text runs through
 * this class. What you must <em>not</em> do is format a time anywhere else. A
 * second formatter is how a shell ends up showing UTC in one column and local
 * time in another, and {@code LocalTimeTest} fails the build if one appears.
 *
 * <p>Two notes on what platforms actually give us:
 * <ul>
 *   <li>The zone comes from the registry on Windows and from {@code /etc/localtime}
 *       or {@code $TZ} elsewhere. The JVM caches it, so a zone change part way
 *       through a session is not noticed.
 *   <li>FAT and exFAT store local time rather than UTC, so timestamps on
 *       removable media shift with the machine's zone. Java hands us an instant
 *       either way; there is nothing to do about it here, but it explains a
 *       surprise a user may report.
 * </ul>
 */
public final class Times {

    /** What a person reads: minute precision, no zone clutter. */
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * What a machine reads: still local time, but carrying the offset so it is
     * unambiguous. Local time without an offset is the one format that manages to
     * be both friendly and useless.
     */
    private static final DateTimeFormatter MACHINE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** Sortable and safe in a file name. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Times() {}

    /**
     * The user's zone, resolved from the operating system. The single point where
     * MainFrame decides what "local" means.
     */
    public static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    private static ZonedDateTime local(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone());
    }

    /** For tables, records and anything a person is about to read. */
    public static String display(long epochMillis) {
        return DISPLAY.format(local(epochMillis));
    }

    /** For JSON and anything another program will read back. Local, with the offset. */
    public static String machine(long epochMillis) {
        return MACHINE.format(local(epochMillis));
    }

    /** For names that have to sort, such as a trash directory. */
    public static String stamp(long epochMillis) {
        return STAMP.format(local(epochMillis));
    }
}
