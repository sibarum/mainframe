package dev.mainframe.value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.lang.Ast;

/**
 * Every value that flows through a MainFrame pipeline. Values are typed and
 * immutable-ish: a pipe carries structure, never re-parsed text, which is what
 * makes "no-surprise" behaviour possible.
 */
public sealed interface Value {

    /** The absence of a value. Printing it prints nothing at all. */
    record Nothing() implements Value {
        public static final Nothing INSTANCE = new Nothing();
    }

    record Bool(boolean value) implements Value {}

    /** Whole number. */
    record Int(long value) implements Value {}

    /** Fractional number. */
    record Float(double value) implements Value {}

    record Str(String value) implements Value {}

    /** A byte count. Distinct from Int so it can render as "1.4 MB" and compare with plain numbers. */
    record Size(long bytes) implements Value {}

    /** A moment in time, epoch millis. Rendered and read as local time. */
    record Time(long epochMillis) implements Value {}

    /** A span of time, in milliseconds. What you get from subtracting two moments. */
    record Duration(long millis) implements Value {}

    /** A filesystem path. Always absolute by the time it reaches a builtin. */
    record PathVal(Path path) implements Value {}

    /** A first-class media type, e.g. text/markdown. */
    record Mime(String type, String subtype, String detectedBy) implements Value {
        public String full() { return type + "/" + subtype; }
    }

    record ListVal(List<Value> items) implements Value {
        public static ListVal of(List<Value> items) { return new ListVal(List.copyOf(items)); }
    }

    /** An ordered map of named fields. A list of records is a table. */
    record Rec(SequencedMap<String, Value> fields) implements Value {
        public static Rec of(Object... kv) {
            SequencedMap<String, Value> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Value) kv[i + 1]);
            return new Rec(m);
        }
        public Value get(String name) { return fields.get(name); }
        public boolean has(String name) { return fields.containsKey(name); }
        public Rec with(String name, Value v) {
            SequencedMap<String, Value> m = new LinkedHashMap<>(fields);
            m.put(name, v);
            return new Rec(m);
        }
        public Rec only(List<String> names) {
            SequencedMap<String, Value> m = new LinkedHashMap<>();
            for (String n : names) if (fields.containsKey(n)) m.put(n, fields.get(n));
            return new Rec(m);
        }
    }

    /** An unevaluated `{ ... }` body, used by builtins like `each`. */
    record Block(Ast.Program body) implements Value {}

    // ---- convenience constructors -------------------------------------------------

    static Value nothing() { return Nothing.INSTANCE; }
    static Value of(boolean b) { return new Bool(b); }
    static Value of(long n) { return new Int(n); }
    static Value of(double d) { return new Float(d); }
    static Value of(String s) { return new Str(s); }
    static Value of(Path p) { return new PathVal(p); }

    static ListVal list(List<Value> items) { return ListVal.of(items); }
    static ListVal table(List<Rec> rows) { return new ListVal(List.copyOf(new ArrayList<Value>(rows))); }
}
