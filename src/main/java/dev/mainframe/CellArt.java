package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Sketch;

/**
 * Characters a terminal draws itself instead of taking from the font: box drawing, block elements and braille.
 *
 * <p>A font's box glyphs are as tall as its em, but a terminal cell is as tall as the line, so font glyphs leave
 * gaps between rows and the lines of a TUI border do not meet. Drawing them as rectangles that span the whole cell
 * makes them join, whatever the font. Braille is drawn here because the bundled mono face has none of it.
 */
final class CellArt {

    private static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;
    private static final int LIGHT = 1, HEAVY = 2, DOUBLE = 3;

    private static final int[][] BOX = new int[0x80][];

    private CellArt() {
    }

    static boolean handles(int cp) {
        return (cp >= 0x2500 && cp <= 0x259F) || (cp >= 0x2800 && cp <= 0x28FF);
    }

    static void draw(Sketch s, int cp, float x, float y, float w, float h, int rgb) {
        Color c = Color.rgb(rgb);
        if (cp >= 0x2800) braille(s, cp, x, y, w, h, c);
        else if (cp >= 0x2580) block(s, cp, x, y, w, h, rgb, c);
        else box(s, cp, x, y, w, h, c);
    }

    // -- braille ----------------------------------------------------------------------------------------------

    private static void braille(Sketch s, int cp, float x, float y, float w, float h, Color c) {
        // dot n of the eight, in the order Unicode numbers them: two columns of three, then the bottom row
        final int[] col = {0, 0, 0, 1, 1, 1, 0, 1};
        final int[] row = {0, 1, 2, 0, 1, 2, 3, 3};
        double r = Math.min(w, h) * 0.11;
        for (int bit = 0; bit < 8; bit++) {
            if ((cp & (1 << bit)) != 0) {
                s.circle(x + w * (0.3 + 0.4 * col[bit]), y + h * (0.125 + 0.25 * row[bit]), r, c);
            }
        }
    }

    // -- block elements ---------------------------------------------------------------------------------------

    private static void block(Sketch s, int cp, float x, float y, float w, float h, int rgb, Color c) {
        switch (cp) {
            case 0x2580 -> part(s, x, y, w, h, 0, 0, 1, 0.5f, c);
            case 0x2590 -> part(s, x, y, w, h, 0.5f, 0, 0.5f, 1, c);
            case 0x2591 -> s.fill(x, y, w, h, Color.argb(0x40000000 | rgb));
            case 0x2592 -> s.fill(x, y, w, h, Color.argb(0x80000000 | rgb));
            case 0x2593 -> s.fill(x, y, w, h, Color.argb(0xC0000000 | rgb));
            case 0x2594 -> part(s, x, y, w, h, 0, 0, 1, 1 / 8f, c);
            case 0x2595 -> part(s, x, y, w, h, 7 / 8f, 0, 1 / 8f, 1, c);
            default -> {
                if (cp >= 0x2581 && cp <= 0x2588) { // lower n/8
                    float f = (cp - 0x2580) / 8f;
                    part(s, x, y, w, h, 0, 1 - f, 1, f, c);
                } else if (cp >= 0x2589 && cp <= 0x258F) { // left n/8, narrowing
                    float f = (0x2590 - cp) / 8f;
                    part(s, x, y, w, h, 0, 0, f, 1, c);
                } else if (cp >= 0x2596 && cp <= 0x259F) {
                    // quadrants as bits: 1 upper-left, 2 upper-right, 4 lower-left, 8 lower-right
                    final int[] q = {4, 8, 1, 1 | 4 | 8, 1 | 8, 1 | 2 | 4, 1 | 2 | 8, 2, 2 | 4, 2 | 4 | 8};
                    int m = q[cp - 0x2596];
                    if ((m & 1) != 0) part(s, x, y, w, h, 0, 0, 0.5f, 0.5f, c);
                    if ((m & 2) != 0) part(s, x, y, w, h, 0.5f, 0, 0.5f, 0.5f, c);
                    if ((m & 4) != 0) part(s, x, y, w, h, 0, 0.5f, 0.5f, 0.5f, c);
                    if ((m & 8) != 0) part(s, x, y, w, h, 0.5f, 0.5f, 0.5f, 0.5f, c);
                }
            }
        }
    }

    private static void part(Sketch s, float x, float y, float w, float h, float fx, float fy, float fw, float fh,
                             Color c) {
        float x0 = Math.round(x + fx * w), y0 = Math.round(y + fy * h);
        float x1 = Math.round(x + (fx + fw) * w), y1 = Math.round(y + (fy + fh) * h);
        s.fill(x0, y0, x1 - x0, y1 - y0, c);
    }

    // -- box drawing ------------------------------------------------------------------------------------------

    private static void box(Sketch s, int cp, float x, float y, float w, float h, Color c) {
        float lt = Math.max(1f, Math.round(w / 10f));
        float ht = Math.max(lt + 1f, Math.round(w / 5f));
        float d = Math.max(2f, lt * 2f); // a double line is two light strokes this far either side of centre
        float cx = Math.round(x + w / 2), cy = Math.round(y + h / 2);

        if (cp >= 0x2571 && cp <= 0x2573) {
            if (cp != 0x2572) s.line(x + w, y, x, y + h, lt, c);
            if (cp != 0x2571) s.line(x, y, x + w, y + h, lt, c);
            return;
        }
        int[] a = arms(cp);
        for (int dir = 0; dir < 4; dir++) {
            int wt = a[dir];
            if (wt == 0) continue;
            boolean horizontal = dir == RIGHT || dir == LEFT;
            boolean forward = dir == RIGHT || dir == DOWN;
            float thick = wt == HEAVY ? ht : lt;
            // perpendicular arms: for a horizontal arm, up and down
            int p0 = horizontal ? a[UP] : a[LEFT], p1 = horizontal ? a[DOWN] : a[RIGHT];
            int opposite = a[(dir + 2) % 4];
            float[] offsets = wt == DOUBLE ? new float[] {-d, d} : new float[] {0};
            for (float o : offsets) {
                float st; // how far from the centre, along the arm, the stroke begins; negative reaches past it
                if (wt == DOUBLE) {
                    int v = o < 0 ? p0 : p1;
                    if (v == DOUBLE) st = d - lt / 2;
                    else if (v != 0) st = -half(v, lt, ht, d);
                    else st = opposite != 0 ? 0 : -Math.max(half(p0, lt, ht, d), half(p1, lt, ht, d));
                } else if (p0 == DOUBLE || p1 == DOUBLE) {
                    st = d - lt / 2;
                } else {
                    st = opposite != 0 ? 0 : -Math.max(half(p0, lt, ht, d), half(p1, lt, ht, d));
                }
                if (horizontal) {
                    float top = Math.round(cy + o - thick / 2);
                    float x0 = forward ? cx + st : x, x1 = forward ? x + w : cx - st;
                    s.fill(x0, top, x1 - x0, thick, c);
                } else {
                    float left = Math.round(cx + o - thick / 2);
                    float y0 = forward ? cy + st : y, y1 = forward ? y + h : cy - st;
                    s.fill(left, y0, thick, y1 - y0, c);
                }
            }
        }
    }

    private static float half(int wt, float lt, float ht, float d) {
        return switch (wt) {
            case LIGHT -> lt / 2;
            case HEAVY -> ht / 2;
            case DOUBLE -> d + lt / 2;
            default -> 0;
        };
    }

    /**
     * The weight of each arm (up, right, down, left) of a box drawing character, read from its Unicode name
     * ("BOX DRAWINGS DOWN HEAVY AND RIGHT LIGHT", "BOX DRAWINGS DOUBLE VERTICAL AND HORIZONTAL", ...) rather than
     * from a hand-typed table of 128 entries. Dashed and arc variants draw as their solid, square cousins.
     */
    private static int[] arms(int cp) {
        int i = cp - 0x2500;
        if (BOX[i] != null) return BOX[i];
        int[] a = new int[4];
        String name = Character.getName(cp);
        if (name != null) {
            name = name.replace("BOX DRAWINGS ", "").replace("SINGLE", "LIGHT").replace("ARC ", "");
            boolean dash = name.contains("DASH");
            int inherited = 0;
            for (String seg : name.split(" AND ")) {
                int wt = 0;
                boolean up = false, down = false, left = false, right = false;
                for (String word : seg.split(" ")) {
                    switch (word) {
                        case "LIGHT" -> wt = LIGHT;
                        case "HEAVY" -> wt = HEAVY;
                        case "DOUBLE" -> { if (!dash) wt = DOUBLE; }
                        case "UP" -> up = true;
                        case "DOWN" -> down = true;
                        case "LEFT" -> left = true;
                        case "RIGHT" -> right = true;
                        case "VERTICAL" -> { up = true; down = true; }
                        case "HORIZONTAL" -> { left = true; right = true; }
                        default -> { }
                    }
                }
                if (wt == 0) wt = inherited;
                inherited = wt;
                if (up) a[UP] = wt;
                if (down) a[DOWN] = wt;
                if (left) a[LEFT] = wt;
                if (right) a[RIGHT] = wt;
            }
        }
        BOX[i] = a;
        return a;
    }
}
