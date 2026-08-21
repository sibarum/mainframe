package dev.mainframe.builtins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.ExitRequest;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.ui.Help;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/** Finding your way around: help, where you are, what a value is. */
public final class CoreBuiltins {

    private static final String CATEGORY = "getting around";
    public static final String VERSION = "0.1.0";

    private CoreBuiltins() {}

    public static void register(Registry registry) {
        registry.add(help(registry));
        registry.add(describe());
        registry.add(pwd());
        registry.add(cd());
        registry.add(echo());
        registry.add(which(registry));
        registry.add(version());
        registry.add(exit());
    }

    private static dev.mainframe.eval.Builtin help(Registry registry) {
        Signature signature = Signature.named("help", CATEGORY)
                .summary("list every command, or explain one of them")
                .optional("command", ValueType.STRING, "the command you want explained")
                .output(ValueType.NOTHING)
                .example("help")
                .example("help where")
                .build();
        return Cmd.of(signature, args -> {
            if (!args.has(0)) {
                Help.overview(args.session().out(), registry);
                return Value.Nothing.INSTANCE;
            }
            String name = args.str(0);
            var builtin = registry.get(name);
            if (builtin == null) {
                var error = args.fail("E501", "there is no command called " + name);
                String closest = Suggest.closest(name, registry.names());
                if (closest != null) error.hint("did you mean " + closest + "?");
                error.hint("run help with no arguments to see the whole list");
                return error.raise();
            }
            Help.command(args.session().out(), builtin);
            return Value.Nothing.INSTANCE;
        });
    }

    private static dev.mainframe.eval.Builtin describe() {
        Signature signature = Signature.named("describe", CATEGORY)
                .summary("say what kind of value came down the pipe")
                .input(ValueType.ANY)
                .output(ValueType.RECORD)
                .example("ls | describe")
                .build();
        return Cmd.of(signature, args -> {
            Value input = args.input();
            Value.Rec description = Value.Rec.of("type", new Value.Str(ValueType.of(input).display()));
            if (input instanceof Value.ListVal list) {
                description = description.with("items", new Value.Int(list.items().size()));
                if (Values.isTable(input)) {
                    List<String> columns = new ArrayList<>(Values.columns(Values.rows(input)));
                    description = description
                            .with("rows", new Value.Int(list.items().size()))
                            .with("columns", new Value.Str(String.join(", ", columns)));
                }
            } else if (input instanceof Value.Rec rec) {
                description = description.with("fields", new Value.Str(String.join(", ", rec.fields().keySet())));
            } else if (input instanceof Value.Str str) {
                description = description
                        .with("characters", new Value.Int(str.value().length()))
                        .with("lines", new Value.Int(str.value().isEmpty() ? 0 : str.value().lines().count()));
            } else if (input instanceof Value.Mime mime) {
                description = description
                        .with("media-type", new Value.Str(mime.full()))
                        .with("detected-by", new Value.Str(mime.detectedBy()));
            }
            return description.with("shown-as", new Value.Str(Values.display(input)));
        });
    }

    private static dev.mainframe.eval.Builtin pwd() {
        Signature signature = Signature.named("pwd", CATEGORY)
                .summary("show the directory you are in")
                .output(ValueType.PATH)
                .effect(Effect.READS)
                .build();
        return Cmd.of(signature, args -> new Value.PathVal(args.session().cwd()));
    }

    private static dev.mainframe.eval.Builtin cd() {
        Signature signature = Signature.named("cd", CATEGORY)
                .summary("go to another directory")
                .optional("directory", ValueType.PATH, "where to go; your home directory by default")
                .output(ValueType.PATH)
                .effect(Effect.SESSION)
                .example("cd ./src")
                .example("cd ..")
                .example("cd")
                .build();
        return Cmd.of(signature, args -> {
            Path target = args.has(0) ? args.path(0) : dev.mainframe.fs.SafeFs.userHome();
            if (!Files.exists(target)) {
                var error = args.fail("E502", "there is no directory at " + target);
                Path parent = target.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    String closest = Suggest.closest(target.getFileName().toString(), childNames(parent));
                    if (closest != null) error.hint("did you mean " + closest + "?");
                }
                return error.raise();
            }
            if (!Files.isDirectory(target)) {
                return args.fail("E503", target.getFileName() + " is a file, not a directory")
                        .hint("to read it, try: cat " + target.getFileName())
                        .raise();
            }
            args.session().cd(target);
            return new Value.PathVal(target);
        });
    }

    private static List<String> childNames(Path directory) {
        try (var children = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            children.forEach(p -> names.add(p.getFileName().toString()));
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static dev.mainframe.eval.Builtin echo() {
        Signature signature = Signature.named("echo", CATEGORY)
                .summary("pass values along, one per argument")
                .rest("values", ValueType.ANY, "whatever you want to send down the pipe")
                .output(ValueType.ANY)
                .example("echo \"hello\"")
                .example("echo 1 2 3 | length")
                .build();
        return Cmd.of(signature, args -> {
            if (args.count() == 0) return new Value.Str("");
            if (args.count() == 1) return args.value(0);
            List<Value> items = new ArrayList<>(args.count());
            for (int i = 0; i < args.count(); i++) items.add(args.value(i));
            return new Value.ListVal(List.copyOf(items));
        });
    }

    private static dev.mainframe.eval.Builtin which(Registry registry) {
        Signature signature = Signature.named("which", CATEGORY)
                .summary("say where a command comes from")
                .required("name", ValueType.STRING, "the command to look up")
                .output(ValueType.RECORD)
                .effect(Effect.READS)
                .example("which ls")
                .example("which git")
                .build();
        return Cmd.of(signature, args -> {
            String name = args.str(0);
            if (registry.has(name)) {
                Signature found = registry.get(name).signature();
                return Value.Rec.of(
                        "name", new Value.Str(name),
                        "kind", new Value.Str("builtin"),
                        "summary", new Value.Str(found.summary()),
                        "usage", new Value.Str(found.usage()));
            }
            // Looked up on MainFrame's PATH, so which agrees with what ^name will run.
            Path onPath = args.session().env().findProgram(name);
            if (onPath != null) {
                return Value.Rec.of(
                        "name", new Value.Str(name),
                        "kind", new Value.Str("external program"),
                        "path", new Value.PathVal(onPath),
                        "run-it-with", new Value.Str("^" + name));
            }
            var error = args.fail("E504", "nothing called " + name + " is a command or on your PATH");
            String closest = Suggest.closest(name, registry.names());
            if (closest != null) error.hint("did you mean the builtin " + closest + "?");
            error.hint("run path to see where MainFrame looks for programs");
            return error.raise();
        });
    }

    private static dev.mainframe.eval.Builtin version() {
        Signature signature = Signature.named("version", CATEGORY)
                .summary("show which MainFrame this is")
                .output(ValueType.RECORD)
                .build();
        return Cmd.of(signature, args -> Value.Rec.of(
                "mainframe", new Value.Str(VERSION),
                "runtime", new Value.Str(runtimeName()),
                "java", new Value.Str(System.getProperty("java.version", "unknown")),
                "os", new Value.Str(System.getProperty("os.name", "unknown"))));
    }

    private static String runtimeName() {
        // Set by native-image at build time; absent when running on the JVM.
        String image = System.getProperty("org.graalvm.nativeimage.imagecode");
        return image != null ? "native-image" : "jvm";
    }

    private static dev.mainframe.eval.Builtin exit() {
        Signature signature = Signature.named("exit", CATEGORY)
                .summary("leave MainFrame")
                .optional("code", ValueType.INT, "the exit code to report; 0 by default")
                .output(ValueType.NOTHING)
                .build();
        return Cmd.of(signature, args -> {
            throw new ExitRequest((int) args.lng(0, 0));
        });
    }
}
