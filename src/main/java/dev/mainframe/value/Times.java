package dev.mainframe.value;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The only place in MainFrame that turns a moment into text, or text into a
 * moment.
 *
 * <h2>The invariant</h2>
 * <b>Every timestamp is local, going in and coming out.</b> A time MainFrame
 * shows is the wall clock the user can look up at, and a time the user
 * <em>writes</em> -- in a filter, in a range, in a file being read back -- means
 * their wall clock too. Both directions live here, because they are the same
 * decision made twice, and a shell that renders local time but parses UTC is
 * worse than one that is consistently wrong.
 *
 * <p>What travels between those two edges is an instant: epoch milliseconds, no
 * zone. That is the only form that survives daylight saving, a machine moving
 * between zones, and a file written on one computer being read on another. A
 * literal that carries an explicit offset keeps it; one that does not is read in
 * the local zone.
 *
 * <h2>The written form is the read form</h2>
 * {@link #machine} produces {@code 2026-08-21T12:33:17.804-04:00} -- local time
 * carrying its offset -- and {@link #parse} accepts exactly that, unchanged. So
 * a time can be written to a file and read back without moving, which is what
 * makes {@code fetch | filter | save} then {@code open | aggregate} give the same
 * answer as doing it all in one pipeline. Local time without an offset manages to
 * be both friendly and useless; with the offset it is friendly and exact.
 *
 * <h2>If you are adding support for another operating system</h2>
 * You do not have to do anything for the invariant to keep holding: the
 * operating system tells the JVM its zone, and every path between a moment and
 * text runs through this class. What you must <em>not</em> do is format or parse
 * a time anywhere else. A second formatter is how a shell ends up showing UTC in
 * one column and local time in another, and {@code LocalTimeTest} fails the
 * build if one appears.
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

    /** What is written to a file and read back: local time carrying its offset. */
    private static final DateTimeFormatter MACHINE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** Sortable and safe in a file name. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Accepted written forms, most precise first. Those with an offset keep it. */
    private static final List<DateTimeFormatter> WITH_OFFSET = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX"));

    private static final List<DateTimeFormatter> WITHOUT_OFFSET = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

    /** How long each duration unit lasts, longest first so the canonical form is shortest. */
    private static final List<Unit> DURATION_UNITS = List.of(
            new Unit("d", 86_400_000L),
            new Unit("h", 3_600_000L),
            new Unit("m", 60_000L),
            new Unit("s", 1_000L),
            new Unit("ms", 1L));

    private record Unit(String suffix, long millis) {}

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

    // ---- moments out ------------------------------------------------------------------

    /** For tables, records and anything a person is about to read. */
    public static String display(long epochMillis) {
        return DISPLAY.format(local(epochMillis));
    }

    /**
     * For files, and for anything that will be read back. This is also the
     * literal form MainFrame accepts, so writing and reading are symmetrical.
     */
    public static String machine(long epochMillis) {
        return MACHINE.format(local(epochMillis));
    }

    /** For names that have to sort, such as a trash directory. */
    public static String stamp(long epochMillis) {
        return STAMP.format(local(epochMillis));
    }

    // ---- moments in -------------------------------------------------------------------

    /**
     * Reads a written time. An explicit offset is honoured; without one the text
     * means the user's local wall clock, and a bare date means local midnight.
     *
     * @throws IllegalArgumentException with a readable reason, for the caller to
     *         wrap in whatever error the context calls for
     */
    public static long parse(String text) {
        String trimmed = text.trim();
        for (DateTimeFormatter format : WITH_OFFSET) {
            try {
                return ZonedDateTime.parse(trimmed, format).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        for (DateTimeFormatter format : WITHOUT_OFFSET) {
            try {
                return LocalDateTime.parse(trimmed, format).atZone(zone()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(zone()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a time I can read");
        }
    }

    /** True when text looks like a written time, without committing to reading it. */
    public static boolean looksLikeTime(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- spans ------------------------------------------------------------------------

    /**
     * The canonical form of a span: the largest unit that divides it exactly, so
     * that reading the text back gives the same span. 90 minutes is {@code 90m},
     * not {@code 1.5h}.
     */
    public static String duration(long millis) {
        if (millis == 0) return "0s";
        String sign = millis < 0 ? "-" : "";
        long size = Math.abs(millis);
        for (Unit unit : DURATION_UNITS) {
            if (size % unit.millis() == 0) return sign + (size / unit.millis()) + unit.suffix();
        }
        return sign + size + "ms";
    }

    /**
     * A span as a person reads it: the two largest units that say anything, so 19
     * hours and change is "19h 38m" rather than 70670771ms. The exact form is
     * {@link #duration}; this one is for looking at, and the two are allowed to
     * differ for the same reason 4mb displays as "4.0 MB".
     */
    public static String displayDuration(long millis) {
        if (millis == 0) return "0s";
        String sign = millis < 0 ? "-" : "";
        long left = Math.abs(millis);
        StringBuilder sb = new StringBuilder(sign);
        int shown = 0;
        for (Unit unit : DURATION_UNITS) {
            if (shown == 2) break;
            long whole = left / unit.millis();
            if (whole == 0 && shown == 0) continue;
            if (whole == 0) break;
            if (shown > 0) sb.append(' ');
            sb.append(whole).append(unit.suffix());
            left -= whole * unit.millis();
            shown++;
        }
        return sb.toString();
    }

    /** How long a duration unit lasts, or null when the suffix is not one. */
    public static Long durationUnit(String suffix) {
        for (Unit unit : DURATION_UNITS) {
            if (unit.suffix().equals(suffix.toLowerCase())) return unit.millis();
        }
        return null;
    }

    /** The unit suffixes a duration may carry, for error messages. */
    public static String durationUnits() {
        return "ms, s, m, h and d";
    }
}
