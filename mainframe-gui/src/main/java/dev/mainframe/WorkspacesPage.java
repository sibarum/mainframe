package dev.mainframe;

import dev.mainframe.core.Messages.EditScript;
import dev.mainframe.core.Messages.RemoveRow;
import dev.mainframe.core.Workspace;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.Ellipsis;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Board;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Deck;
import dev.vexelray.gui.widget.Framing;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Workspaces window's page (docs/workspaces-page.md): the workspaces down the left, and on the right the one
 * selected, its name as a heading that is a field, and its folders as cards on a {@link Board}'s one deck, dragged to
 * reorder. Below, the last change, said once.
 *
 * <p>It holds what it shows and nothing it decides: the rows and cards are the {@code Workspaces} component's
 * {@code Showing}, put in as it comes. A card is its row's script key, which stays the row's through renaming,
 * reordering and a new folder, so the board keeps its node and moves it rather than fading one out and another in.
 *
 * <p>Built in the {@code TREE} phase into a window declared by name ({@code Recipes.workspaces}), a satellite of the
 * main one, as Settings and Open are.
 */
final class WorkspacesPage {

    /** The window's name: {@code Messages.WORKSPACES_WINDOW}, which the component opens it by. */
    static final String KEY = dev.mainframe.core.Messages.WORKSPACES_WINDOW;

    static final int W = 820;
    static final int H = 560;

    /** What a script may refer to in this window, as {@code workspaces/<landmark>}. */
    static final String LIST = "workspaces.list";
    static final String NEW = "workspaces.new";
    static final String NAME = "workspaces.name";
    static final String PROBLEM = "workspaces.problem";
    static final String CARDS = "workspaces.cards";
    static final String ADD = "workspaces.add";
    static final String ENV = "workspaces.env";
    static final String TAB_NAME = "workspaces.tab-name";
    static final String SAID = "workspaces.said";

    /** A workspace row's menu. */
    static final MenuAction OPEN = MenuAction.of("ws-open", "Open");
    static final MenuAction HERE = MenuAction.of("ws-here", "Open in this window");
    static final MenuAction RENAME = MenuAction.of("ws-rename", "Rename");
    static final MenuAction DELETE = MenuAction.of("ws-delete", "Delete");

    /** A row of the list: a workspace, and whether Settings opens it at launch. */
    record Row(String name, boolean launch) {
    }

    /** What a card's menu asks of the view: rename its tab ({@code folder} false), or pick it another folder. */
    record CardAsk(String key, boolean folder) {
        static final Topic<CardAsk> TOPIC = Topic.edge(CardAsk.class);
    }

    /** The window's own keys: Esc puts a field back as it was ({@code cancel}); Ctrl+Shift+R goes to the name. */
    record Key(boolean cancel) {
        static final Topic<Key> TOPIC = Topic.edge(Key.class);
    }

    /** One card's nodes, kept while its row is on the board, so its words change rather than the card. */
    private record Card(Node node, Node title, Node path, IconButton more) {
    }

    private final Gui gui;
    private final ListView<Row> list;
    private final Button make;
    private final Node right;
    private final Node empty;
    private final TextField name;
    private final Node problem;
    private final Node subtitle;
    private final Board<String> board;
    private final Deck<String> deck;
    private final Node noFolders;
    private final Node retitling;
    private final Node retitleLabel;
    private final TextField retitle;
    private final Button add;
    private final Button env;
    private final Node said;
    private final Node root;
    private final Map<String, Card> cards = new HashMap<>();

    WorkspacesPage(Window window) {
        this.gui = window.gui();
        if (gui.history() == null) {
            gui.history(new History());   // the window's history, which Ctrl+Z walks; the framework's when it has one
        }

        list = new ListView<Row>(gui, 2.25f, (nodes, r) -> nodes.row().gap(Length.dp(8)).alignItems(AlignItems.CENTER)
                .width(Length.FILL).children(
                        nodes.text(r.name()).textColor(Role.INK).wordWrap(false).width(Length.grow(1f))
                                .ellipsis(Ellipsis.END),
                        nodes.text(r.launch() ? "launch" : "").textColor(Role.DIM).textSize(Length.rem(0.75f))))
                .name(LIST).action(OPEN).action(HERE).action(RENAME).action(DELETE);
        list.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(LIST, list.node());
        make = new Button(gui, "New workspace").name(NEW);
        gui.landmark(NEW, make.node());
        Node left = gui.column().gap(Length.rem(0.75f)).width(Length.rem(15f)).height(Length.FILL)
                .children(list.node(), make.node());

        // Each field keeps its own typing: in the window's history, a field put away blocks every step behind it (FN-18).
        name = new TextField(gui).name(NAME).frame(Framing.FLUSH).history(new History());
        name.node().width(Length.FILL).textSize(Length.rem(1.25f));
        gui.landmark(NAME, name.node());
        problem = gui.text("").textColor(gui.theme().color(Role.DANGER)).width(Length.FILL).visible(false);
        gui.landmark(PROBLEM, problem);
        subtitle = gui.text("").textColor(gui.theme().color(Role.DIM)).textSize(Length.rem(0.8125f));

        board = new Board<String>(gui, (nodes, key) -> card(key)).motion(new Motion(gui).arrival);
        deck = board.column().name(CARDS).reorderable();
        deck.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(CARDS, deck.node());
        noFolders = gui.text("No folders yet. Add folder… gives this workspace its first tab.")
                .textColor(gui.theme().color(Role.DIM)).visible(false);

        // Renaming a card's tab: a field of its own under the cards, as the tab bar's is beside it (FN-10, FN-16).
        retitle = new TextField(gui).name(TAB_NAME).history(new History());
        retitle.node().width(Length.rem(16f));
        gui.landmark(TAB_NAME, retitle.node());
        retitleLabel = gui.text("").textColor(gui.theme().color(Role.INK));
        retitling = gui.row().gap(Length.dp(8)).alignItems(AlignItems.CENTER).visible(false)
                .children(retitleLabel, retitle.node());

        add = new Button(gui, "Add folder…").name(ADD);
        gui.landmark(ADD, add.node());
        env = new Button(gui, "Edit environment…").name(ENV);   // the workspace's own script
        gui.landmark(ENV, env.node());
        Node buttons = gui.row().gap(Length.dp(8)).width(Length.FILL).children(add.node(),
                gui.box().width(Length.grow(1f)), env.node());

        right = gui.column().gap(Length.rem(0.6f)).width(Length.grow(1f)).height(Length.FILL)
                .children(name.node(), problem, subtitle, noFolders, deck.node(), retitling, buttons);
        empty = gui.text("No workspaces yet. New workspace makes one; Add folder… then gives it its tabs.")
                .textColor(gui.theme().color(Role.DIM)).width(Length.grow(1f)).visible(false);

        said = gui.text("").textColor(gui.theme().color(Role.DIM)).width(Length.FILL).wordWrap(false)
                .ellipsis(Ellipsis.END);
        gui.landmark(SAID, said);

        Node body = gui.row().gap(Length.rem(1.25f)).padding(Length.rem(1.25f)).width(Length.FILL)
                .height(Length.grow(1f)).children(left, right, empty);
        Node footer = gui.row().padding(Length.rem(0.5f), Length.rem(1.25f)).width(Length.FILL).children(said);
        window.titleBar().wordmark(Wordmark.bar(gui));
        root = gui.root();
        root.direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), body, footer);
    }

    /** A card: a grip, the tab's name over its folder, and the ⋯ that drops its menu. */
    private Node card(String key) {
        Node title = gui.text("").textColor(gui.theme().color(Role.INK)).wordWrap(false);
        Node path = gui.text("").textColor(gui.theme().color(Role.DIM)).wordWrap(false).width(Length.FILL)
                .ellipsis(Ellipsis.MIDDLE);
        IconButton more = new IconButton(gui, Icon.MORE, "More");
        Node node = gui.row().gap(Length.dp(10)).alignItems(AlignItems.CENTER).width(Length.FILL)
                .padding(Length.dp(8), Length.dp(10)).background(gui.theme().color(Role.WELL))
                .children(dev.vexelray.gui.icons.IconView.of(gui, Icon.GRIP).node(),
                        gui.row().gap(Length.dp(14)).alignItems(AlignItems.CENTER).width(Length.grow(1f))
                                .children(title.width(Length.rem(9f)), path),
                        more.node());
        cards.put(key, new Card(node, title, path, more));
        return node;
    }

    /**
     * Shows {@code all} with {@code selected} on the right: its name, unless the field is being typed in; its cards,
     * each with its menu; and the footer's line. Returns the workspace it selected in the list, whose selection the list will say
     * back, or null when the list already had it selected.
     */
    String show(List<Workspace> all, String selected, String launch, String problemText, String saidText,
              boolean typingName, Sender<RemoveRow> remove, Sender<CardAsk> asks,
                Sender<EditScript> scripts) {
        List<Row> rows = all.stream().map(w -> new Row(w.name(), w.name().equals(launch))).toList();
        list.items(rows);
        String echo = null;
        for (Row r : rows) {
            if (r.name().equals(selected) && !list.selection().isSelected(r)) {
                list.selection().at(r);   // which the list says back as a selection, a frame or more later
                echo = r.name();
            }
        }

        Workspace w = all.stream().filter(x -> x.name().equals(selected)).findFirst().orElse(null);
        right.visible(w != null);
        empty.visible(w == null);
        problem.text(problemText).visible(!problemText.isBlank());
        said.text(saidText);
        if (w == null) {
            deck.items(List.of());
            return echo;
        }
        if (!typingName) {
            name.text(w.name());
        }
        int n = w.entries().size();
        subtitle.text((n == 0 ? "No folders" : n == 1 ? "1 folder" : n + " folders")
                + (w.name().equals(launch) ? " · opens at launch" : ""));
        noFolders.visible(n == 0);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            keys.add(key(w.entries().get(i), i));
        }
        deck.items(keys);
        for (int i = 0; i < n; i++) {
            Workspace.Entry e = w.entries().get(i);
            Card c = cards.get(keys.get(i));
            if (c == null) {
                continue;   // built when the board lays the deck out; filled by the next show
            }
            c.title().text(e.title()).textColor(gui.theme().color(e.name().isBlank() ? Role.DIM : Role.INK));
            c.path().text(e.path());
            Menu menu = Menu.builder()
                    .item("Rename tab", asks, new CardAsk(keys.get(i), false))
                    .item("Change folder…", asks, new CardAsk(keys.get(i), true))
                    .item("Edit environment…", scripts,
                            new EditScript(w.name(), e.env(), e.title(), e.path(), java.util.Optional.empty()))
                    .separator()
                    .item("Remove", remove, new RemoveRow(w.name(), i))
                    .build();
            gui.menu(c.node(), menu);
            c.more().menu(menu);
        }
        return echo;
    }

    /** The card a row is: its script key, or its place when it has none, which a listed workspace never lacks. */
    static String key(Workspace.Entry e, int at) {
        return e.env().isBlank() ? "#" + at : e.env();
    }

    /** Show the field for renaming the tab {@code title}, holding {@code text} selected; or put it away. */
    void retitling(boolean on, String title, String text) {
        if (on) {
            retitleLabel.text("Rename " + title + " to");
            retitle.text(text).select(0, text.length());
            gui.focus(retitle.node());
        }
        retitling.visible(on);
    }

    /** The keyboard in the name, all of it selected so typing replaces it. */
    void focusName() {
        name.select(0, name.text().length());
        gui.focus(name.node());
    }

    void name(String text) {
        name.text(text);
    }

    Gui gui() {
        return gui;
    }

    Node root() {
        return root;
    }

    String listAddress() {
        return list.address();
    }

    Node listNode() {
        return list.node();
    }

    String newAddress() {
        return make.address();
    }

    String nameAddress() {
        return name.address();
    }

    String retitleAddress() {
        return retitle.address();
    }

    String envAddress() {
        return env.address();
    }

    String addAddress() {
        return add.address();
    }

    String deckAddress() {
        return deck.address();
    }

    /** What the deck shows: for a test. */
    List<String> cards() {
        return deck.items();
    }
}
