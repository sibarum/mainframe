package dev.mainframe;

import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.os.NativePlatform;

/**
 * MainFrame: a terminal window around the native shell. v1 hosts Windows PowerShell on a pseudoconsole (ConPTY).
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. <b>The application edge is the
 * framework's:</b> opening input, the clipboard, remembering where the window was, the clock, the frame loop with its
 * wakes and its pacing, the dialogs, the command line and closing everything in order. What MainFrame is made of is
 * in {@link Recipes}, one method per part; {@code MainFrameAppWiring}, which builds those parts in order, is generated
 * from them and from the {@code @VexelApp} on {@code MainFrameApp} while the project compiles.
 *
 * <p>The command line is MainFrame's own before it is the framework's: see {@link Command}.
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><b>Two editions declare the application.</b> {@code MainFrameApp} is in {@code src/edition-debug} and
 * {@code src/edition-release}, and the pom compiles one of them. The debug edition's names {@code AutomationStarter},
 * the driving socket; the release edition names none, and {@code -Pnative-release} drops the automation modules from
 * the class path, so the shipped binary links no socket at all.
 */
public final class MainFrame {

    /** The application's own name, which is what its settings directory is called. Stable across releases. */
    static final String APP = "mainframe";

    /** The window's title. */
    static final String TITLE = "MainFrame";

    /** Window size on a first run, in the engine's logical coordinates. */
    static final int W = 1100;
    static final int H = 700;

    /**
     * The mark, from the class path's root: {@code src/main/rc/mainframe.ico}, which the pom also puts there and the
     * executable links, so the window, its taskbar button and the {@code .exe} wear one file.
     */
    static final String ICON = "/mainframe.ico";

    /** The smallest this UI is still coherent at, in root ems: a few tab headers, and a terminal still worth typing in. */
    static final float MIN_W_EM = 30;
    static final float MIN_H_EM = 12;

    /**
     * Entry point: MainFrame's part of the command line is taken out first, and the rest is the framework's.
     * Screenshots are {@code ottermate}'s; see the README.
     */
    public static void main(String[] args) {
        Command command = Command.parse(osArguments(args));
        Command.launched(command);
        VexelApplication.run(new MainFrameAppWiring(), command.framework().toArray(String[]::new));
    }

    /**
     * The arguments as the OS holds them: a native image is handed them decoded as ANSI, which loses any character
     * outside the code page, and a folder named in one would not be found. The framework does this too, but only to
     * what it is handed, which is after this has read the folders.
     */
    private static String[] osArguments(String[] args) {
        try {
            return NativePlatform.current().arguments(args);
        } catch (RuntimeException e) {
            return args;
        }
    }

    private MainFrame() {
    }
}
