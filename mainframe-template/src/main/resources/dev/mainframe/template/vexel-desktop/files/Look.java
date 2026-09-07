package ${packageName};

import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The palette, as anchors.
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
 * <p>What is here is a neutral dark starting point, and it is meant to be replaced. The two families are:
 * a cool near-black page with a neutral ink, and one accent. Anything the framework does not name --
 * a rule that has to read against a panel, an accent at its brightest -- goes at the bottom as its own role.
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** The page: the colour behind everything, and what the frame clears to. */
    private static final Oklab PAGE = Oklab.polar(0.2141, 0.0278, -82.48);

    /** Primary text. Neutral, so it does not tint every label in the application. */
    private static final Oklab INK = Oklab.polar(0.9352, 0.0054, -73.70);

    /** The one chromatic decision: a hover wash, a selected row, a focus ring. */
    private static final Oklab ACCENT = Oklab.polar(0.6600, 0.1245, -70.45);

    /** The fill of a filled control, and the border of anything the accent has claimed. */
    private static final Oklab ACTION = Oklab.polar(0.4801, 0.1041, -70.46);

    /** The accent's chroma taken round to red, so a confirmation dialog belongs to this palette. */
    private static final Oklab DANGER = Oklab.polar(0.5894, 0.1448, 18.40);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DEPTH = Oklab.polar(0.1236, 0.0130, -86.65);

    /** How far one surface is from the next. Two steps from the page is a panel. */
    private static final double STEP = 0.0271;

    /** How fast the ink fades towards the page: text(1) is DIM, text(2) is FAINT. */
    private static final double FADE = 0.202;

    /** How dark a shadow is. */
    private static final double SHADOW_ALPHA = 0.60;

    static final Palette PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint. Letterpress is the opposite call: it buys
     * contrast for white-on-fill labels by spending crispness, which small text can least afford. Both are
     * decisions to make against a real design rather than defaults to leave alone.
     */
    static final Theme THEME = Theme.of(PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    private Look() {
    }
}
