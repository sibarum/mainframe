package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The two looks, dark and light, as anchors: the application's own, and the person chooses between them in the app
 * menu (Theme, then Light or Dark). The framework remembers the choice and re-themes every window with it. MainFrame
 * opens dark until the person chooses.
 *
 * <h2>A palette is a construction, not a list of colours</h2>
 *
 * <p>{@link Palette} takes a handful of anchors and derives the rest: surfaces are an even ladder from the page
 * at a fixed lightness step, and text is the ink blended towards the page at a fixed rate. That is the point --
 * every surface in the application stays in step with every other one for free, and a design change is a
 * number here rather than fifteen hex codes spread across the tree.
 *
 * <p>So when a design has fifteen authored colours, the first job is to <b>measure them in Oklab</b> and find
 * out how many decisions they actually are. Usually far fewer, and the ones that do not fit the construction
 * are the interesting ones -- declare those as roles of your own, at their measured values, and note the gap
 * rather than absorbing it. A theme that silently rounds a designer's colour is a theme nobody can check.
 *
 * <h2>Two looks, each authored</h2>
 *
 * <p>The light look is not the dark one reflected. A mirror of the dark page (L 0.214) lands at 0.79, a grey, where
 * a light page wants 0.975; the accent has to come down to stay readable on white; and a shadow that is depth on a
 * dark page is a smudge on a light one, so its alpha drops by two thirds. Every number below is a decision. The
 * framework derives neither look, on purpose.
 *
 * <h2>Write the role, never the colour</h2>
 *
 * <p>The person can change the look while MainFrame runs. A node that holds a {@link Role} is repainted in the new
 * look, and fades to it; a node that was handed {@code gui.theme().color(role)} keeps the colour it was given,
 * forever. So everything here writes roles, and a colour the framework does not name is a role of its own below,
 * with an answer in each look. The terminal takes only colours, so the view hands it this file's terminal roles
 * again each time the look shifts ({@code View.shifted}).
 */
final class Look {

    // ---------------------------------------------------------------- the dark look

    /** The page: the colour behind everything, and what the frame clears to. */
    private static final Oklab DARK_PAGE = Oklab.polar(0.2141, 0.0278, -82.48);

    /** Primary text. Neutral, so it does not tint every label in the application. */
    private static final Oklab DARK_INK = Oklab.polar(0.9352, 0.0054, -73.70);

    /** The one chromatic decision: a hover wash, a selected row, a focus ring. */
    private static final Oklab DARK_ACCENT = Oklab.polar(0.6600, 0.1245, -70.45);

    /** The fill of a filled control, and the border of anything the accent has claimed. */
    private static final Oklab DARK_ACTION = Oklab.polar(0.4801, 0.1041, -70.46);

    /** The accent's chroma taken round to red, so a confirmation dialog belongs to this palette. */
    private static final Oklab DARK_DANGER = Oklab.polar(0.5894, 0.1448, 18.40);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DARK_DEPTH = Oklab.polar(0.1236, 0.0130, -86.65);

    /** The dark palette: two steps from the page is a panel, and text(1) is DIM, text(2) is FAINT. */
    static final Palette DARK_PALETTE = new Palette(DARK_PAGE, 0.0271, DARK_INK, 0.202, DARK_ACCENT, DARK_ACTION,
            DARK_DANGER, DARK_DEPTH, 0.60);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint. Letterpress is the opposite call: it buys
     * contrast for white-on-fill labels by spending crispness, which small text can least afford. Both are
     * decisions to make against a real design rather than defaults to leave alone.
     */
    static final Theme DARK = Theme.of(DARK_PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ---------------------------------------------------------------- the light look

    /** A near-white page at the dark page's hue, so the two read as one family. */
    private static final Oklab LIGHT_PAGE = Oklab.polar(0.9750, 0.0060, -82.48);

    /** Primary text: a deep slate rather than black, at the ink's own hue. */
    private static final Oklab LIGHT_INK = Oklab.polar(0.2500, 0.0180, -73.70);

    /** The accent, brought down from 0.66 so a selected tab and a focus ring still read on white. */
    private static final Oklab LIGHT_ACCENT = Oklab.polar(0.5400, 0.1450, -70.45);

    /** A filled control's fill: a little deeper than the accent, so its white label has room. */
    private static final Oklab LIGHT_ACTION = Oklab.polar(0.4900, 0.1300, -70.46);

    /** Danger, darker and stronger than the dark look's, for the same reason as the accent. */
    private static final Oklab LIGHT_DANGER = Oklab.polar(0.5200, 0.1700, 18.40);

    /** Shadows, at the page's hue. Lighter than the dark look's: here depth is a hint, not a void. */
    private static final Oklab LIGHT_DEPTH = Oklab.polar(0.2000, 0.0100, -86.65);

    /**
     * The light palette: each surface a step down from the page (a little wider than the dark look's, since the eye
     * tells near-whites apart less well), the ink fading faster so DIM and FAINT land at the same reading distance,
     * and a shadow at 0.18 rather than 0.60.
     */
    static final Palette LIGHT_PALETTE = new Palette(LIGHT_PAGE, 0.0300, LIGHT_INK, 0.320, LIGHT_ACCENT,
            LIGHT_ACTION, LIGHT_DANGER, LIGHT_DEPTH, 0.18);

    /** No edge light, which on a white panel reads as a smear, and no letterpress. */
    static final Theme LIGHT = Theme.of(LIGHT_PALETTE, Shading.ON_LIGHT, Relief.STANDARD, false, false);

    // ---------------------------------------------------------------- roles of our own

    /** The terminal's ground on the dark page: Windows Terminal's "Campbell", what every console here has worn. */
    private static final Color DARK_TERMINAL_PAGE = Color.rgb(0x0C0C0C);

    /** The terminal's default text on the dark page: Campbell's. */
    private static final Color DARK_TERMINAL_INK = Color.rgb(0xCCCCCC);

    /**
     * The terminal's ground on the light page: a neutral near-white, a shade brighter than the page, as Campbell's
     * ground is a shade darker than the dark page -- a sheet set into the window rather than a hole in it.
     */
    private static final Color LIGHT_TERMINAL_PAGE = Color.rgb(0xFCFCFC);

    /** The terminal's default text on the light page: a neutral charcoal, as far from its ground as Campbell's. */
    private static final Color LIGHT_TERMINAL_INK = Color.rgb(0x2C2C2C);

    /**
     * Where a program asked for no background of its own: the terminal's ground. Neutral, not the page's hue, because a
     * console's colours are the program's and a tint under them would shift every one.
     */
    static final Role TERMINAL_PAGE = p -> p.equals(LIGHT_PALETTE) ? LIGHT_TERMINAL_PAGE : DARK_TERMINAL_PAGE;

    /** Where a program asked for no colour of its own: the terminal's default text. */
    static final Role TERMINAL_INK = p -> p.equals(LIGHT_PALETTE) ? LIGHT_TERMINAL_INK : DARK_TERMINAL_INK;

    private Look() {
    }
}
