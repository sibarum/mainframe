package ${packageName};

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;

import java.io.IOException;

/**
 * A headless PNG of the window, with no window.
 *
 * <pre>
 * mvn compile exec:exec -Dapp.args="--capture out.png"
 * mvn compile exec:exec -Dapp.args="--capture out.png 1600 900"
 * </pre>
 *
 * <h2>What a capture can and cannot show</h2>
 *
 * <p>{@link GuiApp#capture} is {@code static} and builds its <b>own</b> Vulkan instance and device for the
 * occasion. Anything in the tree whose content comes from <em>this application's</em> device -- a render
 * target, a storage buffer, a marched scene -- is not on that device, and draws as the framework's placeholder
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

    static void run(String[] args) throws IOException {
        String out = args.length >= 2 ? args[1] : "capture.png";
        int width = args.length >= 4 ? Integer.parseInt(args[2]) : ${className}.W;
        int height = args.length >= 4 ? Integer.parseInt(args[3]) : ${className}.H;

        Gui gui = new Gui();
        gui.theme(Look.THEME);
        gui.minSize(Length.em(24), Length.em(16));

        // The same clock and the same tree the application builds, so a capture is a photograph of this
        // application rather than of a second arrangement of it that has to be kept in step.
        KronoGui krono = KronoGui.attach(gui);
        Model model = new Model();
        Ui ui = new Ui(gui, model);
        ui.show(model.doc());

        Color page = ${className}.page();
        GuiApp.capture(gui, width, height, page.r(), page.g(), page.b(), out);
        System.out.println("wrote " + out + " (" + width + "x" + height + ")");

        krono.close();
        gui.close();
    }

    private Capture() {
    }
}
