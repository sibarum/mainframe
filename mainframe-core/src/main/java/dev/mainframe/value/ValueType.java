package dev.mainframe.value;

/**
 * The type vocabulary used by builtin signatures. Signatures are checked before
 * a single side effect runs, so these names show up in error messages and in
 * `help`; keep them readable.
 */
public enum ValueType {
    ANY("any"),
    NOTHING("nothing"),
    BOOL("bool"),
    INT("int"),
    FLOAT("float"),
    NUMBER("number"),
    STRING("string"),
    SIZE("size"),
    TIME("time"),
    DURATION("duration"),
    PATH("path"),
    MIME("mime"),
    LIST("list"),
    RECORD("record"),
    TABLE("table"),
    BLOCK("block"),
    /** The argument is handed to the builtin unevaluated (e.g. `where size > 1mb`). */
    EXPR("expression");

    private final String display;

    ValueType(String display) { this.display = display; }

    public String display() { return display; }

    /**
     * The display name with the article in front of it: "a size", "an int".
     *
     * <p>Small, but an error that says "a int" reads like it was assembled rather
     * than written, and these are the messages people are handed when they are
     * already stuck.
     */
    public String withArticle() {
        return ("aeiou".indexOf(display.charAt(0)) >= 0 ? "an " : "a ") + display;
    }

    /** Is {@code v} acceptable where {@code this} was declared? */
    public boolean accepts(Value v) {
        return switch (this) {
            case ANY, EXPR -> true;
            case NOTHING -> v instanceof Value.Nothing;
            case BOOL -> v instanceof Value.Bool;
            case INT -> v instanceof Value.Int;
            case FLOAT -> v instanceof Value.Float;
            case NUMBER -> v instanceof Value.Int || v instanceof Value.Float || v instanceof Value.Size;
            // Paths are text you can always fall back to reading as text.
            case STRING -> v instanceof Value.Str || v instanceof Value.PathVal || v instanceof Value.Mime;
            case SIZE -> v instanceof Value.Size || v instanceof Value.Int;
            case TIME -> v instanceof Value.Time;
            case DURATION -> v instanceof Value.Duration;
            case PATH -> v instanceof Value.PathVal || v instanceof Value.Str;
            case MIME -> v instanceof Value.Mime || v instanceof Value.Str;
            // One value counts as a list of one, and one record as a table of one
            // row. That rule keeps `... | first | get name` working without the
            // user having to think about whether they still hold a list.
            case LIST -> !(v instanceof Value.Nothing);
            case RECORD -> v instanceof Value.Rec;
            case TABLE -> Values.isTable(v) || v instanceof Value.Rec;
            case BLOCK -> v instanceof Value.Block;
        };
    }

    public static ValueType of(Value v) {
        return switch (v) {
            case Value.Nothing _ -> NOTHING;
            case Value.Bool _ -> BOOL;
            case Value.Int _ -> INT;
            case Value.Float _ -> FLOAT;
            case Value.Str _ -> STRING;
            case Value.Size _ -> SIZE;
            case Value.Time _ -> TIME;
            case Value.Duration _ -> DURATION;
            case Value.PathVal _ -> PATH;
            case Value.Mime _ -> MIME;
            case Value.Rec _ -> RECORD;
            case Value.Block _ -> BLOCK;
            case Value.ListVal l -> Values.isTable(l) ? TABLE : LIST;
        };
    }
}
