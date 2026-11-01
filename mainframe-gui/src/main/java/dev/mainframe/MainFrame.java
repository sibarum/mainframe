package dev.mainframe;

import dev.vexelray.framework.shell.VexelApplication;


/**
 * A terminal window that wraps the native OS shell.
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. That is all it is, and the reason is
 * worth knowing before anything else here: <b>the application edge is the framework's.</b> Opening an input
 * backend, installing a clipboard, remembering where the window was, attaching a clock, wiring the frame loop with
 * its wakes and its pacing, installing the dialogs, parsing the command line and closing everything in the right
 * order are all done for you.
 *
 * <p>What this application builds — a text editor with tabs and a file navigator — is in two modules. This one,
 * {@code -gui}, is the view: {@link Recipes}, one method per part, and the {@link View}, which shows what the editor
 * decides. {@code -core} is the controller: the editor, which decides, and the jobs that touch the disk. They talk by
 * messages and nothing else ({@code Messages}, in {@code -core}). {@code MainFrameAppWiring}, which builds all of it
 * in order, is generated from them and from the {@code @VexelApp} on {@code MainFrameApp} while this module
 * compiles — so the facts below are stated once, here, and the wiring reads them.
 *
 * <pre>
 * MainFrame                     the editor, on the folder it was last showing (or the working directory)
 * MainFrame &lt;path&gt;...          open these: a folder becomes the navigator's root, a file a tab
 * MainFrame &lt;frames&gt;            run a fixed number of frames and quit (a script, not a session)
 * MainFrame --key=value         override a setting for this launch
 * </pre>
 *
 * <p>A misspelled flag is refused by name with the alternatives listed, rather than a stack trace before any
 * window. Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><b>{@code MainFrameApp} declares the application.</b> Its {@code starters} is everything configuring this
 * application beyond this module, listed rather than discovered: {@code Core}, which lists {@code -core}'s parts, and
 * {@code AutomationStarter}, the automation sockets, in every build, the release one included — off until the launch
 * or the title bar's plug switches them on.
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
     * The mark, from the class path's root: {@code src/main/rc/mainframe.ico}, which the pom also puts there and
     * the executable links. It starts as the framework's own; replace the file, and the window, its taskbar button
     * and the {@code .exe} all change together.
     */
    static final String ICON = "/mainframe.ico";

    /**
     * The smallest this UI is still coherent at, in root ems — a floor, not the design size: a navigator and a
     * document side by side, each still wide enough to read.
     *
     * <p>Named here rather than written at each use because it is read from two places that have to agree:
     * {@link Recipes#look} declares it to the framework, and a screenshot at exactly the minimum
     * ({@code ottermate --size 36emx20em shot}) is the picture that shows a panel outgrowing it.
     */
    static final float MIN_W_EM = 36;
    static final float MIN_H_EM = 20;

    /**
     * Entry point.
     *
     * <p>There is no screenshot flag here on purpose. A picture of a running window, at a chosen size, zoom and
     * density, is {@code ottermate}'s, because it photographs the application's own device and so is correct
     * about content as well as chrome. See the README, <i>Taking a screenshot</i>.
     */
    public static void main(String[] args) {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        VexelApplication.run(new MainFrameAppWiring(), cleaned);
    }

    private MainFrame() {
    }
}
