package dev.mainframe;

import dev.mainframe.Intent.Command;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Tabs;
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
 * <p>Which tabs there are, what each runs and when its shell starts are the {@code Frame}'s, in {@code -core}. This
 * turns {@link AddTab} into a {@link TerminalScreen} in the bar and {@link CloseTab} into one fewer, hands each tab's
 * {@link Output} to its screen, and gives a key-map command to the screen in front.
 *
 * <h2>It forwards the screens' messages with the tab's id</h2>
 *
 * <p>A screen publishes what goes to its program ({@code SENT}) and its size in cells ({@code FITTED}) addressed by
 * the window, not by itself, so a part keyed per tab cannot hear them as they are. The view knows which screen is
 * which tab, and sends each on as {@link Typed} or {@link Resized}, keyed by the tab's id, which each tab's
 * {@code Shell} hears (docs/framework-notes.md, FN-2).
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, so the processor sees every message the view can send without
 * reading a line of its body (ROADMAP fork 5).
 */
@dev.vexelray.framework.api.View
final class View {

    private final Gui gui;
    private final Tabs tabs;
    private final Sender<Resized> resized;
    private final Sender<Typed> typed;
    private final Sender<Intent> intents;

    /** The mono face's layout, one for every tab: it is only read. */
    private final GlyphLayout glyphs;

    /** The tabs' screens, in the bar's order, and which tab each screen's address is. */
    private final List<Long> order = new ArrayList<>();
    private final Map<Long, TerminalScreen> screens = new HashMap<>();
    private final Map<String, Long> byAddress = new HashMap<>();

    /** Where on {@link Type#TERMINAL_PX} the terminals' type is. */
    private int size = Type.TERMINAL_NORMAL;

    View(Gui gui, Keymap keys, Ui ui, Sender<Resized> resized, Sender<Typed> typed, Sender<Intent> intents) {
        this.gui = gui;
        this.tabs = ui.tabs();
        this.resized = resized;
        this.typed = typed;
        this.intents = intents;
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        commands(keys);
    }

    /**
     * Every command the keyboard reaches, as a declaration: a stable id, what the person calls it, the chords it starts
     * with, and the {@link Intent} it sends. The chords are the person's to change from the title bar's key button. A
     * chord held here outranks the terminal's typing, which is why Ctrl+C copies and Esc interrupts (0.1's README,
     * <i>Keys</i>): unbind one and the program has it again.
     */
    private void commands(Keymap keys) {
        command(keys, "terminal.copy", "Copy the selection, or interrupt", Command.COPY,
                Shortcut.of(Key.C, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.C, Modifier.CONTROL));
        command(keys, "terminal.paste", "Paste", Command.PASTE,
                Shortcut.of(Key.V, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.V, Modifier.CONTROL));
        command(keys, "terminal.interrupt", "Interrupt", Command.INTERRUPT, Shortcut.of(Key.ESCAPE));
        command(keys, "terminal.page-back", "Scroll back a page", Command.PAGE_BACK,
                Shortcut.of(Key.PAGE_UP, Modifier.SHIFT));
        command(keys, "terminal.page-on", "Scroll on a page", Command.PAGE_ON,
                Shortcut.of(Key.PAGE_DOWN, Modifier.SHIFT));
        command(keys, "view.larger", "Larger text", Command.LARGER,
                Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL));
        command(keys, "view.smaller", "Smaller text", Command.SMALLER,
                Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL));
        command(keys, "view.normal", "Normal text size", Command.NORMAL,
                Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL));
    }

    private void command(Keymap keys, String id, String label, Command command, Shortcut... chords) {
        keys.action(id, label, chords).sends(intents, new Intent(command));
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
        order.add(m.id());
        tabs.add(m.header(), screen.node());
    }

    @Subscribe
    public void front(Front m) {
        int at = order.indexOf(m.id());
        TerminalScreen screen = screens.get(m.id());
        if (at < 0 || screen == null) {
            return;
        }
        tabs.select(at);
        gui.landmark(Landmarks.TERMINAL, screen.node());
        gui.focus(screen.node());
    }

    @Subscribe
    public void closeTab(CloseTab m) {
        int at = order.indexOf(m.id());
        TerminalScreen screen = screens.remove(m.id());
        if (at < 0 || screen == null) {
            return;
        }
        order.remove(at);
        byAddress.remove(screen.address());
        tabs.remove(at);
        screen.close();
    }

    @Subscribe
    public void output(Output m) {
        TerminalScreen screen = screens.get(m.id());
        if (screen != null) {
            screen.feed(m.text());
        }
    }

    // ------------------------------------------------------------------ what the screens say, sent on with the tab

    @Subscribe
    public void sent(TerminalScreen.Sent m) {
        Long id = byAddress.get(m.terminal());
        if (id != null) {
            typed.send(new Typed(id, m.text()));
        }
    }

    @Subscribe
    public void fitted(TerminalScreen.Fitted m) {
        Long id = byAddress.get(m.terminal());
        if (id != null) {
            resized.send(new Resized(id, m.cols(), m.rows()));
        }
    }

    /** A tab's screen, or null: for a test reading the view on the GUI thread. */
    TerminalScreen screen(long id) {
        return screens.get(id);
    }

    // ------------------------------------------------------------------ what the person did, by chord

    /** The screen in front, or null with no tabs. */
    private TerminalScreen front() {
        int at = tabs.selected();
        return at >= 0 && at < order.size() ? screens.get(order.get(at)) : null;
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
        int at = tabs.selected();
        if (at >= 0 && at < order.size()) {
            typed.send(new Typed(order.get(at), "\u0003"));
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
}
