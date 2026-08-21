package dev.mainframe.builtins;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * Turning values into text and back again.
 *
 * <p>These commands exist to make one workflow reliable: send results to a file,
 * read them back later, and get the same answer as if you had never stopped.
 * Every writer here uses {@link Values#source} for its values and every reader
 * uses {@link Values#parseAs}, so the written form and the read form cannot drift
 * apart. Where a format cannot carry a type -- JSON has no size, no moment -- the
 * command says so rather than quietly losing it.
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

    // ---- CSV: human-readable, and exact ------------------------------------------------

    private static Builtin toCsv() {
        Signature signature = Signature.named("to-csv", CATEGORY)
                .summary("write a table as CSV, with the column types in the header")
                .switchFlag("plain", '\0', "leave the types out of the header, for other programs")
                .input(ValueType.TABLE)
                .output(ValueType.STRING)
                .example("ls | to-csv | save listing.csv")
                .example("ls | to-csv --plain | save for-excel.csv")
                .build();
        return Cmd.of(signature, args -> {
            List<Value.Rec> rows = args.rows();
            List<String> columns = new ArrayList<>(Values.columns(rows));
            if (columns.isEmpty()) return new Value.Str("");

            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) csv.append(',');
                String header = columns.get(i);
                // The type goes in the header so that from-csv can hand back what
                // it was given rather than a table of text that looks similar.
                if (!args.flag("plain")) header += ":" + columnType(rows, columns.get(i)).display();
                csv.append(escape(header));
            }
            csv.append('\n');
            for (Value.Rec row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) csv.append(',');
                    Value cell = row.get(columns.get(i));
                    csv.append(escape(cell == null ? "" : cellText(cell)));
                }
                csv.append('\n');
            }
            return new Value.Str(csv.toString());
        });
    }

    /**
     * The written form of one cell. Deliberately the same text the language uses
     * for a literal -- 4mb, 2026-08-21T14:30:00.000-04:00 -- minus the quoting
     * that CSV does for itself.
     */
    private static String cellText(Value cell) {
        return switch (cell) {
            case Value.Str s -> s.value();
            case Value.PathVal p -> p.path().toString();
            case Value.Mime m -> m.full();
            case Value.Nothing _ -> "";
            default -> Values.source(cell);
        };
    }

    /** The one type every value in a column shares, or text when they disagree. */
    private static ValueType columnType(List<Value.Rec> rows, String column) {
        ValueType found = null;
        for (Value.Rec row : rows) {
            Value cell = row.get(column);
            if (cell == null || cell instanceof Value.Nothing) continue;
            ValueType type = ValueType.of(cell);
            if (found == null) found = type;
            else if (found != type) return ValueType.STRING;
        }
        return found == null ? ValueType.STRING : found;
    }

    private static Builtin fromCsv() {
        Signature signature = Signature.named("from-csv", CATEGORY)
                .summary("read CSV back into a table, restoring the types in its header")
                .switchFlag("text", '\0', "treat every column as text, whatever the header says")
                .input(ValueType.STRING)
                .output(ValueType.TABLE)
                .example("cat listing.csv | from-csv | where size > 1mb")
                .example("cat from-elsewhere.csv | from-csv --text")
                .build();
        return Cmd.of(signature, args -> {
            String text = Values.asString(args.input(), args.span());
            List<List<String>> lines = readCsv(text);
            if (lines.isEmpty()) return new Value.ListVal(List.of());

            List<String> names = new ArrayList<>();
            List<ValueType> types = new ArrayList<>();
            for (String header : lines.getFirst()) {
                int colon = header.lastIndexOf(':');
                ValueType type = ValueType.STRING;
                String name = header;
                if (colon > 0 && !args.flag("text")) {
                    ValueType named = typeNamed(header.substring(colon + 1));
                    if (named != null) {
                        type = named;
                        name = header.substring(0, colon);
                    }
                }
                names.add(name);
                types.add(type);
            }

            List<Value> rows = new ArrayList<>(lines.size() - 1);
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = lines.get(i);
                SequencedMap<String, Value> fields = new LinkedHashMap<>();
                for (int c = 0; c < names.size(); c++) {
                    String cell = c < cells.size() ? cells.get(c) : "";
                    fields.put(names.get(c), Values.parseAs(types.get(c), cell, args.span()));
                }
                rows.add(new Value.Rec(fields));
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static ValueType typeNamed(String name) {
        for (ValueType type : ValueType.values()) {
            if (type.display().equals(name.trim())) return type;
        }
        return null;
    }

    /** A CSV reader that understands quotes, doubled quotes and newlines inside them. */
    private static List<List<String>> readCsv(String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> { quoted = true; any = true; }
                case ',' -> { row.add(cell.toString()); cell.setLength(0); any = true; }
                case '\r' -> { }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (any || row.size() > 1 || !row.getFirst().isEmpty()) lines.add(List.copyOf(row));
                    row.clear();
                    any = false;
                }
                default -> { cell.append(c); any = true; }
            }
        }
        if (any || !cell.isEmpty()) {
            row.add(cell.toString());
            lines.add(List.copyOf(row));
        }
        return lines;
    }

    private static String escape(String cell) {
        boolean needsQuotes = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (!needsQuotes) return cell;
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    // ---- MainFrame's own written form ---------------------------------------------------

    private static Builtin toSource() {
        Signature signature = Signature.named("to-source", CATEGORY)
                .summary("write any value as MainFrame source, losing nothing")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("ls | to-source | save listing.mf")
                .example("echo 4mb | to-source")
                .build();
        return Cmd.of(signature, args -> new Value.Str(Values.source(args.input())));
    }

    private static Builtin fromSource() {
        Signature signature = Signature.named("from-source", CATEGORY)
                .summary("read a value written by to-source")
                .input(ValueType.STRING)
                .output(ValueType.ANY)
                .example("cat listing.mf | from-source | where size > 1mb")
                .build();
        return Cmd.of(signature, args -> {
            String text = Values.asString(args.input(), args.span()).trim();
            if (text.isEmpty()) return Value.Nothing.INSTANCE;
            try {
                // The written form is source, so reading it is just running it --
                // which is the whole point of the two being the same thing.
                return args.evalSource(Parser.parse(text));
            } catch (dev.mainframe.MfError e) {
                throw args.fail("E1101", "that is not something to-source wrote: " + e.getMessage())
                        .hint("to-source and from-source are a pair; for other text try from-json or from-csv")
                        .build();
            }
        });
    }

    // ---- JSON: interoperable, and honest about it ----------------------------------------

    private static Builtin toJson() {
        Signature signature = Signature.named("to-json", CATEGORY)
                .summary("write what came down the pipe as JSON, for other programs")
                .switchFlag("compact", 'c', "leave out the indentation")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("ls | select name size | to-json")
                .build();
        return Cmd.of(signature, args ->
                new Value.Str(Values.toJson(args.input(), args.flag("compact") ? 0 : 2)));
    }

    private static Builtin fromJson() {
        Signature signature = Signature.named("from-json", CATEGORY)
                .summary("read JSON text into values")
                .input(ValueType.STRING)
                .output(ValueType.ANY)
                .example("cat package.json | from-json | get name")
                .example("^curl -s https://example.com/data.json | from-json")
                .build();
        return Cmd.of(signature, args ->
                Json.parse(Values.asString(args.input(), args.span()), args.span()));
    }

    // ---- text ----------------------------------------------------------------------------

    private static Builtin lines() {
        Signature signature = Signature.named("lines", CATEGORY)
                .summary("split text into a list of lines")
                .switchFlag("keep-empty", '\0', "keep blank lines instead of dropping them")
                .input(ValueType.STRING)
                .output(ValueType.LIST)
                .example("cat notes.txt | lines | length")
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
