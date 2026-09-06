package dev.mainframe.gui.console;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The output pane as something you can get text back out of — with no window, no GPU and no pointer.
 *
 * <p>What is checked here is what "selectable and copyable" actually means once the pane is one document: the
 * lines are <em>in</em> that document with their line breaks, a range over it reads back exactly what was
 * printed, and — the one that would rot silently — a range somebody is holding is still the same range after the
 * next line of output lands on the end. A pane that lost the selection on every append would look selectable and
 * be useless, because a terminal is never quiet for long.
 *
 * <p>The job thread is not stood in for: {@link Scrollback#post} is what it calls and {@link Scrollback#flush} is
 * what the frame loop calls, and here the test thread plays both — the same arrangement {@code Console.tick} runs,
 * without a window to draw into.
 */
final class ScrollbackTest {

    private Gui gui;
    private Scrollback scrollback;

    @BeforeEach
    void setUp() {
        gui = new Gui();
        gui.theme(Phosphor.THEME);
        scrollback = new Scrollback(gui, Ansi.of(gui.theme()));
    }

    @AfterEach
    void tearDown() {
        scrollback.close();
    }

    /** Queue {@code lines} and publish them, which is one frame of the console's tick. */
    private void print(String... lines) {
        for (String line : lines) {
            scrollback.post(line);
        }
        scrollback.flush();
    }

    @Test
    void outputIsOneDocumentWithItsLineBreaks() {
        print("first", "second", "");
        // Every line carries its own newline, so the last one is a place rather than a line -- see append().
        assertEquals("first\nsecond\n\n", scrollback.document().text());
    }

    @Test
    void aRangeReadsBackWhatWasPrinted() {
        print("alpha", "beta", "gamma");
        String all = scrollback.document().text();
        scrollback.select(0, all.length());
        assertEquals("alpha\nbeta\ngamma\n", scrollback.document().selectedText());
        // And a range inside it, across a line break, which is the copy a column of separate lines could not make.
        int from = all.indexOf("beta");
        scrollback.select(from, from + "beta\ngam".length());
        assertEquals("beta\ngam", scrollback.document().selectedText());
    }

    @Test
    void aHeldSelectionSurvivesTheNextLineOfOutput() {
        print("keep this");
        scrollback.select(0, "keep".length());
        print("and more", "and more still");
        Document after = scrollback.document();
        assertTrue(after.hasSelection());
        assertEquals("keep", after.selectedText());
        assertEquals("keep this\nand more\nand more still\n", after.text());
    }

    @Test
    void escapesBecomeSpansAtTheirOffsetsInTheWholeDocument() {
        print("plain");
        scrollback.post("\033[1mbold\033[0m tail");
        scrollback.flush();
        int base = "plain\n".length();
        List<Span> spans = scrollback.document().spans();
        assertEquals(1, spans.size());
        assertEquals(base, spans.get(0).start());
        assertEquals(base + "bold".length(), spans.get(0).end());
        // The escapes are gone from the text, not carried into it as glyphs.
        assertEquals("plain\nbold tail\n", scrollback.document().text());
    }

    @Test
    void theOldestLinesAreDroppedAndTheRestKeepTheirSpans() {
        // Two frames, so the trim happens with lines already in the document rather than on the first append.
        for (int i = 0; i < 3_000; i++) {
            scrollback.post("line " + i);
        }
        scrollback.flush();
        for (int i = 3_000; i < 5_200; i++) {
            scrollback.post(i == 5_199 ? "\033[1mlast\033[0m" : "line " + i);
        }
        scrollback.flush();

        String text = scrollback.document().text();
        String[] rows = text.split("\n", -1);
        // 5000 lines plus the empty place the next one goes.
        assertEquals(5_001, rows.length);
        assertEquals("line 200", rows[0]);   // 5,200 printed, 5,000 kept
        assertEquals("last", rows[4_999]);
        // The span that came in with the last line still covers it, on offsets a trim moved underneath it.
        List<Span> spans = scrollback.document().spans();
        Span hot = spans.get(spans.size() - 1);
        assertEquals("last", text.substring(hot.start(), hot.end()));
    }

    @Test
    void clearingEmptiesWhatIsShownAndWhatIsQueued() {
        print("something");
        scrollback.post("queued but never published");
        scrollback.clear();
        assertEquals("", scrollback.document().text());
        assertTrue(scrollback.document().spans().isEmpty());
        scrollback.flush();
        assertEquals("", scrollback.document().text());
        assertFalse(scrollback.document().hasSelection());
    }
}
