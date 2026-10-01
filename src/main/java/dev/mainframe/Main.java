package dev.mainframe;

import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

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
        // --automation[=off|on|<port>] (or -Dautomation) opens the loopback socket ottermate drives; the rest is a shell.
        String automation = System.getProperty("automation", "off");
        if (args.length >= 1 && args[0].startsWith("--automation")) {
            int eq = args[0].indexOf('=');
            automation = eq < 0 ? "on" : args[0].substring(eq + 1);
            args = java.util.Arrays.copyOfRange(args, 1, args.length);
        }
        AppSettings settings = new AppSettings(dev.vexelray.gui.core.app.Settings.open("mainframe"));
        if (args.length > 0) settings.overrideShell(String.join(" ", args));

        Gui gui = new Gui();
        // The window is closed by asking it to, which needs the window; the tabs are built first, so the last
        // tab closing goes through this cell.
        java.util.concurrent.atomic.AtomicReference<Runnable> quit = new java.util.concurrent.atomic.AtomicReference<>(() -> { });
        KronoGui krono = KronoGui.attach(gui);
        java.util.concurrent.atomic.AtomicReference<SettingsPanel> menu = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalTabs view = new TerminalTabs(gui, krono, settings, () -> menu.get().toggle(), () -> quit.get().run());
        menu.set(new SettingsPanel(gui, settings, view::focus));
        // Tabs fill what the docked settings panel leaves.
        gui.root().children(gui.row().width(dev.vexelray.gui.core.layout.Length.FILL)
                .height(dev.vexelray.gui.core.layout.Length.FILL)
                .children(view.node().width(dev.vexelray.gui.core.layout.Length.grow(1)), menu.get().node()));

        AutomationServer server = null;
        try (Tactroller input = Tactroller.open();
             GuiApp app = new GuiApp(WindowConfig.of("MainFrame", 1100, 680).decorations(Decorations.SYSTEM));
             Clipboard clip = Clipboard.open()) {
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
            server = openAutomation(automation, gui, app, krono);
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
     * The driving socket, if this launch asked for one: {@code off}, {@code on} for the default port, or a port
     * number (0 is a free one). The line it prints is protocol: {@code ottermate --launch} reads the port from it.
     * A socket that cannot bind leaves the window running undriven.
     */
    private static AutomationServer openAutomation(String want, Gui gui, GuiApp app, KronoGui krono) {
        if (want.isBlank() || want.equals("off") || want.equals("false")) return null;
        try {
            int port = want.equals("on") || want.equals("true") ? AutomationServer.DEFAULT_PORT : Integer.parseInt(want);
            AutomationServer server = AutomationServer.start(
                    new Automation(gui, app.controls(), krono::quiescentAtLastTick), port);
            System.out.println("automation: localhost:" + server.port());
            return server;
        } catch (java.io.IOException | NumberFormatException e) {
            System.err.println("automation '" + want + "' bound nothing: " + e.getMessage());
            return null;
        }
    }
}

