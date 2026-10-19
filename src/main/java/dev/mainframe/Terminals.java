package dev.mainframe;

import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The window's tabs: one {@link TerminalView} per tab, the bar that selects between them, and what opens, closes,
 * renames and cycles them.
 *
 * <p>Each tab owns its own shell. A tab whose shell exits closes itself, and closing the last tab closes the window.
 * Only the selected tab draws (see {@link TerminalView#active}); the others keep reading their shells.
 *
 * <h2>The frame</h2>
 *
 * <p>{@link #tick} is this application's one piece of per-frame work, in {@code FrameStage.APP}: draw what a shell
 * wrote, retitle a tab its shell renamed, and close a tab whose shell has ended. The loop parks while nothing is due,
 * so this is also a {@link WakeSource}: a shell writing into an idle window wakes it.
 *
 * <h2>One place a tab leaves</h2>
 *
 * <p>Every structural change goes through {@link Tabs}, which reports a removal in {@code onRemove}, so the list of
 * views here is edited in exactly one place whoever asked for it: the header menu's Close, a shortcut, or a shell that
 * exited. The lock is never held while calling into {@code Tabs}: its header menu reaches back in through
 * {@code onRemove} while holding its own.
 */
final class Terminals implements TerminalView.Hooks, WakeSource, AutoCloseable {

    private static final Log LOG = Log.of("mainframe.tabs");
    private static final String PLUS = " + ";

    /** A tab to open: the folder its shell starts in (null: the new tab directory), and its name (null: the folder's). */
    record Opening(Path dir, String title) {
    }

    private final Gui gui;
    private final AppSettings settings;
    private final Motion motion;
    private final Dialogs dialogs;
    private final Window window;
    private final GlyphLayout glyphs;
    private final Tabs tabs;
    private final AtomicLong ids = new AtomicLong();
    /** Parallel to the bar's tabs. Guarded by {@code this}. */
    private final List<TerminalView> views = new ArrayList<>();
    /** The plain tab opened because the launch asked for none, while it may still be there (see {@link #spare}). */
    private volatile TerminalView defaultTab;
    /** The loop's wake, once there is a loop; before that a frame is coming anyway. */
    private volatile Runnable wake = () -> { };
    private volatile Runnable open = () -> { };
    private Node plus;
    private Rename renaming;
    /** The zoom the tabs' type was last sized for (see {@link #fontPx}). Written by the frame loop. */
    private volatile float zoom;
    /** The key map's terminal commands, which move to whichever tab is selected ({@link #followSelection}). */
    private volatile List<Action> terminalKeys = List.of();

    /** The header being edited: the field standing in for it, and what to undo when editing ends. */
    private record Rename(TerminalView view, TextField field, Node header, Subscription blur) {
    }

    Terminals(Gui gui, Motion motion, AppSettings settings, Dialogs dialogs, Window window, Keymap keys) {
        this.gui = gui;
        this.motion = motion;
        this.settings = settings;
        this.dialogs = dialogs;
        this.window = window;
        // The mono face of the atlas the framework bakes; one layout, shared by every tab, as it is only read.
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        this.tabs = new Tabs(gui).onRemove(this::removed).onSelect(this::selected)
                .onContextMenu((index, menu) -> {
                    TerminalView v = at(index);
                    if (v != null) {
                        menu.item("Rename", () -> rename(v));
                    }
                });
        this.zoom = gui.zoom().value();
        tabs.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TABS, tabs.node());
        declareKeys(keys);
        applyMotion();
        settings.onChange(this::settingsChanged);
    }

    Node node() {
        return tabs.node();
    }

    /** What Ctrl+O runs: the window's to say. */
    void onOpen(Runnable open) {
        this.open = open;
    }

    /** The terminal on show, or null when there is none. */
    synchronized TerminalView current() {
        int i = tabs.selected();
        return i >= 0 && i < views.size() ? views.get(i) : null;
    }

    void focus() {
        TerminalView v = current();
        if (v != null) {
            v.focus();
        }
    }

    /**
     * Open {@code openings} as tabs, in order, with the first of them selected; with none, one plain tab in the new tab
     * directory.
     */
    void openAll(List<Opening> openings) {
        if (openings.isEmpty()) {
            newTab();
            defaultTab = current();
            return;
        }
        int first;
        synchronized (this) {
            first = views.size();
        }
        for (Opening o : openings) {
            openTab(o.dir(), o.title());
        }
        tabs.select(first);
        focus();
    }

    /**
     * The tab nobody has used, which whatever Ctrl+O opens may take the place of, or null. It is the plain tab the
     * launch opened, no other tab has been opened since, and the user has sent its shell nothing.
     */
    synchronized TerminalView spare() {
        TerminalView d = defaultTab;
        return d != null && views.size() == 1 && views.getFirst() == d && !d.touched() ? d : null;
    }

    /** Run {@code open}, and if it opened a tab, close the {@link #spare} tab there was before it. */
    void replacingSpare(Runnable open) {
        TerminalView spare = spare();
        open.run();
        if (spare != null && current() != spare) {
            closeTab(spare);
        }
    }

    /** A new tab whose shell starts in {@code dir}, named after the folder, selected and focused. */
    void openFolder(Path dir) {
        openTab(dir, null);
    }

    @Override
    public void onWake(Runnable wake) {
        this.wake = wake;
    }

    @Override
    public void wake() {
        wake.run();
    }

    /** Once a frame, on the main thread: draw, retitle, and close the tabs whose shells have ended. */
    @BeforeFrame
    void tick() {
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        float z = gui.zoom().value();
        if (z != zoom) {
            zoom = z;
            snapshot.forEach(v -> v.fontPx(fontPx()));
        }
        for (TerminalView v : snapshot) {
            v.tick();
            String title = v.takeTitle();
            if (title != null) {
                v.shellTitle(shorten(title));
                retitle(v);
            }
            if (v.exited()) {
                closeTab(v);
            }
        }
    }

    @Override
    public void close() {
        List<TerminalView> all;
        synchronized (this) {
            all = List.copyOf(views);
            views.clear();
        }
        all.forEach(TerminalView::close);
    }

    // -- keys -------------------------------------------------------------------------------------------------

    /**
     * The chords a shell has a use for that MainFrame's defaults take: Ctrl+T, Ctrl+W and Ctrl+O for tabs and
     * opening, Ctrl+C and Ctrl+V for the clipboard, and Esc for the interrupt Ctrl+C no longer sends. Unbound, each
     * reaches the shell again; {@link #handShellKeysBack} does that for all of them at once.
     */
    static final Set<Trigger> SHELL_KEYS = Set.of(ctrl(Key.T), ctrl(Key.W), ctrl(Key.O), ctrl(Key.C), ctrl(Key.V),
            Shortcut.of(Key.ESCAPE));

    /**
     * Every command, by name, on the chords the user gives it ({@code vexelray-gui/docs/reference/keymap.md}).
     *
     * <p>The window's apply application-wide, wherever the keyboard is: in a terminal, on a tab's header, in the
     * rename field. The terminal's apply only while the selected tab's terminal has the keyboard ({@link
     * #followSelection}): Ctrl+V in the rename field is the field's paste, and Esc there leaves it. Those that
     * write to the shell are {@code ordered}, so they land among the typed characters in the order they were pressed.
     *
     * <p>A chord no action holds is the shell's: the terminal sends whatever it spells
     * ({@link TerminalView}). So the way to give one back is to unbind it in the key editor.
     */
    private void declareKeys(Keymap keys) {
        keys.action("ui.zoom-in", "Larger text and controls", ctrl(Key.EQUAL), ctrl(Key.NUMPAD_ADD))
                .on(gui::zoomIn);
        keys.action("ui.zoom-out", "Smaller text and controls", ctrl(Key.MINUS), ctrl(Key.NUMPAD_SUBTRACT))
                .on(gui::zoomOut);
        keys.action("ui.zoom-reset", "Back to normal size", ctrl(Key.DIGIT_0), ctrl(Key.NUMPAD_0))
                .on(gui::resetZoom);
        keys.action("tab.new", "New tab", ctrlShift(Key.T), ctrl(Key.T)).on(this::newTab);
        keys.action("tab.close", "Close the tab, after asking", ctrl(Key.W)).on(() -> withCurrent(this::askClose));
        keys.action("tab.close-now", "Close the tab without asking", ctrlShift(Key.W))
                .on(() -> withCurrent(this::closeTab));
        keys.action("tab.rename", "Rename the tab", ctrlShift(Key.R)).on(() -> withCurrent(this::rename));
        keys.action("tab.next", "Next tab", ctrl(Key.TAB)).on(() -> cycle(1));
        keys.action("tab.previous", "Previous tab", ctrlShift(Key.TAB)).on(() -> cycle(-1));
        keys.action("file.open", "Open a workspace or a folder", ctrl(Key.O)).on(this::open);

        // Placed on the tab bar, which never has the keyboard, until there is a terminal to follow.
        terminalKeys = List.of(
                keys.action("term.copy", "Copy the selection", ctrlShift(Key.C), ctrl(Key.C))
                        .on(() -> withCurrent(TerminalView::copy)),
                keys.action("term.paste", "Paste", ctrlShift(Key.V), ctrl(Key.V))
                        .on(() -> withCurrent(TerminalView::paste)).ordered(),
                keys.action("term.interrupt", "Interrupt (what Ctrl+C sends a shell)", Shortcut.of(Key.ESCAPE))
                        .on(() -> withCurrent(TerminalView::interrupt)).ordered(),
                keys.action("term.page-up", "Scroll back a page", Shortcut.of(Key.PAGE_UP, Modifier.SHIFT))
                        .on(() -> withCurrent(v -> v.page(1))).ordered(),
                keys.action("term.page-down", "Scroll on a page", Shortcut.of(Key.PAGE_DOWN, Modifier.SHIFT))
                        .on(() -> withCurrent(v -> v.page(-1))).ordered());
        followSelection(tabs.node());
    }

    /**
     * Move the terminal's commands onto {@code grid}, the selected tab's cell grid, at {@link ClaimScope#FOCUSED}: a
     * place is one node, and only the selected tab's terminal can have the keyboard.
     */
    private void followSelection(Node grid) {
        for (Action a : terminalKeys) {
            a.within(gui, grid, ClaimScope.FOCUSED);
        }
    }

    /**
     * Unbind every {@link #SHELL_KEYS} chord, so each reaches the shell: what turning Remap keybindings off did
     * before the key map. Run once, for a user who had it off ({@link AppSettings#takeRemapOff}).
     */
    static void handShellKeysBack(Keymap keys) {
        for (Action a : keys.actions()) {
            List<Trigger> kept = a.chords().stream().filter(c -> !SHELL_KEYS.contains(c)).toList();
            if (kept.size() != a.chords().size()) {
                keys.bind(a, kept);
            }
        }
    }

    private static Shortcut ctrl(Key k) {
        return Shortcut.of(k, Modifier.CONTROL);
    }

    private static Shortcut ctrlShift(Key k) {
        return Shortcut.of(k, Modifier.CONTROL, Modifier.SHIFT);
    }

    private void withCurrent(java.util.function.Consumer<TerminalView> command) {
        TerminalView v = current();
        if (v != null) {
            command.accept(v);
        }
    }

    // -- settings --------------------------------------------------------------------------------------------

    private void settingsChanged() {
        applyMotion();
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        snapshot.forEach(v -> v.fontPx(fontPx()));
    }

    /**
     * The terminal's type size: the Font size setting, zoomed. The grid is a picture in pixels, which no
     * {@code Length} reaches, so without this Ctrl+= would grow the tab headers and leave the text it was pressed for.
     */
    private float fontPx() {
        return settings.fontPx() * zoom;
    }

    /**
     * Slide: the arriving tab travels a short way into place while the old one dissolves. Fade: the dissolve alone.
     * Short, because a terminal is something you type into the moment it appears.
     */
    private void applyMotion() {
        Tabs.TabTransition t = switch (settings.motion()) {
            case SLIDE -> Tabs.slide(motion.change);
            case FADE -> Tabs.crossfade(motion.change);
            case NONE -> Tabs.TabTransition.NONE;
        };
        tabs.transition(t);
    }

    // -- commands ---------------------------------------------------------------------------------------------

    void newTab() {
        openTab(null, null);
    }

    /**
     * A tab whose shell starts in {@code dir}, called {@code title} or else after the folder; a null dir is a plain
     * tab in the new tab directory ({@link AppSettings#tabDir}). A shell that cannot be started is said rather than
     * thrown, and opens no tab.
     */
    private void openTab(Path dir, String title) {
        defaultTab = null;   // any tab after the first, and the first is no longer spare
        TerminalView cur = current();
        TerminalView v;
        try {
            v = new TerminalView(ids.incrementAndGet(), gui, glyphs, settings.shellCommand(),
                    (dir == null ? settings.tabDir() : dir).toString(), cur == null ? 100 : cur.cols(), cur == null ? 30 : cur.rows(),
                    fontPx(), this);
        } catch (RuntimeException e) {
            LOG.warn("could not start {}", settings.shellCommand(), e);
            dialogs.info("Could not start the shell", settings.shellCommand() + ": " + e.getMessage());
            return;
        }
        if (title != null && !title.isBlank()) {
            v.customTitle(title.strip());
        } else if (dir != null) {
            v.customTitle(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
        }
        int index;
        synchronized (this) {
            views.add(v);
            index = views.size() - 1;
        }
        tabs.add(v.displayTitle(), v.node());
        gui.onDoubleClick(tabs.header(index), () -> rename(v));
        tabs.select(index);
        movePlus();
        v.focus();
    }

    void closeTab(TerminalView view) {
        int i;
        synchronized (this) {
            i = views.indexOf(view);
        }
        if (i >= 0) {
            tabs.remove(i);
        }
    }

    /** Ctrl+W: asks first, as closing a tab ends its shell and whatever it was running. Cancel goes back to it. */
    void askClose(TerminalView view) {
        dialogs.confirm("Close " + view.displayTitle() + "?", "Its shell ends, and anything running in it.",
                "Close", () -> closeTab(view), view::focus);
    }

    void open() {
        open.run();
    }

    void cycle(int by) {
        int n;
        synchronized (this) {
            n = views.size();
        }
        if (n > 1) {
            tabs.select(Math.floorMod(tabs.selected() + by, n));
        }
    }

    // -- Tabs callbacks ---------------------------------------------------------------------------------------

    /** Runs inline inside {@link Tabs#remove}, with the bar already short one tab. */
    private void removed(int index) {
        finishRename(false);
        TerminalView gone;
        boolean empty;
        synchronized (this) {
            gone = index < views.size() ? views.remove(index) : null;
            empty = views.isEmpty();
        }
        if (gone != null) {
            gone.close();
        }
        if (empty) {
            window.close();
        }
    }

    private void selected(int index) {
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            snapshot.get(i).active(i == index);
        }
        if (index >= 0 && index < snapshot.size()) {
            followSelection(snapshot.get(index).grid());
            snapshot.get(index).focus();
        }
    }

    private void retitle(TerminalView v) {
        int i;
        synchronized (this) {
            i = views.indexOf(v);
        }
        if (i >= 0) {
            tabs.title(i, v.displayTitle());
        }
    }

    private synchronized TerminalView at(int index) {
        return index >= 0 && index < views.size() ? views.get(index) : null;
    }

    // -- renaming ---------------------------------------------------------------------------------------------

    /**
     * Edit a tab's name in place: a text field takes the header's seat in the bar, and the header is hidden (not
     * removed, which would release its handlers) until editing ends. Enter or clicking away keeps the new name,
     * Escape keeps the old one, and a blank name gives the header back to the shell's own title.
     */
    void rename(TerminalView view) {
        finishRename(true);
        int i;
        synchronized (this) {
            i = views.indexOf(view);
        }
        if (i < 0) {
            return;
        }
        Node header = tabs.header(i);
        // An inline rename, gone once it commits: its typing is its own, so none of it is left in the window's
        // history pointing at a field that no longer exists.
        TextField field = new TextField(gui, view.displayTitle()).history(new History());
        field.node().width(Length.rem(12)).height(Length.FILL);
        Subscription blur = gui.bus().subscribe(gui.focusEvents(), e -> {
            if (!e.gained() && e.nodeId() == field.node().id()) {
                finishRename(true);
            }
        });
        synchronized (this) {
            renaming = new Rename(view, field, header, blur);
        }
        field.onSubmit(text -> finishRename(true));
        gui.claimUi(field.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> finishRename(false));
        header.visible(false);
        tabs.bar().insert(field.node(), i);
        gui.focus(field.node());
        field.select(0, field.text().length());
    }

    private void finishRename(boolean keep) {
        Rename r;
        synchronized (this) {
            r = renaming;
            renaming = null;   // first, so the focus change below is not mistaken for another way out
        }
        if (r == null) {
            return;
        }
        String text = r.field().text();
        r.blur().close();
        r.field().node().remove();
        r.field().close();
        r.header().visible(true);
        if (keep) {
            r.view().customTitle(text);
        }
        retitle(r.view());
        r.view().focus();
    }

    /** A shell reports its title as a path to its executable; a tab wants the name. */
    static String shorten(String title) {
        String t = title.strip();
        int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        if (slash >= 0 && slash < t.length() - 1) {
            t = t.substring(slash + 1);
        }
        if (t.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            t = t.substring(0, t.length() - 4);
        }
        return t.isEmpty() ? "Shell" : t;
    }

    /**
     * The new-tab button lives in the bar after the headers. Headers are appended, so each new tab lands behind
     * the button; removing the button releases its handlers, so it is rebuilt rather than moved.
     */
    private void movePlus() {
        if (plus != null) {
            plus.remove();
        }
        plus = gui.text(PLUS).height(Length.FILL);
        gui.landmark(Landmarks.NEW_TAB, plus);
        gui.onClick(plus, this::newTab);
        tabs.bar().append(plus);
    }
}
