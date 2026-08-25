package dev.mainframe.builtins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;

import dev.mainframe.eval.Args;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * The formats MainFrame reads and writes, in one place.
 *
 * <h2>A file says what is in it</h2>
 * A format MainFrame saves its own data in has to carry its own schema. Nobody
 * should have to tell the shell a second time that a column holds sizes, just to
 * get filtering and ordering to work on data the shell itself wrote -- that is
 * the paperwork this whole design exists to avoid. So {@code save} picks the
 * format from the file name and {@code open} picks the reader the same way, and
 * a table written to a .csv comes back as the table that went in.
 *
 * <h2>Which formats can do that, and how</h2>
 * <ul>
 *   <li><b>Source</b> needs no schema: every value is written as the literal it
 *       would be typed as, so {@code 4mb} says what it is on its own.
 *   <li><b>CSV</b> has a header row, which is already metadata by universal
 *       agreement, so the types go there: {@code size:size}. One annotation per
 *       column, stated once.
 *   <li><b>JSON</b> has the same slot if a table is written as a table -- a
 *       header row and one array per row -- rather than as a list of objects.
 *       That is still ordinary JSON, it costs one annotation per column instead
 *       of repeating every key on every row, and it is about a third smaller for
 *       the trouble.
 * </ul>
 *
 * <p>The type-annotated header is what marks a file as one of ours, in both CSV
 * and JSON. Without it a file is what it appears to be: a CSV of text, a list of
 * lists. Nothing is ever inferred from what the values happen to look like.
 *
 * <p>{@code plain} turns the annotations off in either format, for handing data
 * to a program that wants an ordinary CSV or the usual list of objects.
 */
final class Formats {

    /** How the bytes in a file are arranged. */
    enum Format {
        /** Rows and columns, with a type on each column. Keeps everything. */
        CSV("csv"),
        /** MainFrame's own literals. Keeps everything, including values CSV has no shape for. */
        SOURCE("source"),
        /** For other programs. Keeps JSON's types, which are fewer than MainFrame's. */
        JSON("json"),
        /** Whatever text came down the pipe. */
        TEXT("text");

        private final String name;

        Format(String name) { this.name = name; }

        String display() { return name; }

        /** True when a value written this way comes back as the same value. */
        boolean keepsTypes() { return this != TEXT; }
    }

    private Formats() {}

    /** The format a file name implies. Unknown extensions are text. */
    static Format forFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? Format.TEXT : named(name.substring(dot + 1));
    }

    /** The format of that name, or null. */
    static Format named(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "csv" -> Format.CSV;
            case "json" -> Format.JSON;
            case "mf", "source" -> Format.SOURCE;
            case "txt", "text", "md", "log" -> Format.TEXT;
            default -> null;
        };
    }

    static String formatNames() { return "csv, json, source or text"; }

    // ---- writing ------------------------------------------------------------------------

    static String write(Format format, Value value, boolean plain, Args args) {
        return switch (format) {
            case CSV -> toCsv(value, plain, args);
            case JSON -> toJson(value, plain, false);
            case SOURCE -> Values.source(value);
            case TEXT -> asText(value);
        };
    }

    // ---- JSON: a table is rows, not a pile of repeated keys --------------------------------

    /**
     * Writes JSON. A table becomes a header row and one array per row, which is
     * ordinary JSON that happens to have somewhere to put the column types --
     * the same slot CSV has always had. It is also markedly smaller, since the
     * column names are stated once instead of once per row.
     *
     * <p>{@code plain} gives the array of objects most consumers expect, at the
     * cost of the types.
     */
    static String toJson(Value value, boolean plain, boolean compact) {
        if (plain || !Values.isTable(value) || Values.rows(value).isEmpty()) {
            return Values.toJson(value, compact ? 0 : 2);
        }
        List<Value.Rec> rows = Values.rows(value);
        List<String> columns = new ArrayList<>(Values.columns(rows));

        List<String> header = new ArrayList<>(columns.size());
        for (String column : columns) {
            header.add(Values.quoted(column + ":" + columnType(rows, column).display()));
        }

        StringBuilder json = new StringBuilder("[");
        newRow(json, compact);
        json.append('[').append(String.join(",", header)).append(']');
        for (Value.Rec row : rows) {
            json.append(',');
            newRow(json, compact);
            json.append('[');
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) json.append(compact ? "," : ", ");
                json.append(jsonCell(row.get(columns.get(i))));
            }
            json.append(']');
        }
        if (!compact) json.append('\n');
        return json.append(']').toString();
    }

    /** One row per line, because that is how a table wants to be read and diffed. */
    private static void newRow(StringBuilder json, boolean compact) {
        if (!compact) json.append("\n  ");
    }

    /**
     * A cell as JSON's own types where it has them, and text where it does not.
     * A reader that ignores our header still gets sensible JSON; one that reads it
     * gets the value back exactly.
     */
    private static String jsonCell(Value cell) {
        if (cell == null) return "null";
        return switch (cell) {
            case Value.Nothing _ -> "null";
            case Value.Bool b -> Boolean.toString(b.value());
            case Value.Int i -> Long.toString(i.value());
            case Value.Float f -> Values.toJson(f, 0);
            case Value.Size s -> Long.toString(s.bytes());
            case Value.Str s -> Values.quoted(s.value());
            case Value.PathVal p -> Values.quoted(Values.portable(p.path()));
            case Value.Mime m -> Values.quoted(m.full());
            // A record or a list is nested JSON, not a string that looks like one.
            // Its inner values carry JSON's types, since only the header row has
            // somewhere to say otherwise.
            case Value.Rec _, Value.ListVal _ -> Values.toJson(cell, 0);
            default -> Values.quoted(Values.source(cell));
        };
    }

    /**
     * Reads JSON, turning the table shape back into rows when it is one.
     *
     * <p>The type-annotated header is the marker, exactly as it is in CSV. JSON
     * that is merely a list of lists is a list of lists; nothing is guessed at
     * from what the values happen to look like.
     */
    static Value fromJson(String text, Args args) {
        Value parsed = Json.parse(text, args.span());
        List<String> header = tableHeader(parsed);
        if (header == null) return parsed;

        List<Value> items = ((Value.ListVal) parsed).items();
        List<String> names = new ArrayList<>(header.size());
        List<ValueType> types = new ArrayList<>(header.size());
        for (String column : header) {
            int colon = column.lastIndexOf(':');
            names.add(column.substring(0, colon));
            types.add(typeNamed(column.substring(colon + 1)));
        }

        List<Value> rows = new ArrayList<>(items.size() - 1);
        for (int i = 1; i < items.size(); i++) {
            List<Value> cells = ((Value.ListVal) items.get(i)).items();
            SequencedMap<String, Value> fields = new LinkedHashMap<>();
            for (int c = 0; c < names.size(); c++) {
                Value cell = c < cells.size() ? cells.get(c) : Value.Nothing.INSTANCE;
                fields.put(names.get(c), restore(types.get(c), cell, args));
            }
            rows.add(new Value.Rec(fields));
        }
        return new Value.ListVal(List.copyOf(rows));
    }

    /** A cell read back as the type the header declared. */
    private static Value restore(ValueType type, Value cell, Args args) {
        if (cell instanceof Value.Nothing) return cell;
        return switch (type) {
            case SIZE -> new Value.Size(Values.asLong(cell, args.span()));
            case INT -> new Value.Int(Values.asLong(cell, args.span()));
            case FLOAT -> new Value.Float(Values.asDouble(cell, args.span()));
            // Already the right shape: JSON nests natively.
            case BOOL, STRING, RECORD, LIST, TABLE -> cell;
            default -> Values.parseAs(type, Values.display(cell), args.span());
        };
    }

    /**
     * A CSV cell as the type its header declared. Records and lists have no shape
     * of their own in CSV, so they travel as the literal MainFrame would write and
     * are read back by running it.
     */
    private static Value readCell(ValueType type, String text, Args args) {
        if (type != ValueType.RECORD && type != ValueType.LIST && type != ValueType.TABLE) {
            return Values.parseAs(type, text, args.span());
        }
        return text.isBlank() ? Value.Nothing.INSTANCE : readSource(text, args);
    }

    /**
     * The column headings, if this is a table MainFrame wrote: a list whose first
     * item is a list of strings, every one of them naming a column and a type.
     */
    private static List<String> tableHeader(Value parsed) {
        if (!(parsed instanceof Value.ListVal list) || list.items().isEmpty()) return null;
        if (!(list.items().getFirst() instanceof Value.ListVal first) || first.items().isEmpty()) return null;
        List<String> header = new ArrayList<>(first.items().size());
        for (Value cell : first.items()) {
            if (!(cell instanceof Value.Str text)) return null;
            int colon = text.value().lastIndexOf(':');
            if (colon <= 0 || typeNamed(text.value().substring(colon + 1)) == null) return null;
            header.add(text.value());
        }
        for (int i = 1; i < list.items().size(); i++) {
            if (!(list.items().get(i) instanceof Value.ListVal)) return null;
        }
        return header;
    }

    private static String asText(Value value) {
        if (value instanceof Value.Str s) {
            return s.value().isEmpty() || s.value().endsWith("\n") ? s.value() : s.value() + "\n";
        }
        if (value instanceof Value.ListVal list) {
            StringBuilder sb = new StringBuilder();
            for (Value item : list.items()) sb.append(Values.display(item)).append('\n');
            return sb.toString();
        }
        String text = Values.display(value);
        return text.endsWith("\n") ? text : text + "\n";
    }

    static String toCsv(Value value, boolean plain, Args args) {
        List<Value.Rec> rows = Values.rows(value);
        if (!Values.isTable(value) && !(value instanceof Value.Rec)) {
            throw args.fail("E1105", "CSV holds rows and columns, and this is a "
                            + ValueType.of(value).display())
                    .hint("save it as .mf to keep it exactly, or .json to hand it to another program")
                    .build();
        }
        List<String> columns = new ArrayList<>(Values.columns(rows));
        if (columns.isEmpty()) return "";

        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) csv.append(',');
            // The type goes in the header so the file says what is in it, and
            // nobody has to say it again on the way back.
            String header = plain ? columns.get(i) : columns.get(i) + ":" + columnType(rows, columns.get(i)).display();
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
        return csv.toString();
    }

    /**
     * The written form of one cell: the same text the language uses for a literal
     * -- 4mb, 2026-08-21T14:30:00.000-04:00 -- minus the quoting CSV does itself.
     */
    private static String cellText(Value cell) {
        return switch (cell) {
            case Value.Str s -> s.value();
            case Value.PathVal p -> Values.portable(p.path());
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

    private static String escape(String cell) {
        boolean needsQuotes = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        return needsQuotes ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
    }

    // ---- reading ------------------------------------------------------------------------

    static Value read(Format format, String text, Args args) {
        return switch (format) {
            case CSV -> fromCsv(text, false, args);
            case JSON -> fromJson(text, args);
            case SOURCE -> readSource(text, args);
            case TEXT -> new Value.Str(text);
        };
    }

    private static Value readSource(String text, Args args) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return Value.Nothing.INSTANCE;
        try {
            // The written form is source, so reading it is running it -- which is
            // the whole point of the two being the same thing.
            return args.evalSource(Parser.parse(trimmed));
        } catch (dev.mainframe.MfError e) {
            throw args.fail("E1101", "that is not something MainFrame wrote: " + e.getMessage())
                    .hint("for other text try reading it as csv, json or text")
                    .build();
        }
    }

    static Value fromCsv(String text, boolean asText, Args args) {
        List<List<String>> lines = readCsvLines(text);
        if (lines.isEmpty()) return new Value.ListVal(List.of());

        List<String> names = new ArrayList<>();
        List<ValueType> types = new ArrayList<>();
        for (String header : lines.getFirst()) {
            int colon = header.lastIndexOf(':');
            ValueType type = ValueType.STRING;
            String name = header;
            if (colon > 0 && !asText) {
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
                fields.put(names.get(c), readCell(types.get(c),
                        c < cells.size() ? cells.get(c) : "", args));
            }
            rows.add(new Value.Rec(fields));
        }
        return new Value.ListVal(List.copyOf(rows));
    }

    /** The type of that name, or null. The same names the headers use. */
    static ValueType typeNamed(String name) {
        for (ValueType type : ValueType.values()) {
            if (type.display().equals(name.trim())) return type;
        }
        return null;
    }

    /** A CSV reader that understands quotes, doubled quotes and newlines inside them. */
    private static List<List<String>> readCsvLines(String text) {
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
}
