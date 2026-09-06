package dev.mainframe.gui.console;

import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The console's own look: a green monochrome CRT, and the two roles a theme with one colour needs on top of the
 * framework's.
 *
 * <p><b>Why the console owns a palette at all.</b> A window that has to be themed by whoever embeds it is a
 * window that looks different in every application, and the console is meant to be recognisable — it is the same
 * shell whether it opened out of an editor, a calculator or nothing at all. So this is the default rather than a
 * requirement: {@code ConsoleSpec.theme} takes any {@link Theme}, and the only thing a replacement has to supply
 * is {@link #HOT} and {@link #BEZEL}, which are roles rather than colours and so resolve against whatever
 * palette they are handed.
 *
 * <h2>One colour, on purpose</h2>
 * A phosphor screen has <b>one</b> colour. There is no accent to contrast with the ink and no red to warn in,
 * because the tube can only make the beam brighter or dimmer — so every chromatic anchor here is the same green
 * at a different lightness, and the whole vocabulary collapses onto the ink ladder. That is the point: roles
 * that were separate decisions in a colour theme are allowed to converge here, and nothing had to be forked for
 * them to. It is also what {@link Ansi} is built on — seven SGR codes onto three intensities.
 *
 * <p>{@code depth} is the phosphor rather than a shadow colour, which is what turns {@code Node.elevation} from
 * a drop shadow into a bloom — the halo a bright glyph throws on the glass around it. One anchor, and every
 * raised thing in the window glows instead of casting.
 *
 * <p>Nothing here names a colour. Every value is an angle, a lightness or a chroma handed to
 * {@link Oklab#polar}, which is the same way {@code Palette.DARK} is authored.
 */
public final class Phosphor {

    /**
     * P1 phosphor, measured off the green a 5250 actually glowed: hue 145&deg;, and a chroma at full intensity
     * (0.25) that no grey ladder would ever carry.
     */
    private static final double HUE = 145;

    private Phosphor() {
    }

    /** The tube. What a console is themed with unless it is told otherwise. */
    public static final Theme THEME = Theme.of(
            new Palette(
                    // The unlit tube: not black, because glass in a lit room never is.
                    Oklab.polar(0.150, 0.020, HUE),
                    // A short surface step. A CRT has no panels, cards or elevation — only more or less beam.
                    0.028,
                    Oklab.polar(0.860, 0.255, HUE),
                    0.300,
                    // Accent, action and danger: the same phosphor, hotter and cooler. See the note above.
                    Oklab.polar(0.900, 0.230, HUE),
                    Oklab.polar(0.560, 0.190, HUE),
                    Oklab.polar(0.620, 0.200, HUE),
                    // Depth is the glow.
                    Oklab.polar(0.860, 0.255, HUE),
                    0.42),
            // The beam responds harder than a painted surface does: hover blooms, press drops the gun.
            Shading.of(0.055, -0.045),
            // The framework's ladder, unchanged, because the tube has already said what depth means here: the
            // depth anchor above is the phosphor, so a rung is a wider halo rather than a longer shadow. Hover
            // one rung up and press flush are the right responses either way -- what a raised thing does is the
            // theme's decision, and it was made in the palette.
            Relief.STANDARD,
            true,
            false);

    /**
     * Blown-out phosphor — brighter than {@link Role#INK}, which the ink ladder cannot reach because it only
     * fades <em>towards</em> the page. What bold, and every colour a monochrome screen cannot draw, becomes.
     *
     * <p>A role the framework never heard of is the whole reason {@code Role} is a function and not an enum:
     * this is one lambda, and it themes with everything else — including a palette that is not this one.
     */
    public static final Role HOT = p -> p.ink().atLightness(Math.min(1.0, p.ink().l() + 0.09)).toColor();

    /** The bezel the tube is set into — below the page, which is the direction {@code surface} runs backwards. */
    public static final Role BEZEL = p -> p.surface(-2);
}
