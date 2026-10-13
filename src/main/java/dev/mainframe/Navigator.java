package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The file navigator: a path bar over a tree of what is in one folder.
 *
 * <h2>The path bar is the whole of "open"</h2>
 *
 * <p>There is no file dialog, by choice, though the framework offers native ones ({@code Dialogs}). The bar
 * stands in for all three of them: type a folder and the tree shows it, type a file and it opens, type a name that
 * is not there yet and it is created. Ctrl+L puts the keyboard in it; Enter goes. It is an ordinary single-line
 * {@link TextField}, and {@code onSubmit} is the whole of its wiring.
 *
 * <h2>The tree</h2>
 *
 * <p>Selecting a file opens it — a click, or walking the tree with the arrow keys, which keeps the keyboard in the
 * tree so the walk carries on. Enter opens a folder in place. Rows move rather than jump when a folder opens,
 * because the tree is handed {@link Motion#arrival}.
 *
 * <p>There is one tree for the life of the window, and changing folder re-points its {@link FolderSource} and
 * refreshes it. The refresh lists the new root, which is I/O, so it runs on the offload lane.
 */
final class Navigator {

    private final Gui gui;
    private final Motion motion;
    private final Executor io;
    private final FolderSource source = new FolderSource(null);
    private final TextField path;
    private final TreeView<Path> tree;
    private final Node root;

    private volatile Consumer<Path> openFile = p -> { };
    private volatile Consumer<String> go = p -> { };

    Navigator(Gui gui, Motion motion) {
        this.gui = gui;
        this.motion = motion;
        this.io = gui.offload();

        path = new TextField(gui, "").onSubmit(text -> go.accept(text.strip()));
        path.node().width(Length.FILL).font(Type.UI).textSize(Type.SMALL);
        gui.landmark(Landmarks.PATH, path.node());

        tree = new TreeView<>(gui, source).motion(motion.arrival);
        tree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TREE, tree.node());
        tree.onSelect(p -> {
            if (Files.isRegularFile(p)) {
                openFile.accept(p);
            }
        });
        tree.onActivate(p -> {
            if (Files.isDirectory(p)) {
                tree.expand(p);
            }
        });
        tree.action(TreeView.Action.<Path>of("›", "Open", (p, job) -> openFile.accept(p))
                .enabledWhen(Files::isRegularFile));
        tree.action(TreeView.Action.<Path>of("»", "Show this folder", (p, job) -> show(p))
                .shownWhen(Files::isDirectory));
        tree.onContextMenu((p, menu) -> menu
                .separator()
                .item("Copy path", () -> gui.clipboard().set(p.toString())));

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                .padding(Type.TIGHT, Type.TIGHT)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.STRETCH)
                .children(path.node(), tree.node());
    }

    Node node() {
        return root;
    }

    /** What selecting a file does. */
    void onOpenFile(Consumer<Path> handler) {
        this.openFile = handler;
    }

    /** What submitting the path bar does: it is handed the text, and decides what the text names. */
    void onGo(Consumer<String> handler) {
        this.go = handler;
    }

    /** The folder shown, or null for none yet. */
    Path folder() {
        return source.base();
    }

    /** Show {@code folder}. {@code then} runs once the new root is listed, on the offload lane. */
    void show(Path folder, Runnable then) {
        io.execute(() -> {
            source.base(folder);
            tree.refresh();
            path.text(String.valueOf(source.base()));
            if (then != null) {
                then.run();
            }
        });
    }

    private void show(Path folder) {
        show(folder, null);
    }

    /** Read the shown folder again: a file was created in it. */
    void refresh() {
        io.execute(tree::refresh);
    }

    /**
     * Unfold the tree down to {@code file}, select its row, and ring it — a row the walk scrolled to is one the eye
     * has not found yet. A file outside the folder is left alone: there is no way down to it from here.
     */
    boolean reveal(Path file) {
        List<Path> chain = source.chainTo(file);
        if (chain.isEmpty()) {
            return false;
        }
        Path last = chain.getLast();
        tree.revealPath(chain, () -> {
            Node row = tree.rowNode(last);
            if (row != null) {
                motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
            }
        });
        return true;
    }

    /** Put the keyboard in the path bar, everything in it selected so typing replaces it. */
    void focusPath() {
        gui.focus(path.node());
        path.select(0, path.text().length());
    }

    /** Put the keyboard in the tree. */
    void focusTree() {
        tree.focus();
    }
}
