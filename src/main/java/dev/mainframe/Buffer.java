package dev.mainframe;

import dev.mainframe.text.Markdown;
import dev.mainframe.text.TextFile;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One open file: its {@link TextField}, its spans, and what a save needs to know.
 *
 * <p>The text is the field's own {@code State<Document>}. Whether it is saved is the field's undo history's answer
 * ({@code History.mark()} and {@code clean()}) — the history already knows which state the user last saved, so
 * this keeps no flag of its own for that beyond the one case the history cannot express.
 *
 * <h2>Spans, off the GUI thread</h2>
 *
 * <p>A Markdown file gets {@link Markdown#spans} after every edit. Computing them is a pass over the whole text, so
 * it runs on {@link Gui#async}, and the result is handed to the field only if it is still current: a generation
 * counter drops a run that a newer edit overtook, and the text is checked against what was read, because spans
 * computed for one text attached to another are colours on the wrong characters.
 */
final class Buffer implements AutoCloseable {

    /** Where the caret is, 1-based, for the status line. */
    record Position(int line, int column) {
    }

    final long id;
    final Path path;
    final TextField field;

    private final Gui gui;
    private final boolean crlf;
    private final Markdown.Style markup;
    private final Consumer<Boolean> dirty;
    private final AtomicLong generation = new AtomicLong();
    private final Subscription dirtyWatch;
    private final Subscription caretWatch;

    /** The text the spans were last computed for, so a span-only change does not ask again. */
    private volatile String marked;
    /** A save took the bytes and then failed, so the document is unsaved whatever the history's mark says. */
    private volatile boolean unsavedWrite;
    private volatile boolean closed;

    /**
     * @param markup the Markdown colours, or null for a document that is not Markdown
     * @param dirty  told whether the document differs from what is on disk, each time that changes
     * @param caret  told where the caret is, each time it moves
     */
    Buffer(Gui gui, long id, Path path, TextFile.Loaded content, Markdown.Style markup,
           Consumer<Boolean> dirty, Consumer<Position> caret) {
        this.gui = gui;
        this.id = id;
        this.path = path;
        this.crlf = content.crlf();
        this.markup = markup;
        this.dirty = dirty;
        this.field = new TextField(gui, "").multiline(true).lineNumbers(true).wordWrap(true);
        field.node().width(Length.FILL).height(Length.FILL).font(Type.MONO).textSize(Type.CODE);

        // Content this field has no past with, so text() rather than replace(): the history starts here, and the
        // mark says this is what is on disk.
        field.text(content.text());
        field.caret(0);
        field.history().mark();
        marked = field.text();

        // onChange is one slot, not a list — a second call replaces the first — so this class owns it. It also
        // fires when only the spans changed, which mark() itself causes, so it re-marks only when the text did.
        field.onChange(text -> {
            if (!text.equals(marked)) {
                marked = text;
                mark();
            }
        });
        this.dirtyWatch = field.history().status().onCommitLatest(s -> dirty.accept(unsavedWrite || !s.value().clean()));
        this.caretWatch = field.document().onCommitLatest(d -> caret.accept(position(d.value())));
        mark();
    }

    String name() {
        return String.valueOf(path.getFileName());
    }

    /** Whether closing this would lose work. */
    boolean dirty() {
        return unsavedWrite || !field.history().clean();
    }

    /**
     * The bytes to write, and the history marked clean at this point — before the write, not after it. Typing that
     * lands while the bytes are on their way to disk is not in them, so it has to stay unsaved, and {@code mark()}
     * marks whatever the history is when it is called. {@link #saveFailed} undoes the claim if the write does not
     * land.
     */
    byte[] takeForSave() {
        byte[] bytes = TextFile.encode(field.text(), crlf);
        unsavedWrite = false;
        field.history().mark();
        return bytes;
    }

    /** The write {@link #takeForSave} was for did not land. */
    void saveFailed() {
        unsavedWrite = true;
        dirty.accept(true);
    }

    /** Compute this document's spans on a worker and give them to the field if they are still current. */
    private void mark() {
        if (markup == null) {
            return;
        }
        long run = generation.incrementAndGet();
        gui.async(() -> {
            if (closed || generation.get() != run) {
                return;
            }
            String text = field.text();
            List<Span> spans = Markdown.spans(text, markup);
            if (generation.get() == run && field.text().equals(text) && !spans.equals(field.spans())) {
                field.setSpans(spans);
            }
        });
    }

    /** Where the caret is in {@code doc}: one pass over the text before it. */
    static Position position(Document doc) {
        String text = doc.text();
        int caret = Math.min(doc.caret(), text.length());
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, caret - lineStart + 1);
    }

    @Override
    public void close() {
        closed = true;
        dirtyWatch.close();
        caretWatch.close();
        field.close();
    }
}
