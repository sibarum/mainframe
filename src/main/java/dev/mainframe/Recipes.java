package dev.mainframe;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code MainFrameAppWiring} is written by {@code vexelray-framework-processor} while this project compiles, and
 * lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the look, the model and
 * the session store exist before the {@code Gui} does; the window's contents wait for the {@code Gui}, its clock and
 * its title bar; and the restore, which takes the {@code Shell}, comes last, when the window and the dialogs exist.
 * There is no phase to declare and none to get wrong. Open the generated file after a build to see the order.
 *
 * <p>The driving socket is not here. It comes from {@code AutomationStarter}, which the debug edition of
 * {@code MainFrameApp} names in its {@code @VexelApp} (see {@link MainFrame}).
 *
 * <h2>What is not here is the point</h2>
 *
 * <p>Opening an input backend, installing a clipboard, remembering where the window was, attaching a clock, wiring
 * the frame loop with its wakes and its pacing, parsing the command line, installing the dialogs, and closing
 * everything in the right order are all {@code vexelray-framework}'s. So is closing the parts that are
 * {@code AutoCloseable}.
 *
 * <p>Adding a part is a method here. It returns an interface when other code should be able to swap it; this
 * package's own classes may be returned as they are, because nothing outside the package could hold a call site
 * against them anyway.
 */
@Configuration
final class Recipes {

    /**
     * The look, and the smallest window this UI is still coherent in. Built from nothing the framework makes, so it
     * exists in the first phase and the framework applies it the moment it creates the {@code Gui}: "the theme is
     * applied before the first widget" is structural rather than a comment somebody has to keep obeying.
     */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(MainFrame.MIN_W_EM), Length.em(MainFrame.MIN_H_EM));
    }

    /** The one authoritative state, built before the tree because every label is a view onto it. */
    @Provides
    Model model() {
        return new Model();
    }

    /** What is remembered between runs, over the framework's one settings store. */
    @Provides
    Session session(Settings settings, Model model) {
        return new Session(settings, model);
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window — so a test can build
     * the real tree headless ({@code VexelApplication.tree}), which {@code EditorTest} does.
     *
     * <p>The clock is for motion: {@link Motion} makes the ramps every widget here animates with. Every change to the
     * session redraws what is derived from it, on the committing thread — a worker, because every handler is — and
     * is remembered for next time.
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, Model model, TitleBar titleBar, Session session) {
        Ui ui = new Ui(gui, new Motion(krono), model, titleBar);
        model.onChange(doc -> {
            ui.show(doc);
            session.remember();
        });
        zoomShortcuts(gui);
        return ui;
    }

    /**
     * Every command, and the chords for them. Its questions go to the framework's {@code Dialogs}, which are there
     * from the first phase: asked before the window exists, a question waits for it rather than throwing.
     */
    @Provides
    Actions actions(Gui gui, Model model, Ui ui, Dialogs dialogs) {
        Actions actions = new Actions(gui, model, ui, dialogs);
        actions.shortcuts();
        return actions;
    }

    /**
     * Arm the close gate and bring back last time's session.
     *
     * <p>Takes the {@code Shell} to be last: restoring the session opens files and writes settings, which a tree built
     * headless for a test must not, and the {@code Shell} is the framework's last-phase value. The gate is the main
     * window's, and a window has one: a second is refused, because the one it would replace is as likely as not the
     * one that knew about the unsaved files.
     *
     * <p>Neither half produces anything, and a provider has to return something that is neither {@code void} nor a
     * record — hence {@link Started}, which marks that this ran and is otherwise empty.
     */
    @Provides
    Started start(Shell shell, Actions actions, Session session, Launch launch, Gui gui) {
        shell.windows().main().onClose(actions::guardClose);
        List<Path> paths = launch.rest().stream().map(Path::of).toList();
        session.restore(actions, paths, gui.offload());
        return new Started();
    }

    /** That {@link #start} has run. */
    static final class Started {
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three.
     *
     * <p>An application decision, which is why it is here: which chord zooms, or whether zooming exists at all, is not
     * something a framework should choose. <b>How far the zoom goes is</b>, and it is not stated here — that is
     * {@code Appearance.ZoomRange}, applied by the framework before the first widget, so every window on the desk
     * agrees about it.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }
}
