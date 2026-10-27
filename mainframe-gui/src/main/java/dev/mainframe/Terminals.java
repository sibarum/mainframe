package dev.mainframe;

import dev.mainframe.core.AppSettings;
import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.Gone;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenAsked;
import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Atchung;
import sibarum.atchung.Subscription;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The window's tabs: one {@link TerminalView} per tab, the bar that selects between them, and renaming and cycling
 * them.
 *
 * <p>Each tab owns its own shell. Which tabs there are is the {@code Frame}'s to decide, in {@code -core}: the
 * {@link View} adds one when told to ({@link #add}), and this says when one has gone ({@link Gone}), whoever closed
 * it: the header menu's Close, the frame, or a shell that exited. Only the selected tab draws (see
 * {@link TerminalView#active}); the others keep reading their shells.
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
 * views here is edited in exactly one place whoever asked for it. The lock is never held while calling into
 * {@code Tabs}: its header menu reaches back in through {@code onRemove} while holding its own. The lock is there
 * because the frame loop reads the tabs to draw them and the bar reports a removal on its own thread; what the
 * application decides about them all arrives on the view's lane.
 */
@Publishes({Intent.TOPIC, Messages.NEW_TAB, Messages.OPEN_ASKED, Messages.GONE})
final class Terminals implements TerminalView.Hooks, WakeSource, AutoCloseable {

    private static final Log LOG = Log.of("mainframe.tabs");
    private static final String PLUS = " + ";
    private static final String PASSTHROUGH = " Keys: MainFrame ";
    private static final String PASSTHROUGH_ON = " Keys: all to shell ";

    private final Gui gui;
    private final AppSettings settings;
    private final Motion motion;
    private final Atchung bus;
    private final GlyphLayout glyphs;
    private final Tabs tabs;
    /** Parallel to the bar's tabs. Guarded by {@code this}. */
    private final List<TerminalView> views = new ArrayList<>();
    /** The loop's wake, once there is a loop; before that a frame is coming anyway. */
    private volatile Runnable wake = () -> { };
    private Node plus;
    /** The switch after the +: while it is on, the key map is paused and every chord is the shell's. */
    private Node passthrough;
    private final Keymap keys;
    private Rename renaming;
    /** The zoom the tabs' type was last sized for (see {@link #fontPx}). Written by the frame loop. */
    private volatile float zoom;
    /** The key map's terminal commands, which move to whichever tab is selected ({@link #followSelection}). */
    private volatile List<Action> terminalKeys = List.of();

    /** The header being edited: the field standing in for it, and what to undo when editing ends. */
    private record Rename(TerminalView view, TextField field, Node header, Subscription blur) {
    }

    Terminals(Gui gui, Motion motion, AppSettings settings, Keymap keys, Atchung bus) {
        this.gui = gui;
        this.motion = motion;
        this.settings = settings;
        this.keys = keys;
        this.bus = bus;
        // The mono face of the atlas the framework bakes; one layout, shared by every tab, as it is only read.
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        this.tabs = new Tabs(gui).onRemove(this::removed).onSelect(this::selected)
                .onContextMenu((index, menu) -> {
                    TerminalView v = at(index);
                    if (v != null) {
                        menu.item("Rename", () -> rename(v));
                        if (v.row()) {
                            menu.item("Edit environment…", () -> tell(Intent.Command.EDIT_TAB_ENV, v.id));
                        }
                        if (v.ofWorkspace()) {
                            menu.item("Edit workspace environment…", () -> tell(Intent.Command.EDIT_WORKSPACE_ENV, v.id));
                        }
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

    /** The terminal on show, or null when there is none. */
    synchronized TerminalView current() {
        int i = tabs.selected();
        return i >= 0 && i < views.size() ? views.get(i) : null;
    }

    /** The tab {@code id}, or null when it is not open. */
    synchronized TerminalView byId(long id) {
        for (TerminalView v : views) {
            if (v.id == id) {
                return v;
            }
        }
        return null;
    }

    void focus() {
        TerminalView v = current();
        if (v != null) {
            v.focus();
        }
    }

    /**
     * The view's word on the tab whatever is opened next may take the place of: the only tab, while nobody has sent
     * its shell anything; else {@code NO_TAB}. Whether it is the plain tab the launch opened is the frame's to know.
     */
    synchronized long spare() {
        return views.size() == 1 && !views.getFirst().touched() ? views.getFirst().id : Messages.NO_TAB;
    }

    /**
     * A tab, as the frame describes it: its shell started in its folder, named, with its notice and what it is to
     * type, and selected if it is to be in front. Returns false, having opened nothing, when the shell cannot be
     * started; the caller says so.
     */
    boolean add(AddTab m) {
        TerminalView cur = current();
        TerminalView v;
        try {
            v = new TerminalView(m.id(), gui, glyphs, m.command(), m.dir().toString(),
                    cur == null ? 100 : cur.cols(), cur == null ? 30 : cur.rows(), fontPx(), this);
        } catch (RuntimeException e) {
            LOG.warn("could not start {}", m.command(), e);
            return false;
        }
        v.scripts(m.workspace(), m.row());
        if (!m.notice().isBlank()) {
            v.notice(m.notice());
        }
        if (!m.type().isBlank()) {
            v.typeWhenReady(m.type());
        }
        if (!m.title().isBlank()) {
            v.customTitle(m.title());
        }
        int index;
        synchronized (this) {
            views.add(v);
            index = views.size() - 1;
        }
        tabs.add(v.displayTitle(), v.node());
        gui.onDoubleClick(tabs.header(index), () -> rename(v));
        if (m.front()) {
            tabs.select(index);
            v.focus();
        }
        movePlus();
        return true;
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
     * opening, Ctrl+C over a selection and Ctrl+V for the clipboard, and Esc for the interrupt, which older key maps
     * bound it to. Unbound, each reaches the shell again; {@link #handShellKeysBack} does that for all of them at once.
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
     *
     * <p>Opening and closing tabs are the frame's to decide, so those commands say what was asked and nothing more:
     * a message to the frame, or an {@link Intent} to the view when the view's word on a tab goes with it.
     */
    private void declareKeys(Keymap keys) {
        keys.action("ui.zoom-in", "Larger text and controls", ctrl(Key.EQUAL), ctrl(Key.NUMPAD_ADD))
                .on(gui::zoomIn);
        keys.action("ui.zoom-out", "Smaller text and controls", ctrl(Key.MINUS), ctrl(Key.NUMPAD_SUBTRACT))
                .on(gui::zoomOut);
        keys.action("ui.zoom-reset", "Back to normal size", ctrl(Key.DIGIT_0), ctrl(Key.NUMPAD_0))
                .on(gui::resetZoom);
        keys.action("tab.new", "New tab", ctrlShift(Key.T), ctrl(Key.T)).on(this::newTab);
        keys.action("tab.close", "Close the tab, after asking", ctrl(Key.W))
                .on(() -> withCurrent(v -> tell(Intent.Command.CLOSE, v.id)));
        keys.action("tab.close-now", "Close the tab without asking", ctrlShift(Key.W))
                .on(() -> withCurrent(v -> tell(Intent.Command.CLOSE_NOW, v.id)));
        keys.action("tab.rename", "Rename the tab", ctrlShift(Key.R)).on(() -> withCurrent(this::rename));
        keys.action("tab.next", "Next tab", ctrl(Key.TAB)).on(() -> cycle(1));
        keys.action("tab.previous", "Previous tab", ctrlShift(Key.TAB)).on(() -> cycle(-1));
        keys.action("file.open", "Open a workspace or a folder", ctrl(Key.O))
                .on(() -> bus.publish(Messages.OPEN_ASKED_TOPIC, new OpenAsked()));

        // Placed on the tab bar, which never has the keyboard, until there is a terminal to follow.
        terminalKeys = List.of(
                keys.action("term.copy", "Copy the selection, else interrupt the shell", ctrlShift(Key.C),
                                ctrl(Key.C))
                        .on(() -> withCurrent(TerminalView::copyOrInterrupt)).ordered(),
                keys.action("term.paste", "Paste", ctrlShift(Key.V), ctrl(Key.V))
                        .on(() -> withCurrent(TerminalView::paste)).ordered(),
                // Unbound: Ctrl+C with nothing selected is the interrupt, so Esc stays the shell's.
                keys.action("term.interrupt", "Interrupt (what Ctrl+C sends a shell)")
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

    private void tell(Intent.Command command, long id) {
        bus.publish(Intent.INTENTS, new Intent(command, id, ""));
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

    /** The + button and Ctrl+T: the frame knows which workspace the window is, whose script a new tab sources. */
    void newTab() {
        bus.publish(Messages.NEW_TAB_TOPIC, new NewTab());
    }

    /** Close the tab {@code id}, if it is still open. */
    void close(long id) {
        TerminalView v = byId(id);
        if (v != null) {
            closeTab(v);
        }
    }

    private void closeTab(TerminalView view) {
        int i;
        synchronized (this) {
            i = views.indexOf(view);
        }
        if (i >= 0) {
            tabs.remove(i);
        }
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

    /** Runs inline inside {@link Tabs#remove}, with the bar already short one tab. The frame hears it has gone. */
    private void removed(int index) {
        finishRename(false);
        TerminalView gone;
        synchronized (this) {
            gone = index < views.size() ? views.remove(index) : null;
        }
        if (gone != null) {
            gone.close();
            bus.publish(Messages.GONE_TOPIC, new Gone(gone.id));
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
     * The new-tab button and the passthrough switch live in the bar after the headers. Headers are appended, so each
     * new tab lands behind them, and the two are moved back to the end. Moved, not rebuilt: append of a child moves
     * it with its handlers, and a rebuilt one would leave the old fading where it stood while the new one arrived
     * after the new header, two of each for a moment under the window's motion.
     */
    private void movePlus() {
        if (plus == null) {
            plus = gui.text(PLUS).height(Length.FILL);
            gui.landmark(Landmarks.NEW_TAB, plus);
            gui.onClick(plus, this::newTab);
            passthrough = gui.text(PASSTHROUGH).height(Length.FILL).role("switch");
            gui.landmark(Landmarks.PASSTHROUGH, passthrough);
            gui.onClick(passthrough, this::togglePassthrough);
            showPassthrough();
        }
        tabs.bar().append(plus);
        tabs.bar().append(passthrough);
    }

    /**
     * Every chord to the shell, or back to MainFrame's commands. The way back is this switch, by mouse: with the key
     * map paused, no chord could reach a command that resumes it.
     */
    private void togglePassthrough() {
        keys.paused(!keys.paused());
        showPassthrough();
        withCurrent(TerminalView::focus);
    }

    /** Lit in the accent while on, so a window whose shortcuts have stopped working says why. */
    private void showPassthrough() {
        boolean on = keys.paused();
        passthrough.text(on ? PASSTHROUGH_ON : PASSTHROUGH)
                .background(gui.theme().color(on ? Role.ACCENT : Role.NONE))
                .textColor(gui.theme().color(on ? Role.PAGE : Role.INK));
    }
}
