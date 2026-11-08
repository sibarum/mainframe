package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Feed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wordmark: MAINFRAME on a 5x7 grid, drawn as the brand sheet constructs it. */
class WordmarkTest {

    @Test
    void theGridIsNineLettersOfFiveCellsWithAColumnBetween() {
        assertEquals(9 * 6 - 1, Wordmark.columns());
        for (int c = 5; c < Wordmark.columns(); c += 6) {
            for (int r = 0; r < 7; r++) {
                assertEquals('.', Wordmark.cell(r, c), "the column between letters, at " + c);
            }
        }
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < Wordmark.columns(); c++) {
                char k = Wordmark.cell(r, c);
                assertTrue(k == '.' || k == (c < 4 * 6 ? 'M' : 'F'), "MAIN is M and FRAME is F, at " + r + "," + c);
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
    void aBarsMiddleIsItsLettersGreenAndTheGroundBetweenRowsIsOnlyBloom() {
        byte[] px = Wordmark.pixels(true)[0];
        // M's first bar, cell (0, 0): its centre is at x 2.1, y 2.35 in the sheet's cells
        int[] m = texel(px, 2.1f, 2.35f);
        assertEquals(255, m[3], "a bar's middle is fully lit");
        near(Wordmark.DARK_MAIN, m);
        // F's top bar, from column 24: its middle is at x 1.52 + 26 + 0.58
        int[] f = texel(px, 1.52f + 26.58f, 2.35f);
        near(Wordmark.DARK_FRAME, f);
        // between M's top row and the next, at the left of the empty middle of the first M
        int[] gap = texel(px, 3.1f, 3.1f);
        assertTrue(gap[3] < 255, "between scanlines only the bloom: " + gap[3]);
        // the far corner of the view box has nothing on it
        assertEquals(0, texel(px, 56f, 13.6f)[3]);
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
