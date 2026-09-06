package dev.mainframe.gui.desktop;

import dev.vexelray.os.Icon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * MainFrame's own mark, at the sizes it was drawn in.
 *
 * <h2>Why this is a class and not four files somebody remembers to load</h2>
 * An {@link Icon} is a set of sizes rather than an image, and the sizes are the whole point: the window manager
 * asks for a 16-pixel one for the caption and a large one for the task switcher at moments no application sees.
 * Loading them is four lines and getting them wrong is invisible until a screenshot, so it is done once, here,
 * and everything that wants MainFrame's mark asks for it by name.
 *
 * <p>The artwork is the VexelRay mark for MainFrame, on the shared 96-pixel grid, rasterised at 16, 32, 48 and
 * 256 and shipped beside this class — so it travels in the jar and in the native image, and an application does
 * not have to be installed anywhere to wear it.
 *
 * <h2>What this is not</h2>
 * Not the executable's icon. What Explorer shows for {@code mainframe.exe} on disk, and what a pinned shortcut
 * shows before the program runs, is a resource linked into the binary — see
 * {@code mainframe-dist/src/main/native/mainframe.rc}, where the same artwork goes in as an {@code .ico}. This
 * is the icon of the <em>running</em> application, which is the only one a process can set for itself. Both
 * exist, and they are set in different places because they are answered by different things.
 *
 * <p>The artwork itself is rasterised by {@code mainframe-dist/src/main/native/MakeIcon.java}, once per
 * redesign rather than once per build: regenerating it means going back to {@code vexelray-icons/svg}, not
 * editing the PNGs.
 */
public final class Mark {

    /** The sizes shipped. 16 and 32 are caption and switcher; 48 and 256 are the shell's larger views. */
    private static final int[] SIZES = {16, 32, 48, 256};

    /** Loaded once: decoding four small PNGs is cheap, but it is startup work and there is no reason to repeat it. */
    private static Icon mainframe;

    private Mark() {
    }

    /**
     * MainFrame's mark. Handed to {@code NativePlatform.setApplicationIcon} at boot, which is what makes every
     * window that did not choose one for itself — the console's own, and any app window opened without a mark —
     * wear it.
     *
     * @throws UncheckedIOException if the artwork is missing from the jar, which is a packaging fault and not a
     *                              condition worth writing a fallback for
     */
    public static synchronized Icon mainframe() {
        if (mainframe == null) {
            byte[][] images = new byte[SIZES.length][];
            for (int i = 0; i < SIZES.length; i++) {
                images[i] = read("mainframe-" + SIZES[i] + ".png");
            }
            mainframe = Icon.fromBytes(images);
        }
        return mainframe;
    }

    private static byte[] read(String name) {
        try (InputStream in = Mark.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("not on the classpath beside " + Mark.class.getName());
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read icon " + name, e);
        }
    }
}
