package ${packageName};

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces, the type scale, and the gutters.
 *
 * <h2>Type is rem; gutters are dp</h2>
 *
 * <p>That is the framework's rule and it decides what zoom does. {@code rem} grows with the user's zoom because
 * it is proportional to text; {@code dp} does not, because tripling the frame around content you zoomed in to
 * read means seeing less of it. Getting this backwards is not a subtle bug -- it is an application whose zoom
 * makes the window emptier.
 *
 * <h2>The faces are the atlas's, not yours</h2>
 *
 * <p>The framework's text atlas is baked at build time from the fonts in {@code vexelray-text}, so face 0 and
 * face 1 are whatever it shipped -- a sans and a mono. Shadowing it with a design's own typefaces needs the
 * font files and an {@code msdf} plugin run. Worth knowing before a design review, because it is the one part
 * of a look that a capture will not match.
 */
final class Type {

    /** Face 0: the sans. Labels, names, prose. */
    static final int UI = 0;

    /** Face 1: the mono. Numbers, codes, badges -- anything that should line up in a column. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** A heading. */
    static final Length HEADING = Length.rem(1.125f);

    /** A number worth looking at: the one thing on the card that is the point. */
    static final Length FIGURE = Length.rem(2.0f);

    /** A control's label. */
    static final Length LABEL = Length.rem(0.8125f);

    /** A note, a subtitle, a hint. */
    static final Length SMALL = Length.rem(0.6875f);

    // --------------------------------------------------------------- gutters

    /** The outer inset of a region. */
    static final Length EDGE = Length.dp(22.4f);

    /** A panel's padding. */
    static final Length WIDE = Length.dp(11.2f);

    /** The standard gap between two things that belong together. */
    static final Length GAP = Length.dp(8.4f);

    /** A tight gap. */
    static final Length TIGHT = Length.dp(5.6f);

    /** A border. Not scaled by zoom; a hairline is a hairline. */
    static final Length RULE = Length.dp(1);

    /** The corner radius of a panel or a control. */
    static final Length CORNER = Length.dp(6);

    private Type() {
    }
}
