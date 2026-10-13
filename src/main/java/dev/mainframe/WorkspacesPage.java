package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.Board;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.gui.widget.Deck;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.SelectionModel;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Workspaces tab: the saved workspaces down the left, and the one selected on the right, with its name and its
 * folders, one tab each, in order.
 *
 * <p>A folder is a card on a {@link Board}: the tab's label, a field, over the folder's path. A card is selected by
 * clicking it or by focusing its label; it is dragged to reorder (or moved with Alt+Up and Alt+Down), and removed from
 * its right-click menu or with Backspace or Delete while it, rather than its label, has the keyboard. A label is never
 * left empty: leaving it blank gives it back the folder's own name.
 *
 * <p>The board is the drag: it picks a card up, carries it, opens the gap where it would land and puts it down, and
 * a press on the label is the label's, for selecting text. This page says what a card looks like ({@link #face}),
 * what putting one somewhere means ({@link #place}) and what the folders now are ({@link #display}) — which every
 * change ends in, undo included, so they all move alike.
 *
 * <p>Like the rest of the settings, every change is saved the moment it is made, and every change but a run of
 * typing is a step in this page's history: Ctrl+Z and Ctrl+Y undo and redo moving, adding, removing and relabelling
 * folders, and creating, renaming and deleting workspaces. Inside a field they undo its typing instead, and leaving a
 * label it has changed records the relabel as one step. A workspace is opened by starting MainFrame with
 * {@code --workspace=<name>}.
 */
final class WorkspacesPage {

    /**
     * One folder on show. A thing rather than a value: the board knows a card by its item, so the item has to stay
     * itself while its label is edited. A card keyed by the entry would be a different card after every keystroke.
     */
    private static final class Folder {
        volatile Entry entry;

        Folder(Entry entry) {
            this.entry = entry;
        }
    }

    /** A folder's card, as built for the board: the node it carries, and the label on it. */
    private record Face(Node box, TextField label) { }

    private final Gui gui;
    private final WorkspaceStore store;
    private final Dialogs dialogs;
    private final History history = new History();
    private final Node root;
    private final ListView<String> list;
    private final Button delete;
    private final Node editor;
    private final Node empty;
    private final TextField nameField;
    private final Node nameProblem;
    private final Board<Folder> board;
    private final Deck<Folder> deck;
    private final Node noFolders;
    private final Node launchLine;
    /** Every card the board has had built, by folder. */
    private final Map<Folder, Face> faces = new ConcurrentHashMap<>();

    // Volatile, and replaced whole rather than changed, because the board reads them without the page's lock: it
    // asks place() under a lock of its own, and the page calls the board under this one.
    /** The workspace on the right; null when there is none. */
    private volatile String shown;
    /** Its folders, in order. */
    private volatile List<Folder> folders = List.of();
    /** The card that is selected, or null. */
    private volatile Folder selected;
    /** The folders as they were when a label took the keyboard: what leaving it compares against. */
    private List<Entry> labelBefore;

    WorkspacesPage(Gui gui, WorkspaceStore store, Dialogs dialogs, Motion motion) {
        this.gui = gui;
        this.dialogs = dialogs;
        this.store = store;
        ContextMenu.presentOn(gui);

        list = new ListView<String>(gui, 2f, (g, name) -> g.text(name).width(Length.FILL).height(Length.FILL)
                .padding(Length.ZERO, Length.rem(0.5f)).textSize(Length.rem(0.875f))
                .textColor(g.theme().color(Role.INK)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                SelectionModel.single());
        list.selection().onChange(chosen -> {
            if (chosen.size() == 1) show(chosen.iterator().next());
        });
        Button add = new Button(gui, "New").onPress(this::create);
        delete = new Button(gui, "Delete").onPress(this::confirmDelete);
        Node left = gui.column().width(Length.rem(11)).height(Length.FILL).gap(Length.rem(0.5f)).children(
                label("Workspaces"),
                list.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.WELL))
                        .corner(Length.rem(0.4f)),
                gui.row().gap(Length.rem(0.5f)).children(add.node(), delete.node()));

        nameField = new TextField(gui);
        nameField.node().width(Length.FILL);
        nameField.onSubmit(text -> commitName());
        nameProblem = note("").textColor(gui.theme().color(Role.DANGER)).visible(false);
        board = new Board<Folder>(gui, this::face).motion(motion.arrival);
        deck = board.column().reorderable(this::place);
        // Room either side for a carried card's shadow: the deck scrolls, and a scroller clips to its own box.
        deck.node().width(Length.FILL).height(Length.grow(1)).padding(Length.ZERO, Length.dp(8));
        noFolders = note("No folders yet. Add folder picks one; each becomes a tab.").visible(false);
        Button addFolder = new Button(gui, "Add folder…").onPress(this::pickFolder);
        launchLine = note("");
        editor = gui.column().width(Length.grow(1)).height(Length.FILL).gap(Length.rem(0.5f)).children(
                label("Name"), nameField.node(), nameProblem,
                label("Folders, one tab each. Drag to reorder; right-click to remove."), noFolders, deck.node(),
                gui.row().children(addFolder.node()), launchLine);
        empty = note("No workspaces yet. New makes one: a list of folders that MainFrame opens as tabs, each in its "
                + "own folder.").width(Length.grow(1));

        root = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.rem(1)).gap(Length.rem(1))
                .children(left, editor, empty);
        gui.history(root, history, ClaimScope.VISIBLE);
        // Where a drop and the board's Alt+arrow moves are recorded: the page's own history, so Ctrl+Z takes them
        // back with everything else.
        gui.dropHistory(history);
        gui.bus().subscribe(gui.focusEvents(), e -> focusChanged(e.nodeId(), e.gained()));
        // Names for ottermate and agents: "click workspaces.new".
        gui.landmark("workspaces.list", list.node()).landmark("workspaces.new", add.node())
                .landmark("workspaces.delete", delete.node()).landmark("workspaces.name", nameField.node())
                .landmark("workspaces.add-folder", addFolder.node()).landmark("workspaces.folders", deck.node());
        refresh(null);
    }

    Node node() {
        return root;
    }

    /** The page's undo history: every change it makes, as one step each. */
    History history() {
        return history;
    }

    // -- the list ---------------------------------------------------------------------------------------------

    /** Shows the workspace called {@code name}, as clicking it in the list would. */
    synchronized void select(String name) {
        refresh(name);
    }

    /** Re-reads the folder and selects {@code select}, or else the first workspace, or shows there are none. */
    private synchronized void refresh(String select) {
        List<String> names = store.names();
        list.items(names);
        String pick = select != null && names.contains(select) ? select : names.isEmpty() ? null : names.get(0);
        if (pick == null) {
            shown = null;
            folders = List.of();
            selected = null;
            deck.items(List.of());
            editor.visible(false);
            empty.visible(true);
            delete.enabled(false);
            list.selection().clear();
            return;
        }
        list.selection().set(List.of(pick));
        list.reveal(pick);
        show(pick);
    }

    private synchronized void show(String name) {
        if (name.equals(shown)) return;
        Workspace w = store.load(name).orElse(new Workspace(name, List.of()));
        List<Folder> loaded = new ArrayList<>();
        for (Entry e : w.entries()) loaded.add(new Folder(e));
        display(name, loaded, null);
    }

    /**
     * Shows {@code next} as workspace {@code name}'s folders, and selects {@code select}. The one place the folders
     * on show change: the board is handed the new list and moves what differs into place, whatever the change was.
     */
    private void display(String name, List<Folder> next, Folder select) {
        if (!name.equals(shown)) {
            shown = name;
            list.selection().set(List.of(name));
            list.reveal(name);
            editor.visible(true);
            empty.visible(false);
            delete.enabled(true);
            nameField.text(name);
            nameProblem.visible(false);
            launchLine.text("Opens with: mainframe --workspace=\"" + name + "\"\nSaved as " + store.file(name));
        }
        folders = List.copyOf(next);
        selected = select != null && folders.contains(select) ? select : null;
        for (Folder f : folders) {
            Face face = faces.get(f);
            String title = f.entry.title();
            if (face != null && !face.label.text().equals(title)) face.label.text(title);
        }
        noFolders.visible(folders.isEmpty());
        deck.items(folders);
        for (int i = 0; i < folders.size(); i++) {
            Node card = board.cardOf(folders.get(i));
            if (card != null) gui.landmark("workspaces.folder." + i, card);
        }
        paintSelection();
    }

    /** A new workspace, as the New button makes one: a step in the history, its name selected to type over. */
    void create() {
        String name = store.freshName();
        history.perform(created(new Workspace(name, List.of())));
        gui.focus(nameField.node());
        nameField.select(0, name.length());
    }

    private void confirmDelete() {
        String name = shown;
        if (name == null) return;
        dialogs.confirm("Delete " + name + "?",
                "The workspace and its list of folders are deleted. The folders themselves are not touched. "
                        + "Ctrl+Z brings it back.",
                "Delete", () -> history.perform(deleted(name)), () -> { });
    }

    // -- the name ---------------------------------------------------------------------------------------------

    /** Enter or leaving the field renames the file; a name that cannot be one says why and goes back. */
    private void commitName() {
        String from;
        String to;
        String problem;
        synchronized (this) {
            from = shown;
            if (from == null) return;
            to = nameField.text().strip();
            if (to.equals(from)) {
                nameProblem.visible(false);
                return;
            }
            problem = WorkspaceStore.problem(to);
            if (problem == null && !to.equalsIgnoreCase(from) && store.exists(to)) {
                problem = "There is already a workspace called " + to + ".";
            }
            if (problem != null) {
                nameProblem.text(problem).visible(true);
                nameField.text(from);
                return;
            }
        }
        history.perform(renamed(from, to));
    }

    // -- the cards --------------------------------------------------------------------------------------------

    /**
     * What a folder's card looks like, built by the board once per folder while it is on the board. The board makes
     * it focusable, lifts it, carries it and moves it with Alt+Up and Alt+Down; everything else on it is the page's,
     * and finds where its folder is when it is used rather than when it was built, since the card moves.
     */
    private Node face(Folder folder) {
        Entry entry = folder.entry;
        TextField label = new TextField(gui, entry.title());
        label.node().width(Length.FILL);
        label.onChange(text -> relabel(folder, text));
        label.onSubmit(text -> focusCard(folder));
        boolean there = isDirectory(entry.path());
        Node path = gui.text(there ? entry.path() : entry.path() + "  (not found)").width(Length.FILL)
                .height(Length.rem(1.25f)).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(there ? Role.DIM : Role.DANGER))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        Node grip = IconView.of(gui, Icon.GRIP).size(Length.dp(16)).tint(Role.FAINT).node();
        Node box = gui.row().width(Length.FILL).padding(Length.rem(0.5f)).gap(Length.rem(0.5f))
                .alignItems(AlignItems.CENTER).corner(Length.rem(0.4f))
                .children(grip, gui.column().width(Length.grow(1)).gap(Length.rem(0.25f))
                        .children(label.node(), path));
        Face built = new Face(box, label);
        Face old = faces.put(folder, built);
        if (old != null) old.label.close();   // the board built it again: the folder came back after leaving
        paint(built, folder == selected);
        gui.onContextMenu(box, menu -> {
            List<Folder> now = folders;
            int at = now.indexOf(folder);
            menu.item("Move up", at > 0, () -> move(folder, -1))
                    .item("Move down", at >= 0 && at < now.size() - 1, () -> move(folder, 1))
                    .item("Remove", () -> remove(folder));
        });
        // While the card has the keyboard, rather than its label.
        gui.claimUi(box, Shortcut.of(Key.BACKSPACE), ClaimScope.FOCUSED, () -> remove(folder));
        gui.claimUi(box, Shortcut.of(Key.DELETE), ClaimScope.FOCUSED, () -> remove(folder));
        gui.claimUi(box, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> focusNeighbour(folder, -1));
        gui.claimUi(box, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> focusNeighbour(folder, 1));
        gui.claimUi(box, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, () -> editLabel(folder));
        gui.claimUi(box, Shortcut.of(Key.F2), ClaimScope.FOCUSED, () -> editLabel(folder));
        // The label's: the board's Alt+arrows are on the card, and moving a folder works from either.
        gui.claimUi(label.node(), Shortcut.of(Key.UP, Modifier.ALT), ClaimScope.FOCUSED, () -> move(folder, -1));
        gui.claimUi(label.node(), Shortcut.of(Key.DOWN, Modifier.ALT), ClaimScope.FOCUSED, () -> move(folder, 1));
        gui.claimUi(label.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> focusCard(folder));
        return box;
    }

    /** Which card has the keyboard, itself or through its label; and a label that loses it is committed. */
    private void focusChanged(long nodeId, boolean gained) {
        if (!gained && nodeId == nameField.node().id()) {
            commitName();
            return;
        }
        Folder folder = null;
        boolean isLabel = false;
        for (Folder f : folders) {
            Face face = faces.get(f);
            if (face == null) continue;
            if (face.box.id() == nodeId) folder = f;
            if (face.label.node().id() == nodeId) { folder = f; isLabel = true; }
        }
        if (folder == null) return;
        if (gained) {
            synchronized (this) {
                selected = folder;
                paintSelection();
                if (isLabel) labelBefore = entriesOf(folders);
            }
            faces.get(folder).box.scrollIntoView();
            return;
        }
        if (isLabel) commitLabel(folder);
    }

    private void paintSelection() {
        Folder on = selected;
        for (Folder f : folders) {
            Face face = faces.get(f);
            if (face != null) paint(face, f == on);
        }
    }

    private void paint(Face face, boolean on) {
        face.box.background(gui.theme().color(on ? Role.RAISED : Role.WELL))
                .border(Length.dp(1), gui.theme().color(on ? Role.ACCENT : Role.NONE));
    }

    private void focusCard(Folder folder) {
        Face face = faces.get(folder);
        if (face != null) gui.focus(face.box);
    }

    private void focusNeighbour(Folder folder, int by) {
        List<Folder> now = folders;
        int at = now.indexOf(folder) + by;
        if (at >= 0 && at < now.size()) focusCard(now.get(at));
    }

    private void editLabel(Folder folder) {
        Face face = faces.get(folder);
        if (face == null) return;
        gui.focus(face.label.node());
        face.label.select(0, face.label.text().length());
    }

    // -- changing the folders ---------------------------------------------------------------------------------

    /**
     * What putting {@code moved} at {@code where} means: the folders in that order, or null where it would change
     * nothing. Asked by the board once a frame while a card is carried, and by its Alt+arrows; the change it returns
     * is performed on the drop, through the page's history.
     */
    private Change place(Folder moved, Placement<Folder> where, DropEffect effect) {
        String name = shown;
        List<Folder> now = folders;
        if (effect != DropEffect.MOVE || name == null || !now.contains(moved)) return null;
        List<Folder> next = new ArrayList<>(now);
        next.remove(moved);
        int at = next.size();
        if (where.reference() != null) {
            int ref = next.indexOf(where.reference());
            if (ref < 0) return null;
            at = where.relation() == Placement.Relation.BEFORE ? ref : ref + 1;
        }
        next.add(at, moved);
        return next.equals(now) ? null : order(name, next, moved);
    }

    /** The label as typed: saved at once, without rebuilding anything; the step is recorded on leaving it. */
    private synchronized void relabel(Folder folder, String text) {
        String name = shown;
        if (name == null || !folders.contains(folder) || folder.entry.name().equals(text)) return;
        folder.entry = folder.entry.withName(text);
        save(new Workspace(name, entriesOf(folders)));
    }

    /** Leaving a label: a blank one gets the folder's name back, and a changed one is a step in the history. */
    private void commitLabel(Folder folder) {
        String name;
        List<Folder> order;
        List<Entry> before;
        List<Entry> after;
        synchronized (this) {
            before = labelBefore;
            labelBefore = null;
            name = shown;
            order = folders;
            if (name == null || !order.contains(folder)) return;
            Entry e = folder.entry;
            if (e.name().isBlank()) {
                folder.entry = e.withName(e.title());
                save(new Workspace(name, entriesOf(order)));
                Face face = faces.get(folder);
                if (face != null) face.label.text(folder.entry.name());
            }
            after = entriesOf(order);
        }
        if (before != null && before.size() == after.size() && !before.equals(after)) {
            history.record(folders(name, order, before, folder));
        }
    }

    private void move(Folder folder, int by) {
        String name = shown;
        List<Folder> now = folders;
        int from = now.indexOf(folder);
        int to = from + by;
        if (name == null || from < 0 || to < 0 || to >= now.size()) return;
        List<Folder> next = new ArrayList<>(now);
        next.add(to, next.remove(from));
        history.perform(order(name, next, folder));
    }

    private void remove(Folder folder) {
        String name = shown;
        List<Folder> now = folders;
        int at = now.indexOf(folder);
        if (name == null || at < 0) return;
        List<Folder> next = new ArrayList<>(now);
        next.remove(at);
        Folder neighbour = next.isEmpty() ? null : next.get(Math.min(at, next.size() - 1));
        history.perform(order(name, next, neighbour));
    }

    private void addFolder(Path dir) {
        String name = shown;
        if (name == null) return;
        Path p = dir.toAbsolutePath().normalize();
        String title = new Entry("", p.toString()).title();
        Folder added = new Folder(new Entry(title, p.toString()));
        List<Folder> next = new ArrayList<>(folders);
        next.add(added);
        history.perform(order(name, next, added));
    }

    /** Asks the OS for a folder, starting beside the last one added. */
    private void pickFolder() {
        List<Folder> now = folders;
        if (shown == null) return;
        Path start = now.isEmpty() ? Path.of(System.getProperty("user.home"))
                : parentOf(now.get(now.size() - 1).entry.path());
        dialogs.openFolder(start, this::addFolder, () -> { });
    }

    // -- the changes, each undone by the change it returns ----------------------------------------------------

    /** Puts {@code name}'s folders in {@code order}, each as it is now. */
    private Change order(String name, List<Folder> order, Folder select) {
        return folders(name, order, entriesOf(order), select);
    }

    /**
     * Puts {@code name}'s folders in {@code order}, with {@code entries} as theirs, shows them and selects and
     * focuses {@code select}; undone by putting back the order and the entries there were.
     */
    private Change folders(String name, List<Folder> order, List<Entry> entries, Folder select) {
        List<Folder> to = List.copyOf(order);
        List<Entry> with = List.copyOf(entries);
        return () -> {
            List<Folder> backOrder;
            Folder back;
            synchronized (this) {
                if (name.equals(shown)) {
                    backOrder = folders;
                    back = selected != null ? selected : select;
                } else {
                    backOrder = new ArrayList<>();
                    for (Entry e : store.load(name).map(Workspace::entries).orElse(List.of())) {
                        backOrder.add(new Folder(e));
                    }
                    back = select;
                }
                List<Entry> backEntries = entriesOf(backOrder);
                if (save(new Workspace(name, with))) {
                    for (int i = 0; i < to.size(); i++) to.get(i).entry = with.get(i);
                    display(name, to, select);
                }
                Node card = select == null ? null : board.cardOf(select);
                if (card != null) gui.focus(card);
                return folders(name, backOrder, backEntries, back);
            }
        };
    }

    private Change renamed(String from, String to) {
        return () -> {
            try {
                store.rename(from, to);
            } catch (IOException e) {
                dialogs.info("Could not rename " + from, e.getMessage());
                return renamed(to, from);
            }
            synchronized (this) {
                shown = null;
                refresh(to);
            }
            return renamed(to, from);
        };
    }

    private Change created(Workspace w) {
        return () -> {
            save(w);
            synchronized (this) {
                shown = null;
                refresh(w.name());
            }
            return deleted(w.name());
        };
    }

    private Change deleted(String name) {
        return () -> {
            Workspace w = store.load(name).orElse(new Workspace(name, List.of()));
            try {
                store.delete(name);
            } catch (IOException e) {
                dialogs.info("Could not delete " + name, e.getMessage());
            }
            synchronized (this) {
                shown = null;
                refresh(null);
            }
            return created(w);
        };
    }

    private boolean save(Workspace w) {
        try {
            store.save(w);
            return true;
        } catch (IOException e) {
            dialogs.info("Could not save " + w.name(), e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static List<Entry> entriesOf(List<Folder> folders) {
        List<Entry> entries = new ArrayList<>(folders.size());
        for (Folder f : folders) entries.add(f.entry);
        return entries;
    }

    private static boolean isDirectory(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static Path parentOf(String path) {
        try {
            Path p = Path.of(path);
            return p.getParent() == null ? p : p.getParent();
        } catch (InvalidPathException e) {
            return Path.of(System.getProperty("user.home"));
        }
    }

    private Node label(String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(1.5f)).textSize(Length.rem(0.875f))
                .textColor(gui.theme().color(Role.DIM)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.BOTTOM);
    }

    private Node note(String text) {
        return gui.text(text).width(Length.FILL).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }
}
