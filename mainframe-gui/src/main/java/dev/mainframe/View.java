package dev.mainframe;

import dev.mainframe.Intent.Command;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.EditWorkspaces;
import dev.mainframe.core.Messages.EditEnv;
import dev.mainframe.core.Messages.TypeInto;
import dev.mainframe.core.Messages.Touched;
import dev.mainframe.core.Messages.Notice;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Submitted;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;
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
    static final MenuAction ENV_TAB = MenuAction.of("env-tab", "Edit environment…");
    static final MenuAction ENV_WORKSPACE = MenuAction.of("env-workspace", "Edit workspace environment…");

    /** How long a tab's program is quiet after writing before its prompt counts as drawn (0.1's 300 ms). */
    static final java.time.Duration PROMPT_QUIET = java.time.Duration.ofMillis(300);

    /**
     * A tab's program has been quiet since its output number {@code seq}: the timer's, which the view sets after each
     * output while there is something to type into the tab, and which counts only if no output came since.
     */
    record Quiet(long id, long seq) {
        static final sibarum.atchung.Topic<Quiet> TOPIC = sibarum.atchung.Topic.edge(Quiet.class);
    }

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
    private final Sender<ChangePrefs> changePrefs;
    private final Sender<WindowCommand> windows;
    private final Sender<OpenAsked> openAsked;
    private final Sender<EditWorkspaces> editWorkspaces;
    private final Sender<EditEnv> editEnv;
    private final dev.vexelray.framework.core.Timer timer;
    private final Sender<Touched> touched;
    /** The tabs something was typed into, so each is said {@link Touched} once. */
    private final java.util.Set<Long> touchedTabs = new java.util.HashSet<>();
    private final Sender<Intent> intents;

    /** The mono face's layout, one for every tab: it is only read. */
    private final GlyphLayout glyphs;

    /** The tabs' screens, in the bar's order, and which tab each screen's address is. */
    private final List<Long> order = new ArrayList<>();
    private final Map<Long, TerminalScreen> screens = new HashMap<>();
    /** What {@code --insert-path} is to type into a tab, by its id, until it is typed or the person types first. */
    private final Map<Long, String> inserts = new HashMap<>();
    /** How many outputs each such tab has had, so a quiet beat from before the last one counts for nothing. */
    private final Map<Long, Long> outputs = new HashMap<>();
    /** The quiet beat each such tab is waiting on. */
    private final Map<Long, dev.vexelray.framework.core.Timer.Scheduled> waits = new HashMap<>();
    private final Map<String, Long> byAddress = new HashMap<>();
    /** What each header says, as last set: a node's words cannot be read back, and the rename field starts from them. */
    private final Map<Long, String> headers = new HashMap<>();

    /**
     * Each tab's notice: the band above its terminal (docs/workspaces.md), built with the tab and kept, shown while it
     * has something to say; and which tab each band's × is.
     */
    private record Band(Node row, Node text, IconButton close) {
    }

    private final Map<Long, Band> bands = new HashMap<>();
    private final Map<String, Long> byClose = new HashMap<>();
    /** The tabs whose band is showing: what a node cannot be asked. */
    private final java.util.Set<Long> noticing = new java.util.HashSet<>();

    /** The key map's commands that apply to the screen in front, moved to each as it comes to the front. */
    private final List<Action> terminal = new ArrayList<>();

    /**
     * The settings as the frame last said them ({@link Prefs}): the terminals' type size, in the picture's pixels (a
     * terminal is a grid of cells, so its type is the person's to size, not the window's zoom), and the tab motion.
     */
    private Preferences prefs = Preferences.DEFAULT;

    /** The tab whose new name is being typed, or {@link dev.mainframe.core.Messages#NO_TAB}. */
    private long renamingTab = dev.mainframe.core.Messages.NO_TAB;

    View(Gui gui, Keymap keys, Ui ui, Sender<Resized> resized, Sender<Typed> typed, Sender<Retitled> retitled,
            Sender<Shown> shown, Sender<Submitted> submitted, Sender<Fronted> fronted, Sender<NewTab> newTab,
            Sender<CloseAsked> closeAsked, Sender<Rename> rename, Sender<ChangePrefs> changePrefs,
            Sender<WindowCommand> windows, Sender<OpenAsked> openAsked, Sender<Touched> touched,
            OpenPage open, Sender<OpenPage.Key> openKeys, WorkspacesPage workspaces,
            Sender<WorkspacesPage.Key> workspacesKeys, Sender<EditWorkspaces> editWorkspaces, Sender<EditEnv> editEnv,
            dev.vexelray.framework.core.Timer timer, Sender<Intent> intents) {
        this.editEnv = editEnv;
        this.timer = timer;
        this.editWorkspaces = editWorkspaces;
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
        this.changePrefs = changePrefs;
        this.windows = windows;
        this.openAsked = openAsked;
        this.touched = touched;
        this.intents = intents;
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        tabs.action(RENAME).action(ENV_TAB).action(ENV_WORKSPACE).action(CLOSE);
        commands(keys, open, openKeys, workspaces, workspacesKeys);
        ui.menu(false, intents, windows, openAsked, editWorkspaces);
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
    private void commands(Keymap keys, OpenPage open, Sender<OpenPage.Key> openKeys, WorkspacesPage workspaces,
                          Sender<WorkspacesPage.Key> workspacesKeys) {
        // Esc in the rename field puts it away. First, and moved to the field at once: two actions may not both
        // default to Esc in one place, and every action starts application-wide, where the interrupt's Esc is.
        command(keys, "tab.rename-cancel", "Cancel renaming the tab", Command.RENAME_CANCEL, Shortcut.of(Key.ESCAPE))
                .within(gui, ui.rename().node(), ClaimScope.FOCUSED);
        // The Open dialog's Esc, here for the same reason: an action can only be declared application-wide and moved
        // after, so the view whose Esc is first declared wins the place (docs/framework-notes.md, FN-14).
        keys.action("open.close", "Close the Open dialog", Shortcut.of(Key.ESCAPE))
                .sends(openKeys, new OpenPage.Key(false)).within(open.gui(), open.listNode(), ClaimScope.FOCUSED);
        // The Workspaces window's Esc (a field back as it was) and Ctrl+Shift+R (to the name), for the same reason:
        // the terminal's Esc and the tab bar's Ctrl+Shift+R are declared below, application-wide.
        keys.action("workspaces.cancel", "Put back the name being typed", Shortcut.of(Key.ESCAPE))
                .sends(workspacesKeys, new WorkspacesPage.Key(true))
                .within(workspaces.gui(), workspaces.root(), ClaimScope.VISIBLE);
        keys.action("workspaces.rename", "Rename the workspace", Shortcut.of(Key.R, Modifier.CONTROL, Modifier.SHIFT))
                .sends(workspacesKeys, new WorkspacesPage.Key(false))
                .within(workspaces.gui(), workspaces.root(), ClaimScope.VISIBLE);
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
        keys.action("app.open", "Open a workspace or a folder", Shortcut.of(Key.O, Modifier.CONTROL))
                .sends(openAsked, new OpenAsked());
        keys.action("app.settings", "Settings", Shortcut.of(Key.COMMA, Modifier.CONTROL))
                .sends(windows, WindowCommand.show(SettingsPage.KEY));
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
        TerminalScreen screen = new TerminalScreen(gui, glyphs, Type.MONO, prefs.fontPx())
                .name("terminal-" + m.id()).pseudoconsole();
        screens.put(m.id(), screen);
        byAddress.put(screen.address(), m.id());
        headers.put(m.id(), m.header());
        order.add(m.id());
        Band band = band(m.id());
        bands.put(m.id(), band);
        byClose.put(band.close().address(), m.id());
        Node page = gui.column().width(Length.FILL).height(Length.FILL).children(band.row(), screen.node());
        tabs.add(m.header(), page);
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
        Band band = bands.remove(m.id());
        if (band != null) {
            byClose.remove(band.close().address());
        }
        noticing.remove(m.id());
        tabs.remove(at);
        screen.close();
    }

    /** The settings, as the frame says them: every screen's type size, and how the bar changes tabs. */
    @Subscribe
    public void prefs(Prefs m) {
        Preferences next = m.prefs();
        if (next.fontPx() != prefs.fontPx()) {
            for (TerminalScreen s : screens.values()) {
                s.fontPx(next.fontPx());
            }
        }
        if (next.motion() != prefs.motion()) {
            ui.motion(next.motion());
        }
        prefs = next;
    }

    /** A tab's notice band: one line in the danger colour, and a × that puts it away. Hidden until it says something. */
    private Band band(long id) {
        Node text = gui.text("").textColor(gui.theme().color(Role.DANGER)).width(Length.grow(1f));
        IconButton close = new IconButton(gui, Icon.CLOSE, "Dismiss").name("notice-close-" + id);
        Node row = gui.row().gap(Length.dp(8)).alignItems(AlignItems.CENTER).width(Length.FILL)
                .padding(Length.dp(4), Length.dp(10)).background(gui.theme().color(Role.PANEL))
                .visible(false).children(text, close.node());
        return new Band(row, text, close);
    }

    /** What the band above a tab's terminal says; blank puts it away. */
    @Subscribe
    public void notice(Notice m) {
        Band band = bands.get(m.id());
        if (band != null) {
            band.text().text(m.text());
            band.row().visible(!m.text().isBlank());
            if (m.text().isBlank()) {
                noticing.remove(m.id());
            } else {
                noticing.add(m.id());
            }
        }
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
        if (inserts.containsKey(m.id())) {
            long seq = outputs.merge(m.id(), 1L, Long::sum);
            java.util.Optional.ofNullable(waits.remove(m.id())).ifPresent(dev.vexelray.framework.core.Timer.Scheduled::cancel);
            waits.put(m.id(), timer.after(PROMPT_QUIET, Quiet.TOPIC, new Quiet(m.id(), seq)));
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
            if (m.typed() && touchedTabs.add(id)) {
                touched.send(new Touched(id));
                inserts.remove(id);   // the person typed first: text arriving in the middle of theirs would be nobody's line
            }
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

    // ------------------------------------------------------------------ --insert-path

    /**
     * Text to type on a tab's input line once its program has drawn its prompt: once it has written and then been quiet
     * for {@link #PROMPT_QUIET} (0.1's rule), and only if the person has typed nothing there first.
     */
    @Subscribe
    public void typeInto(TypeInto m) {
        if (!touchedTabs.contains(m.id()) && !InsertText.unsafe(m.text())) {
            inserts.put(m.id(), m.text());
        }
    }

    /**
     * A tab's program has been quiet since an output: if none came since, its prompt is drawn, so the text goes on its
     * input line after a space, as a paste when the shell asked for bracketed paste, with the cursor taken home so what
     * the person types next is the command it is an argument of. Never a line end: running it is the person's Enter.
     */
    @Subscribe
    public void quiet(Quiet m) {
        TerminalScreen screen = screens.get(m.id());
        String text = inserts.get(m.id());
        if (text == null || screen == null || outputs.getOrDefault(m.id(), 0L) != m.seq()) {
            return;
        }
        inserts.remove(m.id());
        outputs.remove(m.id());
        waits.remove(m.id());
        typed.send(new Typed(m.id(), InsertText.typed(text, screen.terminal().bracketedPaste())));
        typed.send(new Typed(m.id(), screen.cursorKey('H', 1)));
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
        } else if (m.action().equals(ENV_TAB.id()) || m.action().equals(ENV_WORKSPACE.id())) {
            editEnv.send(new EditEnv(id, m.action().equals(ENV_TAB.id())));
        }
    }

    /** The + or the pill. */
    @Subscribe
    public void pressed(Button.Pressed m) {
        Long dismissed = byClose.get(m.button());
        if (dismissed != null) {
            bands.get(dismissed).row().visible(false);
            noticing.remove(dismissed);
            return;
        }
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

    /** Whether a tab's notice band is showing: for a test. */
    boolean noticing(long id) {
        return noticing.contains(id);
    }

    /** Which tab's band a × address dismisses: for a test. */
    String noticeClose(long id) {
        Band band = bands.get(id);
        return band == null ? "" : band.close().address();
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

    /**
     * A step up ({@code 1}) or down ({@code -1}) the sizes, or back to the first run's ({@code 0}): asked of the frame,
     * which saves it, and shown when it says so ({@link #prefs}), as a change in the settings window is.
     */
    void fontSize(int direction) {
        changePrefs.send(new ChangePrefs(prefs.stepped(direction)));
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
        ui.menu(on, intents, windows, openAsked, editWorkspaces);
        TerminalScreen s = front();
        if (s != null) {
            gui.focus(s.node());
        }
    }
}
