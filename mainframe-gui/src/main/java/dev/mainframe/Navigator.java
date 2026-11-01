package dev.mainframe;

import dev.mainframe.core.ListFolder;
import dev.mainframe.core.Messages.Item;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.Listing;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The file navigator: a path bar over a tree of what is in one folder. The {@link View}'s, on the GUI thread.
 *
 * <h2>The path bar is the whole of "open"</h2>
 *
 * <p>There is no file dialog, by choice, though the framework offers native ones ({@code Dialogs}). The bar
 * stands in for all three of them: type a folder and the tree shows it, type a file and it opens, type a name that
 * is not there yet and it is created. Ctrl+L puts the keyboard in it; Enter goes. It is an ordinary single-line
 * {@link TextField}, named {@code path}, and its {@code SUBMITTED} message is the whole of its wiring.
 *
 * <h2>The tree asks, and is answered</h2>
 *
 * <p>The tree asks for a level the first time it is opened ({@code TreeView.CHILDREN_WANTED}); reading a folder is
 * I/O, so the view asks {@code ListFolder} on the offload lane, and when the answer comes this turns it into the tree's
 * entries ({@link #children}). What a row is drawn from comes with the answer, so drawing calls nothing, and whether a
 * row is a folder is known from the listing that drew it, never by looking at the disk again on the GUI thread.
 *
 * <p>Selecting a file opens it: a click, or walking the tree with the arrow keys, which keeps the keyboard in the tree
 * so the walk carries on. Enter opens a folder in place. Rows move rather than jump when a folder opens, because the
 * tree is handed {@link Motion#arrival}. There is one tree for the life of the window, and showing another folder
 * refreshes it, which asks again for every level it has listed.
 */
final class Navigator {

    /** The rows' menu actions, by the id {@code TreeView.CHOSEN} carries back. */
    static final String OPEN = "open";
    static final String SHOW = "show-folder";
    static final String COPY_PATH = "copy-path";

    private final Gui gui;
    private final Motion motion;
    private final TextField path;
    private final TreeView<Path> tree;
    private final Node root;
    /** Every folder a listing has shown, so a row's kind is known without the disk. */
    private final Set<Path> folders = new HashSet<>();
    /** The folder shown, or null for none yet. */
    private Path folder;
    /** The file a reveal is walking to, to ring when the walk lands. */
    private Path revealing;

    Navigator(Gui gui, Motion motion) {
        this.gui = gui;
        this.motion = motion;

        path = new TextField(gui, "").name("path");
        path.node().width(Length.FILL).font(Type.UI).textSize(Type.SMALL);
        gui.landmark(Landmarks.PATH, path.node());

        tree = new TreeView<Path>(gui, "files").motion(motion.arrival)
                .action(MenuAction.of(OPEN, "›", "Open"))
                .action(MenuAction.of(SHOW, "»", "Show this folder"))
                .action(MenuAction.of(COPY_PATH, "Copy path"));
        tree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TREE, tree.node());

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

    /** The path bar's address, which its messages carry. */
    String pathAddress() {
        return path.address();
    }

    /** The tree's address, which its messages carry. */
    String treeAddress() {
        return tree.address();
    }

    /** The folder shown, or null for none yet. */
    Path folder() {
        return folder;
    }

    /**
     * Show {@code shown}: say in the path bar which folder it is, and have the tree ask for its levels again, which
     * the view answers as it answers every question the tree asks.
     */
    void show(Path shown) {
        folder = shown;
        path.text(String.valueOf(shown));
        tree.refresh();
    }

    /**
     * What to ask {@code ListFolder} for a level the tree wants: {@code parent} is a folder opened in it, or null for
     * its top, the folder shown. Null when there is nothing to list, no folder shown yet.
     */
    Listing wanted(Object parent) {
        if (parent instanceof Path dir) {
            return new Listing(treeAddress(), dir, false);
        }
        return folder == null ? null : new Listing(treeAddress(), folder, true);
    }

    /** {@code ListFolder}'s answer, as the tree's entries for the level it asked about. */
    TreeView.Children children(Listed listed) {
        List<TreeView.Entry> entries = new ArrayList<>(listed.items().size());
        for (Item item : listed.items()) {
            if (item.folder()) {
                folders.add(item.path());
                entries.add(TreeView.Entry.branch(item.path(), item.name()).hides(OPEN));
            } else {
                entries.add(TreeView.Entry.leaf(item.path(), item.name()).hides(SHOW));
            }
        }
        return new TreeView.Children(treeAddress(), listed.roots() ? null : listed.dir(), entries);
    }

    /** An empty top level, for a tree that asks before a folder is shown. */
    TreeView.Children nothing() {
        return new TreeView.Children(treeAddress(), null, List.of());
    }

    /** Whether the tree has a row for {@code item}: listed, and under folders that are open. */
    boolean hasRow(Path item) {
        return tree.rowNode(item) != null;
    }

    /** Whether {@code item} is a folder, as its listing said. */
    boolean isFolder(Path item) {
        return folders.contains(item);
    }

    /** Open a folder's row in place. */
    void expand(Path item) {
        tree.expand(item);
    }

    /**
     * Unfold the tree down to {@code file} and select its row; when the walk lands, {@link #revealed} rings it, since
     * a row the walk scrolled to is one the eye has not found yet. A file outside the folder is left alone: there is
     * no way down to it from here.
     */
    boolean reveal(Path file) {
        List<Path> chain = ListFolder.chain(folder, file);
        if (chain.isEmpty()) {
            return false;
        }
        revealing = chain.getLast();
        tree.revealPath(chain);
        return true;
    }

    /** A walk ended: ring its row if it was the reveal's and it got there. */
    void revealed(Object item, boolean reached) {
        if (reached && item.equals(revealing)) {
            Node row = tree.rowNode(revealing);
            if (row != null) {
                motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
            }
            revealing = null;
        }
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
