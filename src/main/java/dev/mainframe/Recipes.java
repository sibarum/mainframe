package dev.mainframe;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;

import java.nio.file.Path;
import java.util.List;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code MainFrameAppWiring} is written by {@code vexelray-framework-processor} while this project compiles, and
 * lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the look and the settings
 * exist before the {@code Gui} does; the tabs and the window's contents wait for the {@code Gui}; and the start, which
 * takes the {@code Shell}, comes last, when the window and the frame loop exist. Open the generated file after a
 * build to see the order.
 *
 * <p>No shell is started before {@link #start}. A tree built headless for a test ({@code VexelApplication.tree})
 * stops short of it, so a test gets the real window contents and no processes.
 *
 * <p>The driving socket is not here. It comes from {@code AutomationStarter}, which the debug edition of
 * {@code MainFrameApp} names in its {@code @VexelApp} (see {@link MainFrame}).
 */
@Configuration
final class Recipes {

    /** The look, and the smallest window this UI is still coherent in. Applied before the first widget. */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(MainFrame.MIN_W_EM), Length.em(MainFrame.MIN_H_EM));
    }

    /** What the user can change, over the framework's one settings store. */
    @Provides
    AppSettings appSettings(Settings store) {
        return new AppSettings(store);
    }

    /**
     * The tabs, each a shell. Built empty: the shells start in {@link #start}. Its {@code tick} is the frame's
     * application stage, and it is {@code AutoCloseable}, so the framework ends every shell at shutdown.
     */
    @Provides
    Terminals terminals(Gui gui, KronoGui krono, AppSettings settings, Dialogs dialogs, Windows windows) {
        return new Terminals(gui, new Motion(krono), settings, dialogs, windows.main());
    }

    /** The main window's contents. */
    @Provides
    Ui ui(Gui gui, TitleBar titleBar, Terminals terminals) {
        return new Ui(gui, titleBar, terminals);
    }

    /**
     * Connect the tabs to the frame loop's wake, and open the first tab. Takes the {@code Shell} to be last: a wake
     * can only be connected once there is a loop to wake, and a shell is a process a headless test must not start.
     *
     * <p>A command line after the options replaces the shell for this run.
     */
    @Provides
    Started start(Shell shell, Terminals terminals, AppSettings settings, Dialogs dialogs, Launch launch) {
        shell.wake(terminals);
        if (!launch.rest().isEmpty()) {
            settings.overrideShell(String.join(" ", launch.rest()));
        }
        terminals.onOpen(() -> dialogs.openFolder(Path.of(System.getProperty("user.home")),
                terminals::openFolder, terminals::focus));
        terminals.openAll(List.of());
        return new Started();
    }

    /** That {@link #start} has run. A provider has to return something that is neither {@code void} nor a record. */
    static final class Started {
    }
}
