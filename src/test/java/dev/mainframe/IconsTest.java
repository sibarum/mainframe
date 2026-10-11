package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Pictures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The icons MainFrame carries: there, drawable, and drawn in the colour asked for. */
class IconsTest {

    @Test
    void tintSetsTheRootColour() {
        String svg = "<svg color=\"#e9ecf2\" viewBox=\"0 0 16 16\"><rect fill=\"currentColor\"/></svg>";
        String out = new String(Icons.tinted(svg, Color.rgb(0xff8000)), StandardCharsets.UTF_8);
        assertTrue(out.startsWith("<svg color=\"#ff8000\""), out);
    }

    @Test
    void aDrawingWithNoColourIsNotAMask() {
        assertThrows(IllegalArgumentException.class, () -> Icons.tinted("<svg viewBox=\"0 0 16 16\"/>", Color.WHITE));
    }

    /** Every pixel the settings icon covers is the ink, decoded as Icons decodes it, at 1x and 2x. */
    @Test
    void settingsDrawsInTheInk() throws IOException {
        byte[] svg = Icons.tinted(resource("settings-mask.svg"), Color.rgb(0x20c040));
        for (int px : new int[] {16, 32}) {
            Decoded d = Pictures.of("settings", svg, px);
            assertTrue(d.image(), d.identity());
            Picture f = d.picture();
            assertEquals(px, f.frameWidth());
            byte[] rgba = f.sheet();
            int covered = 0;
            for (int i = 0; i < rgba.length; i += 4) {
                if ((rgba[i + 3] & 0xFF) == 255) {
                    covered++;
                    assertEquals(0x20, rgba[i] & 0xFF);
                    assertEquals(0xc0, rgba[i + 1] & 0xFF);
                    assertEquals(0x40, rgba[i + 2] & 0xFF);
                }
            }
            assertTrue(covered > px * px / 4, "the gear covers a good part of its box: " + covered);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Icons.class.getResourceAsStream("icons/" + name)) {
            assertNotNull(in, name + " on the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
