package dev.mainframe;

import java.util.ArrayList;
import java.util.List;

/**
 * What MainFrame's command line asked for, beyond what the framework reads: which tabs to open, a file name to type,
 * and a command line to run instead of the shell.
 *
 * <pre>
 * mainframe [options] [command line]
 *
 *   --dir=&lt;folder&gt;          a tab whose shell starts there; repeatable
 *   --dirs=&lt;file&gt;           a tab per line of the file
 *   --workspace=&lt;name&gt;      the tabs of that saved workspace, named as it names them
 *   --insert-path=&lt;file&gt;    type that file's name on the first tab's input line (see {@link InsertPath})
 *   --key=value             anything else is the framework's: a setting, --automation, --log
 *   command line            the first argument that is not an option, and everything after it, runs in place of
 *                           the shell, for this run only
 * </pre>
 *
 * <h2>Why MainFrame parses before the framework does</h2>
 *
 * <p>The framework's grammar is {@code --key=value} overrides, each key once, and a bare number as a frame count. This
 * one has a repeatable option and a trailing command line that is somebody else's grammar entirely: {@code pwsh
 * -NoProfile} would be refused as an unknown option, and {@code python 3.py} has a number in it. So {@link #parse}
 * takes MainFrame's part out, and hands the framework only the options that are not MainFrame's, which it accepts or
 * refuses by name as it would anyway. An option this build does not know is still refused rather than run as a
 * program.
 *
 * <p>The parse reaches the wiring through {@link #launched}, set once by {@code main}: a recipe is handed what the
 * framework built and nothing else, so a value made before the framework runs has nowhere else to go
 * ({@code docs/framework-notes.md}, FN-1).
 *
 * @param sources    where tabs come from, in the order given
 * @param insertPath the file named by {@code --insert-path}, or null
 * @param shell      the command line to run instead of the shell, split as given; empty for the shell in Settings
 * @param framework  the arguments the framework is to parse
 * @param notes      what was given and is ignored, said once the window is up
 */
record Command(List<Source> sources, String insertPath, List<String> shell, List<String> framework,
               List<String> notes) {

    /** Where tabs come from. */
    enum Kind {
        /** {@code --dir}: one folder. */
        DIR,
        /** {@code --dirs}: a file listing folders. */
        DIRS,
        /** {@code --workspace}: a saved workspace, by name. */
        WORKSPACE
    }

    /** One {@code --dir}, {@code --dirs} or {@code --workspace}, unresolved: nothing is looked at on disk here. */
    record Source(Kind kind, String value) {
    }

    static final Command NONE = new Command(List.of(), null, List.of(), List.of(), List.of());

    private static volatile Command launched = NONE;

    Command {
        sources = List.copyOf(sources);
        shell = List.copyOf(shell);
        framework = List.copyOf(framework);
        notes = List.copyOf(notes);
    }

    /** What this process was launched with; {@link #NONE} for a tree built by a test. */
    static Command launched() {
        return launched;
    }

    /** Set by {@code main}, once, before the framework runs. */
    static void launched(Command command) {
        launched = command;
    }

    /** {@code args} split into MainFrame's part and the framework's. Never throws: a bad option is the framework's. */
    static Command parse(String[] args) {
        List<Source> sources = new ArrayList<>();
        List<String> shell = new ArrayList<>();
        List<String> framework = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String insertPath = null;
        for (String a : args) {
            if (a == null || a.isBlank()) {
                continue;   // an empty variable expanded by a script has never meant anything
            }
            if (!shell.isEmpty()) {
                shell.add(a);
            } else if (a.startsWith("--dir=")) {
                sources.add(new Source(Kind.DIR, a.substring("--dir=".length())));
            } else if (a.startsWith("--dirs=")) {
                sources.add(new Source(Kind.DIRS, a.substring("--dirs=".length())));
            } else if (a.startsWith("--workspace=")) {
                sources.add(new Source(Kind.WORKSPACE, a.substring("--workspace=".length())));
            } else if (a.startsWith("--insert-path=")) {
                if (insertPath == null) {
                    insertPath = a.substring("--insert-path=".length());
                } else {
                    notes.add("--insert-path was given twice; only the first is used.");
                }
            } else if (a.startsWith("-")) {
                framework.add(a);
            } else {
                shell.add(a);
            }
        }
        return new Command(sources, insertPath, shell, framework, notes);
    }

    /** The command line to run in place of the shell, or null for the one chosen in Settings. */
    String shellLine() {
        return shell.isEmpty() ? null : String.join(" ", shell);
    }
}
