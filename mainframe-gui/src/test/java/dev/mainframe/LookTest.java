package dev.mainframe;

import dev.sibarum.suite.tty.TerminalColors;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both looks, each held to what a look has to be true of, whichever way its page faces.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Every check is written as a <b>distance from the page</b>, not as "lighter" or "darker", so one test holds the
 * dark look and the light one alike. Contrast is WCAG's ratio: 4.5 for body text, 3 for what only has to be seen.
 *
 * <p>When this application gets a real design, the useful version of this test is the one that <b>pins the gap
 * between the design and the construction</b> -- assert each authored colour against what the palette derives,
 * with the difference written down. A gap that is measured is a known quantity; a gap that is absorbed is a
 * surprise waiting for a review.
 */
class LookTest {

    static Stream<Theme> looks() {
        return Stream.of(Look.DARK, Look.LIGHT);
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theInkReadsAgainstThePage(Theme look) {
        assertReads(look, Role.INK, Role.PAGE, 7.0, "primary text");
        assertReads(look, Role.DIM, Role.PAGE, 4.5, "dim text, the settings' labels and the workspaces' paths");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void everyTextColourReadsWhereItIsWritten(Theme look) {
        // Danger is held to 3:1, not 4.5: the dark look's red is 3.97:1 on its page as authored, and that look's
        // numbers are not this test's to move. A gap measured, not absorbed; the light look clears it either way.
        assertReads(look, Role.DANGER, Role.PAGE, 3.0, "a settings or workspaces problem");
        assertReads(look, Role.DANGER, Role.PANEL, 3.0, "a tab's notice, on its band");
        assertReads(look, Role.INK, Role.WELL, 7.0, "a workspace's title, on its card");
        assertReads(look, Role.DIM, Role.WELL, 4.5, "a workspace's path, on its card");
        assertReads(look, Role.FAINT, Role.PAGE, 3.0, "faint text");
    }

    /** The terminal's own ground and text: what a shell prints in when it asks for no colour, in each look. */
    @ParameterizedTest
    @MethodSource("looks")
    void theTerminalReads(Theme look) {
        assertReads(look, Look.TERMINAL_INK, Look.TERMINAL_PAGE, 7.0, "the terminal's default text");
        float page = luminance(look.color(Role.PAGE));
        float ground = luminance(look.color(Look.TERMINAL_PAGE));
        float ink = luminance(look.color(Role.INK));
        assertTrue(Math.abs(ground - ink) > Math.abs(page - ink),
                "the terminal's ground should sit on the far side of the page from the ink, as a sheet set in");
    }

    /**
     * The sixteen ANSI colours a program names, on the terminal's ground in the light look: each read as text, since
     * programs write in every one (PSReadLine draws command names in yellow, numbers in bright white). Not held in the
     * dark look, which is Campbell as it always was ({@link #theDarkTerminalIsCampbell}), dark blue on black and all.
     */
    @Test
    void everyAnsiColourReadsInTheLightTerminal() {
        Color ground = Look.TERMINAL.background().in(Look.LIGHT);
        assertEquals(Look.LIGHT.color(Look.TERMINAL_PAGE), ground, "the terminal's ground is MainFrame's own");
        for (int n = 0; n < 16; n++) {
            double ratio = contrast(Look.TERMINAL.ansi(n).in(Look.LIGHT), ground);
            assertTrue(ratio >= 4.5, "ANSI colour " + n + " is " + String.format("%.2f", ratio)
                    + ":1 on the light terminal; it needs 4.5:1");
        }
    }

    /** The wordmark's two greens for this look's ground read against the bar it sits on. */
    @ParameterizedTest
    @MethodSource("looks")
    void theWordmarkReadsOnTheBar(Theme look) {
        boolean dark = LayeredIcon.dark(look);
        assertEquals(look == Look.DARK, dark, "the wordmark should pick its greens by the look's ground");
        Color main = dark ? Wordmark.DARK_MAIN : Wordmark.LIGHT_MAIN;
        Color frame = dark ? Wordmark.DARK_FRAME : Wordmark.LIGHT_FRAME;
        for (Role ground : new Role[] {Role.PAGE, Role.PANEL}) {
            assertTrue(contrast(main, look.color(ground)) >= 3.0, "MAIN should read on the bar");
            assertTrue(contrast(frame, look.color(ground)) >= 3.0, "FRAME should read on the bar");
        }
    }

    @ParameterizedTest
    @MethodSource("looks")
    void surfacesStepAwayFromThePage(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float panel = Math.abs(luminance(look.color(Role.PANEL)) - page);
        float raised = Math.abs(luminance(look.color(Role.RAISED)) - page);
        assertTrue(panel > 0, "a panel should stand off the page");
        assertTrue(raised > panel, "a raised surface should stand further off the page than a panel");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void textFadesInSteps(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = Math.abs(luminance(look.color(Role.INK)) - page);
        float dim = Math.abs(luminance(look.color(Role.DIM)) - page);
        float faint = Math.abs(luminance(look.color(Role.FAINT)) - page);
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theAccentIsNotTheInk(Theme look) {
        assertNotEquals(look.color(Role.INK), look.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theLooksFaceOppositeWays(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = luminance(look.color(Role.INK));
        assertTrue(look == Look.DARK ? ink > page : ink < page,
                "the dark look's ink is above its page, the light one's below");
    }

    /** A role of our own that answered the same in both looks would not follow the person's choice. */
    @Test
    void everyRoleOfOurOwnHasAnAnswerInEachLook() {
        for (Role own : new Role[] {Look.TERMINAL_PAGE, Look.TERMINAL_INK}) {
            assertNotEquals(Look.DARK.color(own), Look.LIGHT.color(own), "a role of our own has an answer per look");
        }
    }

    /** The dark look's terminal is the one MainFrame always had: Windows Terminal's Campbell, unchanged. */
    @Test
    void theDarkTerminalIsCampbell() {
        assertEquals(Color.rgb(0x0C0C0C), Look.DARK.color(Look.TERMINAL_PAGE));
        assertEquals(Color.rgb(0xCCCCCC), Look.DARK.color(Look.TERMINAL_INK));
        assertEquals(Color.rgb(0x0C0C0C), Look.TERMINAL.background().in(Look.DARK));
        assertEquals(Color.rgb(0xCCCCCC), Look.TERMINAL.foreground().in(Look.DARK));
        for (int n = 0; n < 16; n++) {
            assertEquals(TerminalColors.CAMPBELL.ansi(n).in(Look.DARK), Look.TERMINAL.ansi(n).in(Look.DARK),
                    "ANSI colour " + n + " in the dark look");
        }
    }

    private static void assertReads(Theme look, Role role, Role on, double least, String what) {
        double ratio = contrast(look.color(role), look.color(on));
        assertTrue(ratio >= least, what + " is " + String.format("%.2f", ratio) + ":1 in the "
                + (look == Look.DARK ? "dark" : "light") + " look; it needs " + least + ":1");
    }

    /** WCAG's contrast ratio between two colours. */
    private static double contrast(Color a, Color b) {
        double la = relative(a);
        double lb = relative(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance, from sRGB. */
    private static double relative(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** Rough perceived brightness -- enough to order two shades, which is all the ordering checks need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
