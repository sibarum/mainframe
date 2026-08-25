package dev.mainframe.panel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import dev.mainframe.Span;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * An editor that does exactly what it is told, for tests.
 *
 * <p>Screens go in a list to be looked at afterwards, and events come off a queue
 * that a test filled in beforehand. Both are written the way they would go on the
 * wire -- as JSON -- so a test is exercising the protocol rather than a shortcut
 * around it, and a screen a test asserts on is the screen an editor would receive.
 */
final class FakeEditor implements Editor {

    private Hello hello;
    private final Deque<String> events = new ArrayDeque<>();
    /** What room this editor has once each queued event has gone, or null where it does not change. */
    private final List<Hello> rooms = new ArrayList<>();
    private final List<Value.Rec> screens = new ArrayList<>();
    private final List<String> printed = new ArrayList<>();

    private FakeEditor(Hello hello) { this.hello = hello; }

    /** An editor that can do everything the protocol offers. */
    static FakeEditor rich() {
        return new FakeEditor(new Hello("fake", 1, 40, 90,
                capabilities("text", "entry", "choice", "action", "box", "click", "resize")));
    }

    /** The least an editor can be and still conform: text, entries, submit, cancel. */
    static FakeEditor plain() {
        return new FakeEditor(new Hello("plain", 1, 24, 80, capabilities("text", "entry")));
    }

    /** Queues a message exactly as it would arrive down the wire. */
    FakeEditor sends(String json) {
        events.add(json);
        rooms.add(null);
        return this;
    }

    /** Queues a submit carrying the values of the entries, as {@code name, value} pairs. */
    FakeEditor submits(String... fields) {
        return sends("{\"event\": {\"did\": \"submit\", \"key\": \"F12\", \"fields\": "
                + record(fields) + "}}");
    }

    FakeEditor clicks(String on, String... fields) {
        return sends("{\"event\": {\"did\": \"click\", \"on\": \"" + on + "\", \"fields\": "
                + record(fields) + "}}");
    }

    FakeEditor cancels() { return sends("{\"event\": {\"did\": \"cancel\", \"key\": \"F3\"}}"); }

    /**
     * Queues a resize, as an editor reports one: the window is a different size now, and the values that were on
     * the screen come back with the event like they do with any other.
     *
     * <p>The new room takes effect when the event is sent, which is what a window being dragged does -- MainFrame
     * asks {@link #hello} again while laying out the next screen, and gets the new answer.
     */
    FakeEditor resizesTo(int rows, int cols, String... fields) {
        sends("{\"event\": {\"did\": \"resize\", \"fields\": " + record(fields) + "}}");
        rooms.remove(rooms.size() - 1);
        rooms.add(new Hello(hello.name(), hello.protocol(), rows, cols, hello.can()));
        return this;
    }

    @Override public Hello hello() { return hello; }

    @Override
    public Event show(Screen screen) {
        // Round-tripped through JSON on purpose: a test that asserts on a screen
        // should be asserting on what an editor would actually be handed.
        Value sent = Json.parse(Values.toJson(screen.message(), 0), Span.NONE);
        screens.add((Value.Rec) ((Value.Rec) sent).get("screen"));
        if (events.isEmpty()) return Event.gone();
        Event answer = Event.read(Json.parse(events.removeFirst(), Span.NONE));
        Hello room = rooms.remove(0);
        if (room != null) hello = room;
        return answer;
    }

    @Override
    public void print(String text, String style) { printed.add(style + ": " + text); }

    // ---- looking at what happened ---------------------------------------------------------

    int screenCount() { return screens.size(); }

    Value.Rec screen(int index) { return screens.get(index); }

    Value.Rec lastScreen() { return screens.getLast(); }

    List<String> printed() { return printed; }

    /** Every part of a screen written out, one per line, for a readable assertion. */
    String parts(int index) {
        StringBuilder sb = new StringBuilder();
        for (Value part : ((Value.ListVal) screen(index).get("parts")).items()) {
            sb.append(Values.display(part)).append('\n');
        }
        return sb.toString();
    }

    String lastParts() { return parts(screens.size() - 1); }

    /** The text of every part carrying a given style, joined. */
    String styled(int index, String style) {
        List<String> found = new ArrayList<>();
        for (Value part : ((Value.ListVal) screen(index).get("parts")).items()) {
            Value.Rec record = (Value.Rec) part;
            if (!style.equals(Values.display(record.get("style")))) continue;
            found.add(Values.display(record.get("text")));
        }
        return String.join(" | ", found);
    }

    String lastStyled(String style) { return styled(screens.size() - 1, style); }

    /** What an entry on a screen was showing. */
    String entry(int index, String name) {
        for (Value part : ((Value.ListVal) screen(index).get("parts")).items()) {
            Value.Rec record = (Value.Rec) part;
            Value entry = record.get("entry") == null ? record.get("choice") : record.get("entry");
            if (entry != null && Values.display(entry).equals(name)) {
                return Values.display(record.get("value"));
            }
        }
        return null;
    }

    String focus(int index) {
        Value focus = screen(index).get("focus");
        return focus == null ? null : Values.display(focus);
    }

    private static String record(String... fields) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(fields[i]).append("\": \"").append(fields[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private static SequencedSet<String> capabilities(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }
}
