package dev.mainframe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the action bar's Send types (docs/action-bar.md). */
class InsertTextTest {

    @Test
    void oneLineIsTypedAndRun() {
        assertEquals("git status\r", InsertText.sent("git status", false));
    }

    @Test
    void manyLinesAreCrSeparatedOrOnePasteWhenTheShellAskedForBracketedPaste() {
        assertEquals("cd web\rnpm test\r", InsertText.sent("cd web\nnpm test", false));
        assertEquals("\u001b[200~cd web\rnpm test\u001b[201~\r", InsertText.sent("cd web\r\nnpm test", true));
    }

    @Test
    void blankIsEnterAlone() {
        assertEquals("\r", InsertText.sent("", true));
    }

    @Test
    void noControlCharacterButTheLineEndsGetsThrough() {
        assertEquals("a b[31mred\r", InsertText.sent("a\tb\u001b[31mred\u0003", false));
    }
}
