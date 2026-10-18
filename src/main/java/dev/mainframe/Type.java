package dev.mainframe;

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces, the type scale, and the gutters.
 *
 * <h2>Type is rem; gutters are dp</h2>
 *
 * <p>That is the framework's rule and it decides what zoom does. {@code rem} grows with the user's zoom because it
 * is proportional to text; {@code dp} does not, because tripling the frame around content you zoomed in to read
 * means seeing less of it.
 *
 * <p>The terminal's own text is the exception, and on purpose: a cell grid is drawn as a picture, whose marks are in
 * box-local pixels, so its size is the <i>Font size</i> setting in pixels ({@link AppSettings#fontPx}) and nothing
 * here. Zoom reaches it by hand: {@code Terminals} multiplies the setting by it.
 *
 * <h2>The faces are the atlas's, not yours</h2>
 *
 * <p>The framework's text atlas is baked at build time from the fonts in {@code vexelray-text}, so face 0 and face
 * 1 are whatever it shipped -- a sans and a mono -- and so is which characters each has. A character the atlas lacks
 * draws as the missing-glyph box; the terminal draws box drawing, block elements and braille itself
 * (vex-suite-common's {@code CellArt}) for that reason.
 */
final class Type {

    /** Face 0: the sans. Tab headers, settings, notices. */
    static final int UI = 0;

    /** Face 1: the mono. The terminal, so columns line up. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** A notice over the terminal, a setting's label. */
    static final Length BODY = Length.rem(0.875f);

    /** A setting's explanation. */
    static final Length SMALL = Length.rem(0.75f);

    private Type() {
    }
}
