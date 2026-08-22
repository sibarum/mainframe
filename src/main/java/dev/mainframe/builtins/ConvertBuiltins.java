package dev.mainframe.builtins;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * Turning values into text and back again.
 *
 * <p>These are the same formats {@code save} and {@code open} use -- one
 * implementation each, in {@link Formats} -- so a file written by
 * {@code ... | to-csv | save x.csv} is the file {@code save x.csv} writes, and
 * either can be read by either. Most of the time you want {@code save} and
 * {@code open}, which pick the format from the file name; these exist for when
 * the text itself is what you are after.
 */
public final class ConvertBuiltins {

    private static final String CATEGORY = "converting";

    private ConvertBuiltins() {}

    public static void register(Registry registry) {
        registry.add(toCsv());
        registry.add(fromCsv());
        registry.add(toSource());
        registry.add(fromSource());
        registry.add(toJson());
        registry.add(fromJson());
        registry.add(lines());
        registry.add(toText());
    }

    // ---- CSV: rows, columns, and a type on every column ----------------------------------

    private static Builtin toCsv() {
        Signature signature = Signature.named("to-csv", CATEGORY)
                .summary("write a table as CSV, with the column types in the header")
                .switchFlag("plain", '\0', "leave the types out, for programs that do not want them")
                .input(ValueType.TABLE)
                .output(ValueType.STRING)
                .example("ls | to-csv")
                .example("ls | to-csv --plain | save for-excel.csv")
                .build();
        return Cmd.of(signature, args ->
                new Value.Str(Formats.toCsv(args.input(), args.flag("plain"), args)));
    }

    private static Builtin fromCsv() {
        Signature signature = Signature.named("from-csv", CATEGORY)
                .summary("read CSV into a table, restoring the types in its header")
                .switchFlag("text", '\0', "treat every column as text, whatever the header says")
                .input(ValueType.STRING)
                .output(ValueType.TABLE)
                .example("open listing.csv | where size > 1mb")
                .example("cat from-elsewhere.csv | from-csv")
                .build();
        return Cmd.of(signature, args ->
                Formats.fromCsv(Values.asString(args.input(), args.span()), args.flag("text"), args));
    }

    // ---- MainFrame's own written form -----------------------------------------------------

    private static Builtin toSource() {
        Signature signature = Signature.named("to-source", CATEGORY)
                .summary("write any value as MainFrame source, losing nothing")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("echo 4mb | to-source")
                .example("ls | save listing.mf")
                .build();
        return Cmd.of(signature, args -> new Value.Str(Values.source(args.input())));
    }

    private static Builtin fromSource() {
        Signature signature = Signature.named("from-source", CATEGORY)
                .summary("read a value written as MainFrame source")
                .input(ValueType.STRING)
                .output(ValueType.ANY)
                .example("open listing.mf | where size > 1mb")
                .build();
        return Cmd.of(signature, args ->
                Formats.read(Formats.Format.SOURCE, Values.asString(args.input(), args.span()), args));
    }

    // ---- JSON: for handing data to somebody else ------------------------------------------

    private static Builtin toJson() {
        Signature signature = Signature.named("to-json", CATEGORY)
                .summary("write what came down the pipe as JSON, a table as rows with a header")
                .switchFlag("compact", 'c', "leave out the indentation")
                .switchFlag("plain", '\0', "write a list of objects instead, losing the column types")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("ls | select name size | to-json")
                .example("ls | to-json --plain | save for-their-script.json")
                .build();
        return Cmd.of(signature, args ->
                new Value.Str(Formats.toJson(args.input(), args.flag("plain"), args.flag("compact"))));
    }

    private static Builtin fromJson() {
        Signature signature = Signature.named("from-json", CATEGORY)
                .summary("read JSON text into values")
                .input(ValueType.STRING)
                .output(ValueType.ANY)
                .example("open package.json | get name")
                .example("^curl -s https://example.com/data.json | from-json")
                .build();
        return Cmd.of(signature, args ->
                Formats.read(Formats.Format.JSON, Values.asString(args.input(), args.span()), args));
    }

    // ---- text ------------------------------------------------------------------------------

    private static Builtin lines() {
        Signature signature = Signature.named("lines", CATEGORY)
                .summary("split text into a list of lines")
                .switchFlag("keep-empty", '\0', "keep blank lines instead of dropping them")
                .input(ValueType.STRING)
                .output(ValueType.LIST)
                .example("open notes.txt | lines | length")
                .build();
        return Cmd.of(signature, args -> {
            String text = Values.asString(args.input(), args.span());
            List<Value> out = new ArrayList<>();
            text.lines().forEach(line -> {
                if (args.flag("keep-empty") || !line.isBlank()) out.add(new Value.Str(line));
            });
            return new Value.ListVal(List.copyOf(out));
        });
    }

    private static Builtin toText() {
        Signature signature = Signature.named("to-text", CATEGORY)
                .summary("flatten anything into plain text")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .effect(Effect.PURE)
                .example("ls | get name | to-text")
                .build();
        return Cmd.of(signature, args -> {
            Value input = args.input();
            if (input instanceof Value.ListVal list) {
                StringBuilder sb = new StringBuilder();
                for (Value item : list.items()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(Values.display(item));
                }
                return new Value.Str(sb.toString());
            }
            return new Value.Str(Values.display(input));
        });
    }
}
