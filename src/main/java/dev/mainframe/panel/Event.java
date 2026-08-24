package dev.mainframe.panel;

import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * What the person did to a screen.
 *
 * <p>One event per screen: the editor lets them type, tab and click about as much
 * as they like, and speaks up only when something happens that MainFrame has to
 * decide about. What comes back is every entry on the screen, not the ones that
 * changed -- diffing is an optimisation, and an optimisation that can disagree
 * with itself is not worth the bytes.
 *
 * <p>Reading one never fails. A message from a newer MainFrame, or from an editor
 * being creative, turns into an event with the parts that were understood and
 * nothing said about the rest; {@link #did()} is the only field anything depends
 * on, and an unrecognised one is treated as a cancel by the caller rather than as
 * an error. That is rule one of the protocol: an older reader and a newer writer
 * have to be able to talk.
 */
public record Event(int screen, String did, String key, String on, String focus, Value.Rec fields) {

    public static final String SUBMIT = "submit";
    public static final String CANCEL = "cancel";
    public static final String CLICK = "click";
    public static final String CHANGE = "change";
    public static final String RESIZE = "resize";

    /** The event that stands for the editor going away. */
    public static Event gone() {
        return new Event(0, CANCEL, null, null, null, Value.Rec.of());
    }

    /** Reads one message. Anything unrecognised is left out rather than refused. */
    public static Event read(Value message) {
        if (!(message instanceof Value.Rec envelope)) return gone();
        // "bye" is the editor closing the panel, which is a cancel like any other.
        if (envelope.has("bye")) return gone();
        if (!(envelope.get("event") instanceof Value.Rec event)) return gone();
        return new Event(
                (int) number(event, "screen"),
                text(event, "did", CANCEL),
                text(event, "key", null),
                text(event, "on", null),
                text(event, "focus", null),
                event.get("fields") instanceof Value.Rec fields ? fields : Value.Rec.of());
    }

    public boolean is(String what) { return did.equals(what); }

    /** What the editor says is in an entry now, or null when it said nothing. */
    public String field(String name) {
        Value value = fields.get(name);
        return value == null || value instanceof Value.Nothing ? null : Values.display(value);
    }

    public boolean has(String name) { return field(name) != null; }

    private static String text(Value.Rec record, String name, String fallback) {
        Value value = record.get(name);
        return value == null || value instanceof Value.Nothing ? fallback : Values.display(value);
    }

    private static long number(Value.Rec record, String name) {
        Value value = record.get(name);
        return value instanceof Value.Int whole ? whole.value() : 0;
    }
}
