package dev.mainframe.gui.console;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The output pane: everything MainFrame has printed, as <b>one read-only document</b> that tails as the job
 * thread produces output and can be dragged through, selected across and copied out of.
 *
 * <h2>Why one document and not a node per line</h2>
 * A column of one text node per line paints the same pixels and cannot be selected: a selection is a range over
 * <em>a</em> document, and a pane made of five thousand documents has no range that spans two lines. Output you
 * cannot get out of a terminal is output you have to retype, so the pane is a document — and the widget that
 * already knows how to select over one, wash the range and put it on the clipboard is {@link TextField} in
 * {@link TextField#readOnly read-only} mode, which closes the edit channel and keeps the caret, the selection,
 * the motion keys and Ctrl+C.
 *
 * <p>It is also cheaper than the column it replaces, which is worth saying because it sounds as though it should
 * not be. The compute phase measures every text node it can see, so a column of five thousand nodes measured five
 * thousand strings a frame; this measures one. The renderer culls the lines outside the viewport before it
 * generates their glyphs, so an unbounded document does not become unbounded vertex data.
 *
 * <h2>Two rules keep a flooding command from becoming a frame-rate problem</h2>
 * Lines arrive on the job thread and are only <em>queued</em>; {@link #flush()} drains the queue once per frame
 * inside one {@link Gui#batch}, so a command emitting fifty thousand lines a second costs one append per frame,
 * not fifty thousand. And the document is a ring of at most {@link #CAP} lines — appending past it drops the
 * oldest off the front, because a document per line is fine at thousands and wrong at millions.
 *
 * <p>The whole text is mirrored here, in {@link #text}, so an append is an append: the document is handed the new
 * characters and the ones already in it are not touched, which is what leaves a selection somebody is holding
 * alone. Only a trim — which moves every offset in the document — rebuilds it.
 *
 * <h2>How it tails without holding the caret</h2>
 * This pane is never focused: the console's caret lives in the command field and comes back to it after every
 * click, which is what makes the whole window typeable without aiming. An unfocused field publishes no caret, so
 * the framework's caret-follow scrolling has nothing to follow — and a text leaf is not a scroll-locked
 * container, so it cannot be pinned to its bottom edge either. What it does have is the caret <em>position</em>
 * as a node property, which is what the scroll follows: so {@link #flush} writes the end of the document there
 * when the reader is already at the bottom, and writes nothing when they have scrolled up to read. Which is
 * exactly a scroll lock — attached at the edge, detached away from it — expressed in the one term this node has.
 */
final class Scrollback {

    /** Lines kept in the document. Older output is dropped, not hidden. */
    private static final int CAP = 5_000;

    /** How far off the bottom edge still counts as being at it, in pixels. One line would be too forgiving. */
    private static final float EDGE = 2f;

    private final Gui gui;
    private final Ansi ansi;
    private final TextField view;
    private final ConcurrentLinkedQueue<Ansi.Line> incoming = new ConcurrentLinkedQueue<>();

    /**
     * The whole scrollback, mirrored. The document holds the same characters; this is what makes an append an
     * append rather than a rebuild, and what a trim measures itself against.
     */
    private final StringBuilder text = new StringBuilder();

    /** Absolute spans over {@link #text}, in line order — so a trim drops them off the front with their lines. */
    private final List<Span> spans = new ArrayList<>();

    /** Per line, oldest first: how many characters it occupies (its newline included) and how many spans it has. */
    private final ArrayDeque<int[]> lines = new ArrayDeque<>();

    /**
     * What this pane last told the node the caret was, so it can tell whether saying it again would move it.
     *
     * <p>The scroll follows the caret only when it <em>changes</em>, which is right — it is what lets the wheel
     * scroll a field away from its caret and have it stay there. It also means "go back to the bottom" cannot be
     * said twice in a row, so when the offset would be the one already there, {@link #tail} names the character
     * before it instead: the same last line, a different number.
     */
    private int followed = -1;

    /** Set by {@link #tail}, consumed by the next {@link #flush}: go back to the bottom whatever the scroll says. */
    private volatile boolean retail;

    Scrollback(Gui gui, Ansi ansi) {
        this.gui = gui;
        this.ansi = ansi;
        this.view = new TextField(gui, "");
        view.multiline(true)
                // Wrapped, because that is what the column of labels did: a long line folds rather than sliding
                // out of sight behind a scrollbar nobody in a terminal expects to have to use.
                .wordWrap(true)
                .readOnly(true);
        view.node()
                .width(Length.FILL)
                .scroll(false, true)
                .font(1)
                .textSize(Length.rem(0.8125f))
                .textColor(ansi.normal())
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP)
                // The widget paints itself as a sunken well with a border, which is right on a page and wrong on
                // glass. Taken away here rather than by the console, because a pane that has to be undressed by
                // whoever places it is a pane that looks like a form field in the one window that places it.
                .background(gui.theme().color(Role.NONE))
                .corner(Length.ZERO)
                .border(Length.ZERO, gui.theme().color(Role.NONE))
                .padding(Length.ZERO);
        // Not focusable, and that is the whole arrangement rather than a detail: the caret belongs to the command
        // field, so this pane must not take it off a click. Selecting is a pointer gesture and needs no focus;
        // copying is bound by the console, which can read this document from anywhere. Keeping focus away also
        // keeps the widget's focus ring off the glass -- it repaints its own border on every focus change, and a
        // field that is never focused never repaints it.
        gui.focusable(view.node(), false);
    }

    /** The pane, to place in a layout. Sized by whoever places it. */
    Node node() {
        return view.node();
    }

    /** This document, for the console's own Copy: the selection is a fact about it and not about the widget. */
    Document document() {
        return view.document().value();
    }

    /**
     * Select {@code [start, end)} of the output, exactly as dragging across it does.
     *
     * <p>The pointer is the way anybody selects here, and this is the same range said in code — which is what a
     * command that wanted to hand somebody its own output pre-selected would use, and what lets the append path
     * be held to the promise that matters: a range somebody is holding survives the next line of output.
     */
    void select(int start, int end) {
        view.select(start, end);
    }

    /**
     * Add the window's own items under this pane's right-click menu.
     *
     * <p>The widget puts Copy at the top of it — greyed when nothing is selected, and the only clipboard action a
     * read-only document has — and everything handed here follows. Without this the console's menu would simply
     * not appear over the output, because a context menu is answered by the nearest node that has one.
     */
    void menu(Consumer<MenuSink> source) {
        view.onContextMenu(source);
    }

    /** Queue one line of MainFrame output. Called from the job thread; the escapes are parsed there too. */
    void post(String raw) {
        incoming.add(ansi.parse(raw));
    }

    /** Queue one line the terminal itself wrote — an echoed prompt, a status note. */
    void post(String text, List<Span> spans) {
        incoming.add(new Ansi.Line(text, spans));
    }

    /**
     * Send the display back to the tail on the next frame, re-attaching it however far up the reader had scrolled.
     *
     * <p>Called when they have said they are finished reading history by doing something — pressing Enter. Not a
     * scroll of its own: the jump lands in the same frame as the output that prompted it, which is what stops the
     * view moving twice.
     */
    void tail() {
        retail = true;
    }

    /** GUI thread, once per frame: everything queued becomes one append, in one batch. */
    void flush() {
        boolean forced = retail;
        retail = false;
        if (incoming.isEmpty() && !forced) {
            return;
        }
        gui.batch(() -> {
            Document before = view.document().value();
            boolean selected = before.hasSelection();
            int anchor = before.anchor();
            int caret = before.caret();
            // Read the scroll before the append, not after: whether the reader was at the bottom is a fact about
            // the frame that has been on screen, and the append is what would otherwise answer for them.
            boolean atEdge = forced || atBottom();

            int base = text.length();
            StringBuilder added = new StringBuilder();
            Ansi.Line line;
            while ((line = incoming.poll()) != null) {
                append(line, added);
            }
            int dropped = trim();

            if (dropped > 0) {
                // A trim moves every offset in the document, so there is nothing to preserve and no cheaper way
                // to say it. It also resets the widget's history, which a pane nobody can edit has no use for.
                view.text(text.toString());
            } else if (added.length() > 0) {
                view.caret(base);          // an append goes on the end, which is where the document ended
                view.insert(added.toString());
            }
            if (added.length() > 0 || dropped > 0) {
                view.setSpans(spans);
            }
            // Put back whatever range was held. The offsets are the same ones unless a trim moved them, and a
            // selection a trim ate part of is one that no longer describes anything.
            if (selected) {
                int a = anchor - dropped;
                int c = caret - dropped;
                if (a >= 0 && c >= 0 && a != c) {
                    view.select(a, c);
                }
            }
            if (atEdge) {
                tailNow();
            }
        });
    }

    /** Drop everything, queued and shown. */
    void clear() {
        incoming.clear();
        gui.batch(() -> {
            text.setLength(0);
            spans.clear();
            lines.clear();
            followed = -1;
            view.text("");
            view.clearSpans();
        });
    }

    /** Release the widget's subscriptions and claims. Called when the console shuts down. */
    void close() {
        view.close();
    }

    // ---- the document ----------------------------------------------------------------

    /** Add one line to the mirror — text, spans and the row that records both — and to this frame's append. */
    private void append(Ansi.Line line, StringBuilder added) {
        int base = text.length();
        for (Span span : line.spans()) {
            spans.add(new Span(span.start() + base, span.end() + base,
                    span.fg(), span.bg(), span.underline()));
        }
        // Every line carries its own newline, so the document ends with one and a trim is a prefix deletion of
        // whole lines. The empty last line that leaves is a line: it is where the next one will go.
        added.append(line.text()).append('\n');
        text.append(line.text()).append('\n');
        lines.addLast(new int[]{line.text().length() + 1, line.spans().size()});
    }

    /**
     * Drop the oldest lines back to {@link #CAP} and shift what is left onto its new offsets.
     *
     * @return how many characters came off the front, which is what every surviving offset moves by
     */
    private int trim() {
        if (lines.size() <= CAP) {
            return 0;
        }
        int dropped = 0;
        int spansDropped = 0;
        while (lines.size() > CAP) {
            int[] row = lines.removeFirst();
            dropped += row[0];
            spansDropped += row[1];
        }
        spans.subList(0, spansDropped).clear();
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            spans.set(i, new Span(span.start() - dropped, span.end() - dropped,
                    span.fg(), span.bg(), span.underline()));
        }
        text.delete(0, dropped);
        return dropped;
    }

    // ---- the scroll ------------------------------------------------------------------

    /** Whether the reader is at the bottom of the pane — where output arriving should carry the view with it. */
    private boolean atBottom() {
        NodeLayout layout = view.node().layout();
        if (!layout.present()) {
            return true;   // never laid out, so nothing has been scrolled away from
        }
        float max = Math.max(0f, layout.contentH() - layout.viewH());
        return layout.scrollY() >= max - EDGE;
    }

    /**
     * Point the node's caret at the end of the document, which is what the scroll follows.
     *
     * <p>Follows only what moves, hence the step back: the last character and the one before it are on the same
     * line, so either brings the bottom into view, and alternating between them means "the bottom" can be said
     * twice in a row.
     */
    private void tailNow() {
        int end = text.length();
        int to = followed == end && end > 0 ? end - 1 : end;
        view.node().caret(to);
        followed = to;
    }
}
