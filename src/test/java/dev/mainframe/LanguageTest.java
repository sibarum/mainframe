package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.lang.Lexer;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.Value;

/** The parts of the language that have to behave the same way every time. */
class LanguageTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    @Test
    void numbersAndSizes() {
        assertEquals(new Value.Int(3), mf().eval("1 + 2"));
        assertEquals(new Value.Size(2048), mf().eval("1kb + 1kb"));
        assertEquals(new Value.Float(2.5), mf().eval("5 / 2"));
        assertEquals(new Value.Int(2), mf().eval("4 / 2"));
        assertEquals(new Value.Int(1_500_000), mf().eval("1_500_000"));
    }

    @Test
    void sizesCompareWithPlainNumbers() {
        assertEquals(new Value.Bool(true), mf().eval("1kb > 1000"));
        assertEquals(new Value.Bool(true), mf().eval("1mb == 1048576"));
    }

    @Test
    void gluedOperatorsAreRejectedRatherThanGuessed() {
        // 5-3 could be arithmetic or a name; MainFrame refuses to choose.
        assertEquals("E003", mf().errorCode("echo 5-3"));
        assertEquals(new Value.Int(2), mf().eval("5 - 3"));
    }

    @Test
    void dashesBelongToNames() {
        // sort-by is one identifier, which is only safe because of the rule above.
        var tokens = Lexer.tokenize("sort-by size");
        assertEquals("sort-by", tokens.getFirst().text());
    }

    @Test
    void starAndSlashAreArithmeticOnlyWhenTheyStandAlone() {
        assertEquals(new Value.Int(6), mf().eval("2 * 3"));
        assertEquals(new Value.Int(2), mf().eval("6 / 3"));
        // Glued to something, they belong to a path or a glob instead.
        assertEquals(new Value.Str("*.java"), mf().eval("echo *.java"));
        assertEquals(new Value.Str("/usr/bin"), mf().eval("echo /usr/bin"));
        assertEquals(new Value.Str("*"), mf().eval("echo *"));
    }

    @Test
    void pathsAreSingleWords() {
        assertEquals(new Value.Str("./src/main"), mf().eval("echo ./src/main"));
        assertEquals(new Value.Str("*.java"), mf().eval("echo *.java"));
    }

    @Test
    void textIsNeverSplitOnSpaces() {
        Value value = mf().eval("echo \"two words\"");
        assertEquals(new Value.Str("two words"), value);
    }

    @Test
    void unterminatedTextIsExplained() {
        MfError error = mf().error("echo \"oops");
        assertEquals("E001", error.code());
        assertTrue(error.getMessage().contains("closing"), error.getMessage());
        assertTrue(error.hints().getFirst().contains("end of the text"), error.hints().toString());
    }

    @Test
    void listsRecordsAndBlocks() {
        assertEquals(new Value.Int(3), mf().eval("[1 2 3] | length"));
        assertEquals(new Value.Int(3), mf().eval("[1, 2, 3] | length"));
        Value record = mf().eval("{name: \"x\", size: 2}");
        assertInstanceOf(Value.Rec.class, record);
        assertEquals(new Value.Str("x"), ((Value.Rec) record).get("name"));
        assertInstanceOf(Value.Block.class, mf().eval("echo { echo 1 }"));
    }

    @Test
    void variablesNeedLet() {
        assertEquals("E110", mf().errorCode("count = 3"));
        assertEquals(new Value.Int(4), mf().eval("let count = 4\n$count"));
    }

    @Test
    void unknownVariableSuggestsTheRealOne() {
        MfError error = mf().error("let count = 1\n$cont");
        assertEquals("E312", error.code());
        assertTrue(error.hints().getFirst().contains("count"), error.hints().toString());
    }

    @Test
    void chainedComparisonsAreRejected() {
        assertEquals("E102", mf().errorCode("echo (1 < 2 < 3)"));
    }

    @Test
    void aPipeMayStartALineInAScript() {
        Parser.parse("[1 2 3]\n  | length");
        assertEquals(new Value.Int(3), mf().eval("[1 2 3]\n  | length"));
    }

    @Test
    void aPipeMayNotStartAStatement() {
        MfError error = mf().error("| length");
        assertEquals("E111", error.code());
        assertTrue(error.hints().getFirst().contains("end the previous line"), error.hints().toString());
    }

    @Test
    void ifAndFor() {
        assertEquals(new Value.Int(1), mf().eval("if 1 < 2 { echo 1 } else { echo 2 }"));
        Mf mf = mf();
        mf.eval("let total = 0\nfor n in [1 2 3] { echo $n }");
        assertEquals("", mf.printed());  // results inside a block are not printed
    }

    @Test
    void matchingUsesSubstringsUnlessYouAskForGlobs() {
        assertEquals(new Value.Bool(true), mf().eval("echo (\"text/markdown\" =~ \"text/\")"));
        assertEquals(new Value.Bool(true), mf().eval("echo (\"notes.java\" =~ \"*.java\")"));
        assertEquals(new Value.Bool(false), mf().eval("echo (\"notes.javax\" =~ \"*.java\")"));
    }

    @Test
    void fieldAccessOnRecordsAndMediaTypes() {
        assertEquals(new Value.Str("x"), mf().eval("let r = {name: \"x\"}\n$r.name"));
        MfError error = mf().error("let r = {name: \"x\"}\n$r.nme");
        assertEquals("E314", error.code());
    }

    @Test
    void dividingByZeroSaysSo() {
        assertEquals("E321", mf().errorCode("echo (1 / 0)"));
    }

    @Test
    void addingMismatchedThingsIsAnError() {
        assertEquals("E319", mf().errorCode("echo (\"a\" + 1)"));
    }
}
