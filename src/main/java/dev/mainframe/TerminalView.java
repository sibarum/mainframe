package dev.mainframe;

import dev.sibarum.suite.tty.ConPty;
import dev.sibarum.suite.tty.Pump;
import dev.sibarum.suite.tty.Terminal;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.GlyphLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.function.BooleanSupplier;

/**
 * One tab's terminal: a shell on a {@link ConPty}, its output on a {@link TerminalScreen}, and the keyboard and
 * mouse wired back to the shell.
 *
 * <p>The screen is vex-suite-common's, shared with Vex's build console: the cell grid, scrolling, selecting and
 * copying. What is here is what makes it MainFrame's: a shell behind it, the window's chords, <i>Remap
 * keybindings</i>, the notice over it, and typing a path once the prompt is drawn.
 *
 * <h2>Threads</h2>
 *
 * <p>The pty's reader thread feeds the terminal (whose methods are synchronized) and marks the picture stale; the
 * frame loop's {@link #tick}, through {@link Terminals}' {@code @BeforeFrame}, turns a stale picture into a new one.
 * The loop parks when nothing is due, so every way the picture goes stale also wakes it ({@link Hooks#wake}): a
 * shell that writes while the window is idle would otherwise be drawn on the next mouse move.
 */
final class TerminalView implements AutoCloseable {

    private static final Color NOTICE_BG = Color.rgb(0x4A3A14);
    private static final Color NOTICE_INK = Color.rgb(0xF2E6C9);

    /** The prompt counts as drawn once the shell has been quiet this long after writing. */
    private static final long PROMPT_QUIET_NANOS = 300_000_000L;

    /** What a tab's terminal asks of the window it lives in. */
    interface Hooks {

        void newTab();

        /** Close {@code view} now. */
        void closeTab(TerminalView view);

        /** Close {@code view} once the user says so. */
        void askClose(TerminalView view);

        /** Open a folder or a workspace: what Ctrl+O asks for. */
        void open();

        void cycle(int by);

        void rename(TerminalView view);

        /** Wake the frame loop: this terminal has something new to draw, or has ended. */
        void wake();
    }

    final long id;
    private final Gui gui;
    private final Hooks hooks;
    private final BooleanSupplier remapKeys;
    /** The cell grid: what draws, takes focus and keys. */
    private final TerminalScreen screen;
    private final Node node;
    /** The tab's page: the grid, with a notice above it while there is one. */
    private final Node frame;
    private volatile Node notice;
    private final Terminal term;
    private final ConPty pty;
    private volatile boolean exited;
    private volatile String pendingTitle;
    private volatile String shellTitle = "Shell";
    private volatile String customTitle;
    /** When the shell last wrote, in nanos; zero until it first does. */
    private volatile long lastOutput;
    /** Text to type once the shell has drawn its prompt (see {@link #typeWhenReady}); null when there is none. */
    private volatile String pendingType;
    /** Whether the user has typed or pasted anything into this tab's shell (see {@link #touched}). */
    private volatile boolean touched;

    /**
     * Starts {@code commandLine} on a pseudoconsole of {@code cols} by {@code rows}, in {@code cwd} (null for
     * MainFrame's own directory).
     */
    TerminalView(long id, Gui gui, GlyphLayout glyphs, String commandLine, String cwd, int cols, int rows,
                 float fontPx, BooleanSupplier remapKeys, Hooks hooks) {
        this.id = id;
        this.gui = gui;
        this.remapKeys = remapKeys;
        this.hooks = hooks;

        this.pty = ConPty.start(commandLine, cols, rows, cwd);
        this.term = new Terminal(cols, rows, new Terminal.Host() {
            @Override
            public void reply(String s) {
                pty.write(s);
            }

            @Override
            public void title(String title) {
                pendingTitle = title;
            }
        });
        this.screen = new TerminalScreen(gui, glyphs, Type.MONO, term, fontPx, hooks::wake)
                .onFit(pty::resize);
        this.node = screen.node();
        this.frame = gui.column().width(Length.FILL).height(Length.FILL).children(node);
        wire();
        Pump.start(Pump.of(pty), term, () -> {
            lastOutput = System.nanoTime();
            screen.invalidate();
        }, () -> {
            exited = true;
            hooks.wake();
        }, "pty-reader-" + id);
    }

    Node node() {
        return frame;
    }

    /**
     * Whether the user has sent this tab's shell anything: a key, a paste. What MainFrame answers the shell on its
     * own, and what {@link #typeWhenReady} types, do not count.
     */
    boolean touched() {
        return touched;
    }

    /**
     * Type {@code text} on the input line, after a space and with the cursor taken home, so what the user types next
     * is the command it is an argument of. Nothing is typed until the shell has written and then been quiet for a
     * moment, which is its prompt drawn; and nothing at all if the user types first, since text arriving in the
     * middle of their own would be a line neither of them wrote. Never a line end: running it is the user's Enter.
     *
     * <p>The caller vouches for {@code text} ({@link InsertPath}); this refuses a control character regardless. The
     * wait is a thread of its own rather than a frame's work, because the frame loop parks while nothing changes,
     * and a quiet shell is exactly nothing changing.
     */
    void typeWhenReady(String text) {
        if (InsertPath.hasControl(text)) {
            throw new IllegalArgumentException("not typing a control character");
        }
        pendingType = text;
        Thread.ofVirtual().name("mainframe-type-when-ready").start(() -> {
            try {
                while (pendingType != null && !exited) {
                    Thread.sleep(50);
                    typePending();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void typePending() {
        String text = pendingType;
        long last = lastOutput;
        if (text == null || last == 0 || System.nanoTime() - last < PROMPT_QUIET_NANOS) {
            return;
        }
        pendingType = null;
        String typed = " " + text;
        write(term.bracketedPaste() ? "\u001b[200~" + typed + "\u001b[201~" : typed);
        write(screen.cursorKey('H', 1));
    }

    /** Show {@code text} above the terminal until the user clicks it or presses Enter. */
    void notice(String text) {
        Node n = gui.text(text).width(Length.FILL).padding(Length.rem(0.5f), Length.rem(0.75f))
                .font(Type.UI).textSize(Type.BODY).textColor(NOTICE_INK).background(NOTICE_BG);
        gui.landmark(Landmarks.NOTICE, n);
        gui.onClick(n, this::dismissNotice);
        dismissNotice();
        notice = n;
        frame.insert(n, 0);
    }

    private void dismissNotice() {
        Node n = notice;
        notice = null;
        if (n != null) {
            n.remove();
        }
    }

    boolean exited() {
        return exited;
    }

    void focus() {
        gui.focus(node);
    }

    /** Change the type size: the cell grid is remeasured and the shell is told its new dimensions. */
    void fontPx(float size) {
        screen.fontPx(size);
    }

    /** What the tab's header says: the name the user gave it, else whatever the shell last called itself. */
    String displayTitle() {
        String c = customTitle;
        return c != null ? c : shellTitle;
    }

    void shellTitle(String title) {
        shellTitle = title;
    }

    /** A blank name hands the header back to the shell. */
    void customTitle(String title) {
        customTitle = title == null || title.isBlank() ? null : title.strip();
    }

    int cols() {
        return screen.cols();
    }

    int rows() {
        return screen.rows();
    }

    /** Takes the pending title, if the shell has set one since last asked. */
    String takeTitle() {
        String t = pendingTitle;
        pendingTitle = null;
        return t;
    }

    /** Only the tab on show draws; a background tab keeps reading its shell and draws when it comes back. */
    void active(boolean on) {
        screen.active(on);
    }

    @Override
    public void close() {
        screen.close();
        pty.close();
    }

    /** On the main thread, once a frame. */
    void tick() {
        screen.tick();
    }

    // -- input ------------------------------------------------------------------------------------------------

    private void wire() {
        gui.onCharUi(node, cp -> {
            if (cp < 0x20 || cp == 0x7f) {
                return;
            }
            send(new String(Character.toChars(cp)));
        });
        gui.onKeyUi(node, this::key);
        gui.claimUi(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> send("\t"));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> send("\u001b[Z"));
        gui.onContextClick(node, c -> {
            if (screen.hasSelection()) {
                screen.copySelection();
            } else {
                paste();
            }
        });
        gui.claimUi(node, Shortcut.of(Key.T, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED, hooks::newTab);
        gui.claimUi(node, Shortcut.of(Key.W, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                () -> hooks.closeTab(this));
        gui.claimUi(node, Shortcut.of(Key.R, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                () -> hooks.rename(this));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.CONTROL), ClaimScope.FOCUSED, () -> hooks.cycle(1));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                () -> hooks.cycle(-1));
        // Claimed here as well as by the window (Terminals), which takes them wherever else the keyboard is: a
        // focused claim outranks the window's, so these three still reach the shell when Remap keybindings is off.
        remappable(Key.T, hooks::newTab);
        remappable(Key.W, () -> hooks.askClose(this));
        remappable(Key.O, hooks::open);
    }

    /** Ctrl+{@code k}: {@code action} with <i>Remap keybindings</i> on, the shell's control character with it off. */
    private void remappable(Key k, Runnable action) {
        gui.claimUi(node, Shortcut.of(k, Modifier.CONTROL), ClaimScope.FOCUSED, () -> {
            if (remapKeys.getAsBoolean()) {
                action.run();
            } else {
                send(String.valueOf((char) (k.name().charAt(0) - 'A' + 1)));
            }
        });
    }

    private void key(KeyEvent e) {
        Key k = e.key();
        boolean ctrl = e.has(Modifier.CONTROL), alt = e.has(Modifier.ALT), shift = e.has(Modifier.SHIFT);
        if (ctrl && shift && k == Key.C) {
            screen.copySelection();
            return;
        }
        if (ctrl && shift && k == Key.V) {
            paste();
            return;
        }
        if (remapKeys.getAsBoolean() && !alt && !shift && remapped(k, ctrl)) {
            return;
        }
        if (shift && k == Key.PAGE_UP) {
            screen.page(1);
            return;
        }
        if (shift && k == Key.PAGE_DOWN) {
            screen.page(-1);
            return;
        }
        String out = screen.keySequence(k, ctrl, alt, shift);
        if (out != null) {
            send(out);
        }
    }

    /**
     * With <i>Remap keybindings</i> on: Ctrl+C and Ctrl+V are the clipboard's, which leaves the shell without its
     * interrupt, so Esc sends it. True when the key was one of these. Ctrl+T, Ctrl+W and Ctrl+O, the window's when
     * remapped, are claims ({@link #remappable}).
     */
    private boolean remapped(Key k, boolean ctrl) {
        if (ctrl && k == Key.C) {
            screen.copySelection();
        } else if (ctrl && k == Key.V) {
            paste();
        } else if (!ctrl && k == Key.ESCAPE) {
            send("\u0003");
        } else {
            return false;
        }
        return true;
    }

    /** What the user typed or pasted: it wins over anything waiting to be typed, and Enter puts a notice away. */
    private void send(String s) {
        touched = true;
        pendingType = null;
        if (s.indexOf('\r') >= 0) {
            dismissNotice();
        }
        write(s);
    }

    private void write(String s) {
        screen.snapToBottom();
        pty.write(s);
    }

    private void paste() {
        String text = gui.clipboard().get();
        if (text == null || text.isEmpty()) {
            return;
        }
        send(screen.pasteText(text));
    }
}
