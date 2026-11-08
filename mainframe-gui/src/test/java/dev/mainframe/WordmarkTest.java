package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Feed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wordmark: MAINFRAME on the sheet's 5-row grid, drawn as the brand sheet constructs it. */
class WordmarkTest {

    /** M A I N F R A M E: M five cells, I three, the rest four, each followed by an empty column. */
    private static final int[] WIDTHS = {5, 4, 3, 4, 4, 4, 4, 5, 4};

    @Test
    void theGridIsFiveRowsOfProportionalLettersWithAColumnBetween() {
        int columns = 0;
        for (int w : WIDTHS) {
            columns += w + 1;
        }
        assertEquals(columns - 1, Wordmark.columns());
        int at = 0;
        for (int letter = 0; letter < WIDTHS.length; letter++) {
            at += WIDTHS[letter];
            for (int r = 0; r < Wordmark.rows(); r++) {
                if (at < Wordmark.columns()) {
                    assertEquals('.', Wordmark.cell(r, at), "the column after letter " + letter);
                }
            }
            at++;
        }
        assertEquals(5, Wordmark.rows());
        int frame = WIDTHS[0] + WIDTHS[1] + WIDTHS[2] + WIDTHS[3] + 4;
        for (int r = 0; r < Wordmark.rows(); r++) {
            for (int c = 0; c < Wordmark.columns(); c++) {
                char k = Wordmark.cell(r, c);
                assertTrue(k == '.' || k == (c < frame ? 'M' : 'F'), "MAIN is M and FRAME is F, at " + r + "," + c);
            }
        }
    }

    @Test
    void theRasterIsTheSheetsViewBoxAtItsTexelsToACell() {
        Feed f = Wordmark.feed(true);
        assertEquals(Math.round(Wordmark.VIEW_W * Wordmark.TEXELS), f.width());
        assertEquals(Math.round(Wordmark.VIEW_H * Wordmark.TEXELS), f.height());
        assertEquals(1, f.slots());
    }

    @Test
    void atRestABarIsItsLettersGreenAndTheGroundBetweenRowsIsBare() {
        byte[] px = Wordmark.pixels(true)[0];
        // M's first bar, cell (0, 0): its middle is half a cell in from the left edge, on the first row's centre line
        int[] m = texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP);
        assertEquals(255, m[3], "a bar's middle is fully lit");
        near(Wordmark.DARK_MAIN, m);
        // F's top bar, columns 20 to 23
        int[] f = texel(px, Wordmark.LEFT + 22f, Wordmark.TOP);
        near(Wordmark.DARK_FRAME, f);
        // between M's top row and the next: no bloom at rest
        assertEquals(0, texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP + 0.75f)[3], "between scanlines, bare ground");
        // the far corner of the view box has nothing on it
        assertEquals(0, texel(px, Wordmark.VIEW_W - 0.2f, Wordmark.VIEW_H - 0.1f)[3]);
    }

    @Test
    void underThePointerTheGreensGlow() {
        byte[] px = Wordmark.glowing(true)[0];
        near(Wordmark.DARK_MAIN, texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP));
        near(Wordmark.DARK_FRAME, texel(px, Wordmark.LEFT + 22f, Wordmark.TOP));
        int gap = texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP + 0.75f)[3];
        assertTrue(gap > 0 && gap < 255, "between scanlines, the bloom: " + gap);
    }

    @Test
    void pressedTheWholeWordIsLitInThePaintAndGlows() {
        Color accent = Color.rgb(0x8877ee);
        byte[] px = Wordmark.pixels(accent)[0];
        near(accent, texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP));
        near(accent, texel(px, Wordmark.LEFT + 22f, Wordmark.TOP));
        int gap = texel(px, Wordmark.LEFT + 0.5f, Wordmark.TOP + 0.75f)[3];
        assertTrue(gap > 0 && gap < 255, "between scanlines, the bloom: " + gap);
    }

    private static int[] texel(byte[] px, float x, float y) {
        int i = ((int) (y * Wordmark.TEXELS) * Wordmark.W + (int) (x * Wordmark.TEXELS)) * 4;
        return new int[] {px[i] & 0xff, px[i + 1] & 0xff, px[i + 2] & 0xff, px[i + 3] & 0xff};
    }

    private static void near(Color want, int[] got) {
        int[] w = {Math.round(want.r() * 255), Math.round(want.g() * 255), Math.round(want.b() * 255)};
        for (int ch = 0; ch < 3; ch++) {
            assertTrue(Math.abs(w[ch] - got[ch]) <= 2, "channel " + ch + ": wanted " + w[ch] + ", got " + got[ch]);
        }
    }
}
