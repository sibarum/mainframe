package dev.mainframe;

import dev.mainframe.core.Markdown;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Tabs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open files: a {@link Tabs} bar, one {@link Buffer} per tab, and the bookkeeping that keeps the two in step.
 *
 * <h2>The view's, on the GUI thread</h2>
 *
 * <p>Tabs are added, closed, fronted and cycled only by the {@link View}, which runs on the GUI thread in the frame,
 * so the bar and the buffer list are never changed by two threads at once, and there is no lock and no concurrent
 * collection here.
 *
 * <h2>The bar never closes a tab on its own</h2>
 *
 * <p>{@link Tabs#closable} is off. The bar's own Close removes a tab the moment it is chosen, and a tab with unsaved
 * work has to be asked about first; the editor asks, and then tells the view to {@link #close}. The tab's menu is the
 * application's {@link MenuAction}s, and what is chosen arrives as {@code Tabs.CHOSEN}, for the view to act on.
 */
final class Workspace {

    /** The tab menu's actions, by the id {@code Tabs.CHOSEN} carries back. */
    static final String SAVE = "save";
    static final String CLOSE = "close-tab";
    static final String CLOSE_OTHERS = "close-others";
    static final String CLOSE_ALL = "close-all";
    static final String REVEAL = "reveal";

    private final Gui gui;
    private final Motion motion;
    private final Markdown.Style markup;
    private final Tabs tabs;
    private final Node hint;
    private final Node root;
    private final Color accent;

    /** Parallel to the bar's tabs. */
    private final List<Buffer> buffers = new ArrayList<>();
    private final Map<Long, Buffer> byId = new HashMap<>();
    private final Map<String, Buffer> byField = new HashMap<>();

    Workspace(Gui gui, Motion motion, Markdown.Style markup) {
        this.gui = gui;
        this.motion = motion;
        this.markup = markup;
        this.accent = gui.theme().color(Role.ACCENT);

        tabs = new Tabs(gui).name("documents").closable(false).slide(motion.change)
                .action(MenuAction.of(SAVE, "Save"))
                .action(MenuAction.of(CLOSE, "Close"))
                .action(MenuAction.of(CLOSE_OTHERS, "Close others"))
                .action(MenuAction.of(CLOSE_ALL, "Close all"))
                .action(MenuAction.of(REVEAL, "Reveal in navigator"));
        tabs.node().width(Length.FILL).height(Length.grow(1f)).visible(false);
        gui.landmark(Landmarks.TABS, tabs.node());

        Node empty = gui.text("Open a file from the navigator, or type a path above it (Ctrl+L)")
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

    /** The bar's address, which its messages carry. */
    String address() {
        return tabs.address();
    }

    /** Open {@code text} as {@code path} in a new tab, in front. {@code focus}: whether it takes the keyboard. */
    Buffer add(long id, Path path, String text, boolean focus) {
        Buffer buffer = new Buffer(gui, id, path, text,
                Markdown.handles(String.valueOf(path.getFileName())) ? markup : null);
        tabs.add(buffer.name(), buffer.field.node());
        buffers.add(buffer);
        byId.put(id, buffer);
        byField.put(buffer.address(), buffer);
        tabs.select(buffers.size() - 1);
        showing(true);
        if (focus) {
            gui.focus(buffer.field.node());
        }
        return buffer;
    }

    /** Close tab {@code id} without asking: the editor has already asked. */
    void close(long id) {
        Buffer gone = byId.remove(id);
        if (gone == null) {
            return;
        }
        int i = buffers.indexOf(gone);
        buffers.remove(i);
        byField.remove(gone.address());
        tabs.remove(i);
        showing(!buffers.isEmpty());
        gone.close();
    }

    /** The bar and its pages, or the hint that says how to open something. */
    private void showing(boolean tabsShown) {
        tabs.node().visible(tabsShown);
        hint.visible(!tabsShown);
    }

    /** The file in front, or null. */
    Buffer front() {
        return at(tabs.selected());
    }

    /** The file in tab {@code index}, or null. */
    Buffer at(int index) {
        return index >= 0 && index < buffers.size() ? buffers.get(index) : null;
    }

    /** The open tab for {@code file}, or null. */
    Buffer find(Path file) {
        Path want = file.toAbsolutePath().normalize();
        for (Buffer b : buffers) {
            if (b.path.equals(want)) {
                return b;
            }
        }
        return null;
    }

    Buffer byId(long id) {
        return byId.get(id);
    }

    /** The file whose field has {@code address}, or null: what a field's message is about. */
    Buffer byField(String address) {
        return byField.get(address);
    }

    /** Every open file, in tab order. */
    List<Buffer> all() {
        return List.copyOf(buffers);
    }

    /**
     * Bring an open tab to the front (asked for a file that is already open) and ring its header, so the answer to
     * "where did it go" is visible.
     */
    void show(Buffer buffer, boolean focus) {
        int i = buffers.indexOf(buffer);
        if (i < 0) {
            return;
        }
        tabs.select(i);
        motion.cues.play(tabs.header(i), Cue.ring(accent, 1));
        if (focus) {
            gui.focus(buffer.field.node());
        }
    }

    /** Move {@code delta} tabs along the bar, wrapping at the ends. */
    void cycle(int delta) {
        int n = buffers.size();
        if (n < 2) {
            return;
        }
        int i = Math.floorMod(tabs.selected() + delta, n);
        tabs.select(i);
        gui.focus(buffers.get(i).field.node());
    }

    /** What a tab's header says, as the editor decided it. */
    void retitle(long id, String title) {
        int i = buffers.indexOf(byId.get(id));
        if (i >= 0) {
            tabs.title(i, title);
        }
    }
}
