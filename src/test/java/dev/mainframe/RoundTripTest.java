package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * The written form is the read form.
 *
 * <p>Every value has one canonical text, that text is valid MainFrame source, and
 * reading it back gives the identical value. The consequence is the thing that
 * actually matters to somebody using the shell: sending results to a file and
 * picking them up later has to give the same answer as never having stopped, so
 * that
 *
 * <pre>fetch | filter | save f      then      open f | aggregate</pre>
 *
 * <p>and
 *
 * <pre>fetch | filter | aggregate</pre>
 *
 * <p>are the same workflow. These tests are what stops that being a claim.
 */
class RoundTripTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    /** One value of every kind that can travel through a pipe, awkward cases included. */
    private static List<Value> everyKind() {
        return List.of(
                Value.Nothing.INSTANCE,
                new Value.Bool(true),
                new Value.Bool(false),
                new Value.Int(0),
                new Value.Int(-42),
                new Value.Int(9_007_199_254_740_993L),          // beyond a double's reach
                new Value.Float(1.5),
                new Value.Float(2.0),                            // whole, but still a float
                new Value.Float(-0.125),
                new Value.Str(""),
                new Value.Str("plain"),
                new Value.Str("he said \"hi\"\nthen\tleft"),     // quotes, newline, tab
                new Value.Str("nothing"),                        // reads back as text, not as nothing
                new Value.Size(0),
                new Value.Size(4L * 1024 * 1024),                // exactly 4mb
                new Value.Size(4L * 1024 * 1024 + 1),            // one byte off, so no round unit
                new Value.Size(-2048),
                new Value.Time(0),
                new Value.Time(1_787_330_787_123L),
                new Value.Duration(0),
                new Value.Duration(7 * 86_400_000L),
                new Value.Duration(70_670_771L),                 // no whole unit divides it
                new Value.Duration(-500),
                new Value.PathVal(Path.of("relative", "with space", "x.txt")),
                new Value.Mime("text", "markdown", "extension"),
                new Value.ListVal(List.of()),
                new Value.ListVal(List.of(new Value.Int(1), new Value.Str("two"), new Value.Size(1024))),
                new Value.Rec(new java.util.LinkedHashMap<>()),
                Value.Rec.of("name", new Value.Str("x"), "size", new Value.Size(2048)));
    }

    // ---- the law ------------------------------------------------------------------------

    @Test
    void everyValueReadsBackAsItself() {
        Mf mf = mf();
        for (Value original : everyKind()) {
            String written = Values.source(original);
            Value read = mf.eval(written);
            assertTrue(Values.equal(original, read),
                    "wrote " + ValueType.of(original).display() + " as " + written
                            + " and read back " + Values.source(read));
            // And the type came back too, not just something that compares equal.
            assertEquals(ValueType.of(original), ValueType.of(read),
                    "the type of " + written + " changed on the way back");
        }
    }

    @Test
    void theWrittenFormIsStable() {
        // Writing, reading and writing again must give the same text, or the form
        // is not canonical and two files of the same data could differ.
        Mf mf = mf();
        for (Value original : everyKind()) {
            String once = Values.source(original);
            String twice = Values.source(mf.eval(once));
            assertEquals(once, twice, "the written form of " + once + " is not settled");
        }
    }

    @Test
    void everyKindOfValueHasAWrittenForm() {
        // If a type is added without a written form, this fails rather than the
        // problem being found later by somebody whose data went through a file.
        List<ValueType> covered = new ArrayList<>();
        for (Value v : everyKind()) covered.add(ValueType.of(v));
        for (ValueType type : ValueType.values()) {
            boolean isData = switch (type) {
                case ANY, NUMBER, EXPR, BLOCK, TABLE -> false;   // not kinds a value can be
                default -> true;
            };
            if (!isData) continue;
            assertTrue(covered.contains(type),
                    type.display() + " has no example here, so nothing proves it survives a file");
        }
    }

    // ---- through a file -----------------------------------------------------------------

    private static final String TABLE = """
            [{name: "alpha", size: 4mb, when: 2026-08-21T14:30:00.000-04:00, took: 90m},
             {name: "beta",  size: 4194305b, when: 2026-01-02T09:00:00.000-05:00, took: 500ms}]
            """;

    @Test
    void aTableSurvivesACsvFile() throws IOException {
        Mf mf = mf();
        Value before = mf.eval(TABLE);
        mf.eval(TABLE + " | to-csv | save ./table.csv");
        Value after = mf.eval("cat ./table.csv | from-csv");
        assertTrue(Values.equal(before, after),
                "before: " + Values.source(before) + "\nafter:  " + Values.source(after));
    }

    @Test
    void aTableSurvivesASourceFile() {
        Mf mf = mf();
        Value before = mf.eval(TABLE);
        mf.eval(TABLE + " | to-source | save ./table.mf");
        Value after = mf.eval("cat ./table.mf | from-source");
        assertTrue(Values.equal(before, after),
                "before: " + Values.source(before) + "\nafter:  " + Values.source(after));
    }

    @Test
    void theCsvIsSomethingAPersonCanRead() throws IOException {
        Mf mf = mf();
        mf.eval(TABLE + " | to-csv | save ./table.csv");
        String csv = Files.readString(here.resolve("table.csv"));
        // The header carries the types, which is what lets from-csv hand back a
        // size rather than a number that happens to look like one.
        assertEquals("name:string,size:size,when:time,took:duration", csv.lines().findFirst().orElseThrow());
        // And the cells are written the way the language writes them.
        assertTrue(csv.contains("alpha,4mb,2026-08-21T14:30:00.000-04:00,90m"), csv);
    }

    // ---- the workflow the whole thing is for ----------------------------------------------

    @Test
    void goingThroughAFileChangesNothing() {
        Mf direct = mf();
        Mf viaFile = mf();

        String fetchAndFilter = TABLE + " | where size > 1mb";
        String aggregate = " | sum size";

        Value straight = direct.eval(fetchAndFilter + aggregate);

        viaFile.eval(fetchAndFilter + " | to-csv | save ./step.csv");
        Value stopped = viaFile.eval("cat ./step.csv | from-csv" + aggregate);

        assertTrue(Values.equal(straight, stopped),
                "fetch|filter|aggregate gave " + Values.source(straight)
                        + " but fetch|filter|file then file|aggregate gave " + Values.source(stopped));
        assertEquals(ValueType.SIZE, ValueType.of(stopped), "the total should still be a size");
    }

    @Test
    void filteringWorksTheSameOnEitherSideOfAFile() {
        Mf mf = mf();
        mf.eval(TABLE + " | to-csv | save ./step.csv");

        // A filter written against the live table, applied to the reloaded one.
        Value live = mf.eval(TABLE + " | where when > 2026-06-01 | get name");
        Value reloaded = mf.eval("cat ./step.csv | from-csv | where when > 2026-06-01 | get name");
        assertTrue(Values.equal(live, reloaded),
                "live: " + Values.source(live) + " reloaded: " + Values.source(reloaded));

        Value spans = mf.eval("cat ./step.csv | from-csv | where took > 1m | get name");
        assertEquals(new Value.Str("alpha"), ((Value.ListVal) spans).items().getFirst());
    }

    @Test
    void aRealListingSurvivesTheTrip() {
        Mf mf = mf();
        mf.eval("mkdir ./tree");
        mf.eval("echo \"hello\" | save ./tree/a.txt");
        mf.eval("ls ./tree | to-csv | save ./listing.csv");

        Value live = mf.eval("ls ./tree | select name size modified");
        Value reloaded = mf.eval("cat ./listing.csv | from-csv | select name size modified");
        assertTrue(Values.equal(live, reloaded),
                "live: " + Values.source(live) + "\nreloaded: " + Values.source(reloaded));
    }

    // ---- where the edges are, said out loud -------------------------------------------------

    @Test
    void pathsAreWrittenWithForwardSlashesWhateverTheMachineCallsThem() {
        Mf mf = mf();
        String written = Values.display(mf.eval("echo path\"./docs/notes.md\" | to-source"));
        assertTrue(written.contains("./docs/notes.md"),
                "a path written on one platform has to be readable on another: " + written);
        assertFalse(written.contains("\\"), written);

        // And it comes back as a path this machine can actually use.
        Value read = mf.eval("echo path\"./docs/notes.md\" | to-source | from-source");
        assertEquals(ValueType.PATH, ValueType.of(read));
        assertEquals(Path.of("./docs/notes.md").toString(), Values.display(read));
    }

    @Test
    void aMediaTypeComesBackKnowingItWasReadRatherThanDetected() {
        // Provenance is not part of the value. After a trip through a file, "we
        // sniffed the bytes" would be false -- we read it off a line -- so the
        // media type survives and how we came by it honestly does not.
        Mf mf = mf();
        Value.Mime detected = new Value.Mime("text", "markdown", "content");
        Value read = mf.eval(Values.source(detected));
        assertTrue(Values.equal(detected, read), "the media type itself must survive");
        assertEquals("written", ((Value.Mime) read).detectedBy());
    }

    @Test
    void jsonCanBeToldWhatItsColumnsWere() {
        // JSON cannot carry a size, so the caller names the columns instead --
        // the same information a CSV header would have held, said out loud.
        Mf mf = mf();
        mf.eval(TABLE + " | to-json | save ./table.json");
        Value guessed = mf.eval("cat ./table.json | from-json");
        assertEquals(ValueType.INT, ValueType.of(Values.rows(guessed).getFirst().get("size")));

        Value told = mf.eval("cat ./table.json | from-json --types=\"size:size\" --types=\"when:time\"");
        assertTrue(Values.equal(mf.eval(TABLE + " | select size when"), mf.eval(
                        "cat ./table.json | from-json --types=\"size:size\" --types=\"when:time\" | select size when")),
                "named columns should come back exactly: " + Values.source(told));
    }

    @Test
    void namingAColumnThatIsNotThereIsAnError() {
        Mf mf = mf();
        mf.eval(TABLE + " | to-json | save ./table.json");
        MfError error = mf.error("cat ./table.json | from-json --types=\"siez:size\"");
        assertEquals("E1104", error.code());
        assertTrue(error.hints().getFirst().contains("size"), error.hints().toString());
        assertEquals("E1102", mf.errorCode("cat ./table.json | from-json --types=\"size\""));
    }

    @Test
    void jsonIsForOtherProgramsAndSaysSoByLosingTypes() {
        // JSON has no size and no moment, so a size comes back a number. This is
        // not a bug to fix quietly -- it is why to-csv and to-source exist, and the
        // test is here so nobody mistakes JSON for the lossless path.
        Mf mf = mf();
        Value reloaded = mf.eval("echo 4mb | to-json | from-json");
        assertEquals(ValueType.INT, ValueType.of(reloaded));
        assertEquals(ValueType.SIZE, ValueType.of(mf.eval("echo 4mb | to-source | from-source")));
    }

    @Test
    void savingTextWritesTheTextRatherThanEncodingItAgain() throws IOException {
        Mf mf = mf();
        mf.eval("echo 4mb | to-json | save ./once.json");
        String written = Files.readString(here.resolve("once.json"));
        // Not "\"4194304\"" -- text that is already JSON is written as it stands.
        assertEquals("4194304", written.strip());
        assertFalse(written.startsWith("\""), written);
    }

    @Test
    void bothWaysOfWritingJsonAgree() throws IOException {
        Mf mf = mf();
        mf.eval(TABLE + " | to-json | save ./explicit.json");
        mf.eval(TABLE + " | save ./implicit.json");
        assertEquals(Files.readString(here.resolve("explicit.json")).strip(),
                Files.readString(here.resolve("implicit.json")).strip(),
                "save should convert or not convert, but not do it twice");
    }

    @Test
    void aForeignCsvComesInAsTextRatherThanBeingGuessedAt() {
        Mf mf = mf();
        mf.eval("echo \"name,when\\nalpha,2026-08-21\" | save ./foreign.csv");
        Value read = mf.eval("cat ./foreign.csv | from-csv");
        Value.Rec row = Values.rows(read).getFirst();
        // No type in the header means text. A column that merely looks like a date
        // is not turned into one, because guessing is how data gets mangled.
        assertEquals(ValueType.STRING, ValueType.of(row.get("when")));
    }

    @Test
    void readingACellThatDoesNotFitItsColumnIsAnError() {
        Mf mf = mf();
        mf.eval("echo \"size:size\\nnot-a-size\" | save ./broken.csv");
        MfError error = mf.error("cat ./broken.csv | from-csv");
        assertEquals("E205", error.code());
        assertTrue(error.hints().getLast().contains("4mb"), error.hints().toString());
    }
}
