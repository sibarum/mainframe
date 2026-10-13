package dev.mainframe.text;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown's spans as plain ranges. No field, no window: the highlighter is a function, so its test is a list of
 * which characters are which colour.
 */
class MarkdownTest {

    private static final Color HEADING = Color.rgb(0x000001);
    private static final Color MARKER = Color.rgb(0x000002);
    private static final Color EMPHASIS = Color.rgb(0x000003);
    private static final Color LINK = Color.rgb(0x000004);
    private static final Color QUOTE = Color.rgb(0x000005);
    private static final Color CODE = Color.rgb(0x000006);
    private static final Color CODE_BG = Color.rgb(0x000007);

    private static final Markdown.Style STYLE = new Markdown.Style(HEADING, MARKER, EMPHASIS, LINK, QUOTE, CODE, CODE_BG);

    /** The foreground on {@code needle}'s first character, or null if nothing colours it. */
    private static Color fgOf(String text, String needle) {
        int at = text.indexOf(needle);
        for (Span s : Markdown.spans(text, STYLE)) {
            if (s.fg() != null && s.covers(at)) {
                return s.fg();
            }
        }
        return null;
    }

    private static boolean backgroundOn(String text, String needle) {
        int at = text.indexOf(needle);
        return Markdown.spans(text, STYLE).stream().anyMatch(s -> s.bg() != null && s.covers(at));
    }

    private static boolean underlined(String text, String needle) {
        int at = text.indexOf(needle);
        return Markdown.spans(text, STYLE).stream().anyMatch(s -> s.underline() && s.covers(at));
    }

    @Test
    void onlyMarkdownFilesAreMarkdown() {
        assertTrue(Markdown.handles("README.md"));
        assertTrue(Markdown.handles("notes.MARKDOWN"));
        assertFalse(Markdown.handles("notes.txt"));
        assertFalse(Markdown.handles(null));
    }

    @Test
    void plainTextHasNoSpans() {
        assertEquals(List.of(), Markdown.spans("just words, nothing else\nand another line", STYLE));
    }

    @Test
    void aHeadingIsItsMarkerAndItsText() {
        String text = "## Title here\nbody";
        assertEquals(MARKER, fgOf(text, "##"));
        assertEquals(HEADING, fgOf(text, "Title"));
        assertEquals(null, fgOf(text, "body"));
    }

    @Test
    void sevenHashesOrNoSpaceIsNotAHeading() {
        assertEquals(null, fgOf("####### seven", "seven"));
        assertEquals(null, fgOf("#hashtag", "hashtag"));
    }

    @Test
    void emphasisIsColouredAndItsDelimitersAreQuiet() {
        String text = "some *em* and **strong** and _also_";
        assertEquals(EMPHASIS, fgOf(text, "em*"));
        assertEquals(MARKER, fgOf(text, "*em"));
        assertEquals(EMPHASIS, fgOf(text, "strong"));
        assertEquals(EMPHASIS, fgOf(text, "also"));
    }

    @Test
    void anUnderscoreInsideAWordIsNotEmphasis() {
        assertEquals(List.of(), Markdown.spans("call snake_case_name here", STYLE));
    }

    @Test
    void aLoneStarIsNotEmphasis() {
        assertEquals(List.of(), Markdown.spans("2 * 3 * 4", STYLE));
    }

    @Test
    void inlineCodeHasABackground() {
        String text = "run `mvn test` now";
        assertEquals(CODE, fgOf(text, "mvn"));
        assertTrue(backgroundOn(text, "mvn"));
        assertFalse(backgroundOn(text, "now"));
    }

    @Test
    void aLinksTextIsUnderlinedAndItsTargetIsQuiet() {
        String text = "see [the docs](https://example.com) for more";
        assertEquals(LINK, fgOf(text, "the docs"));
        assertTrue(underlined(text, "the docs"));
        assertEquals(MARKER, fgOf(text, "https"));
        assertFalse(underlined(text, "https"));
    }

    @Test
    void aFenceMakesEverythingInsideItCode() {
        String text = "before\n```\n# not a heading\n*not em*\n```\nafter";
        assertEquals(CODE, fgOf(text, "# not"));
        assertEquals(CODE, fgOf(text, "*not"));
        assertTrue(backgroundOn(text, "# not"));
        assertEquals(null, fgOf(text, "after"));
    }

    @Test
    void quotesListsAndRules() {
        String text = "> quoted\n- item\n12. numbered\n---";
        assertEquals(QUOTE, fgOf(text, "quoted"));
        assertEquals(MARKER, fgOf(text, "- item"));
        assertEquals(null, fgOf(text, "item"));
        assertEquals(MARKER, fgOf(text, "12."));
        assertEquals(MARKER, fgOf(text, "---"));
    }

    @Test
    void noTwoForegroundsOverlap() {
        String text = "# H\n> q *e*\n- a **b** `c` [d](e)\n```\nx\n```\n";
        List<Span> fgs = Markdown.spans(text, STYLE).stream().filter(s -> s.fg() != null).toList();
        for (int i = 0; i < fgs.size(); i++) {
            for (int j = i + 1; j < fgs.size(); j++) {
                Span a = fgs.get(i);
                Span b = fgs.get(j);
                assertTrue(a.end() <= b.start() || b.end() <= a.start(), a + " overlaps " + b);
            }
        }
    }
}
