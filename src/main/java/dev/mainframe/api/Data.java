package dev.mainframe.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;

import dev.mainframe.Span;
import dev.mainframe.fs.MimeDetector;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * A typed value on its way into or out of a hosted command.
 *
 * <p>This is the whole data contract between MainFrame and the program hosting
 * it. Values arrive with their types intact -- a size is a size, a media type is
 * a media type, a table is rows of named fields -- so a callback never has to
 * parse text that the shell had already understood.
 */
public final class Data {

    private static final Data NOTHING = new Data(Value.Nothing.INSTANCE);

    private final Value value;

    private Data(Value value) { this.value = value; }

    static Data wrap(Value value) { return value == null ? NOTHING : new Data(value); }

    Value unwrap() { return value; }

    // ---- making values ----------------------------------------------------------------

    public static Data nothing() { return NOTHING; }

    public static Data text(String text) { return new Data(new Value.Str(text == null ? "" : text)); }

    public static Data number(long number) { return new Data(new Value.Int(number)); }

    public static Data decimal(double number) { return new Data(new Value.Float(number)); }

    public static Data bool(boolean yes) { return new Data(new Value.Bool(yes)); }

    /** A byte count, which prints as "1.4 MB" and compares with plain numbers. */
    public static Data size(long bytes) { return new Data(new Value.Size(bytes)); }

    public static Data time(Instant when) {
        return new Data(new Value.Time(when == null ? 0 : when.toEpochMilli()));
    }

    public static Data path(Path path) { return new Data(new Value.PathVal(path)); }

    /** @param full a media type such as {@code text/markdown} */
    public static Data mime(String full) {
        if (full == null || full.indexOf('/') < 0) {
            throw new IllegalArgumentException("a media type looks like text/plain, not \"" + full + "\"");
        }
        return new Data(MimeDetector.mime(full, "host"));
    }

    public static Data list(List<Data> items) {
        List<Value> values = new ArrayList<>(items.size());
        for (Data item : items) values.add(item == null ? Value.Nothing.INSTANCE : item.value);
        return new Data(new Value.ListVal(List.copyOf(values)));
    }

    /** A record, keeping the order the map iterates in -- which is the column order. */
    public static Data record(Map<String, Data> fields) {
        SequencedMap<String, Value> converted = new LinkedHashMap<>();
        fields.forEach((name, item) -> converted.put(name, item == null ? Value.Nothing.INSTANCE : item.value));
        return new Data(new Value.Rec(converted));
    }

    /** A table: every row becomes a record, and the first row sets the column order. */
    public static Data table(List<Map<String, Data>> rows) {
        List<Data> records = new ArrayList<>(rows.size());
        for (Map<String, Data> row : rows) records.add(record(row));
        return list(records);
    }

    /** Starts a record without building a map first. */
    public static Builder row() { return new Builder(); }

    /**
     * Best-effort conversion from an ordinary Java value, for hosts that would
     * rather hand over what they already have.
     */
    public static Data of(Object object) {
        return switch (object) {
            case null -> nothing();
            case Data data -> data;
            case String string -> text(string);
            case Boolean flag -> bool(flag);
            case Long number -> number(number);
            case Integer number -> number(number.longValue());
            case Short number -> number(number.longValue());
            case Byte number -> number(number.longValue());
            case Double number -> decimal(number);
            case Float number -> decimal(number.doubleValue());
            case Path path -> path(path);
            case Instant when -> time(when);
            case Map<?, ?> map -> {
                SequencedMap<String, Data> fields = new LinkedHashMap<>();
                map.forEach((key, item) -> fields.put(String.valueOf(key), of(item)));
                yield record(fields);
            }
            case Collection<?> collection -> {
                List<Data> items = new ArrayList<>(collection.size());
                for (Object item : collection) items.add(of(item));
                yield list(items);
            }
            default -> text(String.valueOf(object));
        };
    }

    /** Builds a record field by field. */
    public static final class Builder {
        private final SequencedMap<String, Data> fields = new LinkedHashMap<>();

        private Builder() {}

        public Builder put(String name, Data item) { fields.put(name, item); return this; }
        public Builder put(String name, String item) { return put(name, text(item)); }
        public Builder put(String name, long item) { return put(name, number(item)); }
        public Builder put(String name, double item) { return put(name, decimal(item)); }
        public Builder put(String name, boolean item) { return put(name, bool(item)); }
        public Builder put(String name, Path item) { return put(name, path(item)); }
        public Builder put(String name, Instant item) { return put(name, time(item)); }
        public Builder size(String name, long bytes) { return put(name, Data.size(bytes)); }
        public Builder any(String name, Object item) { return put(name, of(item)); }

        public Data build() { return record(fields); }
    }

    // ---- reading values ---------------------------------------------------------------

    public DataType type() { return DataType.of(ValueType.of(value)); }

    public boolean isNothing() { return value instanceof Value.Nothing; }

    public boolean isTable() { return Values.isTable(value); }

    /** The text form: what the shell would print for this value. */
    public String text() { return Values.display(value); }

    public long number() { return Values.asLong(value, Span.NONE); }

    public double decimal() { return Values.asDouble(value, Span.NONE); }

    public boolean bool() { return Values.truthy(value); }

    public Path path() {
        return value instanceof Value.PathVal p ? p.path() : Path.of(text());
    }

    public Instant time() {
        return value instanceof Value.Time t ? Instant.ofEpochMilli(t.epochMillis()) : Instant.EPOCH;
    }

    /** The items of a list; a single value counts as a list of one. */
    public List<Data> items() {
        if (value instanceof Value.ListVal list) {
            List<Data> items = new ArrayList<>(list.items().size());
            for (Value item : list.items()) items.add(wrap(item));
            return List.copyOf(items);
        }
        return isNothing() ? List.of() : List.of(this);
    }

    /** The rows of a table; a single record counts as one row. */
    public List<Data> rows() {
        List<Data> rows = new ArrayList<>();
        for (Value.Rec row : Values.rows(value)) rows.add(wrap(row));
        return List.copyOf(rows);
    }

    /** The field names of a record, or the columns of a table, in order. */
    public SequencedSet<String> fields() {
        if (value instanceof Value.Rec rec) return new java.util.LinkedHashSet<>(rec.fields().keySet());
        return Values.columns(Values.rows(value));
    }

    public boolean has(String field) {
        return value instanceof Value.Rec rec && rec.has(field);
    }

    /** One field of a record, or {@link #nothing()} when it is not there. */
    public Data field(String name) {
        return value instanceof Value.Rec rec ? wrap(rec.get(name)) : NOTHING;
    }

    /** The whole value as JSON. */
    public String toJson() { return Values.toJson(value, 2); }

    @Override
    public String toString() { return text(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof Data data && Values.equal(value, data.value);
    }

    @Override
    public int hashCode() { return text().hashCode(); }
}
