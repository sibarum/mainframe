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
 * <h2>Which formats can do that</h2>
 * CSV can: its header carries a type per column. MainFrame's own source form can:
 * every value is written as the literal it would be typed as. JSON cannot -- a
 * JSON array has nowhere to put a schema without ceasing to be an ordinary JSON
 * array, which is the only reason anybody wants JSON. So JSON is the format for
 * handing data to somebody else's program, it reads back as the types JSON has,
 * and {@code save} says so at the moment you write one rather than leaving you to
 * find out on the way back in.
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
        boolean keepsTypes() { return this == CSV || this == SOURCE; }
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

    static String write(Format format, Value value, Args args) {
        return switch (format) {
            case CSV -> toCsv(value, false, args);
            case JSON -> Values.toJson(value, 2);
            case SOURCE -> Values.source(value);
            case TEXT -> asText(value);
        };
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
            case JSON -> Json.parse(text, args.span());
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
                fields.put(names.get(c), Values.parseAs(types.get(c),
                        c < cells.size() ? cells.get(c) : "", args.span()));
            }
            rows.add(new Value.Rec(fields));
        }
        return new Value.ListVal(List.copyOf(rows));
    }

    private static ValueType typeNamed(String name) {
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
