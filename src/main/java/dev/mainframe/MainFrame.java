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
 * <p>What this application builds — a text editor with tabs and a file navigator — is in {@link Recipes}, one
 * method per part. {@code MainFrameAppWiring}, which builds those parts in order, is generated from them and from
 * the {@code @VexelApp} on {@code MainFrameApp} while the project compiles — so the facts below are stated once,
 * here, and the wiring reads them.
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
 * <p><b>Two editions declare the application.</b> {@code MainFrameApp} is in {@code src/edition-debug} and
 * {@code src/edition-release}, and the pom compiles one of them. Its {@code starters} is everything configuring this
 * application beyond {@link Recipes}, listed rather than discovered. The debug edition's names
 * {@code AutomationStarter}, the driving socket; the release edition names none, and {@code -Pnative-release} drops
 * the automation modules from the class path, so the shipped binary links no socket at all. Everything else — a
 * plain build, {@code exec:exec}, the tests, {@code -Pnative} — is the debug edition.
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
