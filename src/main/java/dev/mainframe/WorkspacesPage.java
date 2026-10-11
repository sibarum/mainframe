package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.SelectionModel;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Workspaces tab: the saved workspaces down the left, and the one selected on the right, with its name and its
 * folders, one tab each, in order.
 *
 * <p>Like the rest of the settings, every change is saved the moment it is made. A workspace is opened by starting
 * MainFrame with {@code --workspace=<name>}.
 */
final class WorkspacesPage {

    private final Gui gui;
    private final WorkspaceStore store;
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
    private final List<TextField> rowFields = new ArrayList<>();

    private volatile GuiApp app;
    private volatile Supplier<AppWindow> window = () -> null;
    /** The workspace on the right, as last saved; null when there is none. */
    private Workspace current;

    WorkspacesPage(Gui gui, WorkspaceStore store) {
        this.gui = gui;
        this.store = store;

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
        gui.bus().subscribe(gui.focusEvents(), e -> {
            if (!e.gained() && e.nodeId() == nameField.node().id()) commitName();
        });
        nameProblem = note("").textColor(gui.theme().color(Role.DANGER)).visible(false);
        folders = gui.column().width(Length.FILL).height(Length.grow(1)).gap(Length.rem(0.25f)).scroll(false, true);
        Button addFolder = new Button(gui, "Add folder…").onPress(this::pickFolder);
        launchLine = note("");
        editor = gui.column().width(Length.grow(1)).height(Length.FILL).gap(Length.rem(0.5f)).children(
                label("Name"), nameField.node(), nameProblem,
                label("Folders, one tab each; a blank name is the folder's own"), folders,
                gui.row().children(addFolder.node()), launchLine);
        empty = note("No workspaces yet. New makes one: a list of folders that MainFrame opens as tabs, each in its "
                + "own folder.").width(Length.grow(1));

        root = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.rem(1)).gap(Length.rem(1))
                .children(left, editor, empty);
        // Names for ottermate and agents: "click workspaces.new".
        gui.landmark("workspaces.list", list.node()).landmark("workspaces.new", add.node())
                .landmark("workspaces.delete", delete.node()).landmark("workspaces.name", nameField.node())
                .landmark("workspaces.add-folder", addFolder.node());
        refresh(null);
    }

    Node node() {
        return root;
    }

    /** The application and the settings window, once they exist: the folder picker needs both. */
    void attach(GuiApp app, Supplier<AppWindow> window) {
        this.app = app;
        this.window = window;
    }

    // -- the list ---------------------------------------------------------------------------------------------

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
        rebuildRows();
    }

    private synchronized void create() {
        String name = store.freshName();
        if (!save(new Workspace(name, List.of()))) return;
        current = null;
        refresh(name);
        gui.focus(nameField.node());
        nameField.select(0, name.length());
    }

    private void confirmDelete() {
        Workspace w = current;
        if (w == null) return;
        Modals.confirm("Delete " + w.name() + "?",
                "The workspace and its list of folders are deleted. The folders themselves are not touched.",
                "Delete", () -> delete(w.name()), () -> { });
    }

    private synchronized void delete(String name) {
        try {
            store.delete(name);
        } catch (IOException e) {
            Modals.info("Could not delete " + name, e.getMessage());
            return;
        }
        current = null;
        refresh(null);
    }

    // -- the name ---------------------------------------------------------------------------------------------

    /** Enter or leaving the field renames the file; a name that cannot be one says why and goes back. */
    private synchronized void commitName() {
        Workspace w = current;
        if (w == null) return;
        String to = nameField.text().strip();
        if (to.equals(w.name())) {
            nameProblem.visible(false);
            return;
        }
        String problem = WorkspaceStore.problem(to);
        if (problem == null && !to.equalsIgnoreCase(w.name()) && store.exists(to)) {
            problem = "There is already a workspace called " + to + ".";
        }
        if (problem == null) {
            try {
                store.rename(w.name(), to);
            } catch (IOException e) {
                problem = "Could not rename it: " + e.getMessage();
            }
        }
        if (problem != null) {
            nameProblem.text(problem).visible(true);
            nameField.text(w.name());
            return;
        }
        current = null;
        refresh(to);
    }

    // -- the folders ------------------------------------------------------------------------------------------

    /**
     * One row per folder: its tab name, its path, and buttons to move it up, down or out. Rows are rebuilt only when
     * the list changes shape, never while a name is being typed, which would take the field away mid-word.
     */
    private void rebuildRows() {
        rows.forEach(Node::remove);
        rows.clear();
        rowFields.forEach(TextField::close);
        rowFields.clear();
        List<Entry> entries = current.entries();
        if (entries.isEmpty()) {
            Node none = note("No folders yet. Add folder picks one; each becomes a tab.");
            rows.add(none);
            folders.append(none);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Node row = row(i, entries.get(i), entries.size());
            rows.add(row);
            folders.append(row);
        }
    }

    private Node row(int index, Entry entry, int count) {
        TextField name = new TextField(gui, entry.name());
        name.node().width(Length.rem(8));
        name.onChange(text -> rename(index, text));
        rowFields.add(name);
        boolean there = isDirectory(entry.path());
        Node path = gui.text(there ? entry.path() : entry.path() + "  (not found)").width(Length.grow(1))
                .height(Length.rem(2.5f)).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(there ? Role.DIM : Role.DANGER))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        Button up = small("↑", () -> move(index, -1)).enabled(index > 0);
        Button down = small("↓", () -> move(index, 1)).enabled(index < count - 1);
        Button out = small("×", () -> remove(index));
        return gui.row().width(Length.FILL).gap(Length.rem(0.25f)).alignItems(AlignItems.CENTER)
                .children(name.node(), path, up.node(), down.node(), out.node());
    }

    /** The tab's name: saved as typed, without rebuilding anything. Blank is the folder's own name. */
    private synchronized void rename(int index, String text) {
        Workspace w = current;
        if (w == null || index >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        if (entries.get(index).name().equals(text)) return;
        entries.set(index, entries.get(index).withName(text));
        if (save(w.withEntries(entries))) current = w.withEntries(entries);
    }

    private synchronized void move(int index, int by) {
        Workspace w = current;
        int to = index + by;
        if (w == null || to < 0 || to >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.add(to, entries.remove(index));
        change(w.withEntries(entries));
    }

    private synchronized void remove(int index) {
        Workspace w = current;
        if (w == null || index >= w.entries().size()) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.remove(index);
        change(w.withEntries(entries));
    }

    private synchronized void addFolder(Path dir) {
        Workspace w = current;
        if (w == null) return;
        List<Entry> entries = new ArrayList<>(w.entries());
        entries.add(new Entry("", dir.toAbsolutePath().normalize().toString()));
        change(w.withEntries(entries));
    }

    /** Asks the OS for a folder, starting beside the last one added, parented to the settings window. */
    private void pickFolder() {
        GuiApp a = app;
        Workspace w = current;
        if (a == null || w == null) return;
        AppWindow win = window.get();
        long parent = win == null || win.window() == null ? a.windowHandle() : win.window().osHandle();
        Path start = w.entries().isEmpty() ? Path.of(System.getProperty("user.home"))
                : parentOf(w.entries().get(w.entries().size() - 1).path());
        FileDialog.pickFolderAsync(a::post, parent, start)
                .thenAccept(picked -> picked.ifPresent(this::addFolder))
                .exceptionally(e -> {
                    Modals.info("Could not open the folder picker", String.valueOf(e.getMessage()));
                    return null;
                });
    }

    private void change(Workspace w) {
        if (!save(w)) return;
        current = w;
        rebuildRows();
    }

    private boolean save(Workspace w) {
        try {
            store.save(w);
            return true;
        } catch (IOException e) {
            Modals.info("Could not save " + w.name(), e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private Button small(String glyph, Runnable action) {
        return new Button(gui, glyph).kind(Button.Kind.GHOST).onPress(action);
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
