package dev.mainframe.gui.console;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The stream MainFrame prints into. MainFrame talks to a {@link java.io.PrintStream}; the scrollback wants
 * lines — so this is the adapter between them, and it is the only place bytes become text.
 *
 * <p>Splitting on {@code \n} at the byte level is safe for UTF-8: no continuation byte can be {@code 0x0A}, so a
 * multi-byte character is never cut in half. {@link #flush()} emits whatever is buffered without a newline,
 * which is how a prompt written with {@code print} reaches the window at all.
 */
final class LineSink extends OutputStream {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256);
    private final Consumer<String> lines;

    LineSink(Consumer<String> lines) {
        this.lines = lines;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            emit();
        } else {
            pending.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        int start = off;
        for (int i = off; i < off + len; i++) {
            if (b[i] == '\n') {
                pending.write(b, start, i - start);
                emit();
                start = i + 1;
            }
        }
        pending.write(b, start, off + len - start);
    }

    @Override
    public synchronized void flush() {
        if (pending.size() > 0) {
            emit();
        }
    }

    private void emit() {
        String line = pending.toString(StandardCharsets.UTF_8);
        pending.reset();
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line = line.substring(0, line.length() - 1);
        }
        lines.accept(line);
    }
}
