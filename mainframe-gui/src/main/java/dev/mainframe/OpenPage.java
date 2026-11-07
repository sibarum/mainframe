package dev.mainframe;

import dev.mainframe.core.Workspace;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.MenuAction;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

/**
 * The Open dialog's page (Ctrl+O): <i>Open folder…</i> first, then a row per workspace, its name over its folders.
 * Enter or a double-click opens the row; Ctrl+Enter, or the row's menu, opens a workspace's tabs in this window
 * instead of a new one (docs/workspaces.md, slice 2).
 *
 * <p>0.1 drew tiles; this is a list, the widget library's {@code ListView}, which gives the arrows, Enter, a
 * double-click and a row menu with nothing written for them. It holds no state of its own: the rows are the frame's
 * {@code Tiles}, put in as they come.
 *
 * <p>Built in the {@code TREE} phase into a window declared by name ({@code Recipes.open}), a satellite of the main
 * one, as Settings is.
 */
final class OpenPage {

    /** The window's name, in a {@code WindowCommand} and to {@code ottermate} ({@code open/...}). */
    static final String KEY = "open";

    static final int W = 520;
    static final int H = 420;

    /** What a script may refer to in this window, as {@code open/<landmark>}. */
    static final String LIST = "open.list";

    /** A row's menu: its tabs in this window. */
    static final MenuAction HERE = MenuAction.of("open-here", "Open in this window");

    /**
     * One row: a workspace by its name, or the folder picker.
     *
     * @param name   the workspace's name; blank for <i>Open folder…</i>
     * @param detail what is under the name
     */
    record Row(String name, String detail) {

        static final Row FOLDER = new Row("", "A tab for a folder you pick, in this window");

        boolean folder() {
            return name.isEmpty();
        }

        String title() {
            return folder() ? "Open folder…" : name;
        }
    }

    /** The dialog's keys that only its view hears: Esc puts it away, Ctrl+Enter opens here. */
    record Key(boolean here) {
        static final Topic<Key> TOPIC = Topic.edge(Key.class);
    }

    private final Gui gui;
    private final ListView<Row> list;

    OpenPage(Window window) {
        this.gui = window.gui();
        list = new ListView<Row>(gui, 3.25f, (nodes, r) -> nodes.column().children(
                nodes.text(r.title()).textColor(Role.INK),
                nodes.text(r.detail()).textColor(Role.DIM).textSize(Length.rem(0.8125f))))
                .name(LIST).action(HERE);
        list.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(LIST, list.node());
        Node hint = gui.text("Enter opens a workspace in a new window; Ctrl+Enter opens its tabs in this one.")
                .textSize(Length.rem(0.8125f)).textColor(gui.theme().color(Role.DIM));
        Node body = gui.column().gap(Length.rem(0.75f)).padding(Length.rem(1f)).width(Length.FILL)
                .height(Length.grow(1f)).children(list.node(), hint);
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), body);
    }

    /** The rows: the folder picker, then each workspace, its name over its folders. */
    void show(List<Workspace> workspaces) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.FOLDER);
        for (Workspace w : workspaces) {
            rows.add(new Row(w.name(), w.describe()));
        }
        list.items(rows);
        gui.focus(list.node());
    }

    Node listNode() {
        return list.node();
    }

    String listAddress() {
        return list.address();
    }

    /** This window's tree: for a test. */
    Gui gui() {
        return gui;
    }
}
