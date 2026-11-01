package dev.mainframe;

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces, the type scale, and the gutters.
 *
 * <h2>Type is rem; gutters are dp</h2>
 *
 * <p>That is the framework's rule and it decides what zoom does. {@code rem} grows with the user's zoom because it
 * is proportional to text; {@code dp} does not, because tripling the frame around content you zoomed in to read
 * means seeing less of it. Getting this backwards is not a subtle bug -- it is an application whose zoom makes the
 * window emptier.
 *
 * <h2>The faces are the atlas's, not yours</h2>
 *
 * <p>The framework's text atlas is baked at build time from the fonts in {@code vexelray-text}, so face 0 and face
 * 1 are whatever it shipped -- a sans and a mono -- and so is which characters each has. The sans has General
 * Punctuation (the bullet an unsaved tab wears) and not Geometric Shapes: a character the atlas lacks draws as the
 * missing-glyph box, so check the charset in {@code vexelray-text}'s pom before reaching for a symbol.
 */
final class Type {

    /** Face 0: the sans. Labels, the path bar, the status line. */
    static final int UI = 0;

    /** Face 1: the mono. The text being edited, so columns line up. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** The text being edited. */
    static final Length CODE = Length.rem(0.875f);

    /** The path bar, a hint, the navigator's chrome. */
    static final Length SMALL = Length.rem(0.75f);

    // --------------------------------------------------------------- gutters

    /** A tight gap, and the navigator's padding. */
    static final Length TIGHT = Length.dp(5.6f);

    private Type() {
    }
}
