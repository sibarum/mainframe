package dev.mainframe;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.probe.Log;

import java.io.IOException;
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

    private static final Log LOG = Log.of("mainframe");

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

    /** The saved workspaces: a CSV file each, in the {@code workspaces} folder beside the settings. */
    @Provides
    WorkspaceStore workspaces() {
        return new WorkspaceStore(AppHome.of(MainFrame.APP).file("workspaces"));
    }

    /**
     * The tabs, each a shell. Built empty: the shells start in {@link #start}. Its {@code tick} is the frame's
     * application stage, and it is {@code AutoCloseable}, so the framework ends every shell at shutdown.
     */
    @Provides
    Terminals terminals(Gui gui, KronoGui krono, AppSettings settings, Dialogs dialogs, Windows windows) {
        return new Terminals(gui, new Motion(krono), settings, dialogs, windows.main());
    }

    /**
     * The settings window. One declaration: the framework builds its tree now, beside the main one, and claims the
     * window once there is a device, so this needs no window. Its handle is safe from any thread.
     */
    @Provides
    SettingsWindow settingsWindow(Windows windows, AppSettings settings, WorkspaceStore workspaces,
                                  Dialogs dialogs, KronoGui krono) {
        return new SettingsWindow(windows.named(SettingsWindow.DECLARED), settings, workspaces, dialogs,
                new Motion(krono));
    }

    /**
     * What Ctrl+O opens: the Open dialog, or with no workspaces to choose from, straight to the folder picker. A
     * workspace chosen there opens in a MainFrame of its own ({@link SelfLaunch}), or here.
     */
    @Provides
    OpenDialog openDialog(Windows windows, WorkspaceStore workspaces, Terminals terminals,
                          SettingsWindow settings, Dialogs dialogs) {
        Runnable pickFolder = () -> dialogs.openFolder(Path.of(System.getProperty("user.home")),
                terminals::openFolder, terminals::focus);
        OpenDialog open = new OpenDialog(windows.named(OpenDialog.DECLARED), workspaces, new OpenDialog.Actions() {
            @Override
            public void openFolder() {
                pickFolder.run();
            }

            @Override
            public void openWindow(Workspace w) {
                try {
                    SelfLaunch.start(List.of("--workspace=" + w.name()));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("could not start a MainFrame for {}", w.name(), e);
                    dialogs.info("Could not open " + w.name(), "A new MainFrame did not start: " + e.getMessage());
                }
            }

            @Override
            public void openHere(Workspace w) {
                List<Terminals.Opening> tabs = Startup.openings(w);
                if (tabs.isEmpty()) {
                    terminals.focus();   // no folder of it is there: nothing to open, rather than a plain tab
                } else {
                    terminals.openAll(tabs);
                }
            }

            @Override
            public void edit(String name) {
                settings.showWorkspace(name);
            }

            @Override
            public void create() {
                settings.newWorkspace();
            }
        });
        terminals.onOpen(() -> {
            if (workspaces.names().isEmpty()) {
                pickFolder.run();
            } else {
                open.show();
            }
        });
        return open;
    }

    /** The main window's contents. */
    @Provides
    Ui ui(Gui gui, TitleBar titleBar, Terminals terminals, SettingsWindow settings) {
        return new Ui(gui, titleBar, terminals, settings);
    }

    /**
     * Connect the tabs to the frame loop's wake, and open the first tab. Takes the {@code Shell} to be last: a wake
     * can only be connected once there is a loop to wake, and a shell is a process a headless test must not start.
     *
     * <p>The tabs are the command line's ({@link Startup}), or one plain tab; a command line after the options
     * replaces the shell for this run, and {@code --insert-path} types into the first tab. The command line is read
     * from {@link Command#launched}, not taken as a parameter: it is a value, and the framework hands a recipe parts.
     */
    @Provides
    Started start(Shell shell, Terminals terminals, AppSettings settings, Dialogs dialogs,
                  WorkspaceStore workspaces) {
        shell.wake(terminals);
        Command command = Command.launched();
        String line = command.shellLine();
        if (line != null) {
            settings.overrideShell(line);
        }
        List<Terminals.Opening> openings = Startup.openings(command, workspaces);
        terminals.openAll(openings);
        TerminalView first = terminals.current();
        if (first != null && command.insertPath() != null) {
            // Which shell the tab runs, as far as quoting goes: none MainFrame can vouch for if the command line chose it.
            InsertPath.Dialect dialect = line != null ? null : InsertPath.Dialect.of(settings.shell().id()).orElse(null);
            Startup.insert(first, command.insertPath(), openings, dialect);
        }
        command.notes().forEach(note -> LOG.warn("{}", note));
        return new Started();
    }

    /** That {@link #start} has run. A provider has to return something that is neither {@code void} nor a record. */
    static final class Started {
    }
}
