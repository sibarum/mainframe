package dev.mainframe.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.value.Value;

/**
 * A screen for somebody else to paint: where the text goes, where the entries
 * go, and which keys end the transaction.
 *
 * <p>Everything here is measured in character cells, because that is the only
 * unit an editor, a web page and a terminal can all agree on. MainFrame says
 * "row 4, column 12"; whoever is painting decides how wide a character is.
 *
 * <p>A screen is a record, so it is a MainFrame value like any other -- it can be
 * saved, replayed against a different editor, and compared in a test. There is no
 * screen format; there is the written form, and this is a record in it. See
 * <a href="../../../../../../../PROTOCOL.md">PROTOCOL.md</a>.
 */
public final class Screen {

    private final int id;
    private final List<Value> parts = new ArrayList<>();
    private final List<Value> keys = new ArrayList<>();

    private String title = "";
    private int rows;
    private int cols;
    private String focus;

    public Screen(int id) { this.id = id; }

    public int id() { return id; }

    public Screen title(String text) { this.title = text == null ? "" : text; return this; }

    public Screen size(int rows, int cols) { this.rows = rows; this.cols = cols; return this; }

    /** Which entry the cursor starts in. */
    public Screen focus(String entry) { this.focus = entry; return this; }

    public String focused() { return focus; }

    /** How many rows have been used so far, so a caller can lay out downwards. */
    public int rows() { return rows; }

    // ---- the parts ---------------------------------------------------------------------

    public Screen text(int row, int col, String text, String style) {
        parts.add(part(row, col, "text", new Value.Str(text)).with("style", new Value.Str(style)));
        grewTo(row, col + text.length());
        return this;
    }

    /**
     * Somewhere to type.
     *
     * @param holds the type MainFrame will read the answer back as -- a hint for
     *              the editor's own keyboard, never a rule it has to enforce
     */
    public Screen entry(int row, int col, String name, int width, String value, String holds) {
        return entry(row, col, name, width, value, holds, null);
    }

    /**
     * The same, and an offer: {@code pick} asks the editor for a chooser beside
     * it -- {@code "file"}, {@code "folder"} or {@code "save"}.
     *
     * <p>An offer rather than an instruction, and still only an entry. What comes
     * back is text in {@code name} like any other entry, so an editor that never
     * heard of {@code pick} ignores the key and lets the path be typed -- rule one
     * doing exactly what it is for. MainFrame reads the answer as a path either
     * way and judges it either way, so the two are the same field asked twice as
     * well as it can be.
     */
    public Screen entry(int row, int col, String name, int width, String value, String holds,
                        String pick) {
        Value.Rec entry = part(row, col, "entry", new Value.Str(name))
                .with("width", new Value.Int(width))
                .with("value", new Value.Str(value == null ? "" : value))
                .with("holds", new Value.Str(holds))
                .with("style", new Value.Str(name.equals(focus) ? "entry-focus" : "entry"));
        if (pick != null) entry = entry.with("pick", new Value.Str(pick));
        parts.add(entry);
        grewTo(row, col + width);
        return this;
    }

    /** An entry that is shown but cannot be typed in. */
    public Screen locked(int row, int col, String name, int width, String value) {
        parts.add(part(row, col, "entry", new Value.Str(name))
                .with("width", new Value.Int(width))
                .with("value", new Value.Str(value == null ? "" : value))
                .with("locked", new Value.Bool(true))
                .with("style", new Value.Str("entry-locked")));
        grewTo(row, col + width);
        return this;
    }

    /** One of a fixed list. An editor without {@code choice} gets an entry instead. */
    public Screen choice(int row, int col, String name, List<String> of, String value, boolean supported) {
        if (!supported) {
            // Rendered down rather than dropped: the options go above it as text,
            // and it becomes something to type into. The editor never learns it
            // missed anything.
            text(row, col, String.join(" / ", of), "hint");
            return entry(row + 1, col, name, widest(of) + 2, value, "string");
        }
        List<Value> options = new ArrayList<>(of.size());
        for (String option : of) options.add(new Value.Str(option));
        parts.add(part(row, col, "choice", new Value.Str(name))
                .with("of", new Value.ListVal(List.copyOf(options)))
                .with("value", new Value.Str(value == null ? "" : value))
                .with("style", new Value.Str(name.equals(focus) ? "entry-focus" : "entry")));
        grewTo(row, col + widest(of) + 4);
        return this;
    }

    /** A frame, with an optional caption in its top edge. */
    public Screen box(int row, int col, int high, int wide, String caption) {
        parts.add(part(row, col, "box", new Value.ListVal(
                        List.of(new Value.Int(high), new Value.Int(wide))))
                .with("text", new Value.Str(caption == null ? "" : caption))
                .with("style", new Value.Str("frame")));
        grewTo(row + high - 1, col + wide);
        return this;
    }

    /** Something to click, which comes back as {@code did: "click"}. */
    public Screen action(int row, int col, String name, String text, String key) {
        Value.Rec action = part(row, col, "action", new Value.Str(name))
                .with("text", new Value.Str(text))
                .with("style", new Value.Str("action"));
        parts.add(key == null ? action : action.with("key", new Value.Str(key)));
        grewTo(row, col + text.length());
        return this;
    }

    /** A key that ends the transaction, listed so the editor can show it. */
    public Screen key(String key, String does, String text) {
        keys.add(Value.Rec.of(
                "key", new Value.Str(key),
                "does", new Value.Str(does),
                "text", new Value.Str(text)));
        return this;
    }

    // ---- the message -------------------------------------------------------------------

    /** The whole screen as the record that goes on the wire. */
    public Value.Rec message() {
        SequencedMap<String, Value> screen = new LinkedHashMap<>();
        screen.put("id", new Value.Int(id));
        screen.put("title", new Value.Str(title));
        screen.put("size", Value.Rec.of("rows", new Value.Int(rows + 1), "cols", new Value.Int(cols + 2)));
        if (focus != null) screen.put("focus", new Value.Str(focus));
        screen.put("keys", new Value.ListVal(List.copyOf(keys)));
        screen.put("parts", new Value.ListVal(List.copyOf(parts)));
        return Value.Rec.of("screen", new Value.Rec(screen));
    }

    private static Value.Rec part(int row, int col, String kind, Value what) {
        return Value.Rec.of(
                "at", new Value.ListVal(List.of(new Value.Int(row), new Value.Int(col))),
                kind, what);
    }

    private void grewTo(int row, int col) {
        if (row > rows) rows = row;
        if (col > cols) cols = col;
    }

    private static int widest(List<String> of) {
        int widest = 0;
        for (String option : of) widest = Math.max(widest, option.length());
        return widest;
    }
}
