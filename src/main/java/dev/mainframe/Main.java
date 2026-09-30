package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
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

    private static final String DEFAULT_SHELL = "powershell.exe -NoLogo";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String capture = null;
        if (args.length >= 1 && args[0].equals("--capture")) {
            capture = args.length >= 2 ? args[1] : "mainframe.png";
            args = java.util.Arrays.copyOfRange(args, Math.min(args.length, 2), args.length);
        }
        String shell = args.length == 0 ? DEFAULT_SHELL : String.join(" ", args);

        Gui gui = new Gui();
        TerminalView view = new TerminalView(gui, shell);
        gui.root().children(view.node());

        if (capture != null) {
            // A headless still: let the shell draw its prompt, render once, write the PNG. No window, no input.
            Thread.sleep(2500);
            view.tick();
            GuiApp.capture(gui, 1100, 680, 0.047f, 0.047f, 0.047f, capture);
            view.close();
            gui.close();
            System.out.println("captured " + capture);
            return;
        }

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
            view.focus();
            TactrollerInputBridge bridge = new TactrollerInputBridge(input, gui.bus());
            app.run(gui, 0, () -> {
                try {
                    bridge.pump();
                } catch (BackendException e) {
                    // a transient poll failure drops one frame of input
                }
                view.tick();
                if (view.exited()) app.window().requestClose();
            });
        } finally {
            view.close();
        }
        gui.close();
    }
}

