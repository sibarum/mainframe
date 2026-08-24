package dev.mainframe.value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import dev.mainframe.MfError;
import dev.mainframe.Span;

/** Coercions, comparisons and rendering of {@link Value}s. */
public final class Values {

    private Values() {}

    // ---- shape -------------------------------------------------------------------

    /** A table is a list whose every item is a record. The empty list counts. */
    public static boolean isTable(Value v) {
        if (!(v instanceof Value.ListVal l)) return false;
        for (Value item : l.items()) if (!(item instanceof Value.Rec)) return false;
        return true;
    }

    public static List<Value.Rec> rows(Value v) {
        if (v instanceof Value.Rec r) return List.of(r);
        if (v instanceof Value.ListVal l) {
            List<Value.Rec> out = new ArrayList<>(l.items().size());
            for (Value item : l.items()) if (item instanceof Value.Rec r) out.add(r);
            return out;
        }
        return List.of();
    }

    /** Column order for a table: first-seen order across all rows. */
    public static SequencedSet<String> columns(List<Value.Rec> rows) {
        SequencedSet<String> cols = new LinkedHashSet<>();
        for (Value.Rec r : rows) cols.addAll(r.fields().keySet());
        return cols;
    }

    /** Anything that is not nothing, false, zero or empty is true. */
    public static boolean truthy(Value v) {
        return switch (v) {
            case Value.Nothing _ -> false;
            case Value.Bool b -> b.value();
            case Value.Int i -> i.value() != 0;
            case Value.Float f -> f.value() != 0;
            case Value.Size s -> s.bytes() != 0;
            case Value.Str s -> !s.value().isEmpty();
            case Value.ListVal l -> !l.items().isEmpty();
            case Value.Rec r -> !r.fields().isEmpty();
            default -> true;
        };
    }

    // ---- coercion ----------------------------------------------------------------

    public static String asString(Value v, Span span) {
        return switch (v) {
            case Value.Str s -> s.value();
            case Value.PathVal p -> p.path().toString();
            case Value.Mime m -> m.full();
            case Value.Int i -> Long.toString(i.value());
            case Value.Float f -> trimDouble(f.value());
            case Value.Bool b -> Boolean.toString(b.value());
            case Value.Size s -> Long.toString(s.bytes());
            case Value.Time t -> Times.display(t.epochMillis());
            case Value.Duration d -> Times.duration(d.millis());
            case Value.Nothing _ -> "";
            default -> throw MfError.of("E201", "cannot use a " + ValueType.of(v).display() + " as text")
                    .at(span).hint("pipe it through to-json if you want its text form").build();
        };
    }

    public static long asLong(Value v, Span span) {
        return switch (v) {
            case Value.Int i -> i.value();
            case Value.Size s -> s.bytes();
            case Value.Float f -> (long) f.value();
            case Value.Bool b -> b.value() ? 1 : 0;
            case Value.Time t -> t.epochMillis();
            case Value.Duration d -> d.millis();
            default -> throw MfError.of("E202", "expected a number, got a " + ValueType.of(v).display())
                    .at(span).build();
        };
    }

    public static double asDouble(Value v, Span span) {
        return switch (v) {
            case Value.Int i -> i.value();
            case Value.Size s -> s.bytes();
            case Value.Float f -> f.value();
            case Value.Bool b -> b.value() ? 1 : 0;
            default -> throw MfError.of("E202", "expected a number, got a " + ValueType.of(v).display())
                    .at(span).build();
        };
    }

    /**
     * Numbers, and the things that are numbers with a unit. Moments and spans are
     * deliberately not here: comparing a time against a plain number would
     * silently compare epoch milliseconds, which is the sort of quiet nonsense
     * MainFrame is supposed to refuse.
     */
    public static boolean isNumeric(Value v) {
        return v instanceof Value.Int || v instanceof Value.Float || v instanceof Value.Size;
    }

    private static double numeric(Value v) {
        return switch (v) {
            case Value.Int i -> i.value();
            case Value.Float f -> f.value();
            case Value.Size s -> s.bytes();
            default -> throw new IllegalStateException("not numeric: " + v);
        };
    }

    // ---- comparison --------------------------------------------------------------

    public static boolean equal(Value a, Value b) {
        if (a instanceof Value.Nothing && b instanceof Value.Nothing) return true;
        if (a instanceof Value.Time x && b instanceof Value.Time y) return x.epochMillis() == y.epochMillis();
        if (a instanceof Value.Duration x && b instanceof Value.Duration y) return x.millis() == y.millis();
        if (isNumeric(a) && isNumeric(b)) return numeric(a) == numeric(b);
        if (a instanceof Value.Bool x && b instanceof Value.Bool y) return x.value() == y.value();
        if (isTextish(a) && isTextish(b)) return text(a).equals(text(b));
        if (a instanceof Value.ListVal x && b instanceof Value.ListVal y) {
            if (x.items().size() != y.items().size()) return false;
            for (int i = 0; i < x.items().size(); i++) {
                if (!equal(x.items().get(i), y.items().get(i))) return false;
            }
            return true;
        }
        if (a instanceof Value.Rec x && b instanceof Value.Rec y) {
            if (!x.fields().keySet().equals(y.fields().keySet())) return false;
            for (var e : x.fields().entrySet()) if (!equal(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        return false;
    }

    /** Orders two values, or explains why they cannot be ordered. */
    public static int compare(Value a, Value b, Span span) {
        // Moments compare with moments and spans with spans, and neither with a
        // bare number: "modified > 5" should be an error, not epoch arithmetic.
        if (a instanceof Value.Time x && b instanceof Value.Time y) {
            return Long.compare(x.epochMillis(), y.epochMillis());
        }
        if (a instanceof Value.Duration x && b instanceof Value.Duration y) {
            return Long.compare(x.millis(), y.millis());
        }
        if (isNumeric(a) && isNumeric(b)) return Double.compare(numeric(a), numeric(b));
        if (isTextish(a) && isTextish(b)) return text(a).compareToIgnoreCase(text(b));
        if (a instanceof Value.Bool x && b instanceof Value.Bool y) return Boolean.compare(x.value(), y.value());
        if (a instanceof Value.Nothing && b instanceof Value.Nothing) return 0;
        // Nothing sorts last, so a half-empty column does not blow up a sort.
        if (a instanceof Value.Nothing) return 1;
        if (b instanceof Value.Nothing) return -1;
        MfError.Builder error = MfError.of("E203",
                        "cannot compare a " + ValueType.of(a).display() + " with a " + ValueType.of(b).display())
                .at(span);
        if (a instanceof Value.Time || b instanceof Value.Time) {
            error.hint("write the moment out: 2026-08-21, or 2026-08-21T14:30");
            error.hint("or compare against one: where modified > (now - 7d)");
        } else if (a instanceof Value.Duration || b instanceof Value.Duration) {
            error.hint("spans of time are written with a unit: 90m, 2h, 7d");
        } else {
            error.hint("compare a single field instead, e.g. sort-by name rather than the whole row");
        }
        throw error.build();
    }

    private static boolean isTextish(Value v) {
        return v instanceof Value.Str || v instanceof Value.PathVal || v instanceof Value.Mime;
    }

    private static String text(Value v) {
        return switch (v) {
            case Value.Str s -> s.value();
            case Value.PathVal p -> p.path().toString();
            case Value.Mime m -> m.full();
            default -> throw new IllegalStateException("not textish: " + v);
        };
    }

    /**
     * The =~ operator. A pattern containing * or ? is a glob anchored at both
     * ends; anything else is a plain case-insensitive substring test, which is
     * what people mean when they write: where mime =~ "text/"
     */
    public static boolean matches(String subject, String pattern) {
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return subject.toLowerCase().contains(pattern.toLowerCase());
        }
        return globMatch(subject.toLowerCase(), pattern.toLowerCase(), 0, 0);
    }

    private static boolean globMatch(String s, String p, int si, int pi) {
        while (pi < p.length()) {
            char pc = p.charAt(pi);
            if (pc == '*') {
                while (pi + 1 < p.length() && p.charAt(pi + 1) == '*') pi++;
                if (pi == p.length() - 1) return true;
                for (int k = si; k <= s.length(); k++) {
                    if (globMatch(s, p, k, pi + 1)) return true;
                }
                return false;
            }
            if (si >= s.length()) return false;
            if (pc != '?' && pc != s.charAt(si)) return false;
            si++;
            pi++;
        }
        return si == s.length();
    }

    // ---- rendering ---------------------------------------------------------------

    /** One-line human form, used for table cells and scalar output. */
    public static String display(Value v) {
        return switch (v) {
            case Value.Nothing _ -> "";
            case Value.Bool b -> b.value() ? "true" : "false";
            case Value.Int i -> Long.toString(i.value());
            case Value.Float f -> trimDouble(f.value());
            case Value.Str s -> s.value();
            case Value.Size s -> formatSize(s.bytes());
            case Value.Time t -> Times.display(t.epochMillis());
            case Value.Duration d -> Times.displayDuration(d.millis());
            case Value.PathVal p -> p.path().toString();
            case Value.Mime m -> m.full();
            case Value.Block _ -> "{block}";
            case Value.Rec r -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (var e : r.fields().entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(": ").append(display(e.getValue()));
                    first = false;
                }
                yield sb.append('}').toString();
            }
            case Value.ListVal l -> {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(display(l.items().get(i)));
                }
                yield sb.append(']').toString();
            }
        };
    }

    public static String formatSize(long bytes) {
        if (bytes < 0 && bytes > Long.MIN_VALUE) return "-" + formatSize(-bytes);
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double n = bytes / 1024.0;
        int u = 0;
        while (n >= 1024 && u < units.length - 1) { n /= 1024; u++; }
        return (n < 10 ? String.format("%.1f", n) : String.format("%.0f", n)) + " " + units[u];
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    // ---- the written form --------------------------------------------------------------

    /**
     * The canonical written form of a value: text that is valid MainFrame source
     * and that reads back as the identical value.
     *
     * <p>This is the one format MainFrame uses whenever a value has to leave the
     * pipeline and come back -- a cell in a CSV, a line in a file, a literal
     * someone types. Because writing and reading share this one definition,
     * {@code fetch | filter | save} followed by {@code open | aggregate} gives the
     * same answer as doing it in a single pipeline. Anything that writes values
     * some other way is a bug, not a feature.
     */
    public static String source(Value v) {
        return switch (v) {
            case Value.Nothing _ -> "nothing";
            case Value.Bool b -> b.value() ? "true" : "false";
            case Value.Int i -> Long.toString(i.value());
            // A whole float still has to read back as a float, so it keeps its point.
            case Value.Float f -> f.value() == Math.rint(f.value()) && Math.abs(f.value()) < 1e15
                    ? (long) f.value() + ".0"
                    : Double.toString(f.value());
            case Value.Str s -> quoted(s.value());
            case Value.Size s -> sizeSource(s.bytes());
            case Value.Time t -> Times.machine(t.epochMillis());
            case Value.Duration d -> Times.duration(d.millis());
            case Value.PathVal p -> "path" + quoted(portable(p.path()));
            case Value.Mime m -> "mime" + quoted(m.full());
            case Value.Block _ -> "{ }";
            case Value.ListVal l -> {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(source(l.items().get(i)));
                }
                yield sb.append(']').toString();
            }
            case Value.Rec r -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (var e : r.fields().entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(quoted(e.getKey())).append(": ").append(source(e.getValue()));
                    first = false;
                }
                yield sb.append('}').toString();
            }
        };
    }

    /**
     * A size written so that it reads back exactly: the largest unit that divides
     * it without remainder. 4 MB is {@code 4mb}; one byte more is {@code 4194305b}.
     */
    public static String sizeSource(long bytes) {
        if (bytes == 0) return "0b";
        String sign = bytes < 0 ? "-" : "";
        long size = Math.abs(bytes);
        long[] units = {1024L * 1024 * 1024 * 1024, 1024L * 1024 * 1024, 1024L * 1024, 1024L};
        String[] suffixes = {"tb", "gb", "mb", "kb"};
        for (int i = 0; i < units.length; i++) {
            if (size % units[i] == 0) return sign + (size / units[i]) + suffixes[i];
        }
        return sign + size + "b";
    }

    /**
     * Reads a value back from its written form, as the given type.
     *
     * <p>The counterpart to {@link #source}: whatever that writes, this reads. It
     * never guesses -- text is only interpreted as a time or a size because the
     * caller says the column holds one -- because a value that changes type
     * depending on what it looks like is how a spreadsheet eats a phone number.
     */
    public static Value parseAs(ValueType type, String text, Span span) {
        String trimmed = text.trim();
        if (trimmed.equals("nothing") || (trimmed.isEmpty() && type != ValueType.STRING)) {
            return Value.Nothing.INSTANCE;
        }
        try {
            return switch (type) {
                case STRING, ANY -> new Value.Str(text);
                case BOOL -> new Value.Bool(readBool(trimmed));
                case INT -> new Value.Int(Long.parseLong(trimmed));
                case FLOAT, NUMBER -> readNumber(trimmed);
                case SIZE -> new Value.Size(readSize(trimmed));
                case TIME -> new Value.Time(Times.parse(trimmed));
                case DURATION -> new Value.Duration(readDuration(trimmed));
                case PATH -> new Value.PathVal(java.nio.file.Path.of(trimmed));
                case MIME -> readMime(trimmed);
                default -> throw new IllegalArgumentException(
                        type.withArticle() + " has no written form to read back");
            };
        } catch (IllegalArgumentException e) {
            throw MfError.of("E205", "cannot read \"" + trimmed + "\" as " + type.withArticle())
                    .at(span)
                    .hint(e.getMessage() == null ? "check the written form" : e.getMessage())
                    .hint(type.withArticle() + " is written like " + example(type))
                    .build();
        }
    }

    private static String example(ValueType type) {
        return switch (type) {
            case BOOL -> "true or false";
            case INT -> "42";
            case FLOAT, NUMBER -> "1.5";
            case SIZE -> "4mb, or 4194305b";
            case TIME -> "2026-08-21T14:30:00.000-04:00, or 2026-08-21";
            case DURATION -> "7d, 90m, 500ms";
            case MIME -> "text/plain";
            default -> "text";
        };
    }

    private static boolean readBool(String text) {
        if (text.equals("true")) return true;
        if (text.equals("false")) return false;
        throw new IllegalArgumentException("only true and false are accepted, not \"" + text + "\"");
    }

    private static Value readNumber(String text) {
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            return new Value.Int(Long.parseLong(text));
        }
        return new Value.Float(Double.parseDouble(text));
    }

    private static long readSize(String text) {
        int split = unitStart(text);
        String unit = text.substring(split).toLowerCase();
        long multiplier = switch (unit) {
            case "", "b" -> 1L;
            case "kb" -> 1024L;
            case "mb" -> 1024L * 1024;
            case "gb" -> 1024L * 1024 * 1024;
            case "tb" -> 1024L * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException(
                    "\"" + unit + "\" is not a size unit; use b, kb, mb, gb or tb");
        };
        return (long) (Double.parseDouble(text.substring(0, split)) * multiplier);
    }

    private static long readDuration(String text) {
        int split = unitStart(text);
        String unit = text.substring(split).toLowerCase();
        Long multiplier = unit.isEmpty() ? 1L : Times.durationUnit(unit);
        if (multiplier == null) {
            throw new IllegalArgumentException(
                    "\"" + unit + "\" is not a unit of time; use " + Times.durationUnits());
        }
        return (long) (Double.parseDouble(text.substring(0, split)) * multiplier);
    }

    private static int unitStart(String text) {
        int split = 0;
        while (split < text.length()
                && (Character.isDigit(text.charAt(split)) || text.charAt(split) == '.'
                || text.charAt(split) == '-' || text.charAt(split) == '+')) {
            split++;
        }
        if (split == 0) throw new IllegalArgumentException("\"" + text + "\" does not start with a number");
        return split;
    }

    private static Value readMime(String text) {
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new IllegalArgumentException("media types look like text/plain");
        }
        return new Value.Mime(text.substring(0, slash), text.substring(slash + 1), "written");
    }

    /**
     * A path written with forward slashes, whatever this machine calls a
     * separator.
     *
     * <p>A file written on Windows gets opened on a Mac. Backslashes in it would
     * be an unreadable path there and a pile of escapes in the JSON besides,
     * whereas forward slashes are read correctly by every platform -- Windows
     * included. Reading turns them back into whatever the local system uses, so
     * the value is unchanged on the machine that wrote it and usable on one that
     * did not.
     */
    public static String portable(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Text as a MainFrame string literal, escapes and all. */
    public static String quoted(String text) {
        StringBuilder sb = new StringBuilder();
        quote(text, sb);
        return sb.toString();
    }

    public static String toJson(Value v, int indent) {
        StringBuilder sb = new StringBuilder();
        json(v, sb, indent, 0);
        return sb.toString();
    }

    private static void json(Value v, StringBuilder sb, int indent, int depth) {
        switch (v) {
            case Value.Nothing _ -> sb.append("null");
            case Value.Bool b -> sb.append(b.value());
            case Value.Int i -> sb.append(i.value());
            case Value.Size s -> sb.append(s.bytes());
            case Value.Float f -> sb.append(trimDouble(f.value()));
            case Value.Time t -> quote(Times.machine(t.epochMillis()), sb);
            case Value.Duration d -> quote(Times.duration(d.millis()), sb);
            case Value.Str s -> quote(s.value(), sb);
            case Value.PathVal p -> quote(portable(p.path()), sb);
            case Value.Mime m -> quote(m.full(), sb);
            case Value.Block _ -> quote("{block}", sb);
            case Value.ListVal l -> {
                if (l.items().isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) sb.append(',');
                    newline(sb, indent, depth + 1);
                    json(l.items().get(i), sb, indent, depth + 1);
                }
                newline(sb, indent, depth);
                sb.append(']');
            }
            case Value.Rec r -> {
                if (r.fields().isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (var e : r.fields().entrySet()) {
                    if (!first) sb.append(',');
                    newline(sb, indent, depth + 1);
                    quote(e.getKey(), sb);
                    sb.append(':');
                    if (indent > 0) sb.append(' ');
                    json(e.getValue(), sb, indent, depth + 1);
                    first = false;
                }
                newline(sb, indent, depth);
                sb.append('}');
            }
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent <= 0) return;
        sb.append('\n').append(" ".repeat(indent * depth));
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
