package dev.mainframe.gui.console;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The output pane: one text node per line in a column pinned to the bottom, so it tails as the job thread
 * produces output and detaches when the reader scrolls up.
 *
 * <p>Two rules keep a flooding command from becoming a frame-rate problem. Lines arrive on the job thread and
 * are only <em>queued</em>; {@link #flush()} drains the queue once per frame inside one {@link Gui#batch}, so a
 * command emitting fifty thousand lines a second costs one publish per frame, not fifty thousand. And the
 * column is a ring of at most {@link #CAP} nodes — appending past it removes the oldest, because a node per
 * line is fine at thousands and wrong at millions.
 */
final class Scrollback {

    /** Lines kept in the tree. Older output is dropped, not hidden. */
    private static final int CAP = 5_000;

    private final Gui gui;
    private final Node column;
    /** The tube's three intensities, resolved once from the theme — also what parses the escapes. */
    private final Ansi ansi;
    private final ConcurrentLinkedQueue<Ansi.Line> incoming = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Node> live = new ArrayDeque<>();

    Scrollback(Gui gui, Node column, Ansi ansi) {
        this.gui = gui;
        this.column = column;
        this.ansi = ansi;
    }

    /** Queue one line of MainFrame output. Called from the job thread; the escapes are parsed there too. */
    void post(String raw) {
        incoming.add(ansi.parse(raw));
    }

    /** Queue one line the terminal itself wrote — an echoed prompt, a status note. */
    void post(String text, List<dev.vexelray.gui.core.text.Span> spans) {
        incoming.add(new Ansi.Line(text, spans));
    }

    /** GUI thread, once per frame: everything queued becomes nodes in one batch. */
    void flush() {
        if (incoming.isEmpty()) {
            return;
        }
        gui.batch(() -> {
            Ansi.Line line;
            while ((line = incoming.poll()) != null) {
                append(line);
            }
        });
    }

    private void append(Ansi.Line line) {
        // An empty line still occupies one: a text node with no text measures to no height, and MainFrame uses
        // blank lines as spacing.
        Node node = gui.text(line.text().isEmpty() ? " " : line.text())
                .width(Length.FILL)
                .font(1)
                .textSize(Length.rem(0.8125f))
                .textColor(ansi.normal())
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        if (!line.spans().isEmpty()) {
            node.spans(line.spans());
        }
        column.append(node);
        live.addLast(node);
        while (live.size() > CAP) {
            live.removeFirst().remove();
        }
    }

    /** Drop everything, queued and shown. */
    void clear() {
        incoming.clear();
        gui.batch(() -> {
            for (Node node : live) {
                node.remove();
            }
            live.clear();
        });
    }
}
