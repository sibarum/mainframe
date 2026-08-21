package dev.mainframe.builtins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.fs.FsIndex;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * Filesystem indexes: build one, keep it current, and search it.
 *
 * <p>An index is a table like any other, so {@code find} is not a special search
 * language -- it is a source you can pipe into anything, and every other command
 * accepts its rows.
 */
public final class IndexBuiltins {

    private static final String CATEGORY = "searching";

    private IndexBuiltins() {}

    public static void register(Registry registry) {
        registry.add(indexBuild());
        registry.add(indexSync());
        registry.add(indexList());
        registry.add(indexDrop());
        registry.add(fromIndex());
        registry.add(find());
    }

    private static FsIndex load(Args args, String name) {
        IndexStore store = args.session().indexes();
        if (!store.exists(name)) {
            var error = args.fail("E801", "there is no index called " + name);
            String closest = Suggest.closest(name, store.names());
            if (closest != null) error.hint("did you mean " + closest + "?");
            error.hint(store.names().isEmpty()
                    ? "make one with: index-build " + name + " ./some/directory"
                    : "you have: " + String.join(", ", store.names()));
            throw error.build();
        }
        try {
            return store.load(name);
        } catch (IOException | IllegalArgumentException e) {
            throw args.fail("E802", "could not read the index " + name + ": " + e.getMessage())
                    .hint("rebuild it with: index-build " + name + " <directory>")
                    .build();
        }
    }

    private static Builtin indexBuild() {
        Signature signature = Signature.named("index-build", CATEGORY)
                .summary("index a directory tree so searches are instant")
                .required("name", ValueType.STRING, "what to call this index")
                .required("directory", ValueType.PATH, "the directory to index")
                .switchFlag("deep", '\0', "read every file to be sure of its media type (slower)")
                .valueFlag("skip", '\0', ValueType.STRING,
                        "a name or pattern to leave out; repeat for more, e.g. --skip=.git --skip=target")
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("index-build code ./src --skip=target")
                .example("index-build home ~ --skip=.git --deep")
                .build();
        return Cmd.planning(signature, args -> {
            String name = args.str(0);
            Path root = args.path(1);
            if (!Files.isDirectory(root)) {
                throw args.fail("E803", SafeFs.describe(args.session().cwd(), root) + " is not a directory")
                        .hint("an index covers a directory tree")
                        .build();
            }
            if (name.contains("/") || name.contains("\\") || name.contains(".")) {
                throw args.fail("E804", "an index name should be a simple word")
                        .hint("for example: index-build code " + SafeFs.describe(args.session().cwd(), root))
                        .build();
            }
            boolean replacing = args.session().indexes().exists(name);
            List<String> skips = args.flagList("skip");
            FsIndex index = new FsIndex(name, root, skips, args.flag("deep"), 0);
            String what = (replacing ? "rebuild" : "build") + " index " + name + " over "
                    + SafeFs.describe(args.session().cwd(), root)
                    + (skips.isEmpty() ? "" : " skipping " + String.join(", ", skips));
            return new Plan("build").step(what, () -> {
                FsIndex.SyncResult result = index.sync();
                args.session().indexes().save(index);
                args.session().out().note("  indexed " + result.added() + " entries");
            });
        });
    }

    private static Builtin indexSync() {
        Signature signature = Signature.named("index-sync", CATEGORY)
                .summary("bring an index back in line with what is on disk")
                .optional("name", ValueType.STRING, "which index; all of them by default")
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("index-sync code")
                .example("index-sync")
                .build();
        return Cmd.planning(signature, args -> {
            List<String> names = args.has(0)
                    ? List.of(args.str(0))
                    : args.session().indexes().names();
            Plan plan = new Plan("sync");
            if (names.isEmpty()) {
                return plan.note("there are no indexes yet -- make one with index-build");
            }
            for (String name : names) {
                FsIndex index = load(args, name);
                plan.step("sync " + name + " (" + index.size() + " entries under "
                        + SafeFs.describe(args.session().cwd(), index.root()) + ")", () -> {
                    FsIndex.SyncResult result = index.sync();
                    args.session().indexes().save(index);
                    args.session().out().note("  " + name + ": " + result.added() + " added, "
                            + result.updated() + " updated, " + result.removed() + " gone, "
                            + result.unchanged() + " unchanged");
                });
            }
            return plan;
        });
    }

    private static Builtin indexList() {
        Signature signature = Signature.named("index-list", CATEGORY)
                .summary("show the indexes you have")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("index-list")
                .build();
        return Cmd.of(signature, args -> {
            IndexStore store = args.session().indexes();
            List<Value> rows = new ArrayList<>();
            for (String name : store.names()) {
                try {
                    FsIndex index = store.load(name);
                    rows.add(Value.Rec.of(
                            "name", new Value.Str(name),
                            "root", new Value.PathVal(index.root()),
                            "entries", new Value.Int(index.size()),
                            "deep", new Value.Bool(index.deep()),
                            "skips", new Value.Str(String.join(", ", index.skips())),
                            "updated", new Value.Time(index.updatedAt()),
                            "on-disk", new Value.Size(store.fileSize(name))));
                } catch (IOException | IllegalArgumentException e) {
                    rows.add(Value.Rec.of(
                            "name", new Value.Str(name),
                            "root", Value.Nothing.INSTANCE,
                            "entries", Value.Nothing.INSTANCE,
                            "problem", new Value.Str(e.getMessage())));
                }
            }
            if (rows.isEmpty()) {
                args.session().out().note("no indexes yet -- try: index-build code ./src");
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin indexDrop() {
        Signature signature = Signature.named("index-drop", CATEGORY)
                .summary("throw away an index")
                .required("name", ValueType.STRING, "which index to remove")
                .output(ValueType.TABLE)
                .effect(Effect.DESTRUCTIVE)
                .example("index-drop code")
                .build();
        return Cmd.planning(signature, args -> {
            String name = args.str(0);
            FsIndex index = load(args, name);
            return new Plan("drop")
                    .step("drop index " + name + " (" + index.size() + " entries)",
                            () -> args.session().indexes().drop(name))
                    .note("the files themselves are untouched -- only the index is removed");
        });
    }

    private static Builtin fromIndex() {
        Signature signature = Signature.named("from-index", CATEGORY)
                .summary("read a whole index in as a table")
                .required("name", ValueType.STRING, "which index to read")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("from-index code | count-by ext")
                .example("from-index home | where size > 100mb | sort-by size --reverse")
                .build();
        return Cmd.of(signature, args -> {
            FsIndex index = load(args, args.str(0));
            return Value.table(index.rows());
        });
    }

    private static Builtin find() {
        Signature signature = Signature.named("find", CATEGORY)
                .summary("search for files, using an index when you have one")
                .optional("pattern", ValueType.STRING,
                        "matched against the name; * and ? are wildcards, anything else is a substring")
                .valueFlag("index", 'i', ValueType.STRING, "search this index instead of walking the disk")
                .valueFlag("in", '\0', ValueType.PATH, "the directory to walk; the current one by default")
                .valueFlag("ext", 'e', ValueType.STRING, "only files with this extension")
                .valueFlag("mime", 'm', ValueType.STRING, "only files whose media type matches")
                .valueFlag("kind", 'k', ValueType.STRING, "file or dir")
                .valueFlag("larger", '\0', ValueType.SIZE, "only files bigger than this")
                .valueFlag("smaller", '\0', ValueType.SIZE, "only files smaller than this")
                .valueFlag("limit", '\0', ValueType.INT, "stop after this many results")
                .output(ValueType.TABLE)
                .effect(Effect.READS)
                .example("find \"*.java\" --index=code")
                .example("find --ext=png --larger=1mb | sort-by size --reverse")
                .example("find --mime=\"text/\" --in=./docs | cp --to=./backup")
                .build();
        return Cmd.of(signature, args -> {
            List<Value.Rec> candidates;
            if (args.hasFlag("index")) {
                candidates = load(args, args.flagStr("index", "")).rows();
            } else {
                Path root = args.session().resolve(args.flagStr("in", "."));
                if (!Files.isDirectory(root)) {
                    throw args.fail("E805", SafeFs.describe(args.session().cwd(), root) + " is not a directory")
                            .hint("give --in a directory, or search an index with --index=<name>")
                            .build();
                }
                candidates = walk(args, root);
            }

            String pattern = args.str(0, null);
            String ext = args.flagStr("ext", null);
            String mime = args.flagStr("mime", null);
            String kind = args.flagStr("kind", null);
            long larger = args.flagLong("larger", -1);
            long smaller = args.flagLong("smaller", -1);
            long limit = args.flagLong("limit", Long.MAX_VALUE);
            if (kind != null && !kind.equals("file") && !kind.equals("dir")) {
                throw args.fail("E806", "--kind is either file or dir, not " + kind).build();
            }

            List<Value> matches = new ArrayList<>();
            for (Value.Rec row : candidates) {
                if (matches.size() >= limit) break;
                if (pattern != null && !Values.matches(Values.display(row.get("name")), pattern)) continue;
                if (ext != null && !ext.equalsIgnoreCase(Values.display(row.get("ext")))) continue;
                if (kind != null && !kind.equals(Values.display(row.get("kind")))) continue;
                if (mime != null) {
                    Value type = row.get("mime");
                    if (type == null || type instanceof Value.Nothing) continue;
                    if (!Values.matches(Values.display(type), mime)) continue;
                }
                if (larger >= 0 || smaller >= 0) {
                    long size = Values.asLong(row.get("size") == null ? new Value.Int(0) : row.get("size"),
                            args.span());
                    if (larger >= 0 && size <= larger) continue;
                    if (smaller >= 0 && size >= smaller) continue;
                }
                matches.add(row);
            }
            if (matches.isEmpty()) {
                args.session().out().note("nothing matched -- "
                        + (args.hasFlag("index") ? "the index may be out of date, try index-sync"
                        : "try a wider pattern, or build an index with index-build"));
            }
            return new Value.ListVal(List.copyOf(matches));
        });
    }

    /** A live walk, producing the same rows an index would. */
    private static List<Value.Rec> walk(Args args, Path root) {
        List<Value.Rec> rows = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (path.equals(root)) continue;
                rows.add(SafeFs.row(path, SafeFs.MimeMode.NAME));
            }
        } catch (IOException e) {
            throw args.fail("E807", "could not search " + SafeFs.describe(args.session().cwd(), root)
                            + ": " + e.getMessage())
                    .hint("some directories may not be readable")
                    .build();
        }
        return rows;
    }
}
