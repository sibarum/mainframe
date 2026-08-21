package dev.mainframe;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.api.MainFrame;
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

        MainFrame shell = MainFrame.builder()
                .directory(Path.of("").toAbsolutePath())
                .color(color)
                .dryRun(dryRun)
                .assumeYes(assumeYes)
                // With no console there is nobody to answer a confirmation, so
                // destructive commands should refuse rather than prompt into a pipe.
                .interactive(System.console() != null)
                .build();

        if (!leftovers.isEmpty()) {
            System.err.println("mainframe: ignoring extra arguments: " + String.join(", ", leftovers));
        }

        if (command != null) return shell.execute(command);

        if (script != null) {
            Path file = script.isAbsolute() ? script : Path.of("").toAbsolutePath().resolve(script);
            if (!Files.isRegularFile(file)) {
                System.err.println("mainframe: there is no script at " + file);
                System.err.println("check the path, or run mainframe with no arguments for a shell");
                return 1;
            }
            return shell.executeFile(file);
        }

        return shell.repl();
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
        out.println("  -h, --help                this text");
        out.println("  -v, --version             which MainFrame this is");
        out.println();
        out.println("inside the shell, run help to see every command.");
    }
}
