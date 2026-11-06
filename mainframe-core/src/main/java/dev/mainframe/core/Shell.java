package dev.mainframe.core;

import dev.mainframe.core.Messages.CloseShell;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.ShellEnded;
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
 * {@link CloseShell} ends it, and {@link Typed} and {@link Resized} reach the one whose id they carry. The framework
 * holds each tab's {@link Session} and hands it to each half: {@link #run} reads what the program writes, on a thread
 * of its own, and the {@code write}s give it what is typed and its size, on another, since a write may block on a
 * program that reads nothing.
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

    /** How much one read takes: a screenful of a busy program's output, so a flood is a few reads a frame. */
    private static final int READ = 16 * 1024;

    /**
     * A tab's connection: its terminal, or none and why not.
     *
     * @param pty     the program's pseudo-terminal, or null when it would not start
     * @param problem why it would not start, as a person would be told it; blank when it started
     */
    public record Session(Pty pty, String problem) {
    }

    private final Sender<Output> output;

    public Shell(Sender<Output> output) {
        this.output = output;
    }

    /** Start the program, at the size its screen was laid out at. */
    public Session open(OpenShell r) {
        try {
            return new Session(Pty.start(r.line(), r.cols(), r.rows(), r.dir().toString()), "");
        } catch (RuntimeException e) {
            LOG.warn("tab {}: could not start {}: {}", r.id(), r.line(), e.toString());
            String why = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            return new Session(null, "Could not start " + r.line() + ": " + why);
        }
    }

    /** Read what the program writes until it and its terminal are gone, sending it as it comes. */
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
                    output.send(new Output(r.id(), chunk));
                }
            }
        }
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

    public void close(Session s) {
        if (s.pty() != null) {
            s.pty().close();
        }
    }
}
