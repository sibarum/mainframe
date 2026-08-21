package dev.mainframe.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.Set;

import dev.mainframe.ExitRequest;
import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.Shell;
import dev.mainframe.builtins.CoreBuiltins;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;

/**
 * An embedded MainFrame: a shell your program owns, with your commands in it.
 *
 * <p>Commands you register are called back in-process with the piped data and the
 * checked arguments. Nothing is spawned, nothing is serialised, and your command
 * is indistinguishable from a built-in one as far as the user is concerned -- same
 * argument checking, same {@code help}, same {@code --dry-run}, same errors.
 *
 * <pre>{@code
 * MainFrame shell = MainFrame.builder()
 *         .command(CommandSpec.named("customers")
 *                         .category("my app")
 *                         .summary("list customers from the live database")
 *                         .optional("filter", DataType.TEXT, "only names containing this")
 *                         .output(DataType.TABLE)
 *                         .effect(Effect.READS)
 *                         .example("customers | where spend > 1000 | sort-by spend")
 *                         .build(),
 *                 invocation -> Data.table(myDatabase.customers(invocation.text(0, ""))))
 *         .build();
 *
 * Data big = shell.run("customers | where spend > 1000 | first 5");
 * shell.repl();   // or hand the whole shell to the user
 * }</pre>
 *
 * <p>Not thread safe: one shell belongs to one thread, like the session it is.
 */
public final class MainFrame {

    private final Session session;
    private final Registry registry;
    private final Interpreter interpreter;
    private final BufferedReader input;
    private final boolean interactiveWasChosen;

    private OptionalInt exitRequest = OptionalInt.empty();

    private MainFrame(Session session, Registry registry, BufferedReader input, boolean interactiveWasChosen) {
        this.session = session;
        this.registry = registry;
        this.input = input;
        this.interactiveWasChosen = interactiveWasChosen;
        this.interpreter = new Interpreter(session, registry);
    }

    public static Builder builder() { return new Builder(); }

    /** The version of MainFrame you are embedding. */
    public static String version() { return CoreBuiltins.VERSION; }

    // ---- running things ----------------------------------------------------------------

    /**
     * Runs MainFrame source and returns what the last pipeline produced.
     *
     * @throws ShellError if the source could not be read or a command refused to run
     */
    public Data run(String source) {
        try {
            return evaluate(source);
        } catch (MfError e) {
            throw translate(e);
        }
    }

    private Data evaluate(String source) {
        session.source(source);
        try {
            return Data.wrap(interpreter.run(Parser.parse(source)));
        } catch (ExitRequest e) {
            exitRequest = OptionalInt.of(e.code());
            return Data.nothing();
        }
    }

    /** Runs a script file. */
    public Data runFile(Path file) {
        try {
            return run(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ShellError("E1003", "could not read " + file + ": " + e.getMessage(),
                    List.of("check the path and that the file is readable"), 0, 0, 0);
        }
    }

    /**
     * Runs source and reports any problem the way the shell would -- pointing at
     * the offending line, with its hints -- instead of throwing.
     *
     * @return 0 if it worked, the exit code if something ran {@code exit},
     *         1 if it failed
     */
    public int execute(String source) {
        try {
            evaluate(source);
            return exitRequest.orElse(0);
        } catch (MfError e) {
            // Rendered from the original, so the caret still spans the exact text.
            session.out().error(e, source);
            return 1;
        }
    }

    /** Runs a script file, reporting problems rather than throwing. */
    public int executeFile(Path file) {
        try {
            return execute(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            session.out().warn("could not read " + file + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * Hands the shell to the user and returns the exit code when they leave.
     *
     * <p>Unless the builder was told otherwise, this marks the session
     * interactive, so commands that need confirmation can ask for it.
     */
    public int repl() {
        if (!interactiveWasChosen) session.interactive(true);
        return new Shell(session, interpreter, input).loop();
    }

    /** The exit code, if anything ran {@code exit}. */
    public OptionalInt exitRequest() { return exitRequest; }

    // ---- looking at the shell ----------------------------------------------------------

    /** Every command name, built-in and hosted. */
    public List<String> commands() { return List.copyOf(registry.names()); }

    public boolean has(String command) { return registry.has(command); }

    /** The one-line usage of a command, or null if there is no such command. */
    public String usage(String command) {
        Builtin builtin = registry.get(command);
        return builtin == null ? null : builtin.signature().usage();
    }

    // ---- the session -------------------------------------------------------------------

    public Path directory() { return session.cwd(); }

    public void directory(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(directory + " is not a directory");
        }
        session.cd(directory.toAbsolutePath().normalize());
    }

    /** A variable from the shell's environment, or null. */
    public String env(String name) { return session.env().get(name); }

    /** Sets a variable, which every program the shell starts will then see. */
    public void env(String name, String value) { session.env().set(name, value); }

    /** Adds a directory to the shell's PATH, at the end. */
    public void pathAdd(Path directory) {
        var entries = new java.util.ArrayList<>(session.env().pathEntries());
        String resolved = directory.toAbsolutePath().normalize().toString();
        if (!session.env().onPath(directory.toAbsolutePath().normalize())) entries.add(resolved);
        session.env().pathEntries(entries);
    }

    public boolean dryRun() { return session.dryRun(); }

    /** When true, nothing that changes files will actually do it. */
    public void dryRun(boolean value) { session.dryRun(value); }

    /** When true, destructive commands stop asking. Use it only for input you trust. */
    public void assumeYes(boolean value) { session.assumeYes(value); }

    private static ShellError translate(MfError error) {
        return new ShellError(error.code(), error.getMessage(), error.hints(),
                error.span().line(), error.span().col(), error.span().length());
    }

    // ---- building it -------------------------------------------------------------------

    /** Assembles a shell: your commands, plus as much or as little of MainFrame's own. */
    public static final class Builder {

        private final SequencedMap<String, Builtin> hosted = new LinkedHashMap<>();
        private final Set<String> shadowing = new LinkedHashSet<>();
        private final Set<String> without = new LinkedHashSet<>();
        private final SequencedMap<String, String> environment = new LinkedHashMap<>();

        private Path directory = Path.of("").toAbsolutePath();
        private Path indexDirectory;
        private PrintStream out;
        private PrintStream err;
        private BufferedReader input;
        private Boolean color;
        private boolean dryRun;
        private boolean assumeYes;
        private Boolean interactive;

        private Builder() {}

        /**
         * Adds a command of your own. Fails if MainFrame already has one by that
         * name, so a future release cannot silently take your command's place.
         */
        public Builder command(CommandSpec spec, Command command) {
            return add(spec, Hosted.command(spec, command), false);
        }

        /** Adds a command that changes things, so it can be dry-run and confirmed. */
        public Builder command(CommandSpec spec, PlannedCommand command) {
            return add(spec, Hosted.planned(spec, command), false);
        }

        /** Adds a command that deliberately takes the place of a built-in one. */
        public Builder replacing(CommandSpec spec, Command command) {
            return add(spec, Hosted.command(spec, command), true);
        }

        /** Adds a planning command that deliberately takes the place of a built-in one. */
        public Builder replacing(CommandSpec spec, PlannedCommand command) {
            return add(spec, Hosted.planned(spec, command), true);
        }

        private Builder add(CommandSpec spec, Builtin builtin, boolean mayShadow) {
            if (hosted.containsKey(spec.name())) {
                throw new IllegalArgumentException("you have already registered a command called \""
                        + spec.name() + "\"");
            }
            hosted.put(spec.name(), builtin);
            if (mayShadow) shadowing.add(spec.name());
            return this;
        }

        /**
         * Leaves commands out entirely -- useful when the shell is for one job and
         * things like rm have no business being in it.
         */
        public Builder without(String... names) {
            for (String name : names) without.add(name);
            return this;
        }

        /** The directory the shell starts in. Defaults to the working directory. */
        public Builder directory(Path directory) {
            this.directory = directory.toAbsolutePath().normalize();
            return this;
        }

        /** Where filesystem indexes live. Defaults to {@code ~/.mainframe/indexes}. */
        public Builder indexDirectory(Path directory) {
            this.indexDirectory = directory;
            return this;
        }

        /** Where output goes. Defaults to the process streams. */
        public Builder output(PrintStream out, PrintStream err) {
            this.out = out;
            this.err = err;
            return this;
        }

        /** Where confirmations and the REPL read from. Defaults to standard input. */
        public Builder input(BufferedReader input) {
            this.input = input;
            return this;
        }

        public Builder color(boolean color) {
            this.color = color;
            return this;
        }

        /** Starts the shell in dry-run mode: nothing that changes files will do it. */
        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        /**
         * Answers yes to every confirmation. Needed for unattended work, and worth
         * thinking about twice: it is the one switch that turns the guardrails off.
         */
        public Builder assumeYes(boolean assumeYes) {
            this.assumeYes = assumeYes;
            return this;
        }

        /**
         * Says whether there is a person to ask. Left false, destructive commands
         * refuse rather than prompt. {@link MainFrame#repl()} turns it on anyway.
         */
        public Builder interactive(boolean interactive) {
            this.interactive = interactive;
            return this;
        }

        /** A variable for the shell's environment, on top of the inherited ones. */
        public Builder env(String name, String value) {
            environment.put(name, value);
            return this;
        }

        public MainFrame build() {
            PrintStream chosenOut = out != null ? out : new PrintStream(System.out, true, StandardCharsets.UTF_8);
            PrintStream chosenErr = err != null ? err : new PrintStream(System.err, true, StandardCharsets.UTF_8);
            boolean chosenColor = color != null ? color : Renderer.colorSupported();
            BufferedReader chosenInput = input != null ? input
                    : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

            Registry registry = Registry.standard();
            for (String name : without) {
                if (!registry.remove(name) && !hosted.containsKey(name)) {
                    throw new IllegalArgumentException("there is no command called \"" + name + "\" to leave out");
                }
            }
            for (Map.Entry<String, Builtin> entry : hosted.entrySet()) {
                boolean clashes = registry.has(entry.getKey());
                if (clashes && !shadowing.contains(entry.getKey())) {
                    throw new IllegalArgumentException("MainFrame already has a command called \""
                            + entry.getKey() + "\" -- use replacing(...) if you mean to take its place, "
                            + "or without(\"" + entry.getKey() + "\") to drop it first");
                }
                registry.replace(entry.getValue());
            }

            IndexStore indexes = indexDirectory == null
                    ? IndexStore.inState()
                    : new IndexStore(indexDirectory);
            Session session = new Session(new Renderer(chosenOut, chosenErr, chosenColor),
                    indexes, chosenInput, directory);
            session.dryRun(dryRun);
            session.assumeYes(assumeYes);
            session.interactive(interactive != null && interactive);
            environment.forEach((name, value) -> session.env().set(name, value));

            return new MainFrame(session, registry, chosenInput, interactive != null);
        }
    }
}
