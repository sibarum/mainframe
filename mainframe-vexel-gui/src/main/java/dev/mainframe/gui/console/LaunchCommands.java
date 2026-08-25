package dev.mainframe.gui.console;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;

/**
 * The commands that make MainFrame the thing the other windows open out of: {@code apps}, {@code launch}, and
 * one command per app named after the app.
 *
 * <h2>Which way round this goes</h2>
 * An editor with a shell in it is a text editor. A shell that can open an editor is something else — and it is
 * the second one that scales, because the list of things it can open is a list rather than a feature. So the
 * console does not know what an editor is; it knows that some number of {@link ConsoleApp}s said they have a
 * window, and that {@code launch} opens one by name.
 *
 * <h2>Typing a program's name runs it</h2>
 * {@code launch "calculator"} is the explicit form, and it is the right one in a script, where a name that came
 * from somewhere else has to be quoted anyway. But it is not what anyone wants to type, and a shell where the
 * ordinary way to start a program is a verb plus a quoted string is a shell that has forgotten what it is. So
 * every launchable app also gets a command named after itself, and {@code calculator} opens the calculator.
 *
 * <p>Only where the name is <b>free</b>. These are registered last, after every app has added whatever it wanted,
 * so an app that already has a command by that name keeps it and nothing is silently shadowed — and a built-in
 * command can never be displaced by an app being called something unfortunate. An app whose name is taken is
 * still reachable through {@code launch}, which is why that stays.
 *
 * <h2>Why a table</h2>
 * {@code apps} answers with rows, not with a paragraph, so the rest of the language works on it:
 * {@code apps | where launchable} is a sentence, and a listing that were printed prose would not be. That is the
 * same bargain every other MainFrame listing makes.
 */
final class LaunchCommands {

    private static final String CATEGORY = "console";

    private final List<ConsoleApp> apps;
    private final ConsoleContext console;
    /** The apps that got a command named after them, so the menu can teach the short form where there is one. */
    private final Set<String> shortcuts = new HashSet<>();

    LaunchCommands(List<ConsoleApp> apps, ConsoleContext console) {
        this.apps = apps;
        this.console = console;
    }

    /**
     * {@code apps} and {@code launch}, added <em>before</em> the apps' own commands so that neither can be
     * quietly taken by something plugged in.
     */
    void core(Registry registry) {
        registry.add(list());
        registry.add(launch());
    }

    /**
     * One command per launchable app, named after it — added <em>after</em> the apps' own commands, so an app
     * that has already claimed its own name keeps what it registered.
     *
     * <p>{@code launchable()} is read once, here. An app that only grows a window later is still reachable
     * through {@code launch}, which asks again every time.
     */
    void shortcuts(Registry registry) {
        for (ConsoleApp app : apps) {
            if (app.launchable() && !registry.has(app.name())) {
                registry.add(shortcut(app));
                shortcuts.add(app.name());
            }
        }
    }

    /**
     * The shortest line that opens {@code app}: its own name where that name reached the registry, and the
     * explicit {@code launch} otherwise.
     *
     * <p>What the context menu shows. A menu that taught {@code launch "calculator"} when {@code calculator}
     * would have done has taught the long way round, and a menu that taught {@code calculator} when something
     * else owns that name would be teaching a command that does something different.
     */
    String lineFor(ConsoleApp app) {
        return shortcuts.contains(app.name()) ? app.name() : "launch " + quoted(app.name());
    }

    /** {@code apps} — what is plugged into this console, and which of them have a window. */
    private Builtin list() {
        Signature signature = Signature.named("apps", CATEGORY)
                .summary("list what is plugged into this console")
                .input(ValueType.NOTHING)
                .output(ValueType.TABLE)
                .effect(Signature.Effect.READS)
                .example("apps")
                .example("apps | where launchable")
                .build();
        return command(signature, args -> {
            List<Value> rows = new ArrayList<>();
            for (ConsoleApp app : apps) {
                SequencedMap<String, Value> row = new LinkedHashMap<>();
                row.put("name", new Value.Str(app.name()));
                row.put("launchable", new Value.Bool(app.launchable()));
                row.put("summary", new Value.Str(app.summary()));
                rows.add(new Value.Rec(row));
            }
            if (rows.isEmpty()) {
                args.session().out().note("nothing is plugged into this console -- it is the shell on its own");
            }
            return new Value.ListVal(rows);
        });
    }

    /**
     * {@code launch} — open an app's window by name, or raise the one that is already up.
     *
     * <p>The explicit form, and the one to reach for in a script: a name that came out of a variable or a pipe
     * has to be quoted anyway, and this takes any name rather than only the ones that happened to be free. At a
     * prompt, {@link #shortcut} is shorter and means the same thing.
     */
    private Builtin launch() {
        Signature signature = Signature.named("launch", CATEGORY)
                .summary("open one of this console's apps in its own window")
                .required("app", ValueType.STRING, "which app; apps lists them")
                .input(ValueType.NOTHING)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.SESSION)
                .example("launch \"editor\"")
                .build();
        return command(signature, args -> {
            String name = args.str(0);
            ConsoleApp app = find(name);
            if (app == null) {
                throw args.fail("E920", "there is no app called " + name)
                        .hint("apps lists what this console has")
                        .build();
            }
            return open(args, app);
        });
    }

    /**
     * One app's name, as a command: {@code calculator} rather than {@code launch "calculator"}.
     *
     * <p>Filed under the app's own category, so {@code help} lists it beside whatever else that app added rather
     * than off among the console's plumbing — opening the calculator and working something out are the same
     * subject.
     */
    private Builtin shortcut(ConsoleApp app) {
        Signature signature = Signature.named(app.name(), app.name())
                .summary(app.summary().isEmpty()
                        ? "open " + app.name()
                        : "open " + app.name() + " -- " + app.summary())
                .input(ValueType.NOTHING)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.SESSION)
                .example(app.name())
                .build();
        return command(signature, args -> open(args, app));
    }

    /**
     * Open {@code app}, whichever command asked.
     *
     * <p>The hop onto the frame loop happens here rather than in every app: a command body runs on the job
     * thread, and a window built from there would be built while the loop was drawing one.
     */
    private Value open(Args args, ConsoleApp app) {
        if (!app.launchable()) {
            throw args.fail("E921", app.name() + " has no window to open")
                    .hint("it adds commands to this console rather than opening anything")
                    .hint("apps | where launchable shows the ones that do")
                    .build();
        }
        if (console.host().isEmpty()) {
            throw args.fail("E922", "there is no window system here to open " + app.name() + " in")
                    .hint("this console is running without an application behind it")
                    .build();
        }
        console.onGuiThread(() -> app.launch(console));
        args.session().out().note("opening " + app.name());
        return Value.Nothing.INSTANCE;
    }

    /** By name, case-insensitively — this is typed, and the name is also a help category. */
    private ConsoleApp find(String name) {
        for (ConsoleApp app : apps) {
            if (app.name().equalsIgnoreCase(name.trim())) {
                return app;
            }
        }
        return null;
    }

    /** One command, from a signature and what it does. */
    private static Builtin command(Signature signature, java.util.function.Function<Args, Value> body) {
        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                return body.apply(args);
            }
        };
    }

    /** A name as a MainFrame argument. Quoted, because a hyphen in a bare word parses as an operator. */
    static String quoted(String name) {
        return Values.quoted(name);
    }
}
