package dev.mainframe;

import dev.mainframe.text.Markdown;
import dev.mainframe.text.TextFile;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Tabs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The open files: a {@link Tabs} bar, one {@link Buffer} per tab, and the bookkeeping that keeps the two in step.
 *
 * <h2>Two rules, and the deadlock they prevent</h2>
 *
 * <p>The tab order and the buffer list change together under {@link #lock}, and only there. <b>Nothing commits to
 * the {@link Model} while holding it</b>, and <b>nothing reached from a model listener takes it.</b> The reason is
 * in {@code Model.onChange}: delivery is serialised by making a committing thread wait for any delivery already
 * running on another thread, so a commit made under this lock, racing a listener that wanted this lock, is two
 * threads each waiting for the other with nothing thrown.
 *
 * <p>So the listener's half — retitling a header when a file goes unsaved — finds the header by id in a concurrent
 * map, never by index under the lock.
 *
 * <h2>The bar never closes a tab on its own</h2>
 *
 * <p>{@link Tabs#closable} is off. The bar's own Close removes a tab the moment it is chosen, and a tab with unsaved
 * work has to be asked about first; {@link Actions} asks, and then calls {@link #close}.
 */
final class Workspace {

    private final Gui gui;
    private final Model model;
    private final Motion motion;
    private final Markdown.Style markup;
    private final Consumer<Buffer.Position> caret;
    private final Tabs tabs;
    private final Node empty;
    private final Node hint;
    private final Node root;
    private final Color accent;
    private final AtomicLong ids = new AtomicLong();

    private final Object lock = new Object();
    /** Parallel to the bar's tabs. Guarded by {@link #lock}. */
    private final List<Buffer> buffers = new ArrayList<>();
    /** By id, for the paths that must not take {@link #lock}. */
    private final Map<Long, Buffer> byId = new ConcurrentHashMap<>();
    private final Map<Long, Node> headers = new ConcurrentHashMap<>();

    Workspace(Gui gui, Model model, Motion motion, Markdown.Style markup, Consumer<Buffer.Position> caret) {
        this.gui = gui;
        this.model = model;
        this.motion = motion;
        this.markup = markup;
        this.caret = caret;
        this.accent = gui.theme().color(Role.ACCENT);

        tabs = new Tabs(gui).closable(false).transition(Tabs.slide(motion.change));
        tabs.node().width(Length.FILL).height(Length.grow(1f)).visible(false);
        gui.landmark(Landmarks.TABS, tabs.node());
        // Delivered on a worker, after the fact: resolve what is in front now rather than trusting the index, which
        // a close between the click and this may have moved.
        tabs.onSelect(i -> {
            Buffer b = front();
            if (b != null) {
                model.front(b.id);
                caret.accept(Buffer.position(b.field.document().value()));
            }
        });

        empty = gui.text("Open a file from the navigator, or type a path above it (Ctrl+L)")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.EMPTY, empty);
        hint = gui.row().width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .children(empty);

        root = gui.column().width(Length.FILL).height(Length.FILL).children(hint, tabs.node());
    }

    Node node() {
        return root;
    }

    Tabs tabs() {
        return tabs;
    }

    /** Open {@code content} as {@code path} in a new tab, in front. {@code focus}: whether it takes the keyboard. */
    Buffer add(Path path, TextFile.Loaded content, boolean focus) {
        long id = ids.incrementAndGet();
        // Both listeners are document listeners, so neither may take the lock: the caret asks the model which file
        // is in front rather than asking the bar.
        Buffer buffer = new Buffer(gui, id, path, content, Markdown.handles(String.valueOf(path.getFileName())) ? markup : null,
                dirty -> model.dirty(id, dirty),
                position -> {
                    if (model.doc().active() == id) {
                        caret.accept(position);
                    }
                });
        synchronized (lock) {
            tabs.add(buffer.name(), buffer.field.node());
            int index = buffers.size();
            buffers.add(buffer);
            byId.put(id, buffer);
            headers.put(id, tabs.header(index));
            tabs.select(index);
            showing(true);
        }
        model.opened(new Doc.Entry(id, path, false));
        if (focus) {
            gui.focus(buffer.field.node());
        }
        return buffer;
    }

    /** Close tab {@code id} without asking: {@link Actions} has already asked. */
    void close(long id) {
        Buffer gone = null;
        synchronized (lock) {
            for (int i = 0; i < buffers.size(); i++) {
                if (buffers.get(i).id == id) {
                    gone = buffers.remove(i);
                    tabs.remove(i);
                    break;
                }
            }
            showing(!buffers.isEmpty());
        }
        if (gone == null) {
            return;
        }
        byId.remove(id);
        headers.remove(id);
        gone.close();
        model.closed(id);
    }

    /** The bar and its pages, or the hint that says how to open something. */
    private void showing(boolean tabsShown) {
        tabs.node().visible(tabsShown);
        hint.visible(!tabsShown);
    }

    /** The file in front, or null. */
    Buffer front() {
        synchronized (lock) {
            int i = tabs.selected();
            return i >= 0 && i < buffers.size() ? buffers.get(i) : null;
        }
    }

    /** The open tab for {@code file}, or null. */
    Buffer find(Path file) {
        Path want = file.toAbsolutePath().normalize();
        for (Buffer b : all()) {
            if (b.path.equals(want)) {
                return b;
            }
        }
        return null;
    }

    Buffer byId(long id) {
        return byId.get(id);
    }

    /** Every open file, in tab order — a snapshot. */
    List<Buffer> all() {
        synchronized (lock) {
            return List.copyOf(buffers);
        }
    }

    /**
     * Bring an open tab to the front — asked for a file that is already open — and ring its header, so the answer
     * to "where did it go" is visible.
     */
    void show(Buffer buffer, boolean focus) {
        synchronized (lock) {
            int i = buffers.indexOf(buffer);
            if (i >= 0) {
                tabs.select(i);
            }
        }
        Node header = headers.get(buffer.id);
        if (header != null) {
            motion.cues.play(header, Cue.ring(accent, 1));
        }
        if (focus) {
            gui.focus(buffer.field.node());
        }
    }

    /** Move {@code delta} tabs along the bar, wrapping at the ends. */
    void cycle(int delta) {
        Buffer next;
        synchronized (lock) {
            int n = buffers.size();
            if (n < 2) {
                return;
            }
            int i = Math.floorMod(tabs.selected() + delta, n);
            tabs.select(i);
            next = buffers.get(i);
        }
        gui.focus(next.field.node());
    }

    /**
     * Write the session onto the headers. Called from the model's listener, so it takes no lock: each header is
     * found by its file's id, and one that has gone is skipped.
     */
    void retitle(Doc doc) {
        for (Doc.Entry e : doc.tabs()) {
            Node header = headers.get(e.id());
            if (header != null) {
                header.text(e.title());
            }
        }
    }
}
