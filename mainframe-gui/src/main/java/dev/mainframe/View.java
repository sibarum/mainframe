package dev.mainframe;

import dev.mainframe.Intent.Command;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Submitted;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The view: what the frame decided, shown, and what the person did, told. A {@code @View}, so it runs on the GUI
 * thread, in each frame's widget tick (threading.md, T7.4).
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Which tabs there are, what each runs, when its shell starts and whether closing asks are the {@code Frame}'s, in
 * {@code -core}. This turns {@link AddTab} into a {@link TerminalScreen} in the bar and {@link CloseTab} into one
 * fewer, hands each tab's {@link Output} to its screen, and gives a key-map command to the screen in front. What the
 * person does to the bar (picking a tab, the +, a header's menu, the rename field) it tells the frame.
 *
 * <h2>It forwards the screens' messages with the tab's id</h2>
 *
 * <p>A screen publishes what goes to its program ({@code SENT}), its size in cells ({@code FITTED}) and its title
 * ({@code TITLED}) addressed by the window, not by itself, so a part keyed per tab cannot hear them as they are. The
 * view knows which screen is which tab, and sends each on keyed by the tab's id (docs/framework-notes.md, FN-2).
 *
 * <h2>The terminal's chords are the front screen's</h2>
 *
 * <p>Copy, paste, interrupt and paging apply while the screen in front has the keyboard, and move to each screen as it
 * comes to the front, as 0.1's did; so in the rename field Ctrl+V and Esc are the field's. The tab and window
 * commands apply everywhere. While the passthrough switch is on (docs/passthrough.md) the key map lets go of all of
 * them, and the pill at the bar's end, or the Terminal menu, is the way back.
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, so the processor sees every message the view can send without
 * reading a line of its body (ROADMAP fork 5). One mailbox is not declared: double-clicks on a header, which reach a
 * view only as the tree's click topic (FN-11).
 */
@dev.vexelray.framework.api.View
final class View {

    /** The most characters typed in one frame the view takes: see {@link #sent}. */
    static final int SENT_PER_FRAME = 1024;

    /** A header's own menu: the tab's, beside the bar's built-in ones. */
    static final MenuAction RENAME = MenuAction.of("rename", "Rename…");
    static final MenuAction CLOSE = MenuAction.of("close-tab", "Close");

    private final Gui gui;
    private final Keymap keys;
    private final Ui ui;
    private final Tabs tabs;
    private final Sender<Resized> resized;
    private final Sender<Typed> typed;
    private final Sender<Retitled> retitled;
    private final Sender<Shown> shown;
    private final Sender<Submitted> submitted;
    private final Sender<Fronted> fronted;
    private final Sender<NewTab> newTab;
    private final Sender<CloseAsked> closeAsked;
    private final Sender<Rename> rename;
    private final Sender<Intent> intents;

    /** The mono face's layout, one for every tab: it is only read. */
    private final GlyphLayout glyphs;

    /** The tabs' screens, in the bar's order, and which tab each screen's address is. */
    private final List<Long> order = new ArrayList<>();
    private final Map<Long, TerminalScreen> screens = new HashMap<>();
    private final Map<String, Long> byAddress = new HashMap<>();
    /** What each header says, as last set: a node's words cannot be read back, and the rename field starts from them. */
    private final Map<Long, String> headers = new HashMap<>();

    /** The key map's commands that apply to the screen in front, moved to each as it comes to the front. */
    private final List<Action> terminal = new ArrayList<>();

    /** Where on {@link Type#TERMINAL_PX} the terminals' type is. */
    private int size = Type.TERMINAL_NORMAL;

    /** The tab whose new name is being typed, or {@link dev.mainframe.core.Messages#NO_TAB}. */
    private long renamingTab = dev.mainframe.core.Messages.NO_TAB;

    View(Gui gui, Keymap keys, Ui ui, Sender<Resized> resized, Sender<Typed> typed, Sender<Retitled> retitled,
            Sender<Shown> shown, Sender<Submitted> submitted, Sender<Fronted> fronted, Sender<NewTab> newTab,
            Sender<CloseAsked> closeAsked, Sender<Rename> rename, Sender<Intent> intents) {
        this.gui = gui;
        this.keys = keys;
        this.ui = ui;
        this.tabs = ui.tabs();
        this.resized = resized;
        this.typed = typed;
        this.retitled = retitled;
        this.shown = shown;
        this.submitted = submitted;
        this.fronted = fronted;
        this.newTab = newTab;
        this.closeAsked = closeAsked;
        this.rename = rename;
        this.intents = intents;
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        tabs.action(RENAME).action(CLOSE);
        commands(keys);
        ui.menu(false, intents);
        // A double-click on a header renames its tab. The bar says nothing of one, so the tree's clicks are read,
        // in a mailbox of the widgets' tick like every other the view has (FN-11).
        gui.mailbox(gui.clicks(), this::clicked, 64);
    }

    /**
     * Every command the keyboard reaches, as a declaration: a stable id, what the person calls it, the chords it starts
     * with, and the {@link Intent} it sends. The chords are the person's to change from the app menu's
     * <i>Key bindings…</i>. A chord held here outranks the terminal's typing, which is why Ctrl+C copies and Esc
     * interrupts (0.1's README, <i>Keys</i>): unbind one and the program has it again.
     */
    private void commands(Keymap keys) {
        // Esc in the rename field puts it away. First, and moved to the field at once: two actions may not both
        // default to Esc in one place, and every action starts application-wide, where the interrupt's Esc is.
        command(keys, "tab.rename-cancel", "Cancel renaming the tab", Command.RENAME_CANCEL, Shortcut.of(Key.ESCAPE))
                .within(gui, ui.rename().node(), ClaimScope.FOCUSED);
        terminal.add(command(keys, "terminal.copy", "Copy the selection, or interrupt", Command.COPY,
                Shortcut.of(Key.C, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.C, Modifier.CONTROL)));
        terminal.add(command(keys, "terminal.paste", "Paste", Command.PASTE,
                Shortcut.of(Key.V, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.V, Modifier.CONTROL)));
        terminal.add(command(keys, "terminal.interrupt", "Interrupt", Command.INTERRUPT, Shortcut.of(Key.ESCAPE)));
        terminal.add(command(keys, "terminal.page-back", "Scroll back a page", Command.PAGE_BACK,
                Shortcut.of(Key.PAGE_UP, Modifier.SHIFT)));
        terminal.add(command(keys, "terminal.page-on", "Scroll on a page", Command.PAGE_ON,
                Shortcut.of(Key.PAGE_DOWN, Modifier.SHIFT)));
        command(keys, "view.larger", "Larger text", Command.LARGER,
                Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL));
        command(keys, "view.smaller", "Smaller text", Command.SMALLER,
                Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL));
        command(keys, "view.normal", "Normal text size", Command.NORMAL,
                Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL));
        command(keys, "tab.new", "New tab", Command.NEW_TAB,
                Shortcut.of(Key.T, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.T, Modifier.CONTROL));
        command(keys, "tab.close", "Close the tab, after asking", Command.CLOSE_TAB, Shortcut.of(Key.W, Modifier.CONTROL));
        command(keys, "tab.close-now", "Close the tab without asking", Command.CLOSE_NOW,
                Shortcut.of(Key.W, Modifier.CONTROL, Modifier.SHIFT));
        command(keys, "tab.rename", "Rename the tab", Command.RENAME,
                Shortcut.of(Key.R, Modifier.CONTROL, Modifier.SHIFT));
        command(keys, "tab.next", "Next tab", Command.NEXT_TAB, Shortcut.of(Key.TAB, Modifier.CONTROL));
        command(keys, "tab.previous", "Previous tab", Command.PREVIOUS_TAB,
                Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT));
    }

    private Action command(Keymap keys, String id, String label, Command command, Shortcut... chords) {
        return keys.action(id, label, chords).sends(intents, new Intent(command));
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    // ------------------------------------------------------------------ what the frame says

    @Subscribe
    public void addTab(AddTab m) {
        TerminalScreen screen = new TerminalScreen(gui, glyphs, Type.MONO, Type.TERMINAL_PX[size])
                .name("terminal-" + m.id()).pseudoconsole();
        screens.put(m.id(), screen);
        byAddress.put(screen.address(), m.id());
        headers.put(m.id(), m.header());
        order.add(m.id());
        tabs.add(m.header(), screen.node());
    }

    /** The frame put a tab in front: show it. What the bar says it selected is then heard as {@link #selected}. */
    @Subscribe
    public void front(Front m) {
        int at = order.indexOf(m.id());
        if (at >= 0) {
            tabs.select(at);
            shownInFront(m.id());
        }
    }

    @Subscribe
    public void closeTab(CloseTab m) {
        int at = order.indexOf(m.id());
        TerminalScreen screen = screens.remove(m.id());
        if (at < 0 || screen == null) {
            return;
        }
        if (renamingTab == m.id()) {
            endRename(false);
        }
        order.remove(at);
        byAddress.remove(screen.address());
        headers.remove(m.id());
        tabs.remove(at);
        screen.close();
    }

    @Subscribe
    public void header(Header m) {
        int at = order.indexOf(m.id());
        if (at >= 0) {
            tabs.title(at, m.text());
            headers.put(m.id(), m.text());
        }
    }

    /**
     * What a tab's program wrote, fed to its screen, and said to be shown: its shell sends the next only then, so a
     * program writing faster than the screen draws waits rather than overflowing this mailbox (FN-9). Said for a tab
     * already gone too, so its shell never waits on a screen that will not come.
     */
    @Subscribe
    public void output(Output m) {
        TerminalScreen screen = screens.get(m.id());
        if (screen != null) {
            screen.feed(m.text());
        }
        shown.send(new Shown(m.id()));
    }

    // ------------------------------------------------------------------ what the screens say, sent on with the tab

    /**
     * A screen sends a message per character typed, all in the frame they arrive in, and this hears them in the next:
     * so this mailbox holds a frame's worth of typing, which a script's {@code type} makes a line's worth. One more
     * than it holds halts the application (docs/framework-notes.md, FN-8). A CR or LF typed means something may now be
     * running, which the frame hears once per tab as {@link Submitted}, not as every key.
     */
    @Subscribe(capacity = SENT_PER_FRAME)
    public void sent(TerminalScreen.Sent m) {
        Long id = byAddress.get(m.terminal());
        if (id != null) {
            typed.send(new Typed(id, m.text()));
            if (m.typed() && (m.text().indexOf('\r') >= 0 || m.text().indexOf('\n') >= 0)) {
                submitted.send(new Submitted(id));
            }
        }
    }

    @Subscribe
    public void fitted(TerminalScreen.Fitted m) {
        Long id = byAddress.get(m.terminal());
        if (id != null) {
            resized.send(new Resized(id, m.cols(), m.rows()));
        }
    }

    @Subscribe
    public void titled(TerminalScreen.Titled m) {
        Long id = byAddress.get(m.terminal());
        if (id != null) {
            retitled.send(new Retitled(id, m.title()));
        }
    }

    // ------------------------------------------------------------------ what the bar says

    /** The bar selected a tab: a click on its header, Ctrl+Tab, or the frame's own {@link Front}. */
    @Subscribe
    public void selected(Tabs.Selected m) {
        if (!m.tabs().equals(tabs.address()) || m.index() < 0 || m.index() >= order.size()) {
            return;
        }
        long id = order.get(m.index());
        shownInFront(id);
        fronted.send(new Fronted(id));
    }

    /** A header's own menu: Rename… or Close, for that tab, whichever is in front. */
    @Subscribe
    public void chosen(Tabs.Chosen m) {
        if (!m.at().equals(tabs.address()) || !(m.item() instanceof Integer at) || at < 0 || at >= order.size()) {
            return;
        }
        long id = order.get(at);
        if (m.action().equals(RENAME.id())) {
            startRename(id);
        } else if (m.action().equals(CLOSE.id())) {
            closeAsked.send(new CloseAsked(id, true));
        }
    }

    /** The + or the pill. */
    @Subscribe
    public void pressed(Button.Pressed m) {
        if (m.button().equals(ui.plus().address())) {
            newTab();
        } else if (m.button().equals(ui.pill().address()) && keys.paused()) {
            passthrough();
        }
    }

    /** Enter in the rename field: the name it holds is the tab's. */
    @Subscribe
    public void named(TextField.Submitted m) {
        if (m.field().equals(ui.rename().address())) {
            endRename(true);
        }
    }

    /** A second click on a header: rename that tab. */
    private void clicked(ClickEvent e) {
        if (e.clicks() != 2) {
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            if (tabs.header(i).id() == e.nodeId()) {
                startRename(order.get(i));
                return;
            }
        }
    }

    /** A tab's screen, or null: for a test reading the view on the GUI thread. */
    TerminalScreen screen(long id) {
        return screens.get(id);
    }

    /** The tab being renamed, or {@code NO_TAB}: for a test. */
    long renaming() {
        return renamingTab;
    }

    // ------------------------------------------------------------------ what the person did, by chord or menu

    /** The tab in front, or {@code NO_TAB}. */
    private long frontId() {
        int at = tabs.selected();
        return at >= 0 && at < order.size() ? order.get(at) : dev.mainframe.core.Messages.NO_TAB;
    }

    /** The screen in front, or null with no tabs. */
    private TerminalScreen front() {
        return screens.get(frontId());
    }

    /** A tab is in front: a script finds it by its landmark, the keyboard is its, and so are the terminal's chords. */
    private void shownInFront(long id) {
        TerminalScreen screen = screens.get(id);
        if (screen == null) {
            return;
        }
        gui.landmark(Landmarks.TERMINAL, screen.node());
        for (Action a : terminal) {
            a.within(gui, screen.node(), ClaimScope.FOCUSED);
        }
        if (renamingTab == dev.mainframe.core.Messages.NO_TAB) {
            gui.focus(screen.node());
        }
    }

    void copyOrInterrupt() {
        TerminalScreen s = front();
        if (s != null) {
            s.copyOrInterrupt();
        }
    }

    void paste() {
        TerminalScreen s = front();
        if (s != null) {
            s.paste();
        }
    }

    /** What Ctrl+C sends a shell, 0x03, for when Ctrl+C is copying. */
    void interrupt() {
        long id = frontId();
        if (id != dev.mainframe.core.Messages.NO_TAB) {
            typed.send(new Typed(id, "\u0003"));
        }
    }

    void page(int direction) {
        TerminalScreen s = front();
        if (s != null) {
            s.page(direction);
        }
    }

    /** A step up ({@code 1}) or down ({@code -1}) the ladder, or back to where it starts ({@code 0}). */
    void fontSize(int direction) {
        int next = direction == 0 ? Type.TERMINAL_NORMAL
                : Math.max(0, Math.min(Type.TERMINAL_PX.length - 1, size + direction));
        if (next == size) {
            return;
        }
        size = next;
        for (TerminalScreen s : screens.values()) {
            s.fontPx(Type.TERMINAL_PX[size]);
        }
    }

    void newTab() {
        newTab.send(new NewTab());
    }

    void closeFront(boolean ask) {
        long id = frontId();
        if (id != dev.mainframe.core.Messages.NO_TAB) {
            closeAsked.send(new CloseAsked(id, ask));
        }
    }

    /** The next tab ({@code 1}) or the previous ({@code -1}), round the ends. */
    void cycle(int direction) {
        int n = order.size();
        if (n > 1) {
            tabs.select(Math.floorMod(tabs.selected() + direction, n));
        }
    }

    void startRename() {
        long id = frontId();
        if (id != dev.mainframe.core.Messages.NO_TAB) {
            startRename(id);
        }
    }

    private void startRename(long id) {
        int at = order.indexOf(id);
        if (at < 0) {
            return;
        }
        if (tabs.selected() != at) {
            tabs.select(at);
        }
        renamingTab = id;
        ui.renaming(true, headers.getOrDefault(id, ""));
        gui.focus(ui.rename().node());
    }

    /** Put the rename field away; with {@code keep}, the name it holds goes to the frame, a blank one included. */
    void endRename(boolean keep) {
        long id = renamingTab;
        if (id == dev.mainframe.core.Messages.NO_TAB) {
            return;
        }
        renamingTab = dev.mainframe.core.Messages.NO_TAB;
        if (keep) {
            rename.send(new Rename(id, ui.rename().text()));
        }
        ui.renaming(false, "");
        TerminalScreen s = front();
        if (s != null) {
            gui.focus(s.node());
        }
    }

    /** Every key to the shell, or back to MainFrame's commands (docs/passthrough.md). */
    void passthrough() {
        boolean on = !keys.paused();
        keys.paused(on);
        ui.passthrough(on);
        ui.menu(on, intents);
        TerminalScreen s = front();
        if (s != null) {
            gui.focus(s.node());
        }
    }
}
