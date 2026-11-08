package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.vulkan.present.MipChain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MAINFRAME as the title bar's logo: a 5x7 dot-matrix face whose every pixel keeps only the green third of an
 * RGB-stripe phosphor, the brand sheet's wordmark ("MainFrame Wordmark", 2026-11-07). The sheet's SVGs cannot be read
 * at run time, so the face is written out below, an {@code M} or an {@code F} to a lit cell, and drawn here as the
 * sheet constructs it:
 *
 * <ul>
 *   <li><b>Grid.</b> 5x7 cells a letter, one empty column between letters; a cell is 1.5 times taller than wide.</li>
 *   <li><b>Stripe.</b> Only the green third of a cell lights, so a row of lit cells is one bar, read as a scanline.</li>
 *   <li><b>Taper.</b> A bar swells from a fine point over 0.42 of a cell, and overshoots each end cell by 0.08.</li>
 *   <li><b>Bloom.</b> Two blurs under the bars, of 0.35 and 1.0 cell, as the sheet's filter has them.</li>
 * </ul>
 *
 * <p>In the sheet's own units, a cell one wide, so the geometry below is its numbers. MAIN and FRAME are two greens,
 * light ones on a dark theme and dark ones on a light theme. Under the pointer the bar hands over the accent, and the
 * whole word lights in it, as the framework's text wordmark does.
 */
final class Wordmark {

    private static final String[] ROWS = {
            "M...M..MMM..MMMMM.M...M.FFFFF.FFFF...FFF..F...F.FFFFF",
            "MM.MM.M...M...M...MM..M.F.....F...F.F...F.FF.FF.F....",
            "M.M.M.M...M...M...M.M.M.F.....F...F.F...F.F.F.F.F....",
            "M.M.M.MMMMM...M...M..MM.FFFF..FFFF..FFFFF.F.F.F.FFFFF",
            "M...M.M...M...M...M...M.F.....F.F...F...F.F...F.F....",
            "M...M.M...M...M...M...M.F.....F..F..F...F.F...F.F....",
            "M...M.M...M.MMMMM.M...M.F.....F...F.F...F.F...F.FFFFF",
    };

    /** The sheet's view box, in cells. */
    static final float VIEW_W = 56.20f;
    static final float VIEW_H = 13.70f;

    /** Where cell (0, 0)'s bar starts (its left tip) and sits (its centre line), and the rows' pitch. */
    private static final float LEFT = 1.52f;
    private static final float TOP = 2.35f;
    private static final float PITCH = 1.5f;

    /** A bar's length beyond its cells (tip to tip, a run of n cells is n + 0.16), its taper, and its half height. */
    private static final float SPAN = 1.16f;
    private static final float TAPER = 0.42f;
    private static final float HALF = 0.25f;

    /** Texels to a cell: the raster is the sheet drawn this much larger, and mipped down to the bar's size. */
    static final int TEXELS = 6;
    static final int W = Math.round(VIEW_W * TEXELS);
    static final int H = Math.round(VIEW_H * TEXELS);

    /** The logo's height in the bar: the sheet's compact bar puts a 15 px mark in a 32 px bar, which is ours. */
    static final float HEIGHT_DP = 15f;

    /** The sheet's greens: MAIN, then FRAME, on a dark theme and on a light one. */
    static final Color DARK_MAIN = Color.rgb(0xd2f6d8);
    static final Color DARK_FRAME = Color.rgb(0x7cd591);
    static final Color LIGHT_MAIN = Color.rgb(0x123d1d);
    static final Color LIGHT_FRAME = Color.rgb(0x1f8a40);

    /** The bloom's two blurs, in cells, and how strongly each is laid under the bars: the sheet's, per ground. */
    private static final float NEAR = 0.35f;
    private static final float FAR = 1.0f;

    /** Points along the taper's curve, from the tip to full height: the sheet's cubic, sampled once. */
    private static final float[] TAPER_D;
    private static final float[] TAPER_H;

    static {
        int n = 64;
        TAPER_D = new float[n + 1];
        TAPER_H = new float[n + 1];
        // (0,0) (0.147,0.138) (0.231,0.25) (0.42,0.25): distance along the bar, half height
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float u = 1f - t;
            float b1 = 3f * u * u * t;
            float b2 = 3f * u * t * t;
            float b3 = t * t * t;
            TAPER_D[i] = b1 * 0.147f + b2 * 0.231f + b3 * TAPER;
            TAPER_H[i] = b1 * 0.138f + b2 * HALF + b3 * HALF;
        }
    }

    private Wordmark() {
    }

    /** The grid's width, in cells. */
    static int columns() {
        return ROWS[0].length();
    }

    /** The cell at row {@code r}, column {@code c}: {@code 'M'}, {@code 'F'} or {@code '.'} for unlit. */
    static char cell(int r, int c) {
        return ROWS[r].charAt(c);
    }

    /**
     * The word as a still, MAIN in {@code main} and FRAME in {@code frame}, its bloom laid under at {@code near} and
     * {@code far} strength: straight RGBA, {@link #W} by {@link #H}, clipped to the view box as the sheet's SVG is.
     */
    static Feed feed(Color main, Color frame, float near, float far) {
        return Feed.still(W, H, true, CHAINS.computeIfAbsent(new Look(main, frame, near, far), Wordmark::chain));
    }

    /** One raster's colours and bloom: what a chain is kept under. */
    private record Look(Color main, Color frame, float near, float far) {
    }

    /**
     * Every raster made, kept for the run: a few milliseconds of blur each, and every window's bar asks for the same
     * two, so they are made once, when the first bar is built, and never under the pointer.
     */
    private static final Map<Look, byte[][]> CHAINS = new ConcurrentHashMap<>();

    private static byte[][] chain(Look look) {
        Color main = look.main();
        Color frame = look.frame();
        float near = look.near();
        float far = look.far();
        int n = W * H;
        float[] lit = new float[n * 4];
        for (int r = 0; r < ROWS.length; r++) {
            String row = ROWS[r];
            int c = 0;
            while (c < row.length()) {
                char k = row.charAt(c);
                if (k == '.') {
                    c++;
                    continue;
                }
                int end = c;
                while (end + 1 < row.length() && row.charAt(end + 1) == k) {
                    end++;
                }
                bar(lit, LEFT + c, LEFT + end + SPAN, TOP + PITCH * r, k == 'M' ? main : frame);
                c = end + 1;
            }
        }
        float[] glow = new float[n * 4];
        under(glow, blur(lit, FAR * TEXELS), far);
        under(glow, blur(lit, NEAR * TEXELS), near);
        under(glow, lit, 1f);
        byte[] rgba = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            float a = glow[i * 4 + 3];
            if (a <= 0f) {
                continue;
            }
            rgba[i * 4] = channel(glow[i * 4] / a);
            rgba[i * 4 + 1] = channel(glow[i * 4 + 1] / a);
            rgba[i * 4 + 2] = channel(glow[i * 4 + 2] / a);
            rgba[i * 4 + 3] = channel(a);
        }
        return MipChain.of(rgba, W, H);
    }

    /** The word in the sheet's two greens for a dark or a light ground. */
    static Feed feed(boolean dark) {
        return Feed.still(W, H, true, pixels(dark));
    }

    /** The word's raster for a dark or a light ground, as a mip chain, its full size first. */
    static byte[][] pixels(boolean dark) {
        return CHAINS.computeIfAbsent(dark ? new Look(DARK_MAIN, DARK_FRAME, 1f, 0.75f)
                : new Look(LIGHT_MAIN, LIGHT_FRAME, 0.55f, 0.35f), Wordmark::chain);
    }

    /** A box showing the word, {@link #HEIGHT_DP} high and as wide as the view box makes it. */
    static Node node(Gui gui, Feed feed) {
        return gui.box().role("wordmark")
                .size(Length.dp(HEIGHT_DP * VIEW_W / VIEW_H), Length.dp(HEIGHT_DP))
                .image(feed);
    }

    /**
     * The wordmark for a title bar's lockup. At rest (the bar hands over its ink) the word wears the sheet's greens for
     * the theme's ground; in any other paint (the accent, under the pointer) the whole word is lit in that paint. A
     * raster per colour, kept, as Rasterware's is (its FN-20: a raster cannot hold a paint, so the paint is resolved
     * against the theme when the bar hands it over). The accent's is made here with the rest, not under the pointer.
     */
    static TitleBar.Wordmark bar(Gui gui) {
        Map<Color, Feed> lit = new ConcurrentHashMap<>();
        Node node = node(gui, rest(gui, lit));
        lit(lit, gui.theme().color(Role.ACCENT));
        return new TitleBar.Wordmark() {
            @Override
            public Node node() {
                return node;
            }

            @Override
            public void ink(Paint ink) {
                Color c = ink.in(gui.theme());
                node.image(c.equals(gui.theme().color(Role.INK)) ? rest(gui, lit) : lit(lit, c));
            }
        };
    }

    /** The whole word lit in {@code c}, with the dark ground's bloom. */
    private static Feed lit(Map<Color, Feed> lit, Color c) {
        return lit.computeIfAbsent(c, k -> feed(k, k, 1f, 0.75f));
    }

    /** The word at rest for the theme now drawing, kept under the theme's ink so a later theme makes its own. */
    private static Feed rest(Gui gui, Map<Color, Feed> lit) {
        boolean dark = LayeredIcon.dark(gui.theme());
        return lit.computeIfAbsent(gui.theme().color(Role.INK), k -> feed(dark));
    }

    /**
     * Light one bar, tip {@code x0} to tip {@code x1} on centre line {@code y}, in {@code color}, into the premultiplied
     * {@code lit}: each texel covered as far as sixteen samples in it fall inside the tapered shape.
     */
    private static void bar(float[] lit, float x0, float x1, float y, Color color) {
        int tx0 = Math.max(0, (int) Math.floor(x0 * TEXELS));
        int tx1 = Math.min(W - 1, (int) Math.ceil(x1 * TEXELS));
        int ty0 = Math.max(0, (int) Math.floor((y - HALF) * TEXELS));
        int ty1 = Math.min(H - 1, (int) Math.ceil((y + HALF) * TEXELS));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int in = 0;
                for (int sy = 0; sy < 4; sy++) {
                    float py = (ty + (sy + 0.5f) / 4f) / TEXELS;
                    for (int sx = 0; sx < 4; sx++) {
                        float px = (tx + (sx + 0.5f) / 4f) / TEXELS;
                        if (px > x0 && px < x1 && Math.abs(py - y) < half(Math.min(px - x0, x1 - px))) {
                            in++;
                        }
                    }
                }
                if (in == 0) {
                    continue;
                }
                float a = in / 16f;
                int i = (ty * W + tx) * 4;
                // over what is there: bars never overlap, so this is the bar's coverage
                lit[i] += color.r() * a * (1f - lit[i + 3]);
                lit[i + 1] += color.g() * a * (1f - lit[i + 3]);
                lit[i + 2] += color.b() * a * (1f - lit[i + 3]);
                lit[i + 3] += a * (1f - lit[i + 3]);
            }
        }
    }

    /** The bar's half height {@code d} cells in from its nearer tip: the taper's curve, then full height. */
    private static float half(float d) {
        if (d >= TAPER) {
            return HALF;
        }
        for (int i = 1; i < TAPER_D.length; i++) {
            if (TAPER_D[i] >= d) {
                float t = (d - TAPER_D[i - 1]) / (TAPER_D[i] - TAPER_D[i - 1]);
                return TAPER_H[i - 1] + t * (TAPER_H[i] - TAPER_H[i - 1]);
            }
        }
        return HALF;
    }

    /** {@code src}, premultiplied, blurred by a gaussian of {@code sigma} texels, across and then down. */
    private static float[] blur(float[] src, float sigma) {
        int radius = (int) Math.ceil(sigma * 3f);
        float[] k = new float[radius * 2 + 1];
        float sum = 0f;
        for (int i = -radius; i <= radius; i++) {
            k[i + radius] = (float) Math.exp(-(i * i) / (2f * sigma * sigma));
            sum += k[i + radius];
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        float[] across = new float[src.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int j = -radius; j <= radius; j++) {
                    int sx = x + j;
                    if (sx < 0 || sx >= W) {
                        continue;
                    }
                    int s = (y * W + sx) * 4;
                    int d = (y * W + x) * 4;
                    float w = k[j + radius];
                    for (int ch = 0; ch < 4; ch++) {
                        across[d + ch] += src[s + ch] * w;
                    }
                }
            }
        }
        float[] down = new float[src.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int j = -radius; j <= radius; j++) {
                    int sy = y + j;
                    if (sy < 0 || sy >= H) {
                        continue;
                    }
                    int s = (sy * W + x) * 4;
                    int d = (y * W + x) * 4;
                    float w = k[j + radius];
                    for (int ch = 0; ch < 4; ch++) {
                        down[d + ch] += across[s + ch] * w;
                    }
                }
            }
        }
        return down;
    }

    /** Lay {@code layer}, its alpha scaled by {@code strength}, over {@code onto}: both premultiplied. */
    private static void under(float[] onto, float[] layer, float strength) {
        for (int i = 0; i < onto.length; i += 4) {
            float a = Math.min(1f, layer[i + 3] * strength);
            if (a <= 0f) {
                continue;
            }
            float s = a / layer[i + 3];
            float keep = 1f - a;
            onto[i] = layer[i] * s + onto[i] * keep;
            onto[i + 1] = layer[i + 1] * s + onto[i + 1] * keep;
            onto[i + 2] = layer[i + 2] * s + onto[i + 2] * keep;
            onto[i + 3] = a + onto[i + 3] * keep;
        }
    }

    private static byte channel(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }
}
