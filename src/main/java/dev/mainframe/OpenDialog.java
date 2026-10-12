package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Standing;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.Icon;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * What Ctrl+O opens when there are workspaces: a grid of tiles. The first opens a folder as a tab in this window, one
 * per saved workspace follows, and the last makes a new workspace.
 *
 * <p>A workspace opens in a new MainFrame window of its own, a process of its own; Ctrl+Enter, or the tile's menu,
 * opens its tabs in this window instead. The tile's menu also edits it, in the settings window, which is the one
 * place workspaces are edited.
 *
 * <p>A dialog in vexelray-gui is a window that blocks the others while it is up, as {@code Modals}' are: asked for
 * each time it opens, with a tree built for it, and gone when it closes. Arrows move between tiles, Enter opens the
 * one with the keyboard, and Esc closes.
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

    private static final int COLUMNS = 3;
    private static final int WIDTH = 660;
    private static final int HEIGHT = 460;

    private final Gui main;
    private final WorkspaceStore store;
    private final Icons icons;
    private final Actions actions;
    private volatile GuiApp app;
    private volatile Icon mark;
    /** The dialog's window while it is up. */
    private final AtomicReference<NativeWindow> up = new AtomicReference<>();

    OpenDialog(Gui main, WorkspaceStore store, Icons icons, Actions actions) {
        this.main = main;
        this.store = store;
        this.icons = icons;
        this.actions = actions;
    }

    /** The application, once it exists, and the mark the dialog's title bar wears. */
    void attach(GuiApp app, Icon mark) {
        this.app = app;
        this.mark = mark;
    }

    /** Opens the dialog over the main window, unless it is up already. */
    void show() {
        GuiApp a = app;
        if (a == null || up.get() != null) return;
        Gui gui = new Gui();
        gui.theme(main.theme());
        TitleBar bar = new TitleBar(gui, WindowControls.NONE, "Open");
        bar.icon(a, mark);
        List<Node> tiles = new ArrayList<>();
        Runnable close = () -> {
            NativeWindow w = up.get();
            if (w != null) w.requestClose();
        };
        // Each choice closes the dialog first, so what it opens (a picker, the settings window) is not behind it.
        java.util.function.Function<Runnable, Runnable> closing = then -> () -> {
            close.run();
            gui.async(then);
        };

        tiles.add(tile(gui, icons.picture(gui, "folder", 32), "Open folder…", "A folder, as a new tab here",
                closing.apply(actions::openFolder)));
        List<Workspace> spaces = store.names().stream().map(n -> store.load(n).orElse(new Workspace(n, List.of())))
                .toList();
        for (Workspace w : spaces) {
            Node t = tile(gui, null, w.name(), describe(w), closing.apply(() -> actions.openWindow(w)));
            gui.claimUi(t, Shortcut.of(Key.ENTER, Modifier.CONTROL), ClaimScope.FOCUSED,
                    closing.apply(() -> actions.openHere(w)));
            gui.onContextMenu(t, menu -> menu
                    .item("Open in a new window", closing.apply(() -> actions.openWindow(w)))
                    .item("Open here", closing.apply(() -> actions.openHere(w)))
                    .item("Edit…", closing.apply(() -> actions.edit(w.name()))));
            tiles.add(t);
        }
        tiles.add(tile(gui, icons.icon(gui, "new", 32, gui.theme().color(Role.DIM)), "New workspace",
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
            gui.claimUi(t, Shortcut.of(Key.LEFT), ClaimScope.FOCUSED, () -> focus(gui, tiles, at - 1));
            gui.claimUi(t, Shortcut.of(Key.RIGHT), ClaimScope.FOCUSED, () -> focus(gui, tiles, at + 1));
            gui.claimUi(t, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> focus(gui, tiles, at - COLUMNS));
            gui.claimUi(t, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> focus(gui, tiles, at + COLUMNS));
        }
        Node hint = gui.text("Enter opens a workspace in a new window, Ctrl+Enter here. Right-click a workspace to "
                        + "edit it.")
                .width(Length.FILL).textSize(Length.rem(0.75f)).textColor(gui.theme().color(Role.FAINT))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        Node page = gui.column().width(Length.FILL).height(Length.grow(1)).padding(Length.rem(1)).gap(Length.rem(0.75f))
                .children(grid, hint);
        gui.root().children(gui.column().width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Role.CHROME)).children(bar.node(), page));
        gui.claimUi(gui.root(), Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, close);
        ContextMenu.presentOn(gui);
        gui.landmark("open.folder", tiles.get(0)).landmark("open.new", tiles.get(tiles.size() - 1));
        for (int i = 0; i < spaces.size(); i++) gui.landmark("open.workspace." + i, tiles.get(i + 1));

        a.requestWindow(WindowSpec.of(WindowConfig.of("Open", WIDTH, HEIGHT).decorations(Decorations.CLIENT)
                        .icon(mark), gui)
                .standing(Standing.SATELLITE)
                .onCreated(window -> {
                    up.set(window);
                    bar.controls(WindowControls.of(window));
                    a.modalWindow(window);
                    // The first workspace has the keyboard: opening one is what the dialog is mostly for.
                    gui.focus(tiles.get(spaces.isEmpty() ? 0 : 1));
                })
                .onClosed(() -> {
                    up.set(null);
                    a.modalWindow(null);
                    gui.async(gui::close);
                }));
    }

    /** "3 folders: api, web, docs", shortened when long. */
    static String describe(Workspace w) {
        List<Entry> e = w.entries();
        if (e.isEmpty()) return "No folders yet";
        String names = e.stream().limit(4).map(Entry::title).collect(Collectors.joining(", "));
        if (e.size() > 4) names += ", …";
        return e.size() + (e.size() == 1 ? " folder: " : " folders: ") + names;
    }

    private Node tile(Gui gui, Node icon, String title, String detail, Runnable open) {
        // The words straight in the tile: a text wraps at its parent's width only when that parent is the one with
        // the width, not a column in between.
        Node name = gui.text(title).width(Length.FILL).textSize(Length.rem(1)).textColor(gui.theme().color(Role.INK))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        Node more = gui.text(detail).width(Length.FILL).textSize(Length.rem(0.75f)).textColor(gui.theme().color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        Node t = gui.column().width(Length.grow(1)).height(Length.rem(8.5f)).padding(Length.rem(0.75f))
                .scroll(false, false).gap(Length.rem(0.375f)).corner(Length.rem(0.5f))
                .background(gui.theme().color(Role.WELL)).border(Length.dp(1), gui.theme().color(Role.NONE));
        if (icon != null) t.append(icon);
        t.append(name);
        t.append(more);
        gui.focusable(t, true);
        gui.onClick(t, open);
        gui.claimUi(t, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, open);
        gui.onState(t, s -> t.background(gui.theme().color(s == InteractionState.NORMAL ? Role.WELL : Role.RAISED)));
        gui.bus().subscribe(gui.focusEvents(), e -> {
            if (e.nodeId() == t.id()) t.border(Length.dp(1), gui.theme().color(e.gained() ? Role.ACCENT : Role.NONE));
        });
        return t;
    }

    private static void focus(Gui gui, List<Node> tiles, int index) {
        if (index >= 0 && index < tiles.size()) gui.focus(tiles.get(index));
    }
}
