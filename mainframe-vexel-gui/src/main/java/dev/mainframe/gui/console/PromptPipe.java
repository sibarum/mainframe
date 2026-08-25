package dev.mainframe.gui.console;

import java.io.Reader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The command line, as something MainFrame can read.
 *
 * <h2>Why this exists</h2>
 * MainFrame's forms and confirmations are <em>printed</em>: the shell writes a prompt and reads a line back, the
 * way a terminal has always worked. That suits this window exactly — the question lands in the scrollback and the
 * answer is typed where every other line is typed — but it needs the session's input to be something the window
 * can put a line into, and the session was built on a null reader.
 *
 * <p>So: a queue with a {@link Reader} face. The job thread blocks in {@link #readLine} while a form is asking;
 * the window drops the next typed line in and the block ends. One consumer, one producer, and the queue is the
 * whole of the synchronisation.
 *
 * <h2>Knowing which one a typed line is</h2>
 * A line typed at the prompt is either a new command or an answer to a question, and the difference is not
 * something the window can see in the text. {@link #waiting()} is how it tells: true exactly while the job thread
 * is blocked here, which is exactly while there is a question outstanding. That is a fact about the shell rather
 * than a mode the window has to be put into and taken out of, so the two cannot get out of step — a form that
 * cancels, fails or finishes stops reading, and the next line is a command again with nothing having to say so.
 *
 * <h2>End of input is an answer</h2>
 * {@link #close} makes every present and future read return end-of-stream, which MainFrame already understands:
 * at a confirmation it means no, and in a form it means the person gave up. So shutting the window down while a
 * form is open cancels the form instead of leaving the job thread parked forever.
 */
final class PromptPipe extends Reader {

    /** How long a blocked read waits before checking whether the pipe has been closed under it. */
    private static final long POLL_MS = 100;

    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean waiting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Characters left over from the line currently being handed out. */
    private String pending = "";
    private int at;

    /** Whether the shell is blocked waiting for a line — that is, whether a question is outstanding. */
    boolean waiting() {
        return waiting.get();
    }

    /**
     * Offer a line as the answer to whatever is asking. Called from a handler thread; does nothing once the pipe
     * is closed, because a line typed into a shutting-down window has nowhere to be.
     */
    void offer(String line) {
        if (!closed.get()) {
            lines.add(line == null ? "" : line);
        }
    }

    /**
     * Read one line, blocking until the window supplies it. Returns {@code null} at end of input.
     *
     * <p>{@code BufferedReader.readLine} would do this over {@link #read}, and does when MainFrame wraps this in
     * one — but a reader that can be asked for a line directly keeps the blocking in one obvious place, and
     * {@link #read} is written in terms of it rather than the other way round.
     */
    private String take() {
        waiting.set(true);
        try {
            while (!closed.get()) {
                String line = lines.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (line != null) {
                    return line;
                }
            }
            // Closed: hand back anything already queued, then end the stream.
            return lines.poll();
        } catch (InterruptedException e) {
            // Ctrl+C reached us. An interrupted read is the end of the input, which a form reads as a cancel.
            Thread.currentThread().interrupt();
            return null;
        } finally {
            waiting.set(false);
        }
    }

    @Override
    public int read(char[] buffer, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        while (at >= pending.length()) {
            String line = take();
            if (line == null) {
                return -1;
            }
            // The newline is part of what a line reader is waiting for; without it readLine never returns.
            pending = line + "\n";
            at = 0;
        }
        int count = Math.min(length, pending.length() - at);
        pending.getChars(at, at + count, buffer, offset);
        at += count;
        return count;
    }

    /** Whether a read would return immediately — a line is queued, or the stream has ended. */
    @Override
    public boolean ready() {
        return at < pending.length() || !lines.isEmpty() || closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
