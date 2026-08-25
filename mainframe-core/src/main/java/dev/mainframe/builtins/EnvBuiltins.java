package dev.mainframe.builtins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.Environment;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;

/**
 * Reading and changing the environment while the shell is running.
 *
 * <p>Every change here takes effect on the very next command -- there is nothing
 * to reload and nothing to restart -- because MainFrame owns its environment
 * rather than borrowing the one the process started with.
 */
public final class EnvBuiltins {

    private static final String CATEGORY = "environment";

    private EnvBuiltins() {}

    public static void register(Registry registry) {
        registry.add(env());
        registry.add(envSet());
        registry.add(envRemove());
        registry.add(path());
        registry.add(pathAdd());
        registry.add(pathRemove());
        registry.add(programs());
    }

    /**
     * The programs the surrounding application provides in-process.
     *
     * <p>These do not appear in {@code help}, because they are not commands --
     * they are run with a caret like anything on the PATH. Without somewhere to
     * list them there would be no way to find out they exist, which is the one
     * thing MainFrame will not do.
     */
    private static Builtin programs() {
        Signature signature = Signature.named("programs", CATEGORY)
                .summary("show the programs this app provides in-process")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("programs")
                .example("programs | get name")
                .build();
        return Cmd.of(signature, args -> {
            List<Value> rows = new ArrayList<>();
            for (dev.mainframe.HostedProgram program : args.session().programs().all()) {
                Value.Rec row = Value.Rec.of(
                        "name", new Value.Str(program.name()),
                        "summary", new Value.Str(program.summary()),
                        "usage", new Value.Str(program.usage()),
                        "run-it-with", new Value.Str("^" + program.name()));
                // The column only appears when something is actually being stood
                // in front of, so an honest table stays a short one.
                Path shadowed = args.session().env().findProgram(program.name());
                rows.add(shadowed == null ? row : row.with("instead-of", new Value.PathVal(shadowed)));
            }
            if (rows.isEmpty()) {
                args.session().out().note("this shell has no programs of its own -- "
                        + "a program embedding MainFrame can install them, and ^name finds the rest on your PATH");
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    // ---- variables --------------------------------------------------------------------

    private static Builtin env() {
        Signature signature = Signature.named("env", CATEGORY)
                .summary("show the environment, or one variable from it")
                .optional("name", ValueType.STRING, "the variable to read; all of them by default")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("env")
                .example("env HOME")
                .example("env | where name =~ \"proxy\"")
                .build();
        return Cmd.of(signature, args -> {
            Environment environment = args.session().env();
            if (args.has(0)) {
                String name = args.str(0);
                String value = environment.get(name);
                if (value == null) {
                    var error = args.fail("E901", name + " is not set");
                    String closest = Suggest.closest(name, environment.names());
                    if (closest != null) error.hint("did you mean " + closest + "?");
                    error.hint("set it with: env-set " + name + " \"some value\"");
                    error.hint("run env with no arguments to see what is set");
                    throw error.build();
                }
                return new Value.Str(value);
            }
            List<Value> rows = new ArrayList<>();
            for (String name : environment.sortedNames()) {
                rows.add(Value.Rec.of(
                        "name", new Value.Str(name),
                        "value", new Value.Str(environment.get(name))));
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin envSet() {
        Signature signature = Signature.named("env-set", CATEGORY)
                .summary("set a variable for this session and everything it starts")
                .required("name", ValueType.STRING, "the variable to set")
                .required("value", ValueType.STRING, "what to set it to")
                .output(ValueType.RECORD)
                .effect(Effect.SESSION)
                .example("env-set EDITOR \"code --wait\"")
                .example("env-set NO_COLOR \"1\"")
                .build();
        return Cmd.of(signature, args -> {
            String name = args.str(0);
            String value = args.str(1);
            String problem = Environment.problemWithName(name);
            if (problem != null) {
                throw args.fail("E902", problem)
                        .hint("names look like EDITOR or MY_TOKEN")
                        .hint("if the value has spaces, it is the value that needs the quotes")
                        .build();
            }
            Environment environment = args.session().env();
            String previous = environment.get(name);
            if (name.equalsIgnoreCase("PATH")) {
                args.session().out().note("replacing the whole PATH -- path-add is usually what you want");
            }
            environment.set(name, value);
            Value.Rec result = Value.Rec.of(
                    "name", new Value.Str(name),
                    "value", new Value.Str(value));
            return previous == null
                    ? result.with("was", Value.Nothing.INSTANCE)
                    : result.with("was", new Value.Str(previous));
        });
    }

    private static Builtin envRemove() {
        Signature signature = Signature.named("env-remove", CATEGORY)
                .summary("unset a variable for this session")
                .required("name", ValueType.STRING, "the variable to unset")
                .switchFlag("force", 'f', "remove it even if other things rely on it")
                .output(ValueType.RECORD)
                .effect(Effect.SESSION)
                .example("env-remove HTTP_PROXY")
                .build();
        return Cmd.of(signature, args -> {
            String name = args.str(0);
            Environment environment = args.session().env();
            if (!environment.has(name)) {
                var error = args.fail("E903", name + " is not set, so there is nothing to remove");
                String closest = Suggest.closest(name, environment.names());
                if (closest != null) error.hint("did you mean " + closest + "?");
                throw error.build();
            }
            if (Environment.isProtected(name) && !args.flag("force")) {
                throw args.fail("E904", name + " is one of the variables programs count on")
                        .hint("removing it would break commands that still work right now")
                        .hint("add --force if you are sure, or change it with env-set instead")
                        .build();
            }
            String previous = environment.remove(name);
            return Value.Rec.of(
                    "name", new Value.Str(name),
                    "removed", new Value.Bool(true),
                    "was", new Value.Str(previous));
        });
    }

    // ---- PATH -------------------------------------------------------------------------

    private static Builtin path() {
        Signature signature = Signature.named("path", CATEGORY)
                .summary("show where programs are looked for, in order")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("path")
                .example("path | where missing == true")
                .build();
        return Cmd.of(signature, args -> pathTable(args));
    }

    /** The PATH as a table. Entries that are not there are flagged, not hidden. */
    private static Value pathTable(Args args) {
        List<String> entries = args.session().env().pathEntries();
        List<Value> rows = new ArrayList<>(entries.size());
        int order = 1;
        for (String entry : entries) {
            boolean exists;
            Value directory;
            try {
                Path resolved = Path.of(entry).toAbsolutePath().normalize();
                exists = Files.isDirectory(resolved);
                directory = new Value.PathVal(resolved);
            } catch (RuntimeException e) {
                exists = false;
                directory = new Value.Str(entry);
            }
            rows.add(Value.Rec.of(
                    "order", new Value.Int(order++),
                    "directory", directory,
                    "missing", new Value.Bool(!exists)));
        }
        if (rows.isEmpty()) {
            args.session().out().note("your PATH is empty -- external programs will not be found");
        }
        return new Value.ListVal(List.copyOf(rows));
    }

    private static Builtin pathAdd() {
        Signature signature = Signature.named("path-add", CATEGORY)
                .summary("add a directory to the PATH for this session")
                .required("directory", ValueType.PATH, "the directory holding the programs")
                .switchFlag("front", '\0', "look here before everywhere else, instead of last")
                .switchFlag("force", 'f', "add it even though it is not a directory yet")
                .output(ValueType.TABLE)
                .effect(Effect.SESSION)
                .example("path-add ./node_modules/.bin")
                .example("path-add ~/tools --front")
                .build();
        return Cmd.of(signature, args -> {
            Path directory = args.path(0);
            Environment environment = args.session().env();
            if (!Files.isDirectory(directory) && !args.flag("force")) {
                var error = args.fail("E905", SafeFs.describe(args.session().cwd(), directory)
                        + (Files.exists(directory) ? " is a file, not a directory" : " does not exist"));
                error.hint("a PATH entry has to be a directory that holds programs");
                error.hint("add --force if you are about to create it");
                throw error.build();
            }
            if (environment.onPath(directory)) {
                args.session().out().note(SafeFs.describe(args.session().cwd(), directory)
                        + " is already on your PATH -- left as it is");
                return pathTable(args);
            }
            List<String> entries = new ArrayList<>(environment.pathEntries());
            if (args.flag("front")) entries.addFirst(directory.toString());
            else entries.addLast(directory.toString());
            environment.pathEntries(entries);
            return pathTable(args);
        });
    }

    private static Builtin pathRemove() {
        Signature signature = Signature.named("path-remove", CATEGORY)
                .summary("take a directory off the PATH for this session")
                .required("directory", ValueType.PATH, "the entry to remove")
                .output(ValueType.TABLE)
                .effect(Effect.SESSION)
                .example("path-remove ~/tools")
                .build();
        return Cmd.of(signature, args -> {
            Path directory = args.path(0);
            Environment environment = args.session().env();
            List<String> entries = environment.pathEntries();
            List<String> kept = new ArrayList<>(entries.size());
            for (String entry : entries) {
                if (!Environment.samePath(entry, directory)) kept.add(entry);
            }
            if (kept.size() == entries.size()) {
                var error = args.fail("E906", SafeFs.describe(args.session().cwd(), directory)
                        + " is not on your PATH");
                String closest = Suggest.closest(directory.toString(), entries);
                if (closest != null) error.hint("the closest entry is " + closest);
                error.hint("run path to see every entry");
                throw error.build();
            }
            environment.pathEntries(kept);
            int gone = entries.size() - kept.size();
            args.session().out().note("removed " + (gone == 1 ? "one entry" : gone + " entries")
                    + " from the PATH");
            return pathTable(args);
        });
    }
}
