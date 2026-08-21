package dev.mainframe.value;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import dev.mainframe.MfError;
import dev.mainframe.Span;

/** Coercions, comparisons and rendering of {@link Value}s. */
public final class Values {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

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
            case Value.Time t -> STAMP.format(Instant.ofEpochMilli(t.epochMillis()));
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

    public static boolean isNumeric(Value v) {
        return v instanceof Value.Int || v instanceof Value.Float || v instanceof Value.Size
                || v instanceof Value.Time;
    }

    private static double numeric(Value v) {
        return switch (v) {
            case Value.Int i -> i.value();
            case Value.Float f -> f.value();
            case Value.Size s -> s.bytes();
            case Value.Time t -> t.epochMillis();
            default -> throw new IllegalStateException("not numeric: " + v);
        };
    }

    // ---- comparison --------------------------------------------------------------

    public static boolean equal(Value a, Value b) {
        if (a instanceof Value.Nothing && b instanceof Value.Nothing) return true;
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
        if (isNumeric(a) && isNumeric(b)) return Double.compare(numeric(a), numeric(b));
        if (isTextish(a) && isTextish(b)) return text(a).compareToIgnoreCase(text(b));
        if (a instanceof Value.Bool x && b instanceof Value.Bool y) return Boolean.compare(x.value(), y.value());
        if (a instanceof Value.Nothing && b instanceof Value.Nothing) return 0;
        // Nothing sorts last, so a half-empty column does not blow up a sort.
        if (a instanceof Value.Nothing) return 1;
        if (b instanceof Value.Nothing) return -1;
        throw MfError.of("E203",
                        "cannot compare a " + ValueType.of(a).display() + " with a " + ValueType.of(b).display())
                .at(span)
                .hint("compare a single field instead, e.g. sort-by name rather than the whole row")
                .build();
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
            case Value.Time t -> STAMP.format(Instant.ofEpochMilli(t.epochMillis()));
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
            case Value.Time t -> quote(STAMP.format(Instant.ofEpochMilli(t.epochMillis())), sb);
            case Value.Str s -> quote(s.value(), sb);
            case Value.PathVal p -> quote(p.path().toString(), sb);
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
