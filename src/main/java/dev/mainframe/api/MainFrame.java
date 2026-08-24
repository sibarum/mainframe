package dev.mainframe.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.Set;

import dev.mainframe.Environment;
import dev.mainframe.ExitRequest;
import dev.mainframe.MfError;
import dev.mainframe.Programs;
import dev.mainframe.Session;
import dev.mainframe.Shell;
import dev.mainframe.Span;
import dev.mainframe.builtins.CoreBuiltins;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormScreen;
import dev.mainframe.form.FormStore;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;

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
 * <p>A shell is also a place to run things: the environment it hands to programs,
 * the PATH it searches, and programs of your own that live in this JVM rather than
 * on disk. Those three go together, and they are all live -- set an environment
 * variable and the very next command sees it.
 *
 * <pre>{@code
 * shell.env("JAVA_HOME", jdk.toString());          // every program it starts sees this
 * shell.pathAddFirst(jdk.resolve("bin"));          // so ^javac is that JDK's javac
 * shell.program(ProgramSpec.named("jdk")           // ^jdk 25, run in this JVM
 *                 .summary("switch the JDK this session uses")
 *                 .usage("jdk <version>")
 *                 .build(),
 *         call -> toolchains.select(call.argument(0, ""), call));
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

    // ---- asking the user ----------------------------------------------------------------

    /** Shows a form on the shell's streams and hands back the answers. */
    public Data form(Data fields) { return form(fields, null, null); }

    /** Shows a form under a heading of your choosing. */
    public Data form(Data fields, String title) { return form(fields, title, null); }

    /**
     * Shows a form, offering {@code starting} as the answers, and hands back what
     * the user entered -- or {@link Data#nothing()} if they cancelled.
     *
     * <p>The fields are the same data the {@code form} command takes: a table of
     * field records, or a list of names. Build them with {@link Data}, or read them
     * from a file the same way anything else is read, since a form is a value like
     * any other.
     *
     * <pre>{@code
     * Data answers = shell.form(Data.of(List.of(
     *                 Map.of("name", "name", "required", true, "min", 2),
     *                 Map.of("name", "email", "match", "[^@ ]+@[^@ ]+"))),
     *         "New contact");
     * if (!answers.isNothing()) contacts.add(answers.field("email").text());
     * }</pre>
     *
     * <p>Anything in {@code starting} the form does not ask about is carried
     * through to the answers rather than dropped, so a record can be edited
     * without losing the parts this form knows nothing about.
     *
     * @throws ShellError if the fields do not describe a form, or if the shell was
     *                    not told there is somebody to ask -- see
     *                    {@link Builder#interactive(boolean)}
     */
    public Data form(Data fields, String title, Data starting) {
        if (fields == null) throw new IllegalArgumentException("a form needs fields to ask for");
        Value.Rec offered = null;
        if (starting != null && !starting.isNothing()) {
            if (!(starting.unwrap() instanceof Value.Rec record)) {
                throw new IllegalArgumentException("a form is filled in over a record, not a "
                        + starting.type());
            }
            offered = record;
        }
        try {
            Form form = Form.read(fields.unwrap(), Span.NONE);
            requireSomebodyToAsk(session, "this shell");
            Value.Rec answers = FormScreen.show(form, offered, title, true, session);
            return answers == null ? Data.nothing() : Data.wrap(answers);
        } catch (MfError e) {
            throw translate(e);
        }
    }

    /**
     * A form has to be filled in by somebody, and MainFrame will not pretend
     * otherwise: with nobody there it stops, rather than handing back a record of
     * blanks that nothing checked.
     */
    static void requireSomebodyToAsk(Session session, String who) {
        if (session.interactive()) return;
        throw MfError.of("E1209", who + " has nobody to ask")
                .hint("a form reads its answers from the terminal, and there is not one here")
                .hint("say so with MainFrame.builder().interactive(true) if there is")
                .build();
    }

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

    public void directory(Path directory) { Sessions.directory(session, directory); }

    /** A variable from the shell's environment, or null. */
    public String env(String name) { return session.env().get(name); }

    /**
     * Sets a variable, which every program the shell starts will then see.
     *
     * <p>MainFrame keeps its own environment rather than the one this process was
     * started with -- which a running JVM cannot change anyway -- so this takes
     * effect on the very next command, spawned or hosted.
     */
    public void env(String name, String value) { Sessions.env(session, name, value); }

    /** Unsets a variable. False when it was not set. */
    public boolean envRemove(String name) { return Sessions.envRemove(session, name); }

    /** The whole environment as it stands, for handing to something else. */
    public Map<String, String> environment() { return session.env().all(); }

    /** The PATH, in the order it is searched. */
    public List<Path> path() { return Sessions.path(session); }

    /**
     * Adds a directory to the end of the shell's PATH. False when it was already
     * on it.
     *
     * <p>The directory does not have to exist yet: a program that is about to
     * create it is not a person who has mistyped something. {@code path} shows
     * entries that are not there rather than hiding them.
     */
    public boolean pathAdd(Path directory) { return Sessions.pathAdd(session, directory, false); }

    /** Adds a directory to the front of the PATH, so it is searched first. */
    public boolean pathAddFirst(Path directory) { return Sessions.pathAdd(session, directory, true); }

    /** Takes a directory off the PATH. False when it was not on it. */
    public boolean pathRemove(Path directory) { return Sessions.pathRemove(session, directory); }

    /**
     * Where {@code ^name} would be found on the PATH, or null when nowhere --
     * resolved against the shell's own PATH, including the Windows habit of
     * trying PATHEXT suffixes.
     *
     * <p>This ignores hosted programs, which have no path; {@link #hasProgram}
     * answers for those, and one of them would win.
     */
    public Path onPath(String program) { return session.env().findProgram(program); }

    // ---- programs the host provides ----------------------------------------------------

    /**
     * Installs a program, run in this JVM but invoked like anything on the PATH.
     *
     * <p>Replaces one already installed under that name, so a host that reloads
     * its plugins does not have to uninstall first.
     */
    public void program(ProgramSpec spec, Program program) {
        session.programs().install(Hosted.program(spec, program));
    }

    /** Uninstalls a hosted program. False when there was none by that name. */
    public boolean programRemove(String name) { return session.programs().remove(name); }

    /** The names of the hosted programs, in the order they were installed. */
    public List<String> programs() { return List.copyOf(session.programs().names()); }

    public boolean hasProgram(String name) { return session.programs().has(name); }

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
        private final Programs programs = new Programs();
        private final Set<String> shadowing = new LinkedHashSet<>();
        private final Set<String> without = new LinkedHashSet<>();
        private final SequencedMap<String, String> environment = new LinkedHashMap<>();
        private final List<Path> pathFront = new ArrayList<>();
        private final List<Path> pathEnd = new ArrayList<>();

        private Path directory = Path.of("").toAbsolutePath();
        private Path indexDirectory;
        private Path formDirectory;
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

        /**
         * Where {@code form-save} keeps its records. Defaults to
         * {@code ~/.mainframe/forms}; point it at your own application's data if
         * a user's preferences belong to your app rather than to their shell.
         */
        public Builder formDirectory(Path directory) {
            this.formDirectory = directory;
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
            String problem = Environment.problemWithName(name);
            if (problem != null) throw new IllegalArgumentException(problem);
            environment.put(name, value);
            return this;
        }

        /**
         * A directory to add to the end of the PATH, so {@code ^tool} finds the
         * programs in it. Relative to the shell's directory.
         */
        public Builder pathAdd(Path directory) {
            pathEnd.add(directory);
            return this;
        }

        /** A directory to add to the front of the PATH, searched before the rest. */
        public Builder pathAddFirst(Path directory) {
            pathFront.add(directory);
            return this;
        }

        /**
         * Installs a program of the host's own: invoked with a caret like anything
         * on the PATH, but run in this JVM.
         *
         * <p>Use this for something that has to look like a tool -- a name people
         * type, flags it parses itself, text it prints. Use {@link #command} for
         * something that should be part of the language, with typed data through
         * the pipe and the guardrails around it.
         *
         * <p>Fails if the host has already registered a program by that name.
         * Hosted programs are searched before the PATH, so one called {@code git}
         * would take the place of the real git; {@code which git} says so, and
         * {@code programs} lists it, but nothing stops you.
         */
        public Builder program(ProgramSpec spec, Program program) {
            if (programs.has(spec.name())) {
                throw new IllegalArgumentException("you have already registered a program called \""
                        + spec.name() + "\"");
            }
            programs.install(Hosted.program(spec, program));
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
            if (formDirectory != null) session.forms(new FormStore(formDirectory));
            session.dryRun(dryRun);
            session.assumeYes(assumeYes);
            session.interactive(interactive != null && interactive);
            environment.forEach((name, value) -> session.env().set(name, value));
            // Front entries go on in reverse, so the first one declared is the
            // first one searched.
            for (Path directory : pathFront.reversed()) Sessions.pathAdd(session, directory, true);
            for (Path directory : pathEnd) Sessions.pathAdd(session, directory, false);
            programs.all().forEach(session.programs()::install);

            return new MainFrame(session, registry, chosenInput, interactive != null);
        }
    }
}
