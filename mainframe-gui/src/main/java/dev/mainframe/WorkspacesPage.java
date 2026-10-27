package dev.mainframe;

import dev.mainframe.core.AppSettings;
import dev.mainframe.core.EnvScripts;
import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.Create;
import dev.mainframe.core.Messages.Created;
import dev.mainframe.core.Messages.Delete;
import dev.mainframe.core.Messages.Deleted;
import dev.mainframe.core.Messages.Load;
import dev.mainframe.core.Messages.Loaded;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Renamed;
import dev.mainframe.core.Messages.Restore;
import dev.mainframe.core.Messages.Restored;
import dev.mainframe.core.Messages.Save;
import dev.mainframe.core.Messages.Saved;
import dev.mainframe.core.Messages.Script;
import dev.mainframe.core.Workspace;
import dev.mainframe.core.Workspace.Entry;
import dev.mainframe.core.WorkspaceStore;
import dev.vexelray.framework.api.Publishes;
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
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Workspaces tab: the saved workspaces down the left, and the one selected on the right, with its name and its
 * folders, one tab each, in order.
 *
 * <p>A folder is a card on a {@link Board}: the tab's label, a field, over the folder's path. A card is selected by
 * clicking it or by focusing its label; it is dragged to reorder (or moved with Alt+Up and Alt+Down), and removed from
 * its right-click menu or with Backspace or Delete while it, rather than its label, has the keyboard. Its folder is
 * changed from the same menu or by double-clicking it anywhere but the label. A label is never left empty: leaving
 * it blank gives it back the folder's own name.
 *
 * <p>The board is the drag: it picks a card up, carries it, opens the gap where it would land and puts it down, and
 * a press on the label is the label's, for selecting text. This page says what a card looks like ({@link #face}),
 * what putting one somewhere means ({@link #place}) and what the folders now are ({@link #display}) — which every
 * change ends in, undo included, so they all move alike.
 *
 * <h2>The files are the jobs'</h2>
 *
 * <p>This page reads and writes nothing itself. It asks the workspace jobs in {@code -core} ({@code Messages}: list,
 * load, save, rename, delete, restore, create) and is told what they did by the {@code SettingsView}, on the
 * settings window's lane, which calls the methods under <i>What the jobs did</i>. A workspace crosses as the text of
 * its file. The folders of a workspace on show are this page's to change: a change shows at once and is saved behind
 * it, one write in flight per workspace and the latest text after it ({@link #save}), since jobs keep no order. A
 * change of the set of workspaces (New, a rename, a delete) shows once it has landed, and only then is it a step in
 * the history: a rename the store refuses is said in the name field, and leaves no step to undo.
 *
 * <p>Like the rest of the settings, every change is saved the moment it is made, and every change but a run of
 * typing is a step in the settings window's history: Ctrl+Z and Ctrl+Y undo and redo moving, adding, removing,
 * relabelling and repointing folders, and creating, renaming and deleting workspaces. Inside a field they undo its
 * typing instead, and leaving a label it has changed records the relabel as one step. Deleting puts the workspace and
 * its scripts aside under a name chosen here, so its undo is a restore of that name.
 *
 * <p>Edit environment… opens the workspace's environment script in VexelRay IDE, and a card's menu has the same for
 * its tab's own script ({@link EnvScripts}). A script is the store's to keep consistent: renaming moves it, deleting
 * puts it aside where undoing finds it, and removing a card leaves its script, which undoing the removal finds again
 * by the row's key. Rows are given keys as a workspace is loaded, before any step that could hold them is recorded,
 * and a card added here is given its key as it is added.
 */
@Publishes({Messages.LOAD, Messages.SAVE, Messages.RENAME, Messages.DELETE, Messages.RESTORE, Messages.CREATE,
        Messages.MAKE_SCRIPT})
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

    /** A folder's card, as built for the board: the node it carries, the label on it, and the path under that. */
    private record Face(Node box, TextField label, Node path) { }

    private final Gui gui;
    private final WorkspaceStore store;
    /** Whose shell decides which language a script that is edited is in. */
    private final AppSettings settings;
    private final Dialogs dialogs;
    private final Atchung bus;
    /** Asks the settings window to list the workspaces again; it hands the names back through {@link #names}. */
    private final Runnable relist;
    /** The settings window's undo history, which the framework binds; this page records its steps there. */
    private final History history;
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

    // Guarded by this.
    /** The workspaces there are, as last listed. */
    private List<String> names = List.of();
    /** The workspace asked for and not yet loaded: a load for any other has been overtaken and is dropped. */
    private String wanted;
    /** What to select when the names next arrive; null for whatever was on show, or the first. */
    private String pendingSelect;
    /** Each workspace's rows as this page last read or wrote them: what undoing a change to one not on show puts back. */
    private final Map<String, List<Entry>> known = new HashMap<>();
    /** The workspaces with a write in flight, and the latest text to write after it. */
    private final Set<String> writing = new HashSet<>();
    private final Map<String, String> queued = new HashMap<>();
    /** Whether New is waiting for its workspace, and then to give its name the keyboard once it is on show. */
    private boolean creating;
    private boolean editNameOnShow;

    WorkspacesPage(Gui gui, WorkspaceStore store, AppSettings settings, Dialogs dialogs, Motion motion, Atchung bus,
                   Runnable relist) {
        this.gui = gui;
        this.dialogs = dialogs;
        this.store = store;
        this.settings = settings;
        this.bus = bus;
        this.relist = relist;
        // The window's, bound by the framework to Ctrl+Z and Ctrl+Y and to every drop: this page's moves, adds and
        // renames undo with everything else changed in the settings window, by the same keys. A tree the framework
        // did not build has none, and then this page brings one.
        History bound = gui.history();
        if (bound == null) {
            bound = new History();
            gui.history(bound).dropHistory(bound);
        }
        this.history = bound;
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

        // Inline editors keep their typing to themselves: committing the name is the step the page records.
        nameField = new TextField(gui).history(new History());
        nameField.node().width(Length.FILL);
        nameField.onSubmit(text -> commitName());
        nameProblem = note("").textColor(gui.theme().color(Role.DANGER)).visible(false);
        board = new Board<Folder>(gui, this::face).motion(motion.arrival);
        deck = board.column().reorderable(this::place);
        // Room either side for a carried card's shadow: the deck scrolls, and a scroller clips to its own box.
        deck.node().width(Length.FILL).height(Length.grow(1)).padding(Length.ZERO, Length.dp(8));
        noFolders = note("No folders yet. Add folder picks one; each becomes a tab.").visible(false);
        Button addFolder = new Button(gui, "Add folder…").onPress(this::pickFolder);
        Button editEnv = new Button(gui, "Edit environment…").onPress(this::editWorkspaceEnv);
        launchLine = note("");
        editor = gui.column().width(Length.grow(1)).height(Length.FILL).gap(Length.rem(0.5f)).children(
                label("Name"), nameField.node(), nameProblem,
                label("Folders, one tab each. Drag to reorder; right-click to change or remove."), noFolders, deck.node(),
                gui.row().gap(Length.rem(0.5f)).children(addFolder.node(), editEnv.node()), launchLine);
        empty = note("No workspaces yet. New makes one: a list of folders that MainFrame opens as tabs, each in its "
                + "own folder.").width(Length.grow(1));

        root = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.rem(1)).gap(Length.rem(1))
                .children(left, editor, empty);
        gui.bus().subscribe(gui.focusEvents(), e -> focusChanged(e.nodeId(), e.gained()));
        // Names for ottermate and agents: "click workspaces.new".
        gui.landmark("workspaces.list", list.node()).landmark("workspaces.new", add.node())
                .landmark("workspaces.delete", delete.node()).landmark("workspaces.name", nameField.node())
                .landmark("workspaces.add-folder", addFolder.node()).landmark("workspaces.folders", deck.node())
                .landmark("workspaces.edit-env", editEnv.node());
        showNone();
    }

    Node node() {
        return root;
    }

    /** The history this page records into — its window's: every change it makes, as one step each. */
    History history() {
        return history;
    }

    // -- the list ---------------------------------------------------------------------------------------------

    /** Shows the workspace called {@code name}, as clicking it in the list would, once the list is read again. */
    synchronized void select(String name) {
        refresh(name);
    }

    /** Reads the workspaces again, then selects {@code select}, or else the one on show, or the first. */
    private synchronized void refresh(String select) {
        pendingSelect = select;
        relist.run();
    }

    /** The workspaces there are, listed: shown in the list, and one of them selected and shown. */
    synchronized void names(List<String> listed) {
        names = List.copyOf(listed);
        list.items(names);
        String select = pendingSelect;
        pendingSelect = null;
        String pick = select != null && names.contains(select) ? select
                : shown != null && names.contains(shown) ? shown
                : names.isEmpty() ? null : names.getFirst();
        if (pick == null) {
            showNone();
            return;
        }
        list.selection().set(List.of(pick));
        list.reveal(pick);
        show(pick);
    }

    private synchronized void showNone() {
        shown = null;
        wanted = null;
        folders = List.of();
        selected = null;
        deck.items(List.of());
        editor.visible(false);
        empty.visible(true);
        delete.enabled(false);
        list.selection().clear();
    }

    /** Asks for {@code name}'s rows, keyed; {@link #loaded} shows them. */
    private synchronized void show(String name) {
        if (name.equals(shown) || name.equals(wanted)) return;
        wanted = name;
        bus.publish(Messages.LOAD_TOPIC, new Load(name));
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
            if (face != null) showPath(face.path, f.entry.path());
        }
        noFolders.visible(folders.isEmpty());
        deck.items(folders);
        for (int i = 0; i < folders.size(); i++) {
            Node card = board.cardOf(folders.get(i));
            if (card != null) gui.landmark("workspaces.folder." + i, card);
        }
        paintSelection();
    }

    /** A new workspace, as the New button makes one: shown, its name selected to type over, once it is made. */
    synchronized void create() {
        creating = true;
        bus.publish(Messages.CREATE_TOPIC, new Create());
    }

    /** Gives the name field the keyboard with the whole name selected, to type over. */
    void editName() {
        gui.focus(nameField.node());
        nameField.select(0, nameField.text().length());
    }

    private void confirmDelete() {
        String name = shown;
        if (name == null) return;
        dialogs.confirm("Delete " + name + "?",
                "The workspace and its list of folders are deleted, and its environment scripts put aside. The "
                        + "folders themselves are not touched. Ctrl+Z brings it all back.",
                "Delete", () -> bus.publish(Messages.DELETE_TOPIC,
                        new Delete(name, WorkspaceStore.stashName(name), true)), () -> { });
    }

    // -- the name ---------------------------------------------------------------------------------------------

    /**
     * Enter or leaving the field renames the workspace; a name that cannot be one says why and goes back. One that
     * is taken by another workspace is refused by the store, and said the same way when its answer comes.
     */
    private void commitName() {
        String from;
        String to;
        synchronized (this) {
            from = shown;
            if (from == null) return;
            to = nameField.text().strip();
            if (to.equals(from)) {
                nameProblem.visible(false);
                return;
            }
            String problem = WorkspaceStore.problem(to);
            if (problem == null && !to.equalsIgnoreCase(from) && names.stream().anyMatch(to::equalsIgnoreCase)) {
                problem = "There is already a workspace called " + to + ".";
            }
            if (problem != null) {
                nameProblem.text(problem).visible(true);
                nameField.text(from);
                return;
            }
        }
        bus.publish(Messages.RENAME_TOPIC, new Rename(from, to, true));
    }

    // -- the cards --------------------------------------------------------------------------------------------

    /**
     * What a folder's card looks like, built by the board once per folder while it is on the board. The board makes
     * it focusable, lifts it, carries it and moves it with Alt+Up and Alt+Down; everything else on it is the page's,
     * and finds where its folder is when it is used rather than when it was built, since the card moves.
     */
    private Node face(Folder folder) {
        Entry entry = folder.entry;
        // Its typing is its own; leaving it records the relabel as one step in the page's history.
        TextField label = new TextField(gui, entry.title()).history(new History());
        label.node().width(Length.FILL);
        label.onChange(text -> relabel(folder, text));
        label.onSubmit(text -> focusCard(folder));
        Node path = gui.text("").width(Length.FILL).height(Length.rem(1.25f)).textSize(Length.rem(0.75f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        showPath(path, entry.path());
        Node grip = IconView.of(gui, Icon.GRIP).size(Length.dp(16)).tint(Role.FAINT).node();
        Node box = gui.row().width(Length.FILL).padding(Length.rem(0.5f)).gap(Length.rem(0.5f))
                .alignItems(AlignItems.CENTER).corner(Length.rem(0.4f))
                .children(grip, gui.column().width(Length.grow(1)).gap(Length.rem(0.25f))
                        .children(label.node(), path));
        Face built = new Face(box, label, path);
        Face old = faces.put(folder, built);
        if (old != null) old.label.close();   // the board built it again: the folder came back after leaving
        paint(built, folder == selected);
        gui.onContextMenu(box, menu -> {
            List<Folder> now = folders;
            int at = now.indexOf(folder);
            menu.item("Change folder…", () -> pickNewPath(folder))
                    .item("Edit environment…", () -> editTabEnv(folder))
                    .item("Move up", at > 0, () -> move(folder, -1))
                    .item("Move down", at >= 0 && at < now.size() - 1, () -> move(folder, 1))
                    .item("Remove", () -> remove(folder));
        });
        // Anywhere on the card but the label, where a double-click selects a word: double-clicks bubble to the
        // nearest handler, so the label has one that does nothing.
        gui.onDoubleClick(box, () -> pickNewPath(folder));
        gui.onDoubleClick(label.node(), () -> { });
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
        save(name, entriesOf(folders));
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
                save(name, entriesOf(order));
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
        Folder added = new Folder(new Entry(title, p.toString()).withEnv(Workspace.freshKey(title, keysOf(folders))));
        List<Folder> next = new ArrayList<>(folders);
        next.add(added);
        history.perform(order(name, next, added));
    }

    /**
     * Points {@code folder} at {@code dir}, as one step in the history. A label that was the old folder's own name
     * becomes the new one's; one typed by hand stays.
     */
    private void repath(Folder folder, Path dir) {
        String name = shown;
        List<Folder> now = folders;
        int at = now.indexOf(folder);
        if (name == null || at < 0) return;
        String p = dir.toAbsolutePath().normalize().toString();
        Entry e = folder.entry;
        if (p.equals(e.path())) return;
        boolean ownName = e.name().isBlank() || e.name().strip().equals(e.withName("").title());
        Entry moved = e.withPath(p);
        if (ownName) moved = moved.withName(moved.withName("").title());
        List<Entry> entries = new ArrayList<>(entriesOf(now));
        entries.set(at, moved);
        history.perform(folders(name, now, entries, folder));
    }

    /** Asks the OS for the folder {@code folder} should be, starting beside the one it is. */
    private void pickNewPath(Folder folder) {
        if (shown == null || !folders.contains(folder)) return;
        dialogs.openFolder(parentOf(folder.entry.path()), dir -> repath(folder, dir), () -> { });
    }

    /** Asks the OS for a folder, starting beside the last one added. */
    private void pickFolder() {
        List<Folder> now = folders;
        if (shown == null) return;
        Path start = now.isEmpty() ? AppSettings.home() : parentOf(now.get(now.size() - 1).entry.path());
        dialogs.openFolder(start, this::addFolder, () -> { });
    }

    // -- the environment -------------------------------------------------------------------------------------

    /**
     * The script every tab of the workspace on show sources, in the language of the shell tabs start, made if need
     * be, in VexelRay IDE. The frame hears what came of it and says what went wrong.
     */
    private void editWorkspaceEnv() {
        String name = shown;
        if (name == null) return;
        bus.publish(Messages.MAKE_SCRIPT_TOPIC, new Script(name, "", "", "", EnvScripts.dialect(settings.shellCommand())));
    }

    /**
     * {@code folder}'s own script, made if need be, in VexelRay IDE. Its row has a key from when the workspace was
     * loaded; one that lost it to a hand edit since gets one now, saved without a step, as loading it would have. The
     * row goes with the request as it is on show, so the script is made for it whether or not its save has landed.
     */
    private void editTabEnv(Folder folder) {
        String name;
        Entry e;
        synchronized (this) {
            name = shown;
            if (name == null || !folders.contains(folder)) return;
            if (!WorkspaceStore.validKey(folder.entry.env())) {
                folder.entry = folder.entry.withEnv(Workspace.freshKey(folder.entry.title(), keysOf(folders)));
                save(name, entriesOf(folders));
            }
            e = folder.entry;
        }
        bus.publish(Messages.MAKE_SCRIPT_TOPIC, new Script(name, e.env(), e.title(), e.path(),
                EnvScripts.dialect(settings.shellCommand())));
    }

    // -- the changes, each undone by the change it returns ----------------------------------------------------

    /** Puts {@code name}'s folders in {@code order}, each as it is now. */
    private Change order(String name, List<Folder> order, Folder select) {
        return folders(name, order, entriesOf(order), select);
    }

    /**
     * Puts {@code name}'s folders in {@code order}, with {@code entries} as theirs, shows them and selects and
     * focuses {@code select}; undone by putting back the order and the entries there were. Shown at once and saved
     * behind it.
     */
    private Change folders(String name, List<Folder> order, List<Entry> entries, Folder select) {
        List<Folder> to = List.copyOf(order);
        List<Entry> with = List.copyOf(entries);
        return () -> {
            synchronized (this) {
                List<Folder> backOrder;
                Folder back;
                if (name.equals(shown)) {
                    backOrder = folders;
                    back = selected != null ? selected : select;
                } else {
                    backOrder = new ArrayList<>();
                    for (Entry e : known.getOrDefault(name, List.of())) {
                        backOrder.add(new Folder(e));
                    }
                    back = select;
                }
                List<Entry> backEntries = entriesOf(backOrder);
                for (int i = 0; i < to.size(); i++) to.get(i).entry = with.get(i);
                save(name, with);
                wanted = null;
                display(name, to, select);
                Node card = select == null ? null : board.cardOf(select);
                if (card != null) gui.focus(card);
                return folders(name, backOrder, backEntries, back);
            }
        };
    }

    /** Renames {@code from} to {@code to}, undone by renaming it back. Shown once it has landed ({@link #renamed}). */
    private Change renamed(String from, String to) {
        return () -> {
            bus.publish(Messages.RENAME_TOPIC, new Rename(from, to, false));
            return renamed(to, from);
        };
    }

    /** Puts {@code name} aside, undone by bringing it back from where it went. */
    private Change deleted(String name) {
        return () -> {
            String stash = WorkspaceStore.stashName(name);
            bus.publish(Messages.DELETE_TOPIC, new Delete(name, stash, false));
            return restored(name, stash);
        };
    }

    /** Brings {@code name} back from {@code stash}, undone by putting it aside again. */
    private Change restored(String name, String stash) {
        return () -> {
            bus.publish(Messages.RESTORE_TOPIC, new Restore(name, stash));
            return deleted(name);
        };
    }

    /**
     * Writes {@code name}'s rows: at once if no write of it is in flight, else after the one that is, and only the
     * latest. Jobs keep no order, so two writes of one file in flight could land the older last.
     */
    private synchronized void save(String name, List<Entry> entries) {
        known.put(name, List.copyOf(entries));
        String text = Workspace.format(new Workspace(name, entries));
        if (writing.contains(name)) {
            queued.put(name, text);
        } else {
            writing.add(name);
            bus.publish(Messages.SAVE_TOPIC, new Save(name, text));
        }
    }

    // -- what the jobs did, on the settings window's lane -----------------------------------------------------

    /** A workspace's rows, keyed: shown, if it is still the one asked for. */
    synchronized void loaded(Loaded m) {
        if (!m.name().equals(wanted)) return;
        wanted = null;
        List<Entry> entries = Workspace.parse(m.name(), m.text()).entries();
        known.put(m.name(), entries);
        List<Folder> loaded = new ArrayList<>();
        for (Entry e : entries) loaded.add(new Folder(e));
        display(m.name(), loaded, null);
        if (editNameOnShow) {
            editNameOnShow = false;
            editName();
        }
    }

    /** A write landed, or did not: the next one waiting for it goes, and one that failed is said. */
    synchronized void saved(Saved m) {
        writing.remove(m.name());
        String next = queued.remove(m.name());
        if (next != null) {
            writing.add(m.name());
            bus.publish(Messages.SAVE_TOPIC, new Save(m.name(), next));
        }
        if (!m.problem().isBlank()) {
            dialogs.info("Could not save " + m.name(), m.problem());
        }
    }

    /** A rename landed: shown under its new name, and a step to undo if it was typed. Refused: said in the field. */
    synchronized void renamed(Renamed m) {
        if (!m.problem().isBlank()) {
            if (m.from().equals(shown)) {
                nameProblem.text("Could not rename " + m.from() + ": " + m.problem()).visible(true);
                nameField.text(m.from());
            } else {
                dialogs.info("Could not rename " + m.from(), m.problem());
            }
            return;
        }
        List<Entry> rows = known.remove(m.from());
        if (rows != null) known.put(m.to(), rows);
        if (m.record()) history.record(renamed(m.to(), m.from()));
        shown = null;
        refresh(m.to());
    }

    /** A delete landed: a step to undo if it was asked for here, and the list without it. */
    synchronized void deleted(Deleted m) {
        if (!m.problem().isBlank()) {
            dialogs.info("Could not delete " + m.name(), m.problem());
            return;
        }
        if (m.record()) history.record(restored(m.name(), m.stash()));
        known.remove(m.name());
        shown = null;
        refresh(null);
    }

    /** A workspace came back from where a delete put it: shown. */
    synchronized void restored(Restored m) {
        if (!m.problem().isBlank()) {
            dialogs.info("Could not bring back " + m.name(), m.problem());
            return;
        }
        shown = null;
        refresh(m.name());
    }

    /** New made a workspace: shown, its name ready to type over, and a step to undo. */
    synchronized void created(Created m) {
        if (!creating || !m.problem().isBlank()) {
            creating = false;
            return;
        }
        creating = false;
        history.record(deleted(m.name()));
        editNameOnShow = true;
        shown = null;
        refresh(m.name());
    }

    // ---------------------------------------------------------------------------------------------------------

    private static List<Entry> entriesOf(List<Folder> folders) {
        List<Entry> entries = new ArrayList<>(folders.size());
        for (Folder f : folders) entries.add(f.entry);
        return entries;
    }

    /** The keys the rows on show have, lower case: what a new one must not be. */
    private static Set<String> keysOf(List<Folder> folders) {
        Set<String> taken = new HashSet<>();
        for (Folder f : folders) taken.add(f.entry.env().toLowerCase(Locale.ROOT));
        return taken;
    }

    /**
     * A card's path line: the folder, or that it is not there. The one look at the disk this page takes itself: a
     * stat per card as it is shown, which a message per row would only slow down (docs/TODO.md).
     */
    private void showPath(Node node, String path) {
        boolean there = isDirectory(path);
        node.text(there ? path : path + "  (not found)")
                .textColor(gui.theme().color(there ? Role.DIM : Role.DANGER));
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
            return AppSettings.home();
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
