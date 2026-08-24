package dev.mainframe.panel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * Whatever is painting for MainFrame: an editor, a web page, anything that can
 * fill a rectangle with text and say what was clicked.
 *
 * <p>The whole interface is "here is a screen, tell me what happened to it". An
 * editor holds no state MainFrame cares about and no meaning at all -- it does
 * not know what a form is, and adding one to MainFrame must never need a new
 * editor. See <a href="../../../../../../../PROTOCOL.md">PROTOCOL.md</a>.
 */
public interface Editor {

    /** What this editor said it can do, read once when it introduced itself. */
    Hello hello();

    /**
     * Paints a screen and waits for the person to do something to it.
     *
     * @return what they did; {@link Event#gone()} when the editor went away,
     *         which is a cancel and never an error
     */
    Event show(Screen screen);

    /** Ordinary output, for the parts of a session that are not a screen. */
    void print(String text, String style);

    /**
     * What an editor said it can do.
     *
     * <p>This is the whole of version negotiation, and it is a list of
     * capabilities rather than a number on purpose: a number tells you what an
     * editor was written against, a list tells you what it can actually do, and
     * only the second one survives somebody writing a small editor of their own.
     */
    record Hello(String name, int protocol, int rows, int cols, SequencedSet<String> can) {

        /** What to assume about an editor that never said. Text and entries only. */
        public static Hello silent() {
            return new Hello("unknown", 1, 24, 80,
                    new LinkedHashSet<>(List.of("text", "entry")));
        }

        public static Hello read(Value message) {
            if (!(message instanceof Value.Rec envelope)
                    || !(envelope.get("hello") instanceof Value.Rec hello)) {
                return silent();
            }
            SequencedSet<String> can = new LinkedHashSet<>(List.of("text", "entry"));
            if (hello.get("can") instanceof Value.ListVal offered) {
                for (Value item : offered.items()) can.add(Values.display(item));
            }
            Value.Rec size = hello.get("size") instanceof Value.Rec given ? given : Value.Rec.of();
            return new Hello(
                    hello.get("editor") == null ? "unknown" : Values.display(hello.get("editor")),
                    (int) whole(hello, "protocol", 1),
                    (int) whole(size, "rows", 24),
                    (int) whole(size, "cols", 80),
                    can);
        }

        public boolean can(String what) { return can.contains(what); }

        private static long whole(Value.Rec record, String name, long fallback) {
            Value value = record.get(name);
            return value instanceof Value.Int number ? number.value() : fallback;
        }
    }
}
