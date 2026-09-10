package ${packageName};

import dev.vexelray.framework.shell.VexelApplication;

import java.io.IOException;

/**
 * ${summary}
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. That is all it is, and the reason is
 * worth knowing before anything else here: <b>the application edge is the framework's now.</b> Opening an
 * input backend and settling its coordinate space, installing a clipboard, remembering where the window was,
 * attaching a clock, wiring the frame loop with its wakes and its pacing, installing the dialogs, parsing the
 * command line and closing everything in the right order — this file used to be three hundred lines of exactly
 * that, near-identically to every other application on this stack.
 *
 * <p>What this application actually builds is in {@link ${className}Wiring}, one method per phase. Everything
 * above that is in {@link Ui}; everything the application <em>knows</em> is in {@link Model}. This class holds
 * no state of its own, and that is a rule worth keeping: the moment the edge starts remembering things, there
 * are two places a value can live.
 *
 * <pre>
 * ${className}                     the window, interactively
 * ${className} &lt;frames&gt;            run a fixed number of frames and quit (a script, not a session)
 * ${className} --key=value         override a setting for this launch
 * ${className} --capture out.png   headless PNG; see {@link Capture}
 * </pre>
 *
 * <p>A misspelled flag is refused by name with the alternatives listed, rather than a stack trace before any
 * window. Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class ${className} {

    /** The application's own name, which is what its settings directory is called. Stable across releases. */
    static final String APP = "${appName}";

    /** The window's title. */
    static final String TITLE = "${title}";

    /** Window size on a first run, in the engine's logical coordinates. */
    static final int W = ${width};
    static final int H = ${height};

    /**
     * The smallest this UI is still coherent at, in root ems — a floor, not the design size.
     *
     * <p>Named here rather than written at each use because it is read from two places that have to agree:
     * {@link ${className}Wiring#config} declares it to the framework, and a capture of the tree at exactly the
     * minimum is the picture that shows a panel outgrowing it.
     */
    static final float MIN_W_EM = 24;
    static final float MIN_H_EM = 16;

    /**
     * Entry point.
     *
     * <p>{@code --capture} is handled before the framework sees the arguments, deliberately: {@link Capture} is
     * an application-specific instrument with its own scenes and its own output paths, and routing it through
     * the framework would read the scene name as something else. An application with its own capture tooling
     * intercepts its own flag first.
     */
    public static void main(String[] args) throws IOException {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (cleaned.length >= 1 && cleaned[0].equals("--capture")) {
            Capture.run(cleaned);
            return;
        }
        VexelApplication.run(new ${className}Wiring(), cleaned);
    }

    private ${className}() {
    }
}
