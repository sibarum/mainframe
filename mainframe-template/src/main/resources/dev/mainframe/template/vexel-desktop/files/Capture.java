package ${packageName};

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.style.Role;

import java.io.IOException;

/**
 * A headless PNG of the window, with no window.
 *
 * <pre>
 * mvn compile exec:exec -Dapp.args="--capture out.png"
 * mvn compile exec:exec -Dapp.args="--capture out.png 1600 900"
 * </pre>
 *
 * <h2>The real tree, through the real wiring</h2>
 *
 * <p>{@code VexelApplication.tree} runs {@code CONFIG} through {@code TREE} and stops — no window, no input
 * backend and no window memory, so nothing reached from here can write a placement. <b>Build the tree by a
 * second route and this stops being a picture of this application:</b> a hand-assembled {@code Gui} misses
 * whatever the wiring says and the framework applies, starting with the theme and the zoom range, and it
 * misses it silently. The picture still looks plausible, which is what makes it worth avoiding rather than
 * worth debugging.
 *
 * <p>So a scene here is a lambda over a {@link Shell}, and the caller owns the shutdown because {@code tree}
 * hands one back rather than closing it. Add scenes by adding cases to {@link #run} — a named panel open, the
 * tree at exactly the minimum size, a ladder of zoom levels — and each one gets the same tree a user gets.
 *
 * <h2>What a capture can and cannot show</h2>
 *
 * <p>{@link GuiApp#capture} is {@code static} and builds its <b>own</b> Vulkan instance and device for the
 * occasion. Anything in the tree whose content comes from <em>this application's</em> device — a render
 * target, a storage buffer, a marched scene — is not on that device, and draws as the framework's placeholder
 * texture instead.
 *
 * <p>It does not fail. It produces a picture that is <b>correct about the chrome and silently wrong about the
 * content</b>, which is the failure mode worth naming here rather than discovering during a review. When this
 * application grows content of that kind, photograph it through the automation socket's {@code shot} against a
 * running window, and say so in the file name.
 *
 * <h2>Two frames, not one</h2>
 *
 * <p>{@code GuiApp.capture} already renders twice and it is worth knowing why, because anything derived from a
 * measured box has the same shape: the observer that reacts to a layout fires <em>inside</em> that layout, and
 * the mutation it posts is applied by the next drain. A still image wants the settled state rather than the
 * instant before it.
 */
final class Capture {

    /**
     * What the wiring is handed. Nothing: a still frame has nothing a setting override could change, and this
     * application's flags all describe a session.
     */
    private static final String[] NO_ARGS = new String[0];

    static void run(String[] args) throws IOException {
        String out = args.length >= 2 ? args[1] : "capture.png";
        int width = args.length >= 4 ? Integer.parseInt(args[2]) : ${className}.W;
        int height = args.length >= 4 ? Integer.parseInt(args[3]) : ${className}.H;

        on(shell -> {
            shoot(shell, width, height, out);
            System.out.println("wrote " + out + " (" + width + "x" + height + ")");
        });
    }

    /**
     * Photograph {@code shell}'s tree at {@code width} by {@code height}.
     *
     * <p>The clear colour is read off the theme the framework applied rather than from {@code Look} a second
     * time: two spellings of one colour is one of them being right and the other waiting to stop being.
     */
    private static void shoot(Shell shell, int width, int height, String out) throws IOException {
        Gui gui = shell.gui();
        Color page = gui.theme().color(Role.PAGE);
        GuiApp.capture(gui, width, height, page.r(), page.g(), page.b(), out);
    }

    /** Build this application as far as its tree, hand it to {@code scene}, and close it. */
    private static void on(Scene scene) throws IOException {
        Shell shell = VexelApplication.tree(new ${className}Wiring(), NO_ARGS);
        try {
            scene.shoot(shell);
        } finally {
            shell.disposer().close();
        }
    }

    /** One scene, against a tree that exists for the length of the call. */
    private interface Scene {

        void shoot(Shell shell) throws IOException;
    }

    private Capture() {
    }
}
