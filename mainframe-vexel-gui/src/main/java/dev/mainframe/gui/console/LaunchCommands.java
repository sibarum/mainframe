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
import java.util.List;
import java.util.SequencedMap;

/**
 * The two commands that make MainFrame the thing the other windows open out of: {@code apps} and {@code launch}.
 *
 * <h2>Which way round this goes</h2>
 * An editor with a shell in it is a text editor. A shell that can open an editor is something else — and it is
 * the second one that scales, because the list of things it can open is a list rather than a feature. So the
 * console does not know what an editor is; it knows that some number of {@link ConsoleApp}s said they have a
 * window, and that {@code launch} opens one by name.
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

    LaunchCommands(List<ConsoleApp> apps, ConsoleContext console) {
        this.apps = apps;
        this.console = console;
    }

    void register(Registry registry) {
        registry.add(list());
        registry.add(launch());
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
     * {@code launch} — open an app's window, or raise the one that is already up.
     *
     * <p>The hop onto the frame loop happens here rather than in every app: a command body runs on the job
     * thread, and a window built from there would be built while the loop was drawing one.
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
            if (!app.launchable()) {
                throw args.fail("E921", name + " has no window to open")
                        .hint("it adds commands to this console rather than opening anything")
                        .hint("apps | where launchable shows the ones that do")
                        .build();
            }
            if (console.host().isEmpty()) {
                throw args.fail("E922", "there is no window system here to open " + name + " in")
                        .hint("this console is running without an application behind it")
                        .build();
            }
            console.onGuiThread(() -> app.launch(console));
            args.session().out().note("opening " + app.name());
            return Value.Nothing.INSTANCE;
        });
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
