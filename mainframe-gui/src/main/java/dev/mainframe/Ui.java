package dev.mainframe;

import dev.mainframe.core.TabMotion;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Tooltip;
import sibarum.atchung.Sender;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The window: the framework's title bar, with the application's Terminal menu after its lockup, over the tab bar,
 * whose pages are the tabs' terminals.
 *
 * <h2>It holds no state</h2>
 *
 * <p>The tabs are the view's to add and remove, as the frame says; this only builds the regions once and keeps them,
 * and the view shows, hides and fills them. Nothing is rebuilt, so nothing on screen changes in one frame: a tab
 * arriving slides in ({@link Motion#change}), and the pill and the rename field fade in and out.
 *
 * <h2>The bar's end</h2>
 *
 * <p>The + (a new tab), the passthrough pill (docs/passthrough.md) and the rename field stand at the tab bar's right
 * end. {@code Tabs} has no place for an application's own nodes beside its headers, nor over one, so they float in a
 * box round the tabs, pushed to its right edge (a floated node is kept inside its parent), and the rename field is
 * there rather than over the header it renames (docs/framework-notes.md, FN-10).
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless.
 */
final class Ui {

    /** The application's menu after the lockup; a script reaches it as {@code TitleBar.menuLandmark(TERMINAL_MENU)}. */
    static final String TERMINAL_MENU = "terminal";

    private final TitleBar titleBar;
    private final Tabs tabs;
    private final IconButton plus;
    private final Button pill;
    private final TextField rename;
    private final Map<TabMotion, Consumer<Tabs>> motions = new EnumMap<>(TabMotion.class);
    private final Node renaming;

    Ui(Gui gui, Motion motion, TitleBar titleBar) {
        this.titleBar = titleBar;
        // Always shown, even with one tab: the + and the passthrough pill stand at its end (docs/shape.md, ruled).
        tabs = new Tabs(gui).name("tabs").closable(false).slide(motion.change);
        tabs.node().width(Length.FILL).height(Length.grow(1f));
        motions.put(TabMotion.SLIDE, t -> t.slide(motion.change));
        motions.put(TabMotion.FADE, t -> t.crossfade(motion.change));
        motions.put(TabMotion.NONE, t -> t.slide(null));
        gui.landmark(Landmarks.TABS, tabs.node());

        plus = new IconButton(gui, Icon.PLUS, "New tab").name(Landmarks.NEW_TAB);
        gui.landmark(Landmarks.NEW_TAB, plus.node());

        pill = new Button(gui, "Every key to the shell   ×").kind(Button.Kind.PRIMARY).name(Landmarks.PASSTHROUGH);
        pill.node().visible(false);
        gui.landmark(Landmarks.PASSTHROUGH, pill.node());
        new Tooltip(gui).attach(pill.node(), "MainFrame's shortcuts are off while this is on, so Ctrl+C, Esc and the "
                + "rest reach the program. Click to turn them back on.");

        rename = new TextField(gui).name(Landmarks.RENAME);
        rename.node().width(Length.rem(12f));
        gui.landmark(Landmarks.RENAME, rename.node());
        renaming = gui.row().gap(Length.dp(6)).alignItems(AlignItems.CENTER).visible(false)
                .children(gui.text("Rename to").textColor(Role.INK), rename.node());

        Node end = gui.row().gap(Length.dp(6)).alignItems(AlignItems.CENTER)
                .padding(Length.dp(3), Length.dp(6))
                .children(renaming, pill.node(), plus.node())
                // Far right: a floated node is clamped inside its parent, so this lands against the right edge.
                .floatAt(Length.rem(10_000f), Length.ZERO);
        Node body = gui.box().width(Length.FILL).height(Length.grow(1f)).children(tabs.node(), end);

        // The brand sheet's wordmark in the lockup, rather than the name in the bar's type (Wordmark).
        TitleBar.Wordmark wordmark = Wordmark.bar(gui);
        titleBar.wordmark(wordmark);
        gui.landmark(Landmarks.WORDMARK, wordmark.node());
        // No title after the menus: the wordmark already says whose window it is, and the title, in the menus' shade,
        // read as a menu that did nothing when clicked (FN-27). The OS's window title stays the app's.
        titleBar.title("");

        titleBar.menu(TERMINAL_MENU, "Terminal", Menu.NONE);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body);
    }

    Tabs tabs() {
        return tabs;
    }

    IconButton plus() {
        return plus;
    }

    Button pill() {
        return pill;
    }

    TextField rename() {
        return rename;
    }

    /** How the bar changes tabs from now on, as Settings says. */
    void motion(TabMotion m) {
        motions.get(m).accept(tabs);
    }

    /** Show the passthrough pill, or not. */
    void passthrough(boolean on) {
        pill.node().visible(on);
    }

    /** Show the rename field holding {@code text}, all of it selected so typing replaces it; or put it away. */
    void renaming(boolean on, String text) {
        if (on) {
            rename.text(text).select(0, text.length());
        }
        renaming.visible(on);
    }

    /**
     * The Terminal menu, declared again: its lines send what the key map's chords send, and its last says which way
     * the passthrough switch would flip.
     */
    void menu(boolean passthrough, Sender<Intent> intents, Sender<WindowCommand> windows,
              Sender<dev.mainframe.core.Messages.OpenAsked> open,
              Sender<dev.mainframe.core.Messages.EditWorkspaces> edit) {
        Menu menu = Menu.builder()
                .item("New tab", intents, new Intent(Intent.Command.NEW_TAB))
                .item(Icon.OPEN, "Open…", open, new dev.mainframe.core.Messages.OpenAsked())
                .item("Close tab", intents, new Intent(Intent.Command.CLOSE_TAB))
                .item("Rename tab…", intents, new Intent(Intent.Command.RENAME))
                .item("Workspaces…", edit, new dev.mainframe.core.Messages.EditWorkspaces("", false))
                .separator()
                .item("Next tab", intents, new Intent(Intent.Command.NEXT_TAB))
                .item("Previous tab", intents, new Intent(Intent.Command.PREVIOUS_TAB))
                .separator()
                .item("Larger text", intents, new Intent(Intent.Command.LARGER))
                .item("Smaller text", intents, new Intent(Intent.Command.SMALLER))
                .item("Normal text size", intents, new Intent(Intent.Command.NORMAL))
                .separator()
                .item(Icon.SETTINGS, "Settings…", windows, WindowCommand.show(SettingsPage.KEY))
                .separator()
                .item(Icon.KEYBOARD, passthrough ? "Give MainFrame its keys back" : "Every key to the shell",
                        intents, new Intent(Intent.Command.PASSTHROUGH))
                .build();
        titleBar.menu(TERMINAL_MENU, "Terminal", menu);
    }
}
