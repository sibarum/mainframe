package dev.mainframe.builtins;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.lang.Ast;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/** Shaping what comes down the pipe: filtering, picking columns, sorting, counting. */
public final class TableBuiltins {

    private static final String CATEGORY = "shaping data";

    private TableBuiltins() {}

    public static void register(Registry registry) {
        registry.add(where());
        registry.add(morph());
        registry.add(select());
        registry.add(reject());
        registry.add(sortBy());
        registry.add(first());
        registry.add(last());
        registry.add(reverse());
        registry.add(length());
        registry.add(get());
        registry.add(each());
        registry.add(uniq());
        registry.add(countBy());
        registry.add(sum());
    }

    /** Wraps a plain value so a row expression can still refer to it, as {@code it}. */
    private static Value.Rec asRow(Value value) {
        return value instanceof Value.Rec rec ? rec : Value.Rec.of("it", value);
    }

    private static void requireColumn(Args args, Value.Rec row, String column) {
        if (row.has(column)) return;
        var error = args.fail("E701", "there is no column called " + column);
        String closest = Suggest.closest(column, row.fields().keySet());
        if (closest != null) error.hint("did you mean " + closest + "?");
        error.hint("the columns here are: " + String.join(", ", row.fields().keySet()));
        throw error.build();
    }

    private static Builtin where() {
        Signature signature = Signature.named("where", CATEGORY)
                .summary("keep only the rows that match")
                .condition("condition", "a test written in terms of the column names")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | where size > 1mb")
                .example("ls | where mime =~ \"text/\" and name !~ \"test\"")
                .build();
        return Cmd.of(signature, args -> {
            Ast.Expr condition = args.expr(0);
            List<Value> kept = new ArrayList<>();
            for (Value item : args.items()) {
                if (Values.truthy(args.evalInRow(condition, asRow(item)))) kept.add(item);
            }
            return new Value.ListVal(List.copyOf(kept));
        });
    }

    private static Builtin morph() {
        Signature signature = Signature.named("morph", CATEGORY)
                .summary("reshape every row into a new one")
                .condition("shape", "a record describing the row you want, written in terms of the columns you have")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | morph {file: name, mb: size / 1mb}")
                .example("open theirs.json | morph {name: full_name, spend: cents / 100}")
                .example("ls | morph {file: {name: name, bytes: size}} | save for-them.json")
                .build();
        return Cmd.of(signature, args -> {
            Ast.Expr shape = args.expr(0);
            List<Value> morphed = new ArrayList<>();
            for (Value item : args.items()) morphed.add(args.evalInRow(shape, asRow(item)));
            return new Value.ListVal(List.copyOf(morphed));
        });
    }

    private static Builtin select() {
        Signature signature = Signature.named("select", CATEGORY)
                .summary("keep only the columns you name, in that order")
                .rest("columns", ValueType.STRING, "the columns to keep")
                .input(ValueType.TABLE)
                .output(ValueType.TABLE)
                .example("ls | select name size")
                .build();
        return Cmd.of(signature, args -> {
            List<String> columns = args.strings(0);
            if (columns.isEmpty()) {
                throw args.failUsage("E702", "select needs at least one column name").build();
            }
            List<Value> rows = new ArrayList<>();
            for (Value.Rec row : args.rows()) {
                for (String column : columns) requireColumn(args, row, column);
                rows.add(row.only(columns));
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin reject() {
        Signature signature = Signature.named("reject", CATEGORY)
                .summary("drop the columns you name")
                .rest("columns", ValueType.STRING, "the columns to leave out")
                .input(ValueType.TABLE)
                .output(ValueType.TABLE)
                .example("ls | reject path where")
                .build();
        return Cmd.of(signature, args -> {
            List<String> columns = args.strings(0);
            List<Value> rows = new ArrayList<>();
            for (Value.Rec row : args.rows()) {
                SequencedMap<String, Value> kept = new LinkedHashMap<>(row.fields());
                for (String column : columns) kept.remove(column);
                rows.add(new Value.Rec(kept));
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin sortBy() {
        Signature signature = Signature.named("sort-by", CATEGORY)
                .summary("order rows by one or more columns")
                .rest("columns", ValueType.STRING, "the columns to order by, most important first")
                .switchFlag("reverse", 'r', "largest or latest first")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | sort-by size --reverse")
                .example("ls | sort-by kind name")
                .build();
        return Cmd.of(signature, args -> {
            List<String> columns = args.strings(0);
            List<Value> items = new ArrayList<>(args.items());
            if (columns.isEmpty()) {
                items.sort((a, b) -> Values.compare(a, b, args.span()));
            } else {
                for (Value item : items) {
                    Value.Rec row = asRow(item);
                    for (String column : columns) requireColumn(args, row, column);
                }
                items.sort((a, b) -> {
                    for (String column : columns) {
                        int result = Values.compare(asRow(a).get(column), asRow(b).get(column), args.span());
                        if (result != 0) return result;
                    }
                    return 0;
                });
            }
            if (args.flag("reverse")) java.util.Collections.reverse(items);
            return new Value.ListVal(List.copyOf(items));
        });
    }

    private static Builtin first() {
        Signature signature = Signature.named("first", CATEGORY)
                .summary("take the first few items")
                .optional("count", ValueType.INT, "how many to take; 1 by default")
                .input(ValueType.LIST)
                .output(ValueType.ANY)
                .example("ls | sort-by size --reverse | first 5")
                .build();
        return Cmd.of(signature, args -> take(args, true));
    }

    private static Builtin last() {
        Signature signature = Signature.named("last", CATEGORY)
                .summary("take the last few items")
                .optional("count", ValueType.INT, "how many to take; 1 by default")
                .input(ValueType.LIST)
                .output(ValueType.ANY)
                .example("ls | sort-by modified | last 3")
                .build();
        return Cmd.of(signature, args -> take(args, false));
    }

    private static Value take(Args args, boolean fromStart) {
        List<Value> items = args.items();
        long requested = args.lng(0, 1);
        if (requested < 0) {
            throw args.fail("E703", "cannot take " + requested + " items")
                    .hint("give a count of zero or more")
                    .build();
        }
        int count = (int) Math.min(requested, items.size());
        List<Value> slice = fromStart
                ? items.subList(0, count)
                : items.subList(items.size() - count, items.size());
        // Asking for one thing gives you the thing, not a list holding it.
        if (!args.has(0) && slice.size() == 1) return slice.getFirst();
        return new Value.ListVal(List.copyOf(slice));
    }

    private static Builtin reverse() {
        Signature signature = Signature.named("reverse", CATEGORY)
                .summary("turn the order upside down")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | reverse")
                .build();
        return Cmd.of(signature, args -> {
            List<Value> items = new ArrayList<>(args.items());
            java.util.Collections.reverse(items);
            return new Value.ListVal(List.copyOf(items));
        });
    }

    private static Builtin length() {
        Signature signature = Signature.named("length", CATEGORY)
                .summary("count how many items there are")
                .input(ValueType.ANY)
                .output(ValueType.INT)
                .example("ls | where kind == \"file\" | length")
                .build();
        return Cmd.of(signature, args -> {
            Value input = args.input();
            if (input instanceof Value.ListVal list) return new Value.Int(list.items().size());
            if (input instanceof Value.Rec rec) return new Value.Int(rec.fields().size());
            if (input instanceof Value.Str str) return new Value.Int(str.value().length());
            if (input instanceof Value.Nothing) return new Value.Int(0);
            return new Value.Int(1);
        });
    }

    private static Builtin get() {
        Signature signature = Signature.named("get", CATEGORY)
                .summary("pull one column out as a plain list")
                .required("column", ValueType.STRING, "the column to pull out")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | get name")
                .build();
        return Cmd.of(signature, args -> {
            String column = args.str(0);
            List<Value> values = new ArrayList<>();
            for (Value item : args.items()) {
                Value.Rec row = asRow(item);
                requireColumn(args, row, column);
                values.add(row.get(column));
            }
            return new Value.ListVal(List.copyOf(values));
        });
    }

    private static Builtin each() {
        Signature signature = Signature.named("each", CATEGORY)
                .summary("run a block once per item")
                .required("block", ValueType.BLOCK, "what to do with each item, which arrives as $it")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | get name | each { echo $it }")
                .build();
        return Cmd.of(signature, args -> {
            if (!(args.value(0) instanceof Value.Block block)) {
                throw args.failUsage("E704", "each needs a block in braces")
                        .hint("for example: ls | each { echo $it }")
                        .build();
            }
            List<Value> results = new ArrayList<>();
            for (Value item : args.items()) {
                Value result = args.runBlock(block, item);
                if (!(result instanceof Value.Nothing)) results.add(result);
            }
            return new Value.ListVal(List.copyOf(results));
        });
    }

    private static Builtin uniq() {
        Signature signature = Signature.named("uniq", CATEGORY)
                .summary("drop repeats, keeping the first of each")
                .optional("column", ValueType.STRING, "compare only this column")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .example("ls | get ext | uniq")
                .build();
        return Cmd.of(signature, args -> {
            String column = args.str(0, null);
            List<Value> kept = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (Value item : args.items()) {
                Value key = item;
                if (column != null) {
                    Value.Rec row = asRow(item);
                    requireColumn(args, row, column);
                    key = row.get(column);
                }
                String fingerprint = Values.display(key);
                if (seen.contains(fingerprint)) continue;
                seen.add(fingerprint);
                kept.add(item);
            }
            return new Value.ListVal(List.copyOf(kept));
        });
    }

    private static Builtin countBy() {
        Signature signature = Signature.named("count-by", CATEGORY)
                .summary("count how many rows share each value of a column")
                .required("column", ValueType.STRING, "the column to group on")
                .input(ValueType.LIST)
                .output(ValueType.TABLE)
                .example("ls --recurse | count-by ext")
                .example("ls | mime | count-by mime")
                .build();
        return Cmd.of(signature, args -> {
            String column = args.str(0);
            SequencedMap<String, Integer> counts = new LinkedHashMap<>();
            for (Value item : args.items()) {
                Value.Rec row = asRow(item);
                requireColumn(args, row, column);
                String key = Values.display(row.get(column));
                counts.merge(key.isEmpty() ? "(none)" : key, 1, Integer::sum);
            }
            List<Value> rows = new ArrayList<>(counts.size());
            counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> rows.add(Value.Rec.of(
                            column, new Value.Str(e.getKey()),
                            "count", new Value.Int(e.getValue()))));
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin sum() {
        Signature signature = Signature.named("sum", CATEGORY)
                .summary("add up a column of numbers or sizes")
                .optional("column", ValueType.STRING, "the column to add up; the items themselves by default")
                .input(ValueType.LIST)
                .output(ValueType.NUMBER)
                .example("ls | sum size")
                .build();
        return Cmd.of(signature, args -> {
            String column = args.str(0, null);
            double total = 0;
            boolean sized = false;
            boolean spans = false;
            boolean fractional = false;
            for (Value item : args.items()) {
                Value value = item;
                if (column != null) {
                    Value.Rec row = asRow(item);
                    requireColumn(args, row, column);
                    value = row.get(column);
                }
                if (value instanceof Value.Duration d) {
                    spans = true;
                    total += d.millis();
                    continue;
                }
                if (!Values.isNumeric(value)) {
                    throw args.fail("E705", "cannot add up a " + ValueType.of(value).display())
                            .hint(column == null
                                    ? "name a column: sum <column>"
                                    : "the " + column + " column holds " + ValueType.of(value).display() + " values")
                            .build();
                }
                sized |= value instanceof Value.Size;
                fractional |= value instanceof Value.Float;
                total += Values.asDouble(value, args.span());
            }
            if (spans) return new Value.Duration((long) total);
            if (fractional) return new Value.Float(total);
            return sized ? new Value.Size((long) total) : new Value.Int((long) total);
        });
    }
}
