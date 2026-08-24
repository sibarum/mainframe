package dev.mainframe.panel;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import dev.mainframe.Span;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * An editor on the other end of two streams: one JSON document per line, out and
 * back.
 *
 * <p>{@code mainframe --panel} speaks this and nothing else on its standard
 * streams. That is the reason there is no marker to hunt for and no escaping to
 * get wrong -- in panel mode there is no ordinary output to interleave with,
 * because every line out is a message.
 */
public final class StdioEditor implements Editor {

    private final BufferedReader in;
    private final PrintStream out;
    private Hello hello;
    private boolean gone;

    public StdioEditor(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Reads the editor's introduction. An editor that says nothing at all is
     * assumed to do the least the protocol allows, rather than being refused.
     */
    @Override
    public Hello hello() {
        if (hello == null) hello = Hello.read(receive());
        return hello;
    }

    @Override
    public Event show(Screen screen) {
        hello();
        send(screen.message());
        return Event.read(receive());
    }

    @Override
    public void print(String text, String style) {
        send(Value.Rec.of("print", Value.Rec.of(
                "text", new Value.Str(text == null ? "" : text),
                "style", new Value.Str(style == null ? "plain" : style))));
    }

    /** The last thing MainFrame says, so an editor knows the session is over. */
    public void done(int exit) {
        send(Value.Rec.of("done", Value.Rec.of("exit", new Value.Int(exit))));
    }

    /**
     * A stream whose every line becomes a {@code print} message.
     *
     * <p>This is what makes "in panel mode there is no ordinary output" true
     * rather than aspirational: the renderer keeps writing lines exactly as it
     * does to a terminal, and they leave as messages instead of as bytes on a
     * channel that is carrying JSON.
     */
    public PrintStream lines(String style) {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                if (b == '\r') return;
                if (b != '\n') { pending.write(b); return; }
                // Decoded a whole line at a time, so a character that takes more
                // than one byte survives the trip.
                print(pending.toString(StandardCharsets.UTF_8), style);
                pending.reset();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void send(Value.Rec message) {
        if (gone) return;
        // Compact, because a message is a line and a line should stay one.
        out.println(Values.toJson(message, 0));
        out.flush();
    }

    private Value receive() {
        if (gone) return Value.Nothing.INSTANCE;
        try {
            String line;
            do {
                line = in.readLine();
                if (line == null) {
                    gone = true;
                    return Value.Nothing.INSTANCE;
                }
            } while (line.isBlank());
            return Json.parse(line, Span.NONE);
        } catch (IOException | RuntimeException e) {
            // A line that is not JSON is an editor that has lost the thread. There
            // is nothing useful to say back down a channel that is not being read
            // properly, so this counts as the editor going away.
            gone = true;
            return Value.Nothing.INSTANCE;
        }
    }
}
