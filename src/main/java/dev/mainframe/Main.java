package dev.mainframe;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.api.MainFrame;
import dev.mainframe.panel.StdioEditor;
import dev.mainframe.ui.Renderer;

/**
 * Command line entry point.
 *
 * <p>This is built on the same embedding API a host program would use, which is
 * the cheapest way to be sure that API can really carry a whole shell.
 */
public final class Main {

    private Main() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    static int run(String[] arguments) {
        boolean dryRun = false;
        boolean assumeYes = false;
        boolean panel = false;
        boolean color = Renderer.colorSupported();
        String command = null;
        Path script = null;
        List<String> leftovers = new ArrayList<>();

        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            switch (argument) {
                case "-c", "--command" -> {
                    if (i + 1 >= arguments.length) {
                        System.err.println("mainframe: -c needs some MainFrame code after it");
                        return 2;
                    }
                    command = arguments[++i];
                }
                case "--dry-run" -> dryRun = true;
                case "--yes" -> assumeYes = true;
                case "--no-color" -> color = false;
                case "--panel" -> panel = true;
                case "-h", "--help" -> {
                    usage(System.out);
                    return 0;
                }
                case "-v", "--version" -> {
                    System.out.println("mainframe " + MainFrame.version());
                    return 0;
                }
                default -> {
                    if (argument.startsWith("-")) {
                        System.err.println("mainframe: I do not know the option " + argument);
                        System.err.println("try: mainframe --help");
                        return 2;
                    }
                    if (script == null) script = Path.of(argument);
                    else leftovers.add(argument);
                }
            }
        }

        if (panel && command == null && script == null) {
            System.err.println("mainframe: --panel needs something to run, e.g. -c \"form [\\\"name\\\"]\"");
            System.err.println("the shell itself does not speak the panel protocol yet");
            return 2;
        }

        MainFrame.Builder building = MainFrame.builder()
                .directory(Path.of("").toAbsolutePath())
                .color(color)
                .dryRun(dryRun)
                .assumeYes(assumeYes)
                // With no console there is nobody to answer a confirmation, so
                // destructive commands should refuse rather than prompt into a pipe.
                .interactive(System.console() != null);

        StdioEditor editor = panel ? attach(building) : null;
        MainFrame shell = building.build();

        if (!leftovers.isEmpty()) {
            System.err.println("mainframe: ignoring extra arguments: " + String.join(", ", leftovers));
        }

        if (command != null) return finish(editor, shell.execute(command));

        if (script != null) {
            Path file = script.isAbsolute() ? script : Path.of("").toAbsolutePath().resolve(script);
            if (!Files.isRegularFile(file)) {
                System.err.println("mainframe: there is no script at " + file);
                System.err.println("check the path, or run mainframe with no arguments for a shell");
                return 1;
            }
            return finish(editor, shell.executeFile(file));
        }

        return shell.repl();
    }

    /**
     * Hands the standard streams over to the panel protocol.
     *
     * <p>In panel mode there is no ordinary output: what the renderer writes
     * leaves as {@code print} messages, so the one channel carrying JSON stays
     * carrying only JSON. Standard input belongs to the editor for the same
     * reason, which is why a confirmation becomes a screen rather than a prompt.
     */
    private static StdioEditor attach(MainFrame.Builder building) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StdioEditor editor = new StdioEditor(in,
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        building.editor(editor)
                .input(in)
                .color(false)
                .interactive(true)
                .output(editor.lines("plain"), editor.lines("error"));
        return editor;
    }

    private static int finish(StdioEditor editor, int exit) {
        if (editor != null) editor.done(exit);
        return exit;
    }

    private static void usage(PrintStream out) {
        out.println("MainFrame " + MainFrame.version() + " -- a shell you cannot mess up");
        out.println();
        out.println("usage");
        out.println("  mainframe                 start the shell");
        out.println("  mainframe <script.mf>     run a script");
        out.println("  mainframe -c \"<code>\"     run one line and stop");
        out.println();
        out.println("options");
        out.println("  --dry-run                 never change anything, just say what would happen");
        out.println("  --yes                     answer yes to confirmations (for scripts you trust)");
        out.println("  --no-color                plain output");
        out.println("  --panel                   speak the panel protocol on stdio (see PROTOCOL.md)");
        out.println("  -h, --help                this text");
        out.println("  -v, --version             which MainFrame this is");
        out.println();
        out.println("inside the shell, run help to see every command.");
    }
}
