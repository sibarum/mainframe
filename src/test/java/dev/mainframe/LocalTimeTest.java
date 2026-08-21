package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.mainframe.value.Times;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * Guards one promise: every timestamp MainFrame shows is in the user's local
 * zone, and there is exactly one place that decides what local means.
 *
 * <p>The first half checks the behaviour. The second half checks that nobody can
 * quietly add a second way of formatting a time -- which is what would break the
 * promise as MainFrame grows onto other operating systems.
 */
class LocalTimeTest {

    /** 2026-08-21T16:33:07.123Z, chosen so no zone in the test shares a wall clock. */
    private static final long INSTANT = 1787330_000_000L + 787_123L;

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void putTheZoneBack() {
        TimeZone.setDefault(original);
    }

    private static void pretendWeAreIn(String zone) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
    }

    // ---- the behaviour ----------------------------------------------------------------

    @Test
    void aTimeIsShownAsTheUsersWallClock() {
        pretendWeAreIn("UTC");
        String utc = Times.display(INSTANT);
        pretendWeAreIn("Asia/Tokyo");
        String tokyo = Times.display(INSTANT);
        pretendWeAreIn("America/Los_Angeles");
        String losAngeles = Times.display(INSTANT);

        // Same instant, three different wall clocks, nine hours apart end to end.
        assertTrue(!utc.equals(tokyo) && !utc.equals(losAngeles),
                "the same instant should read differently in different zones: "
                        + utc + " / " + tokyo + " / " + losAngeles);
    }

    @Test
    void theWallClockIsTheRightOne() {
        pretendWeAreIn("UTC");
        assertEquals("1970-01-01 00:00", Times.display(0));
        pretendWeAreIn("Asia/Tokyo");
        assertEquals("1970-01-01 09:00", Times.display(0));
        pretendWeAreIn("America/New_York");
        assertEquals("1969-12-31 19:00", Times.display(0));
    }

    @Test
    void everyPathToTextIsLocal() {
        pretendWeAreIn("Asia/Tokyo");
        Value.Time midnight = new Value.Time(0);

        // The three ways a time can become text, all agreeing on the zone.
        assertEquals("1970-01-01 09:00", Values.display(midnight));
        assertEquals("1970-01-01 09:00", Values.asString(midnight, Span.NONE));
        assertTrue(Values.toJson(midnight, 0).startsWith("\"1970-01-01T09:00:00.000+09:00\""),
                Values.toJson(midnight, 0));
    }

    @Test
    void theMachineReadableFormCarriesItsOffset() {
        pretendWeAreIn("Asia/Tokyo");
        assertEquals("1970-01-01T09:00:00.000+09:00", Times.machine(0));
        pretendWeAreIn("America/New_York");
        assertEquals("1969-12-31T19:00:00.000-05:00", Times.machine(0));
        // Local time without an offset would be friendly and useless; this is both
        // the user's wall clock and unambiguous to whatever reads it next.
    }

    @Test
    void daylightSavingIsFollowedRatherThanAveraged() {
        pretendWeAreIn("America/New_York");
        long january = 1_705_000_000_000L;   // winter, EST
        long july = 1_720_000_000_000L;      // summer, EDT
        assertTrue(Times.machine(january).endsWith("-05:00"), Times.machine(january));
        assertTrue(Times.machine(july).endsWith("-04:00"), Times.machine(july));
    }

    @Test
    void sortingIsUnaffectedByTheZone() {
        Value.Time earlier = new Value.Time(0);
        Value.Time later = new Value.Time(INSTANT);
        pretendWeAreIn("Asia/Tokyo");
        int inTokyo = Values.compare(earlier, later, Span.NONE);
        pretendWeAreIn("America/Los_Angeles");
        int inLosAngeles = Values.compare(earlier, later, Span.NONE);
        // Instants are what we store and compare; local time is only a rendering.
        assertEquals(inTokyo, inLosAngeles);
        assertTrue(inTokyo < 0);
    }

    // ---- the guarantee ------------------------------------------------------------------

    /**
     * Anything that could format a time, or reach for a zone, belongs in Times.
     * If this fails, the fix is to route the new code through Times rather than to
     * add an exception here.
     */
    private static final List<String> ZONE_AWARE = List.of(
            "DateTimeFormatter", "ZoneId", "ZoneOffset", "systemDefault", "atZone",
            "LocalDateTime", "LocalDate", "LocalTime", "TimeZone", "ofPattern");

    @Test
    void onlyOnePlaceDecidesWhatLocalMeans() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assumeTrue(Files.isDirectory(sources), "running outside the source tree");

        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(sources)) {
            for (Path file : files.toList()) {
                if (!file.toString().endsWith(".java")) continue;
                if (file.getFileName().toString().equals("Times.java")) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : ZONE_AWARE) {
                    if (text.contains(token)) {
                        offenders.add(sources.relativize(file) + " mentions " + token);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "timestamps must be formatted only in Times, so that they are always local:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void timesKeepsItsPromiseWrittenDown() throws IOException {
        Path times = Path.of("src", "main", "java", "dev", "mainframe", "value", "Times.java");
        assumeTrue(Files.isRegularFile(times), "running outside the source tree");
        String text = Files.readString(times, StandardCharsets.UTF_8);
        // The invariant is load-bearing, so it has to be stated where someone
        // adding a platform will read it.
        assertTrue(text.contains("local zone"), "Times should say what it guarantees");
        assertTrue(text.contains("operating system"), "Times should say what a porter must not do");
    }
}
