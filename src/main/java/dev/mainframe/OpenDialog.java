package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What Ctrl+O opens when there are workspaces: a grid of tiles. The first opens a folder as a tab in this window, one
 * per saved workspace follows, and the last makes a new workspace.
 *
 * <p>A workspace opens in a new MainFrame window of its own, a process of its own; Ctrl+Enter, or the tile's menu,
 * opens its tabs in this window instead. The tile's menu also edits it, in the settings window, which is the one
 * place workspaces are edited. Arrows move between tiles, Enter opens the one with the keyboard, and Esc closes.
 *
 * <p>A named {@link Window}, a satellite of the main one: the framework builds its tree once and keeps it, so the
 * tiles are rebuilt each time it opens, from the workspaces on disk then. It does not block the main window while it
 * is up, as the hand-wired one did ({@code docs/framework-notes.md}, FN-4).
 */
final class OpenDialog {

    /** What a tile does, which is the window's business rather than the dialog's. */
    interface Actions {

        /** Pick a folder and open it as a tab here. */
        void openFolder();

        /** Open {@code w} in a new MainFrame window. */
        void openWindow(Workspace w);

        /** Open {@code w}'s folders as tabs here. */
        void openHere(Workspace w);

        /** Edit the workspace called {@code name}, in the settings window. */
        void edit(String name);

        /** Make a new workspace, in the settings window. */
        void create();
    }

    /** The window, as {@link Recipes} declares it. */
    static final NamedWindow DECLARED = NamedWindow.of("open", "Open", 660, 460).satellite();

    private static final int COLUMNS = 3;

    /** The folder in the colour of the suite's folder drawing: the atlas holds its shape, and the colour is the tint. */
    private static final Color FOLDER_INK = Color.rgb(0xe4b750);

    private final Window window;
    private final Gui gui;
    private final WorkspaceStore store;
    private final Actions actions;
    private final Node page;
    /** The tiles and the hint, replaced whole each time the window opens. Guarded by {@code this}. */
    private Node content;
    /** What the tiles on show subscribed to, closed when they are replaced. Guarded by {@code this}. */
    private final List<Subscription> subscriptions = new ArrayList<>();
    /** The tile that takes the keyboard when the window opens. Guarded by {@code this}. */
    private Node first;

    OpenDialog(Window window, WorkspaceStore store, Actions actions) {
        this.window = window;
        this.gui = window.gui();
        this.store = store;
        this.actions = actions;
        page = gui.column().width(Length.FILL).height(Length.grow(1)).padding(Length.rem(1)).gap(Length.rem(0.75f));
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.CHROME))
                .children(window.titleBar().node(), page);
        gui.claimUi(gui.root(), Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, window::close);
        ContextMenu.presentOn(gui);
        // The first workspace has the keyboard: opening one is what the dialog is mostly for.
        window.onOpened(() -> {
            Node f;
            synchronized (this) {
                f = first;
            }
            if (f != null) {
                gui.focus(f);
            }
        });
    }

    /** Opens the dialog over the main window, with a tile for each workspace there is now. */
    void show() {
        fill();
        window.show();
    }

    private synchronized void fill() {
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();

        // Each choice closes the dialog first, so what it opens (a picker, the settings window) is not behind it.
        Function<Runnable, Runnable> closing = then -> () -> {
            window.close();
            gui.async(then);
        };
        List<Node> tiles = new ArrayList<>();
        tiles.add(tile(IconView.of(gui, Icon.FOLDER).size(Length.dp(32)).tint(FOLDER_INK).node(), "Open folder…",
                "A folder, as a new tab here",
                closing.apply(actions::openFolder)));
        List<Workspace> spaces = store.names().stream()
                .map(n -> store.load(n).orElse(new Workspace(n, List.of()))).toList();
        for (Workspace w : spaces) {
            Node t = tile(null, w.name(), describe(w), closing.apply(() -> actions.openWindow(w)));
            gui.claimUi(t, Shortcut.of(Key.ENTER, Modifier.CONTROL), ClaimScope.FOCUSED,
                    closing.apply(() -> actions.openHere(w)));
            gui.onContextMenu(t, menu -> menu
                    .item("Open in a new window", closing.apply(() -> actions.openWindow(w)))
                    .item("Open here", closing.apply(() -> actions.openHere(w)))
                    .item("Edit…", closing.apply(() -> actions.edit(w.name()))));
            tiles.add(t);
        }
        tiles.add(tile(IconView.of(gui, Icon.NEW).size(Length.dp(32)).tint(Role.DIM).node(), "New workspace",
                "Folders that open together, each a tab", closing.apply(actions::create)));

        Node grid = gui.column().width(Length.FILL).height(Length.grow(1)).gap(Length.rem(0.75f)).scroll(false, true);
        for (int i = 0; i < tiles.size(); i += COLUMNS) {
            Node row = gui.row().width(Length.FILL).gap(Length.rem(0.75f));
            for (int j = i; j < i + COLUMNS; j++) {
                row.append(j < tiles.size() ? tiles.get(j) : gui.box().width(Length.grow(1)));
            }
            grid.append(row);
        }
        for (int i = 0; i < tiles.size(); i++) {
            Node t = tiles.get(i);
            int at = i;
            gui.claimUi(t, Shortcut.of(Key.LEFT), ClaimScope.FOCUSED, () -> focus(tiles, at - 1));
            gui.claimUi(t, Shortcut.of(Key.RIGHT), ClaimScope.FOCUSED, () -> focus(tiles, at + 1));
            gui.claimUi(t, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> focus(tiles, at - COLUMNS));
            gui.claimUi(t, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> focus(tiles, at + COLUMNS));
        }
        Node hint = gui.text("Enter opens a workspace in a new window, Ctrl+Enter here. Right-click a workspace to "
                        + "edit it.")
                .width(Length.FILL).font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        if (content != null) {
            content.remove();   // which also releases the old tiles' handlers
        }
        content = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.75f)).children(grid, hint);
        page.append(content);

        gui.landmark(Landmarks.OPEN_FOLDER, tiles.getFirst()).landmark(Landmarks.OPEN_NEW, tiles.getLast());
        for (int i = 0; i < spaces.size(); i++) {
            gui.landmark(Landmarks.OPEN_WORKSPACE + i, tiles.get(i + 1));
        }
        first = tiles.get(spaces.isEmpty() ? 0 : 1);
        if (window.isOpen()) {
            gui.focus(first);
        }
    }

    /** "3 folders: api, web, docs", shortened when long. */
    static String describe(Workspace w) {
        List<Entry> e = w.entries();
        if (e.isEmpty()) {
            return "No folders yet";
        }
        String names = e.stream().limit(4).map(Entry::title).collect(Collectors.joining(", "));
        if (e.size() > 4) {
            names += ", …";
        }
        return e.size() + (e.size() == 1 ? " folder: " : " folders: ") + names;
    }

    private Node tile(Node icon, String title, String detail, Runnable open) {
        // The words straight in the tile: a text wraps at its parent's width only when that parent is the one with
        // the width, not a column in between.
        Node name = gui.text(title).width(Length.FILL).font(Type.UI).textSize(Length.rem(1))
                .textColor(gui.theme().color(Role.INK)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        Node more = gui.text(detail).width(Length.FILL).font(Type.UI).textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        Node t = gui.column().width(Length.grow(1)).height(Length.rem(8.5f)).padding(Length.rem(0.75f))
                .scroll(false, false).gap(Length.rem(0.375f)).corner(Length.rem(0.5f))
                .background(gui.theme().color(Role.WELL)).border(Length.dp(1), gui.theme().color(Role.NONE));
        if (icon != null) {
            t.append(icon);
        }
        t.append(name);
        t.append(more);
        gui.focusable(t, true);
        gui.onClick(t, open);
        gui.claimUi(t, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, open);
        gui.onState(t, s -> t.background(gui.theme().color(s == InteractionState.NORMAL ? Role.WELL : Role.RAISED)));
        subscriptions.add(gui.bus().subscribe(gui.focusEvents(), e -> {
            if (e.nodeId() == t.id()) {
                t.border(Length.dp(1), gui.theme().color(e.gained() ? Role.ACCENT : Role.NONE));
            }
        }));
        return t;
    }

    private void focus(List<Node> tiles, int index) {
        if (index >= 0 && index < tiles.size()) {
            gui.focus(tiles.get(index));
        }
    }
}
