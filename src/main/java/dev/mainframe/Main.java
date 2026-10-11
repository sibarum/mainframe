package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.nfd.SaveScreenshot;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.Icon;
import dev.vexelray.os.WindowConfig;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MainFrame: a window around the native shell. v1 hosts Windows PowerShell; a command line given as arguments
 * replaces it.
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        args = dev.vexelray.os.NativePlatform.current().arguments(args);   // a native image decoded them as ANSI
        // Leading options, then the rest is a shell:
        //   --automation[=off|on|<port>] (or -Dautomation) opens the loopback socket ottermate drives;
        //   --dir=<path>, repeatable, opens a tab starting in that directory;
        //   --dirs=<file> opens one such tab per line of the file;
        //   --workspace=<name> opens the tabs of that saved workspace (see Workspace), named as it names them;
        //   --insert-path=<file> types that file's name, quoted, on the first tab's input line (see InsertPath).
        // Any other --option is refused rather than taken for the start of a shell's command line: an option this
        // build does not know would otherwise be run as a program.
        String automation = System.getProperty("automation", "off");
        List<TerminalTabs.Opening> dirs = new ArrayList<>();
        WorkspaceStore workspaces = new WorkspaceStore(dev.vexelray.gui.core.app.AppHome.of("mainframe").file("workspaces"));
        String insertPath = null;
        int used = 0;
        for (; used < args.length; used++) {
            String a = args[used];
            if (a.startsWith("--automation")) {
                int eq = a.indexOf('=');
                automation = eq < 0 ? "on" : a.substring(eq + 1);
            } else if (a.startsWith("--dir=")) {
                addDir(dirs, a.substring("--dir=".length()), Path.of(""), null);
            } else if (a.startsWith("--dirs=")) {
                readDirs(dirs, Path.of(a.substring("--dirs=".length())));
            } else if (a.startsWith("--workspace=")) {
                openWorkspace(dirs, workspaces, a.substring("--workspace=".length()));
            } else if (a.startsWith("--insert-path=")) {
                if (insertPath != null) {
                    System.err.println("mainframe: --insert-path given twice; only the first is used");
                } else {
                    insertPath = a.substring("--insert-path=".length());
                }
            } else if (a.startsWith("--")) {
                System.err.println("mainframe: unknown option " + a);
                System.exit(2);
            } else {
                break;
            }
        }
        args = java.util.Arrays.copyOfRange(args, used, args.length);
        AppSettings settings = new AppSettings(dev.vexelray.gui.core.app.Settings.open("mainframe"));
        if (args.length > 0) settings.overrideShell(String.join(" ", args));
        // Which shell the tabs run, as far as quoting goes: none MainFrame can vouch for when the command line chose it.
        InsertPath.Dialect dialect = args.length > 0 ? null : InsertPath.Dialect.of(settings.shell().id()).orElse(null);

        Gui gui = new Gui();
        // The window is closed by asking it to, which needs the window; the tabs are built first, so the last
        // tab closing goes through this cell.
        java.util.concurrent.atomic.AtomicReference<Runnable> quit = new java.util.concurrent.atomic.AtomicReference<>(() -> { });
        KronoGui krono = KronoGui.attach(gui);
        TerminalTabs view = new TerminalTabs(gui, krono, settings, dirs, () -> quit.get().run());
        SettingsWindow settingsWindow = new SettingsWindow(gui, settings, workspaces);
        if (insertPath != null) insert(view.current(), insertPath, dirs, dialect);
        // The window's own title bar; it gets the window and the mark once the window exists. The settings button
        // opens the settings window, and the keyboard stays with the terminal. Its icon is drawn once there is a window.
        TitleBar bar = new TitleBar(gui, WindowControls.NONE, "MainFrame");
        Icons icons = new Icons();
        bar.addLeading(SettingsWindow.settingsButton(gui, icons, () -> {
            settingsWindow.show();
            view.focus();
        }));
        Icon mark = mark();
        // Tabs fill the window under the title bar.
        gui.root().children(gui.column().width(Length.FILL).height(Length.FILL).children(
                bar.node(), view.node().width(Length.FILL).height(Length.grow(1))));

        AutoCloseable server = null;
        try (Tactroller input = Tactroller.open();
             GuiApp app = new GuiApp(WindowConfig.of("MainFrame", 1100, 680).decorations(Decorations.CLIENT)
                     .icon(mark));
             Clipboard clip = Clipboard.open()) {
            // The window exists now: point the bar at it, and give it the framework applications' screenshot button,
            // which needs real controls to photograph anything.
            bar.controls(app.controls()).icon(app, mark).instruments(List.of(SaveScreenshot.instrument()));
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
            // Windows the framework opens, the settings window among them, get input of their own.
            app.input(Main::windowInput);
            Modals.install(app, dialog -> dialog.theme(gui.theme()));
            settingsWindow.register(app, mark);
            icons.bind(app);
            gui.clipboard(new TextClipboard() {
                @Override public String get() {
                    try { return clip.getText().orElse(""); } catch (ClipboardException e) { return ""; }
                }
                @Override public void set(String text) {
                    try { clip.setText(text); } catch (ClipboardException e) { /* a dropped copy */ }
                }
            });
            quit.set(() -> app.window().requestClose());
            view.focus();
            // The clock goes with the Gui so settle waits out a tab animation, not only the frame loop.
            server = Driving.open(automation, gui, app, krono);
            TactrollerInputBridge bridge = new TactrollerInputBridge(input, gui.bus());
            app.run(gui, 0, () -> {
                try {
                    bridge.pump();
                } catch (BackendException e) {
                    // a transient poll failure drops one frame of input
                }
                krono.tick();
                view.tick();
            });
        } finally {
            if (server != null) server.close();
            view.close();
        }
        krono.close();
        gui.close();
    }

    /**
     * Input for a window the framework opened: its own backend, attached to that window and bridged onto that
     * window's bus, which the frame loop pumps and closes with the window. A window that cannot have one still
     * opens; it just hears nothing.
     */
    private static WindowInput windowInput(dev.vexelray.os.NativeWindow window, Gui windowGui) {
        Tactroller backend;
        try {
            backend = Tactroller.open();
            backend.attach(NativeWindow.ofHwnd(window.osHandle()));
            backend.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.err.println("mainframe: no input for a new window: " + e.getMessage());
            return WindowInput.NONE;
        }
        TactrollerInputBridge bridge = new TactrollerInputBridge(backend, windowGui.bus());
        return new WindowInput() {
            @Override public void pump() {
                try {
                    bridge.pump();
                } catch (BackendException e) {
                    // a transient poll failure drops one frame of input
                }
            }
            @Override public void close() {
                backend.close();
            }
        };
    }

    /**
     * The mark the window wears in its title bar and, run from a jar, on the taskbar, where a native build already
     * wears it from its own resources. Null, and said, if it cannot be read: the window is still a window.
     */
    private static Icon mark() {
        try (InputStream in = Main.class.getResourceAsStream("/mainframe.ico")) {
            if (in == null) {
                System.err.println("mainframe: no mainframe.ico on the class path; the window goes without a mark");
                return null;
            }
            return Icon.fromIco(in.readAllBytes());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("mainframe: cannot read mainframe.ico: " + e.getMessage());
            return null;
        }
    }

    /**
     * Put {@code spec}'s name on {@code tab}'s input line, or say on the tab why not. Only a file that exists, named by
     * an absolute path, and sits directly in the first {@code --dir} (which is where the tab's shell starts) is
     * considered at all; then {@link InsertPath} decides what, if anything, may be typed for it.
     */
    private static void insert(TerminalView tab, String spec, List<TerminalTabs.Opening> dirs, InsertPath.Dialect dialect) {
        if (tab == null) return;
        Path file;
        try {
            file = Path.of(spec);
        } catch (InvalidPathException e) {
            tab.notice("--insert-path is not a path, so nothing was typed.");
            return;
        }
        if (!file.isAbsolute() || !file.normalize().equals(file) || !Files.isRegularFile(file)
                || file.getFileName() == null) {
            tab.notice("--insert-path does not name a file by its full path, so nothing was typed.");
            return;
        }
        if (dirs.isEmpty() || !dirs.get(0).dir().equals(file.getParent())) {
            tab.notice("--insert-path names a file outside this tab's folder, so nothing was typed.");
            return;
        }
        InsertPath.Outcome o = InsertPath.decide(dialect, file.getFileName().toString(),
                () -> dev.mainframe.pty.ShortName.of(file));
        if (o.typesSomething()) tab.typeWhenReady(o.text());
        if (o.notice() != null) tab.notice(o.notice());
    }

    /**
     * One directory per line; blank lines and lines starting with # are skipped, and a relative path is taken
     * from the file's own directory, so a list can sit beside the projects it names.
     */
    private static void readDirs(List<TerminalTabs.Opening> dirs, Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            System.err.println("mainframe: cannot read directory list " + file + ": " + e.getMessage());
            return;
        }
        Path base = file.toAbsolutePath().getParent();
        for (String line : lines) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) addDir(dirs, t, base, null);
        }
    }

    /**
     * The saved workspace called {@code name}, one tab per folder in its order, each named as the workspace names it.
     * A workspace that is not there is said and opens nothing, so the launch still gets its plain tab.
     */
    private static void openWorkspace(List<TerminalTabs.Opening> dirs, WorkspaceStore store, String name) {
        Workspace w = store.load(name).orElse(null);
        if (w == null) {
            System.err.println("mainframe: no workspace called " + name + " in " + store.dir());
            return;
        }
        for (Workspace.Entry e : w.entries()) addDir(dirs, e.path(), Path.of(""), e.title());
    }

    /**
     * A directory that is not there is reported and skipped rather than failing the launch, so one stale line does
     * not cost the other tabs. A leading ~ is the home directory.
     */
    private static void addDir(List<TerminalTabs.Opening> dirs, String spec, Path base, String title) {
        if (spec.length() >= 2 && spec.startsWith("\"") && spec.endsWith("\"")) spec = spec.substring(1, spec.length() - 1);
        if (spec.equals("~") || spec.startsWith("~/") || spec.startsWith("~\\")) {
            spec = System.getProperty("user.home") + spec.substring(1);
        }
        Path dir;
        try {
            dir = base.resolve(spec).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            System.err.println("mainframe: not a path, skipped: " + spec);
            return;
        }
        if (Files.isDirectory(dir)) dirs.add(new TerminalTabs.Opening(dir, title));
        else System.err.println("mainframe: no such directory, skipped: " + dir);
    }
}
