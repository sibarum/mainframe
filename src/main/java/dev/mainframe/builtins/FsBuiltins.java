package dev.mainframe.builtins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.fs.MimeDetector;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/** Working with files. Everything that changes a file plans first and asks when it matters. */
public final class FsBuiltins {

    private static final String CATEGORY = "files";

    private FsBuiltins() {}

    public static void register(Registry registry) {
        registry.add(ls());
        registry.add(cat());
        registry.add(open());
        registry.add(mime());
        registry.add(save());
        registry.add(mkdir());
        registry.add(cp());
        registry.add(mv());
        registry.add(rm());
    }

    /**
     * The paths a command should work on: the ones given as arguments, or the
     * {@code path} column of whatever came down the pipe.
     *
     * <p>This is why an index or a filter can drive any file command: the output
     * of ls, find and from-index all carry a path column, so they are all
     * interchangeable as input.
     */
    private static List<Path> targets(Args args, int from) {
        List<Path> paths = new ArrayList<>(args.paths(from));
        if (!paths.isEmpty()) return paths;
        for (Value.Rec row : args.rows()) {
            Value path = row.get("path");
            if (path instanceof Value.PathVal p) {
                paths.add(p.path());
            } else if (path != null) {
                paths.add(args.session().resolve(Values.asString(path, args.span())));
            }
        }
        if (paths.isEmpty()) {
            throw args.failUsage("E601", args.signature().name() + " needs at least one file")
                    .hint("name the files, or pipe rows in: find --ext=log | " + args.signature().name())
                    .build();
        }
        return paths;
    }

    // ---- looking ---------------------------------------------------------------------

    private static Builtin ls() {
        Signature signature = Signature.named("ls", CATEGORY)
                .summary("list what is in a directory")
                .optional("directory", ValueType.PATH, "which directory to list; the current one by default")
                .switchFlag("all", 'a', "include entries whose name starts with a dot")
                .switchFlag("recurse", 'r', "look inside subdirectories too")
                .switchFlag("deep", '\0', "read each file to be sure of its media type")
                .valueFlag("max-depth", '\0', ValueType.INT, "how far to recurse; unlimited by default")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("ls")
                .example("ls ./src --recurse | where mime =~ \"text/\"")
                .build();
        return Cmd.of(signature, args -> {
            Path directory = args.path(0, args.session().cwd());
            if (!Files.exists(directory)) {
                throw args.fail("E602", "there is nothing at " + SafeFs.describe(args.session().cwd(), directory))
                        .hint("check the spelling, or run ls on the directory above it")
                        .build();
            }
            SafeFs.MimeMode mode = args.flag("deep") ? SafeFs.MimeMode.CONTENT : SafeFs.MimeMode.NAME;
            if (!Files.isDirectory(directory)) {
                return new Value.ListVal(List.of(SafeFs.row(directory, mode)));
            }
            boolean all = args.flag("all");
            int maxDepth = (int) Math.min(Integer.MAX_VALUE,
                    args.flagLong("max-depth", args.flag("recurse") ? Integer.MAX_VALUE : 1));
            List<Path> found = new ArrayList<>();
            collect(directory, all, maxDepth, 1, found);
            found.sort(Comparator
                    .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                    .thenComparing(p -> p.getFileName().toString().toLowerCase()));
            List<Value> rows = new ArrayList<>(found.size());
            for (Path p : found) rows.add(SafeFs.row(p, mode));
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static void collect(Path directory, boolean all, int maxDepth, int depth, List<Path> into) {
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!all && name.startsWith(".")) continue;
                into.add(child);
                if (depth < maxDepth && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    collect(child, all, maxDepth, depth + 1, into);
                }
            }
        } catch (IOException e) {
            // A directory we cannot read contributes nothing rather than failing the listing.
        }
    }

    private static Builtin cat() {
        Signature signature = Signature.named("cat", CATEGORY)
                .summary("read a text file")
                .rest("files", ValueType.PATH, "the files to read; taken from the pipe if you name none")
                .switchFlag("lines", 'l', "give back a list of lines instead of one piece of text")
                .switchFlag("force", 'f', "read it even if it does not look like text")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .effect(Effect.READS)
                .example("cat README.md")
                .example("cat notes.txt --lines | length")
                .build();
        return Cmd.of(signature, args -> {
            List<Path> files = targets(args, 0);
            StringBuilder text = new StringBuilder();
            for (Path file : files) {
                if (Files.isDirectory(file)) {
                    throw args.fail("E603", SafeFs.describe(args.session().cwd(), file) + " is a directory")
                            .hint("list it instead: ls " + SafeFs.describe(args.session().cwd(), file))
                            .build();
                }
                if (!Files.isReadable(file)) {
                    throw args.fail("E604", "cannot read " + SafeFs.describe(args.session().cwd(), file))
                            .hint("check it exists and that you have permission")
                            .build();
                }
                Value.Mime type = MimeDetector.detect(file);
                if (!args.flag("force") && !isTextual(type)) {
                    throw args.fail("E605", SafeFs.describe(args.session().cwd(), file)
                                    + " is " + type.full() + ", not text")
                            .hint("printing it would scramble your terminal")
                            .hint("use cat --force if you really want the raw bytes")
                            .build();
                }
                try {
                    if (!text.isEmpty()) text.append('\n');
                    // --force means "show me anyway", so undecodable bytes become
                    // replacement characters instead of an error.
                    text.append(args.flag("force")
                            ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                            : Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw args.fail("E606", "could not read " + file.getFileName() + ": " + e.getMessage()).build();
                }
            }
            if (!args.flag("lines")) return new Value.Str(text.toString());
            List<Value> lines = new ArrayList<>();
            text.toString().lines().forEach(line -> lines.add(new Value.Str(line)));
            return new Value.ListVal(List.copyOf(lines));
        });
    }

    private static boolean isTextual(Value.Mime mime) {
        if (mime.type().equals("text")) return true;
        return switch (mime.full()) {
            case "application/json", "application/xml", "application/yaml", "application/toml",
                 "application/sql", "application/x-shellscript", "application/x-powershell",
                 "application/x-ndjson", "application/x-bat", "image/svg+xml", "application/x-empty" -> true;
            default -> false;
        };
    }

    private static Builtin mime() {
        Signature signature = Signature.named("mime", CATEGORY)
                .summary("say what a file really is, by looking inside it")
                .rest("files", ValueType.PATH, "the files to inspect; taken from the pipe if you name none")
                .input(ValueType.ANY)
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("mime ./downloads/mystery")
                .example("ls | mime | where mime.type == \"image\"")
                .build();
        return Cmd.of(signature, args -> {
            List<Value> rows = new ArrayList<>();
            for (Path file : targets(args, 0)) {
                Value.Mime type = MimeDetector.detect(file);
                Value.Mime byName = MimeDetector.fromName(file.getFileName().toString());
                boolean misleading = byName != null && !byName.full().equals(type.full())
                        && type.detectedBy().equals("content");
                rows.add(Value.Rec.of(
                        "name", new Value.Str(file.getFileName().toString()),
                        "mime", type,
                        "detected-by", new Value.Str(type.detectedBy()),
                        "size", new Value.Size(SafeFs.treeSize(file)),
                        "name-suggests", byName == null ? Value.Nothing.INSTANCE : new Value.Str(byName.full()),
                        "misleading-name", new Value.Bool(misleading),
                        "path", new Value.PathVal(file)));
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    // ---- changing --------------------------------------------------------------------

    private static Builtin save() {
        Signature signature = Signature.named("save", CATEGORY)
                .summary("write what came down the pipe to a file, in the format its name implies")
                .required("file", ValueType.PATH, "where to write")
                .switchFlag("force", 'f', "overwrite the file if it already exists")
                .switchFlag("plain", '\0', "leave the column types out, for programs that do not want them")
                .valueFlag("as", '\0', ValueType.STRING,
                        "write it as " + Formats.formatNames() + ", whatever the file is called")
                .input(ValueType.ANY)
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("ls | save listing.csv")
                .example("ls | save listing.json")
                .example("cat a.txt | save b.txt --force")
                .build();
        return Cmd.planning(signature, args -> {
            Path file = args.path(0);
            Plan plan = new Plan("write");
            if (Files.isDirectory(file)) {
                throw args.fail("E607", file.getFileName() + " is a directory, so it cannot be overwritten")
                        .hint("give a file name instead")
                        .build();
            }
            boolean exists = Files.exists(file);
            if (exists && !args.flag("force")) {
                throw args.fail("E608", SafeFs.describe(args.session().cwd(), file) + " already exists")
                        .hint("add --force to replace it")
                        .hint("or pick another name so nothing is lost")
                        .build();
            }
            Formats.Format format = chosenFormat(args, file);
            // Text is written as it stands. Only structured values get converted,
            // so `ls | to-csv | save x.csv` writes CSV rather than CSV wrapped in
            // something -- the file is the same either way you build it.
            if (args.input() instanceof Value.Str && !args.hasFlag("as")) format = Formats.Format.TEXT;

            byte[] bytes = Formats.write(format, args.input(), args.flag("plain"), args).getBytes(StandardCharsets.UTF_8);

            // A table on its way into a form that cannot hold column types is
            // worth a word now, rather than a surprise on the way back in.
            boolean keeps = format.keepsTypes() && !args.flag("plain");
            if (!keeps && Values.isTable(args.input()) && !args.rows().isEmpty()) {
                plan.note("written without column types, so sizes and times read back as plain"
                        + " numbers and text" + (args.flag("plain")
                        ? " -- that is what --plain means" : " -- save it as .csv or .json to keep them"));
            }
            String what = (exists ? "replace " : "create ") + SafeFs.describe(args.session().cwd(), file)
                    + " (" + Values.formatSize(bytes.length) + ", " + format.display() + ")";
            return plan.step(what, () -> SafeFs.atomicWrite(file, bytes));
        });
    }

    /** The format the user asked for, or the one the file name implies. */
    private static Formats.Format chosenFormat(Args args, Path file) {
        if (!args.hasFlag("as")) return Formats.forFile(file);
        String asked = args.flagStr("as", "");
        Formats.Format format = Formats.named(asked);
        if (format == null) {
            throw args.fail("E619", "\"" + asked + "\" is not a format I know")
                    .hint("the formats are " + Formats.formatNames())
                    .build();
        }
        return format;
    }

    private static Builtin open() {
        Signature signature = Signature.named("open", CATEGORY)
                .summary("read a file back in, in the format its name implies")
                .rest("files", ValueType.PATH, "the files to read; taken from the pipe if you name none")
                .valueFlag("as", '\0', ValueType.STRING,
                        "read it as " + Formats.formatNames() + ", whatever the file is called")
                .input(ValueType.ANY)
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("open listing.csv | where size > 1mb")
                .example("open notes.txt")
                .example("open data.dat --as=csv")
                .build();
        return Cmd.of(signature, args -> {
            List<Path> files = targets(args, 0);
            List<Value> read = new ArrayList<>(files.size());
            for (Path file : files) {
                Formats.Format format = chosenFormat(args, file);
                read.add(Formats.read(format, readText(args, file), args));
            }
            if (read.size() == 1) return read.getFirst();
            // Several files of rows join up into one table, which is what anybody
            // opening a folder of them wanted.
            List<Value> joined = new ArrayList<>();
            for (Value value : read) {
                if (value instanceof Value.ListVal list) joined.addAll(list.items());
                else joined.add(value);
            }
            return new Value.ListVal(List.copyOf(joined));
        });
    }

    /** Reads a file as text, refusing binary for the same reason cat does. */
    private static String readText(Args args, Path file) {
        if (Files.isDirectory(file)) {
            throw args.fail("E620", SafeFs.describe(args.session().cwd(), file) + " is a directory")
                    .hint("list it instead: ls " + SafeFs.describe(args.session().cwd(), file))
                    .build();
        }
        Value.Mime type = MimeDetector.detect(file);
        if (!isTextual(type)) {
            throw args.fail("E621", SafeFs.describe(args.session().cwd(), file)
                            + " is " + type.full() + ", which MainFrame cannot read back")
                    .hint("open handles csv, json, MainFrame source and plain text")
                    .build();
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw args.fail("E622", "could not read " + file.getFileName() + ": " + e.getMessage()).build();
        }
    }

    private static Builtin mkdir() {
        Signature signature = Signature.named("mkdir", CATEGORY)
                .summary("make directories, including any missing parents")
                .rest("directories", ValueType.PATH, "the directories to create")
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("mkdir ./build/reports")
                .build();
        return Cmd.planning(signature, args -> {
            Plan plan = new Plan("create");
            List<Path> directories = args.paths(0);
            if (directories.isEmpty()) {
                throw args.failUsage("E609", "mkdir needs at least one directory name").build();
            }
            for (Path directory : directories) {
                if (Files.isDirectory(directory)) {
                    plan.note(SafeFs.describe(args.session().cwd(), directory) + " already exists -- left alone");
                    continue;
                }
                if (Files.exists(directory)) {
                    throw args.fail("E610", SafeFs.describe(args.session().cwd(), directory)
                                    + " already exists as a file")
                            .hint("pick another name")
                            .build();
                }
                plan.step("create " + SafeFs.describe(args.session().cwd(), directory),
                        () -> Files.createDirectories(directory));
            }
            return plan;
        });
    }

    private static Builtin cp() {
        Signature signature = Signature.named("cp", CATEGORY)
                .summary("copy files somewhere else")
                .rest("files", ValueType.PATH, "what to copy; taken from the pipe if you name none")
                .valueFlag("to", 't', ValueType.PATH, "the directory (or new name) to copy into")
                .switchFlag("force", 'f', "replace files at the destination")
                .input(ValueType.ANY)
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("cp ./notes.txt --to=./backup")
                .example("find --ext=png | cp --to=./images")
                .build();
        return Cmd.planning(signature, args -> transfer(args, false));
    }

    private static Builtin mv() {
        Signature signature = Signature.named("mv", CATEGORY)
                .summary("move or rename files")
                .rest("files", ValueType.PATH, "what to move; taken from the pipe if you name none")
                .valueFlag("to", 't', ValueType.PATH, "the directory (or new name) to move into")
                .switchFlag("force", 'f', "replace files at the destination")
                .input(ValueType.ANY)
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("mv ./draft.md --to=./posts/final.md")
                .example("find --ext=tmp | mv --to=./scratch")
                .build();
        return Cmd.planning(signature, args -> transfer(args, true));
    }

    /**
     * cp and mv share one shape: sources, and one explicit --to. Naming the
     * destination with a flag means the last argument is never silently treated
     * as a target, which is the classic way to lose a file.
     */
    private static Plan transfer(Args args, boolean move) {
        String verb = move ? "move" : "copy";
        if (!args.hasFlag("to")) {
            throw args.failUsage("E611", verb + " needs to know where to put things")
                    .hint("add --to=<directory>, for example: " + args.signature().name() + " ... --to=./backup")
                    .build();
        }
        Path destination = args.session().resolve(args.flagStr("to", "."));
        List<Path> sources = targets(args, 0);
        boolean intoDirectory = Files.isDirectory(destination) || sources.size() > 1;
        if (sources.size() > 1 && !Files.isDirectory(destination)) {
            throw args.fail("E612", "cannot put " + sources.size() + " files into "
                            + SafeFs.describe(args.session().cwd(), destination) + ", which is not a directory")
                    .hint("create it first: mkdir " + SafeFs.describe(args.session().cwd(), destination))
                    .build();
        }

        Plan plan = new Plan(verb);
        for (Path source : sources) {
            if (!Files.exists(source)) {
                throw args.fail("E613", "there is nothing at " + SafeFs.describe(args.session().cwd(), source))
                        .hint("nothing has been " + verb + "d -- fix the list and run it again")
                        .build();
            }
            Path target = intoDirectory ? destination.resolve(source.getFileName().toString()) : destination;
            if (target.equals(source)) {
                plan.note("skipping " + source.getFileName() + " -- it is already there");
                continue;
            }
            boolean exists = Files.exists(target);
            if (exists && !args.flag("force")) {
                throw args.fail("E614", SafeFs.describe(args.session().cwd(), target) + " already exists")
                        .hint("add --force to replace it")
                        .hint("nothing has been " + verb + "d yet")
                        .build();
            }
            String what = verb + " " + SafeFs.describe(args.session().cwd(), source)
                    + " to " + SafeFs.describe(args.session().cwd(), target)
                    + (exists ? " (replacing what is there)" : "");
            plan.step(what, () -> {
                Files.createDirectories(target.toAbsolutePath().getParent());
                if (move) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                } else if (Files.isDirectory(source)) {
                    SafeFs.copyTree(source, target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            });
        }
        return plan;
    }

    private static Builtin rm() {
        Signature signature = Signature.named("rm", CATEGORY)
                .summary("delete files, by moving them to the MainFrame trash")
                .rest("files", ValueType.PATH, "what to delete; taken from the pipe if you name none")
                .switchFlag("recurse", 'r', "allow whole directories to go")
                .switchFlag("purge", '\0', "delete for real instead of using the trash")
                .input(ValueType.ANY)
                .output(ValueType.TABLE)
                .effect(Effect.DESTRUCTIVE)
                .example("rm ./old.log")
                .example("rm ./build --recurse --dry-run")
                .example("find --ext=tmp | rm --yes")
                .build();
        return Cmd.planning(signature, args -> {
            List<Path> victims = targets(args, 0);
            boolean purge = args.flag("purge");
            Plan plan = new Plan(purge ? "permanently delete" : "move to trash");
            for (Path victim : victims) {
                if (!Files.exists(victim, LinkOption.NOFOLLOW_LINKS)) {
                    plan.note("skipping " + SafeFs.describe(args.session().cwd(), victim) + " -- already gone");
                    continue;
                }
                boolean directory = Files.isDirectory(victim, LinkOption.NOFOLLOW_LINKS);
                if (directory && !args.flag("recurse")) {
                    throw args.fail("E615", SafeFs.describe(args.session().cwd(), victim)
                                    + " is a directory holding " + SafeFs.countTree(victim) + " item(s)")
                            .hint("add --recurse if you mean to delete all of it")
                            .hint("or see what is in there first: ls "
                                    + SafeFs.describe(args.session().cwd(), victim))
                            .build();
                }
                if (victim.equals(args.session().cwd())) {
                    throw args.fail("E616", "that is the directory you are standing in")
                            .hint("cd somewhere else first")
                            .build();
                }
                if (victim.getParent() == null) {
                    throw args.fail("E617", "MainFrame will not delete a filesystem root").build();
                }
                String size = Values.formatSize(SafeFs.treeSize(victim));
                String what = (purge ? "delete " : "trash ") + SafeFs.describe(args.session().cwd(), victim)
                        + (directory ? " (directory, " + SafeFs.countTree(victim) + " items, " + size + ")"
                        : " (" + size + ")");
                plan.step(what, () -> {
                    if (purge) SafeFs.deleteTree(victim);
                    else SafeFs.trash(victim);
                });
            }
            if (!purge && !plan.isEmpty()) {
                plan.note("recoverable from " + SafeFs.stateDir().resolve("trash"));
            }
            return plan;
        });
    }
}
