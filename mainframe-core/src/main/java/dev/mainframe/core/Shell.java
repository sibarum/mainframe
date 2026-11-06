package dev.mainframe.core;

import dev.mainframe.core.Messages.CloseShell;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.Utf8Chunks;
import dev.vexelray.framework.api.Destination;
import dev.vexelray.framework.api.Source;
import sibarum.atchung.Sender;
import sibarum.probe.Log;

/**
 * A tab's program, on a pseudo-terminal: the outside MainFrame wraps, read and written (components.md §12).
 *
 * <p>One per tab, because every message it hears is on a topic keyed by the tab's id: {@link OpenShell} starts one,
 * {@link CloseShell} ends it, and {@link Typed}, {@link Resized} and {@link Shown} reach the one whose id they carry.
 * The framework holds each tab's {@link Session} and hands it to each half: {@link #run} reads what the program
 * writes, on a thread of its own, and the {@code write}s, on another, give it what is typed and its size, since a
 * write may block on a program that reads nothing.
 *
 * <h2>Paced by what the view has shown</h2>
 *
 * <p>A program can write far faster than a screen can take it ({@code type} of a large file), and an edge that
 * overflows its hearer's mailbox ends the application (T4.2). The framework leaves holding back to the source
 * (components.md §11), so this does it (docs/framework-notes.md, FN-9): at most {@link #IN_FLIGHT} {@link Output}s of a
 * tab are on their way to the view at once, and the view says {@link Shown} for each it has fed to the screen. What
 * is read meanwhile waits in the session's {@link Pace} and goes as one {@code Output} when there is room, so under
 * load each one is what arrived while the view drew the last, and the screen draws about once a frame rather than
 * once a read. Past {@link #HOLD} characters waiting, the reader stops reading, and the program waits on its
 * terminal as it would on a slow console. Only this part's own threads ever wait.
 *
 * <h2>A program that will not start is a reply, not a fault</h2>
 *
 * <p>What escapes {@code open} halts the application (a source's failure policy), and a command line that names no
 * program is the person's typo, not a bug. So {@link #open} catches what starting throws and hands {@link #run} a
 * session with no terminal and the reason, and {@code run} answers at once with {@link ShellEnded} carrying it. A
 * connection that may hold "nothing, and why" is the part's own shape for the expected failure the framework has no
 * other door for (docs/framework-notes.md, FN-3).
 */
@Source(stop = CloseShell.class)
@Destination
public final class Shell {

    private static final Log LOG = Log.of("mainframe.shell");

    /** How much one read takes. */
    private static final int READ = 16 * 1024;

    /** How many of a tab's {@link Output}s may be on their way to the view at once: one being drawn, one waiting. */
    static final int IN_FLIGHT = 2;

    /** How many characters may wait for room before the reader stops reading. */
    static final int HOLD = 1 << 20;

    /** How long the end of a program waits for what it wrote last to be sent, before the reply goes without it. */
    private static final long LAST_WORDS_NANOS = 1_000_000_000L;

    /**
     * A tab's connection: its terminal, or none and why not, and what it has read and not yet sent.
     *
     * @param pty     the program's pseudo-terminal, or null when it would not start
     * @param problem why it would not start, as a person would be told it; blank when it started
     * @param pace    what is waiting to be sent, and how much room the view has
     */
    public record Session(Pty pty, String problem, Pace pace) {
    }

    /**
     * What a tab has read and not yet sent, and how many more {@link Output}s it may send. Shared by the reader
     * ({@link #offer}) and the writer ({@link #shown}), the session's two threads, and by nothing else.
     */
    public static final class Pace {

        private final long id;
        private final Sender<Output> output;
        private final StringBuilder waiting = new StringBuilder();
        private int room = IN_FLIGHT;
        private boolean closed;

        Pace(long id, Sender<Output> output) {
            this.id = id;
            this.output = output;
        }

        /** The reader read {@code text}: send it if there is room, and wait while too much is waiting. */
        synchronized void offer(String text) {
            waiting.append(text);
            if (room > 0) {
                room--;
                send();
            }
            while (waiting.length() >= HOLD && !closed) {
                await(0);
            }
        }

        /** The view has shown one: what is waiting takes its place, or the room is kept for the next read. */
        synchronized void shown() {
            if (waiting.isEmpty()) {
                room = Math.min(IN_FLIGHT, room + 1);
            } else {
                send();
            }
            notifyAll();
        }

        /** The program has ended: give what is still waiting a moment to be sent. */
        synchronized void drain() {
            long until = System.nanoTime() + LAST_WORDS_NANOS;
            while (!waiting.isEmpty() && !closed) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    LOG.debug("tab {}: {} characters never shown", id, waiting.length());
                    return;
                }
                await(Math.max(1, left / 1_000_000));
            }
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }

        /** Characters read and not yet sent: for a test. */
        synchronized int waiting() {
            return waiting.length();
        }

        private void send() {
            output.send(new Output(id, waiting.toString()));
            waiting.setLength(0);
        }

        /** Wait for {@link #shown} or {@link #close}; an interrupt is the framework stopping the run, so it closes. */
        private void await(long millis) {
            try {
                wait(millis);
            } catch (InterruptedException e) {
                closed = true;
                Thread.currentThread().interrupt();
            }
        }
    }

    private final Sender<Output> output;

    public Shell(Sender<Output> output) {
        this.output = output;
    }

    /** Start the program, at the size its screen was laid out at. */
    public Session open(OpenShell r) {
        Pace pace = new Pace(r.id(), output);
        try {
            return new Session(Pty.start(r.line(), r.cols(), r.rows(), r.dir().toString()), "", pace);
        } catch (RuntimeException e) {
            LOG.warn("tab {}: could not start {}: {}", r.id(), r.line(), e.toString());
            String why = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            return new Session(null, "Could not start " + r.line() + ": " + why, pace);
        }
    }

    /** Read what the program writes until it and its terminal are gone, sending it as the view has room. */
    public ShellEnded run(OpenShell r, Session s) {
        if (s.pty() == null) {
            return new ShellEnded(r.id(), -1, s.problem());
        }
        Utf8Chunks text = new Utf8Chunks();
        byte[] buf = new byte[READ];
        for (int n; (n = s.pty().read(buf)) >= 0; ) {
            if (n > 0) {
                String chunk = text.decode(buf, n);
                if (!chunk.isEmpty()) {
                    s.pace().offer(chunk);
                }
            }
        }
        s.pace().drain();
        int code = s.pty().waitFor();
        LOG.info("tab {}: {} ended, {}", r.id(), r.line(), code);
        return new ShellEnded(r.id(), code, "");
    }

    public void write(Session s, Typed t) {
        if (s.pty() != null) {
            s.pty().write(t.text());
        }
    }

    public void write(Session s, Resized z) {
        if (s.pty() != null) {
            s.pty().resize(z.cols(), z.rows());
        }
    }

    public void write(Session s, Shown m) {
        s.pace().shown();
    }

    public void close(Session s) {
        s.pace().close();
        if (s.pty() != null) {
            s.pty().close();
        }
    }
}
