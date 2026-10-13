package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.gui.widget.ListView;
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

/**
 * The Workspaces tab: the saved workspaces down the left, and the one selected on the right, with its name and its
 * folders, one tab each, in order.
 *
 * <p>A folder is a card: the tab's label, a field, over the folder's path. A card is selected by clicking it or by
 * focusing its label; it is dragged to reorder (or moved with Alt+Up and Alt+Down), and removed from its right-click
 * menu or with Backspace or Delete while it, rather than its label, has the keyboard. A label is never left empty:
 * leaving it blank gives it back the folder's own name.
 *
 * <p>Like the rest of the settings, every change is saved the moment it is made, and every change but a run of
 * typing is a step in this page's history: Ctrl+Z and Ctrl+Y undo and redo moving, adding, removing and relabelling
 * folders, and creating, renaming and deleting workspaces. Inside a field they undo its typing instead, and leaving a
 * label it has changed records the relabel as one step. A workspace is opened by starting MainFrame with
 * {@code --workspace=<name>}.
 */
final class WorkspacesPage {

    /** A folder card being dragged: which workspace it belongs to, and where it was. */
    private record Carried(String workspace, int index) { }

    private static final PayloadType<Carried> FOLDER = PayloadType.of("mainframe.workspace-folder");

    /** One folder's card: the strip above it that lights where a drop would land, the card, and its label. */
    private static final class Card {
        final Node seam;
        final Node box;
        final TextField label;
        volatile Rect rect = Rect.ZERO;

        Card(Node seam, Node box, TextField label) {
            this.seam = seam;
            this.box = box;
            this.label = label;
        }
    }

    private final Gui gui;
    private final WorkspaceStore store;
    private final Icons icons;
    private final History history = new History();
    private final Node root;
    private final ListView<String> list;
    private final Button delete;
    private final Node editor;
    private final Node empty;
    private final TextField nameField;
    private final Node nameProblem;
    private final Node folders;
    private final Node launchLine;
    private final List<Node> rows = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    /** The strip after the last card: where a drop at the end lands. */
    private Node endSeam;

    private final Dialogs dialogs;
    /** The workspace on the right, as last saved; null when there is none. */
    private Workspace current;
    /** The card that is selected, or -1. */
    private int selected = -1;
    /** The seam lit by the drag in progress, or -1. */
    private int litSeam = -1;
    /** The folders as they were when a label took the keyboard: what leaving it compares against. */
    private List<Entry> labelBefore;

    WorkspacesPage(Gui gui, WorkspaceStore store, Icons icons, Dialogs dialogs) {
        this.gui = gui;
        this.dialogs = dialogs;
        this.store = store;
        this.icons = icons;
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
        folders = gui.column().width(Length.FILL).height(Length.grow(1)).scroll(false, true);
        gui.onDrop(folders, this::resolveDrop);
        gui.drag().onCommit(v -> {
            DragState d = v.value();
            if (!d.accepts()) light(-1);
        });
        Button addFolder = new Button(gui, "Add folder…").onPress(this::pickFolder);
        launchLine = note("");
        editor = gui.column().width(Length.grow(1)).height(Length.FILL).gap(Length.rem(0.5f)).children(
                label("Name"), nameField.node(), nameProblem,
                label("Folders, one tab each. Drag to reorder; right-click to remove."), folders,
                gui.row().children(addFolder.node()), launchLine);
        empty = note("No workspaces yet. New makes one: a list of folders that MainFrame opens as tabs, each in its "
                + "own folder.").width(Length.grow(1));

        root = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.rem(1)).gap(Length.rem(1))
                .children(left, editor, empty);
        gui.history(root, history, ClaimScope.VISIBLE);
        gui.dropHistory(history);
        gui.bus().subscribe(gui.focusEvents(), e -> focusChanged(e.nodeId(), e.gained()));
        // Names for ottermate and agents: "click workspaces.new".
        gui.landmark("workspaces.list", list.node()).landmark("workspaces.new", add.node())
                .landmark("workspaces.delete", delete.node()).landmark("workspaces.name", nameField.node())
                .landmark("workspaces.add-folder", addFolder.node()).landmark("workspaces.folders", folders);
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
            current = null;
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
        if (current != null && current.name().equals(name)) return;
        Workspace w = store.load(name).orElse(new Workspace(name, List.of()));
        current = w;
        editor.visible(true);
        empty.visible(false);
        delete.enabled(true);
        nameField.text(w.name());
        nameProblem.visible(false);
        launchLine.text("Opens with: mainframe --workspace=\"" + w.name() + "\"\nSaved as " + store.file(w.name()));
        selected = -1;
        rebuildRows();
    }

    /** A new workspace, as the New button makes one: a step in the history, its name selected to type over. */
    void create() {
        String name = store.freshName();
        history.perform(created(new Workspace(name, List.of())));
        gui.focus(nameField.node());
        nameField.select(0, name.length());
    }

    private void confirmDelete() {
        Workspace w;
        synchronized (this) {
            w = current;
        }
        if (w == null) return;
        dialogs.confirm("Delete " + w.name() + "?",
                "The workspace and its list of folders are deleted. The folders themselves are not touched. "
                        + "Ctrl+Z brings it back.",
                "Delete", () -> history.perform(deleted(w.name())), () -> { });
    }

    // -- the name ---------------------------------------------------------------------------------------------

    /** Enter or leaving the field renames the file; a name that cannot be one says why and goes back. */
    private void commitName() {
        Workspace w;
        String to;
        String problem;
        synchronized (this) {
            w = current;
            if (w == null) return;
            to = nameField.text().strip();
            if (to.equals(w.name())) {
                nameProblem.visible(false);
                return;
            }
            problem = WorkspaceStore.problem(to);
            if (problem == null && !to.equalsIgnoreCase(w.name()) && store.exists(to)) {
                problem = "There is already a workspace called " + to + ".";
            }
            if (problem != null) {
                nameProblem.text(problem).visible(true);
                nameField.text(w.name());
                return;
            }
        }
        history.perform(renamed(w.name(), to));
    }

    // -- the cards --------------------------------------------------------------------------------------------

    /**
     * One card per folder. Cards are rebuilt only when the list changes shape, never while a label is being typed,
     * which would take the field away mid-word.
     */
    private void rebuildRows() {
        rows.forEach(Node::remove);
        rows.clear();
        cards.forEach(c -> c.label.close());
        cards.clear();
        endSeam = null;
        litSeam = -1;
        List<Entry> entries = current.entries();
        if (entries.isEmpty()) {
            Node none = note("No folders yet. Add folder picks one; each becomes a tab.");
            rows.add(none);
            folders.append(none);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Card c = card(i, entries.get(i));
            cards.add(c);
            rows.add(c.seam);
            rows.add(c.box);
            folders.append(c.seam);
            folders.append(c.box);
        }
        endSeam = seam();
        rows.add(endSeam);
        folders.append(endSeam);
        paintSelection();
    }

    private Card card(int index, Entry entry) {
        TextField label = new TextField(gui, entry.title());
        label.node().width(Length.FILL);
        label.onChange(text -> relabel(index, text));
        label.onSubmit(text -> focusCard(index));
        boolean there = isDirectory(entry.path());
        Node path = gui.text(there ? entry.path() : entry.path() + "  (not found)").width(Length.FILL)
                .height(Length.rem(1.25f)).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(there ? Role.DIM : Role.DANGER))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        Node grip = icons.icon(gui, "grip", 16, gui.theme().color(Role.FAINT));
        Node box = gui.row().width(Length.FILL).padding(Length.rem(0.5f)).gap(Length.rem(0.5f))
                .alignItems(AlignItems.CENTER).corner(Length.rem(0.4f))
                .children(grip, gui.column().width(Length.grow(1)).gap(Length.rem(0.25f))
                        .children(label.node(), path));
        Card c = new Card(seam(), box, label);
        gui.focusable(box, true);
        gui.onResize(box, l -> c.rect = l.rect());
        gui.onContextMenu(box, menu -> menu
                .item("Move up", index > 0, () -> move(index, -1))
                .item("Move down", index < current.entries().size() - 1, () -> move(index, 1))
                .item("Remove", () -> remove(index)));
        gui.onDragSource(box, (x, y) -> {
            Workspace w = current;
            return w == null ? null : Payload.of(FOLDER, new Carried(w.name(), index));
        });
        // While the card has the keyboard, rather than its label.
        gui.claimUi(box, Shortcut.of(Key.BACKSPACE), ClaimScope.FOCUSED, () -> remove(index));
        gui.claimUi(box, Shortcut.of(Key.DELETE), ClaimScope.FOCUSED, () -> remove(index));
        gui.claimUi(box, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> focusCard(index - 1));
        gui.claimUi(box, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> focusCard(index + 1));
        gui.claimUi(box, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, () -> editLabel(index));
        gui.claimUi(box, Shortcut.of(Key.F2), ClaimScope.FOCUSED, () -> editLabel(index));
        // Either way.
        for (Node n : List.of(box, label.node())) {
            gui.claimUi(n, Shortcut.of(Key.UP, Modifier.ALT), ClaimScope.FOCUSED, () -> move(index, -1));
            gui.claimUi(n, Shortcut.of(Key.DOWN, Modifier.ALT), ClaimScope.FOCUSED, () -> move(index, 1));
        }
        gui.claimUi(label.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> focusCard(index));
        gui.landmark("workspaces.folder." + index, box);
        return c;
    }

    private Node seam() {
        return gui.box().width(Length.FILL).height(Length.dp(3)).corner(Length.dp(1.5f))
                .background(gui.theme().color(Role.NONE));
    }

    /** Which card has the keyboard, itself or through its label; and a label that loses it is committed. */
    private void focusChanged(long nodeId, boolean gained) {
        if (!gained && nodeId == nameField.node().id()) {
            commitName();
            return;
        }
        int card = -1;
        boolean isLabel = false;
        synchronized (this) {
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).box.id() == nodeId) card = i;
                if (cards.get(i).label.node().id() == nodeId) { card = i; isLabel = true; }
            }
            if (card < 0) return;
            if (gained) {
                selected = card;
                paintSelection();
                cards.get(card).box.scrollIntoView();
                if (isLabel && current != null) labelBefore = current.entries();
                return;
            }
        }
        if (isLabel) commitLabel(card);
    }

    private synchronized void paintSelection() {
        for (int i = 0; i < cards.size(); i++) {
            boolean on = i == selected;
            cards.get(i).box.background(gui.theme().color(on ? Role.RAISED : Role.WELL))
                    .border(Length.dp(1), gui.theme().color(on ? Role.ACCENT : Role.NONE));
        }
    }

    private void focusCard(int index) {
        Card c;
        synchronized (this) {
            if (index < 0 || index >= cards.size()) return;
            c = cards.get(index);
        }
        gui.focus(c.box);
    }

    private void editLabel(int index) {
        Card c;
        synchronized (this) {
            if (index < 0 || index >= cards.size()) return;
            c = cards.get(index);
        }
        gui.focus(c.label.node());
        c.label.select(0, c.label.text().length());
    }

    // -- changing the folders ---------------------------------------------------------------------------------

    /** The label as typed: saved at once, without rebuilding anything; the step is recorded on leaving it. */
    private synchronized void relabel(int index, String text) {
        Workspace w = current;
        if (w == null || index >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        if (entries.get(index).name().equals(text)) return;
        entries.set(index, entries.get(index).withName(text));
        if (save(w.withEntries(entries))) current = w.withEntries(entries);
    }

    /** Leaving a label: a blank one gets the folder's name back, and a changed one is a step in the history. */
    private void commitLabel(int index) {
        Workspace w;
        List<Entry> before;
        synchronized (this) {
            w = current;
            before = labelBefore;
            labelBefore = null;
            if (w == null || index >= w.entries().size() || index >= cards.size()) return;
            Entry e = w.entries().get(index);
            if (e.name().isBlank()) {
                Entry named = e.withName(e.title());
                List<Entry> entries = new ArrayList<>(w.entries());
                entries.set(index, named);
                w = w.withEntries(entries);
                if (save(w)) current = w;
                cards.get(index).label.text(named.name());
            }
        }
        if (before != null && !before.equals(w.entries())) history.record(folders(w.name(), before, index));
    }

    private void move(int index, int by) {
        Workspace w;
        synchronized (this) {
            w = current;
        }
        int to = index + by;
        if (w == null || to < 0 || to >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.add(to, entries.remove(index));
        history.perform(folders(w.name(), entries, to));
    }

    private void remove(int index) {
        Workspace w;
        synchronized (this) {
            w = current;
        }
        if (w == null || index >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.remove(index);
        history.perform(folders(w.name(), entries, Math.min(index, entries.size() - 1)));
    }

    private void addFolder(Path dir) {
        Workspace w;
        synchronized (this) {
            w = current;
        }
        if (w == null) return;
        Path p = dir.toAbsolutePath().normalize();
        String title = new Entry("", p.toString()).title();
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.add(new Entry(title, p.toString()));
        history.perform(folders(w.name(), entries, entries.size() - 1));
    }

    /** Asks the OS for a folder, starting beside the last one added. */
    private void pickFolder() {
        Workspace w = current;
        if (w == null) return;
        Path start = w.entries().isEmpty() ? Path.of(System.getProperty("user.home"))
                : parentOf(w.entries().get(w.entries().size() - 1).path());
        dialogs.openFolder(start, this::addFolder, () -> { });
    }

    // -- dragging ---------------------------------------------------------------------------------------------

    /**
     * Where a carried card would land: the seam whose side of the cards' midlines the pointer is on, so every point
     * of the list answers and a slow pointer moves the answer one seam at a time. A drop where the card already is
     * is no drop.
     */
    private synchronized Drop resolveDrop(Payload payload, float x, float y) {
        Carried carried = payload.as(FOLDER).orElse(null);
        Workspace w = current;
        if (carried == null || w == null || !carried.workspace().equals(w.name()) || cards.isEmpty()
                || carried.index() >= w.entries().size()) {
            light(-1);
            return Drop.NONE;
        }
        int seam = 0;
        for (Card c : cards) {
            if (y > c.rect.y() + c.rect.h() / 2f) seam++;
        }
        int from = carried.index();
        if (seam == from || seam == from + 1) {
            light(-1);
            return Drop.NONE;
        }
        int to = seam > from ? seam - 1 : seam;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.add(to, entries.remove(from));
        light(seam);
        Rect edge = seam < cards.size() ? cards.get(seam).rect : cards.get(cards.size() - 1).rect;
        float at = seam < cards.size() ? edge.y() - 2f : edge.y() + edge.h() + 1f;
        return Drop.move(new Rect(edge.x(), at, edge.w(), 2f), folders(w.name(), entries, to));
    }

    /** Lights seam {@code seam} (the one above card {@code seam}, or after the last), or none for -1. */
    private synchronized void light(int seam) {
        if (seam == litSeam) return;
        seamNode(litSeam, Role.NONE);
        litSeam = seam;
        seamNode(seam, Role.ACCENT);
    }

    private void seamNode(int seam, Role role) {
        if (seam < 0) return;
        Node n = seam < cards.size() ? cards.get(seam).seam : seam == cards.size() ? endSeam : null;
        if (n != null) n.background(gui.theme().color(role));
    }

    // -- the changes, each undone by the change it returns ----------------------------------------------------

    /** Puts {@code name}'s folders to {@code to} and selects card {@code select}; undone by putting them back. */
    private Change folders(String name, List<Entry> to, int select) {
        List<Entry> after = List.copyOf(to);
        return () -> {
            Workspace was = store.load(name).orElse(new Workspace(name, List.of()));
            int back;
            synchronized (this) {
                back = current != null && current.name().equals(name) && selected >= 0 ? selected : select;
                Workspace now = was.withEntries(after);
                if (save(now)) {
                    showChanged(now, select);
                }
            }
            return folders(name, was.entries(), back);
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
                current = null;
                refresh(to);
            }
            return renamed(to, from);
        };
    }

    private Change created(Workspace w) {
        return () -> {
            save(w);
            synchronized (this) {
                current = null;
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
                current = null;
                refresh(null);
            }
            return created(w);
        };
    }

    /** After a change to {@code w}'s folders: show that workspace, and select and focus card {@code select}. */
    private void showChanged(Workspace w, int select) {
        if (current == null || !current.name().equals(w.name())) {
            current = null;
            refresh(w.name());
        }
        current = w;
        selected = select < w.entries().size() ? select : w.entries().size() - 1;
        rebuildRows();
        if (selected >= 0) gui.focus(cards.get(selected).box);
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
