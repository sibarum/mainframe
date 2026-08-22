package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * JSON is a subset of MainFrame source.
 *
 * <p>This is the constraint that decides what the language may look like. Records
 * are written {@code {"name": value}} with the key quoted, lists are
 * {@code [a, b]}, and the punctuation is JSON's punctuation -- so any JSON
 * document is already a MainFrame expression, and MainFrame's own written form is
 * JSON plus the literals JSON lacks: {@code 4mb}, {@code 7d},
 * {@code 2026-08-21T14:30:00.000-04:00}, {@code path"./x"}.
 *
 * <p>The practical consequence is that the two readers cannot disagree. Anything
 * that reads as one thing through {@code from-json} and another through
 * {@code from-source} is a bug, and the fact that it was a *silent* disagreement
 * for {@code null} and for {@code \\u} escapes is exactly the kind of quiet
 * mangling this shell exists to refuse.
 *
 * <p>It also settles which brackets are available for anything borrowed later:
 * {@code {}} and {@code []} are spoken for by JSON, {@code ()} is free because
 * JSON has no parentheses at all, and postfix or infix forms -- {@code .{}},
 * {@code ->} -- are free because JSON has no operators.
 */
class JsonSubsetTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    /** Every shape JSON can take, including the ones that used to be read wrongly. */
    private static List<String> everyJsonShape() {
        return List.of(
                "null",
                "true",
                "false",
                "0",
                "-42",
                "3.14",
                "1e5",
                "1E5",
                "1.5e-3",
                "2.5E+2",
                "\"\"",
                "\"plain\"",
                "\"quote \\\" backslash \\\\ slash \\/\"",
                "\"newline \\n tab \\t return \\r\"",
                "\"backspace \\b formfeed \\f\"",
                "\"unicode \\u0041\\u00e9\\u2603\"",
                "[]",
                "[1, 2, 3]",
                "[null, true, \"x\"]",
                "{}",
                "{\"a\": 1}",
                "{\"a\": null, \"b\": [1, {\"c\": \"d\"}]}",
                "[{\"n\": 1}, {\"n\": 2}]",
                "{\"deep\": {\"deeper\": {\"deepest\": [1e2, null]}}}");
    }

    // ---- the law -----------------------------------------------------------------------

    @Test
    void everyJsonDocumentIsAlsoMainFrameSource() {
        Mf mf = mf();
        for (String json : everyJsonShape()) {
            Value asJson = mf.eval("echo " + Values.quoted(json) + " | from-json");
            Value asSource = mf.eval(json);
            assertTrue(Values.equal(asJson, asSource),
                    "the two readers disagree about " + json
                            + "\n  as json:   " + Values.source(asJson)
                            + "\n  as source: " + Values.source(asSource));
            assertEquals(ValueType.of(asJson), ValueType.of(asSource),
                    "the two readers give different types for " + json);
        }
    }

    @Test
    void nullIsNothingRatherThanTheWordNull() {
        // It used to read as the text "null", silently. A wrong value that looks
        // right is worse than an error, which is the whole argument for this test.
        Mf mf = mf();
        Value.Rec row = (Value.Rec) mf.eval("{\"a\": null}");
        assertEquals(ValueType.NOTHING, ValueType.of(row.get("a")));
    }

    @Test
    void jsonStringEscapesMeanWhatJsonSaysTheyMean() {
        Mf mf = mf();
        assertEquals(new Value.Str("A"), mf.eval("\"\\u0041\""));
        assertEquals(new Value.Str("é"), mf.eval("\"\\u00e9\""));
        assertEquals(new Value.Str("\b"), mf.eval("\"\\b\""));
        assertEquals(new Value.Str("\f"), mf.eval("\"\\f\""));
        assertEquals(new Value.Str("/"), mf.eval("\"\\/\""));
        // A malformed one is refused rather than quietly becoming letters.
        assertEquals("E008", mf.errorCode("echo \"\\uZZZZ\""));
    }

    @Test
    void jsonNumbersWithExponentsAreNumbers() {
        Mf mf = mf();
        assertEquals(new Value.Float(100000), mf.eval("1e5"));
        assertEquals(new Value.Float(0.0015), mf.eval("1.5e-3"));
        assertEquals(new Value.Float(250), mf.eval("2.5E+2"));
    }

    @Test
    void theUnitsStillWorkAlongsideExponents() {
        // e is an exponent when digits follow and a unit otherwise, so nothing
        // that used to lex as a size stopped doing so.
        Mf mf = mf();
        assertEquals(new Value.Size(4L * 1024 * 1024), mf.eval("4mb"));
        assertEquals(new Value.Duration(7 * 86_400_000L), mf.eval("7d"));
        assertEquals("E002", mf.errorCode("echo 5ex"));
    }

    // ---- what MainFrame adds on top ----------------------------------------------------

    @Test
    void mainframeSourceIsJsonPlusTheLiteralsJsonLacks() {
        // The extra forms all sit in value position, where JSON has a number or a
        // string, so they extend the grammar rather than colliding with it.
        Mf mf = mf();
        for (String extra : List.of("4mb", "7d", "2026-08-21T14:30:00.000-04:00",
                "path\"./x\"", "mime\"text/plain\"", "nothing")) {
            Value value = mf.eval("{\"v\": " + extra + "}");
            assertTrue(value instanceof Value.Rec, extra + " should sit inside a record");
        }
    }

    @Test
    void aRecordIsWrittenTheWayJsonWritesAnObject() {
        // Quoted keys, so the written form stays JSON-shaped even though the
        // values may not be.
        Mf mf = mf();
        String written = Values.source(mf.eval("{\"a\": 1, \"b\": \"two\"}"));
        assertEquals("{\"a\": 1, \"b\": \"two\"}", written);
    }
}
