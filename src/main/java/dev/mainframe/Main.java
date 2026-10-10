package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.nfd.SaveScreenshot;
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
        // Leading options, then the rest is a shell:
        //   --automation[=off|on|<port>] (or -Dautomation) opens the loopback socket ottermate drives;
        //   --dir=<path>, repeatable, opens a tab starting in that directory;
        //   --dirs=<file> opens one such tab per line of the file.
        String automation = System.getProperty("automation", "off");
        List<Path> dirs = new ArrayList<>();
        int used = 0;
        for (; used < args.length; used++) {
            String a = args[used];
            if (a.startsWith("--automation")) {
                int eq = a.indexOf('=');
                automation = eq < 0 ? "on" : a.substring(eq + 1);
            } else if (a.startsWith("--dir=")) {
                addDir(dirs, a.substring("--dir=".length()), Path.of(""));
            } else if (a.startsWith("--dirs=")) {
                readDirs(dirs, Path.of(a.substring("--dirs=".length())));
            } else {
                break;
            }
        }
        args = java.util.Arrays.copyOfRange(args, used, args.length);
        AppSettings settings = new AppSettings(dev.vexelray.gui.core.app.Settings.open("mainframe"));
        if (args.length > 0) settings.overrideShell(String.join(" ", args));

        Gui gui = new Gui();
        // The window is closed by asking it to, which needs the window; the tabs are built first, so the last
        // tab closing goes through this cell.
        java.util.concurrent.atomic.AtomicReference<Runnable> quit = new java.util.concurrent.atomic.AtomicReference<>(() -> { });
        KronoGui krono = KronoGui.attach(gui);
        java.util.concurrent.atomic.AtomicReference<SettingsPanel> menu = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalTabs view = new TerminalTabs(gui, krono, settings, dirs, () -> menu.get().toggle(), () -> quit.get().run());
        menu.set(new SettingsPanel(gui, settings, view::focus));
        // The window's own title bar; it gets the window and the mark once the window exists.
        TitleBar bar = new TitleBar(gui, WindowControls.NONE, "MainFrame");
        Icon mark = mark();
        // Tabs fill what the docked settings panel leaves, under the title bar.
        gui.root().children(gui.column().width(Length.FILL).height(Length.FILL).children(
                bar.node(),
                gui.row().width(Length.FILL).height(Length.grow(1))
                        .children(view.node().width(Length.grow(1)), menu.get().node())));

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
     * One directory per line; blank lines and lines starting with # are skipped, and a relative path is taken
     * from the file's own directory, so a list can sit beside the projects it names.
     */
    private static void readDirs(List<Path> dirs, Path file) {
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
            if (!t.isEmpty() && !t.startsWith("#")) addDir(dirs, t, base);
        }
    }

    /**
     * A directory that is not there is reported and skipped rather than failing the launch, so one stale line does
     * not cost the other tabs. A leading ~ is the home directory.
     */
    private static void addDir(List<Path> dirs, String spec, Path base) {
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
        if (Files.isDirectory(dir)) dirs.add(dir);
        else System.err.println("mainframe: no such directory, skipped: " + dir);
    }
}
