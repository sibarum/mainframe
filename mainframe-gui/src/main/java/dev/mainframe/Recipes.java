package dev.mainframe;

import dev.mainframe.core.AppSettings;
import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.Begin;
import dev.mainframe.core.WorkspaceStore;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.keymap.KeymapWindow;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

/**
 * What this module builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code MainFrameAppWiring} is written by {@code vexelray-framework-processor} while this module compiles, and
 * lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the look and the settings
 * exist before the {@code Gui} does; the tabs and the windows' contents wait for the {@code Gui}; and the start, which
 * takes the {@code Shell}, comes last, when the window and the frame loop exist. Open the generated file after a
 * build to see the order.
 *
 * <h2>Where the rest comes from</h2>
 *
 * <p>Not every part is a recipe. The {@link View} and the {@link SettingsView} are {@code @Component}s: a lane each,
 * built from their one constructor, reached by messages, as is {@link OpenInIde}, a job. The {@code Frame} and the
 * jobs that touch the disk are in {@code -core}, and reach the wiring through {@code Core}, which {@code MainFrameApp}
 * names among its starters beside {@code AutomationStarter}, the automation sockets. The two services both modules
 * read, {@link AppSettings} and the {@link WorkspaceStore}, are built here: to this module {@code -core}'s classes are
 * somebody else's, which a recipe may return as they are.
 *
 * <p>No shell is started before {@link #start}. A tree built headless for a test ({@code VexelApplication.tree})
 * stops short of it, so a test gets the real window contents and no processes.
 */
@Configuration
final class Recipes {

    /** The look, and the smallest window this UI is still coherent in. Applied before the first widget. */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(MainFrame.MIN_W_EM), Length.rem(MainFrame.MIN_H_EM));
    }

    /** What the user can change, over the framework's one settings store. */
    @Provides
    AppSettings appSettings(Settings store) {
        return new AppSettings(store);
    }

    /**
     * The saved workspaces: a CSV file each, in the {@code workspaces} folder beside the settings, with their
     * environment scripts. A value, the folder and nothing else, which every job that touches them holds.
     */
    @Provides
    WorkspaceStore workspaces() {
        return new WorkspaceStore(AppHome.of(MainFrame.APP).file("workspaces"));
    }

    /**
     * The tabs, each a shell. Built empty: the frame opens them once the window exists. Its {@code tick} is the
     * frame's application stage, and it is {@code AutoCloseable}, so the framework ends every shell at shutdown.
     */
    @Provides
    Terminals terminals(Gui gui, KronoGui krono, AppSettings settings, Keymap keys, Atchung bus) {
        Terminals terminals = new Terminals(gui, new Motion(krono), settings, keys, bus);
        if (settings.takeRemapOff()) {
            Terminals.handShellKeysBack(keys);
        }
        return terminals;
    }

    /**
     * Every command, by name, on the chords the user gives it ({@link Terminals} declares them). What the user
     * changes is kept in a store of its own ({@code keys}), beside the settings, so neither writes back over the
     * other.
     */
    @Provides
    Keymap keys(Gui gui) {
        return new Keymap(gui).persist(AppHome.of(MainFrame.APP).settings("keys"));
    }

    /**
     * The settings window. One declaration: the framework builds its tree now, beside the main one, and claims the
     * window once there is a device, so this needs no window. Its handle is safe from any thread.
     */
    @Provides
    SettingsWindow settingsWindow(Windows windows, AppSettings settings, WorkspaceStore workspaces,
                                  Dialogs dialogs, KronoGui krono, Atchung bus) {
        return new SettingsWindow(windows.named(SettingsWindow.DECLARED), settings, workspaces, dialogs,
                new Motion(krono), bus);
    }

    /** What Ctrl+O opens when there are workspaces: a tile each, which the frame lists and the view hands it. */
    @Provides
    OpenDialog openDialog(Windows windows, Atchung bus) {
        return new OpenDialog(windows.named(OpenDialog.DECLARED), bus);
    }

    /** The main window's contents. */
    @Provides
    Ui ui(Gui gui, TitleBar titleBar, Terminals terminals, SettingsWindow settings) {
        return new Ui(gui, titleBar, terminals, settings);
    }

    /**
     * Connect the tabs to the frame loop's wake, put the key-bindings button in the title bar, and tell the frame to
     * open the first tabs. Takes the {@code Shell} to be last: a wake can only be connected once there is a loop to
     * wake, and a shell is a process a headless test must not start.
     *
     * <p>Run for what it does, so it returns nothing. The message waits in the frame's mailbox until the framework
     * starts the components; which tabs it opens is the frame's to decide, from the command line
     * ({@code Command.launched}).
     */
    @Provides
    @Publishes(Messages.BEGIN)
    void start(Shell shell, Terminals terminals, Keymap keys, Atchung bus) {
        shell.wake(terminals);
        keyBindingsButton(shell, keys);
        bus.publish(Messages.BEGIN_TOPIC, new Begin());
    }

    /**
     * The key-bindings button in the title bar, after whatever the bar already shows: it opens the editor where every
     * command can be given other keys, or have one taken away so the shell gets it. Here, with the shell, because the
     * framework puts the look's tools in the bar once the window exists, and this has to come after; added to the
     * bar's strip rather than replacing it, so the automation button stays.
     */
    private static void keyBindingsButton(Shell shell, Keymap keys) {
        if (!shell.appearance().drawsOwnFrame()) {
            return;
        }
        List<WindowInstrument> tools = new ArrayList<>(shell.titleBar().instruments());
        tools.add(KeymapWindow.instrument(shell.app(), shell.gui(), keys));
        shell.titleBar().instruments(List.copyOf(tools));
    }
}
