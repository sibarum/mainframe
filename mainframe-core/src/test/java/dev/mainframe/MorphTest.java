package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * Adapting one API's shape to another's.
 *
 * <p>Two commands do the work. {@code cast} gives foreign data its real types, so
 * a date that arrived as a string can be filtered on. {@code morph} rebuilds each
 * row into whatever shape the far end wants, using the language already used for
 * filtering -- no second mini-language to learn.
 */
class MorphTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    /** What somebody else's API sends: their names, their units, dates as text. */
    private static final String THEIRS = """
            [{"full_name": "Ada Lovelace", "total_cents": 420000, "signed_up": "2026-01-15"},
             {"full_name": "Grace Hopper", "total_cents": 15000, "signed_up": "2026-03-02"}]
            """;

    private Mf withTheirData() {
        Mf mf = mf();
        mf.eval("echo " + Values.quoted(THEIRS) + " | save ./theirs.json --as=text");
        return mf;
    }

    // ---- reshaping ------------------------------------------------------------------------

    @Test
    void aRowIsRebuiltFromTheColumnsItHas() {
        Mf mf = mf();
        Value out = mf.eval("[{a: 1, b: 2}] | morph {sum: a + b, label: \"row\"}");
        Value.Rec row = Values.rows(out).getFirst();
        assertEquals(new Value.Int(3), row.get("sum"));
        assertEquals(new Value.Str("row"), row.get("label"));
        // Only what was asked for, in the order asked for.
        assertEquals("[sum, label]", row.fields().keySet().toString());
    }

    @Test
    void unitsAndTypesSurviveTheReshape() {
        Mf mf = mf();
        Value out = mf.eval("[{bytes: 4mb, took: 90m}] | morph {mb: bytes / 1mb, half: took / 2}");
        Value.Rec row = Values.rows(out).getFirst();
        assertEquals(new Value.Int(4), row.get("mb"));
        assertEquals(ValueType.DURATION, ValueType.of(row.get("half")));
        assertEquals(new Value.Duration(45 * 60_000L), row.get("half"));
    }

    @Test
    void aTypoInAMorphNamesTheColumnsYouHave() {
        MfError error = mf().error("[{name: \"x\"}] | morph {label: nmae}");
        assertEquals("E313", error.code());
        assertTrue(error.hints().getFirst().contains("name"), error.hints().toString());
    }

    // ---- giving foreign data its types ------------------------------------------------------

    @Test
    void textFromSomebodyElseCanBeGivenItsRealTypes() {
        Mf mf = withTheirData();
        Value raw = mf.eval("open ./theirs.json");
        assertEquals(ValueType.STRING, ValueType.of(Values.rows(raw).getFirst().get("signed_up")));

        Value typed = mf.eval("open ./theirs.json | cast {signed_up: time}");
        assertEquals(ValueType.TIME, ValueType.of(Values.rows(typed).getFirst().get("signed_up")));

        // And now it can be filtered on as a date rather than as text.
        assertEquals(new Value.Int(1),
                mf.eval("open ./theirs.json | cast {signed_up: time} | where signed_up > 2026-02-01 | length"));
    }

    @Test
    void castSaysWhenTheTypeOrTheColumnIsNotReal() {
        Mf mf = withTheirData();
        MfError unknownType = mf.error("open ./theirs.json | cast {signed_up: wibble}");
        assertEquals("E1106", unknownType.code());
        assertTrue(unknownType.hints().getFirst().contains("time"), unknownType.hints().toString());

        MfError unknownColumn = mf.error("open ./theirs.json | cast {signd_up: time}");
        assertEquals("E1108", unknownColumn.code());
        assertTrue(unknownColumn.hints().getFirst().contains("signed_up"), unknownColumn.hints().toString());
    }

    @Test
    void aValueThatWillNotFitItsNewTypeSaysSo() {
        Mf mf = mf();
        MfError error = mf.error("[{when: \"not a date\"}] | cast {when: time}");
        assertEquals("E205", error.code());
    }

    // ---- their API to ours, and back ----------------------------------------------------------

    @Test
    void theirShapeBecomesOurs() {
        Mf mf = withTheirData();
        Value ours = mf.eval("""
                open ./theirs.json
                  | cast {signed_up: time, total_cents: int}
                  | morph {name: full_name, spend: total_cents / 100, joined: signed_up}
                """);
        Value.Rec first = Values.rows(ours).getFirst();
        assertEquals(new Value.Str("Ada Lovelace"), first.get("name"));
        assertEquals(new Value.Int(4200), first.get("spend"));
        assertEquals(ValueType.TIME, ValueType.of(first.get("joined")));
    }

    @Test
    void ourShapeBecomesTheirs() throws IOException {
        // The far end wants nested objects, so it gets nested objects.
        Mf mf = mf();
        mf.eval("""
                [{name: "hero.png", size: 4mb, modified: 2026-08-19T09:12:00.000-04:00}]
                  | morph {asset: {label: name, bytes: size}, updated: modified}
                  | save ./for-them.json --plain
                """);
        String json = Files.readString(here.resolve("for-them.json"));
        assertTrue(json.contains("\"asset\": {"), json);
        assertTrue(json.contains("\"label\": \"hero.png\""), json);
        assertTrue(json.contains("\"bytes\": 4194304"), json);
        // No header row, no type annotations: this is their file, not ours.
        assertTrue(!json.contains(":size"), json);
    }

    @Test
    void aWholeDocumentCanBeBuiltAroundTheRows() throws IOException {
        // Some APIs want one object with the list inside it.
        Mf mf = mf();
        mf.eval("""
                echo {count: 2, users: ([{n: "a"}, {n: "b"}] | morph {label: n})}
                  | save ./envelope.json
                """);
        String json = Files.readString(here.resolve("envelope.json"));
        assertTrue(json.contains("\"count\": 2"), json);
        assertTrue(json.contains("\"users\": ["), json);
        assertTrue(json.contains("\"label\": \"a\""), json);
    }

    @Test
    void theHeaderRowFormIsOnlyEverUsedForTables() throws IOException {
        // A table gets the compact rows-with-a-header form; anything else is
        // ordinary JSON, because only a table has columns to describe.
        Mf mf = mf();
        mf.eval("[{a: 1}] | save ./table.json");
        assertTrue(Files.readString(here.resolve("table.json")).contains("[\"a:int\"]"));

        mf.eval("echo {a: 1} | save ./object.json");
        String object = Files.readString(here.resolve("object.json"));
        assertTrue(object.contains("\"a\": 1"), object);
        assertTrue(!object.contains("a:int"), object);
    }

    @Test
    void aReshapedTableStillSavesWithItsTypes() {
        // Morphing does not cost you the schema on the way out.
        Mf mf = mf();
        mf.eval("[{n: \"x\", b: 4mb}] | morph {label: n, bytes: b} | save ./ours.csv");
        Value back = mf.eval("open ./ours.csv");
        assertEquals(ValueType.SIZE, ValueType.of(Values.rows(back).getFirst().get("bytes")));
    }
}
