package dev.mainframe;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Pictures;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The suite's utility icons, from {@code vexelray-icons/utility/small}: the masks, one solid shape each, drawn in a
 * colour of the theme's at the pixel size of their box.
 *
 * <p>An icon is an SVG decoded by vexelray-codec whenever its box changes size, so it is sharp at any display scale or
 * zoom, and the 16-unit art lands on whole pixels at 16 and 32. A mask's one fill is {@code currentColor}, so the
 * colour is the root's {@code color}, set before decoding. The codec's {@link Textures} uploads each icon at each size
 * once and gives back what has not been shown for a while.
 *
 * <p>Late-bound, as a texture belongs to the window's device: until {@link #bind} there is nothing to upload into, and
 * an icon that was sized before then is drawn when it comes.
 */
final class Icons {

    private final Textures textures = new Textures();
    private final List<Runnable> waiting = new ArrayList<>();
    private boolean bound;

    /** Main thread, once the window exists: draws every icon that was sized before it did. */
    void bind(GuiApp app) {
        textures.bind(app);
        List<Runnable> now;
        synchronized (this) {
            bound = true;
            now = List.copyOf(waiting);
            waiting.clear();
        }
        now.forEach(Runnable::run);
    }

    /** A box {@code sizeDp} square showing the mask {@code name} in {@code ink}. */
    Node icon(Gui gui, String name, float sizeDp, Color ink) {
        // Keyed by name and colour: the codec takes one name to be one image.
        String key = "icon:" + name + ":" + hex(ink);
        byte[] svg = tinted(read(name + "-mask.svg"), ink);
        Node node = gui.box().width(Length.dp(sizeDp)).height(Length.dp(sizeDp)).scroll(false, false);
        gui.onResize(node, layout -> {
            int side = Math.round(Math.max(layout.rect().w(), layout.rect().h()));
            if (side <= 0) return;
            Runnable draw = () -> {
                Decoded d = Pictures.of(key, svg, side);
                if (d.image()) {
                    textures.show(d.picture(), node::image);
                } else {
                    // A box with no icon is still a button, and still has its tooltip.
                    System.err.println("mainframe: cannot draw icon " + name + ": " + d.identity());
                }
            };
            synchronized (this) {
                if (!bound) {
                    waiting.add(draw);
                    return;
                }
            }
            draw.run();
        });
        return node;
    }

    /** The SVG with its root's {@code color}, which its {@code currentColor} fill takes, set to {@code ink}. */
    static byte[] tinted(String svg, Color ink) {
        String root = svg.replaceFirst("<svg color=\"#[0-9a-fA-F]{3,8}\"", "<svg color=\"" + hex(ink) + "\"");
        if (!root.startsWith("<svg color=\"")) {
            throw new IllegalArgumentException("not a tintable mask: its root has no color");
        }
        return root.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(c.r()), channel(c.g()), channel(c.b()));
    }

    private static int channel(float c) {
        return Math.round(Math.max(0f, Math.min(1f, c)) * 255f);
    }

    private static String read(String file) {
        try (InputStream in = Icons.class.getResourceAsStream("icons/" + file)) {
            if (in == null) throw new IllegalStateException("no icon " + file + " on the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read icon " + file + ": " + e.getMessage(), e);
        }
    }
}
