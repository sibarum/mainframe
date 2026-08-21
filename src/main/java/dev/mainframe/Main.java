package dev.mainframe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.builtins.CoreBuiltins;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;

/** Command line entry point. */
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
                    System.out.println("mainframe " + CoreBuiltins.VERSION);
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

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        Renderer renderer = new Renderer(out, err, color);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        Session session = new Session(renderer, IndexStore.inState(), input, Path.of("").toAbsolutePath());
        session.dryRun(dryRun);
        session.assumeYes(assumeYes);

        Registry registry = Registry.standard();
        Interpreter interpreter = new Interpreter(session, registry);

        if (!leftovers.isEmpty()) {
            renderer.warn("ignoring extra arguments: " + String.join(", ", leftovers));
        }

        if (command != null) return runOnce(session, interpreter, command);

        if (script != null) {
            Path file = session.resolve(script.toString());
            if (!Files.isRegularFile(file)) {
                renderer.error(MfError.of("E001", "there is no script at " + file)
                        .hint("check the path, or run mainframe with no arguments for a shell")
                        .build(), null);
                return 1;
            }
            try {
                return runOnce(session, interpreter, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                renderer.error(MfError.of("E002", "could not read " + file + ": " + e.getMessage()).build(), null);
                return 1;
            }
        }

        session.interactive(System.console() != null);
        return new Shell(session, interpreter, input).loop();
    }

    private static int runOnce(Session session, Interpreter interpreter, String source) {
        session.source(source);
        try {
            interpreter.run(Parser.parse(source));
            return 0;
        } catch (ExitRequest e) {
            return e.code();
        } catch (MfError e) {
            session.out().error(e, source);
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("MainFrame " + CoreBuiltins.VERSION + " -- a shell you cannot mess up");
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
