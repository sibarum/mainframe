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

/**
 * One tab's terminal: a shell on a {@link ConPty}, its output on a {@link TerminalScreen}, and the keyboard and
 * mouse wired back to the shell.
 *
 * <p>The screen is vex-suite-common's, shared with Vex's build console: the cell grid, scrolling, selecting and
 * copying. What is here is what makes it MainFrame's: a shell behind it, what the key map's terminal commands do
 * to it ({@link #copy}, {@link #paste}, {@link #interrupt}, {@link #page}), the notice over it, and typing a path
 * once the prompt is drawn. Which chords reach those is the {@code Keymap}'s, claimed by {@link Terminals}: a chord
 * no action holds reaches the shell as it is.
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

        /** Wake the frame loop: this terminal has something new to draw, or has ended. */
        void wake();
    }

    final long id;
    private final Gui gui;
    private final Hooks hooks;
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
    /** What this tab was opened as: its folder, and the workspace and row it is of, if any. */
    private volatile Terminals.Opening opening = Terminals.Opening.PLAIN;

    /**
     * Starts {@code commandLine} on a pseudoconsole of {@code cols} by {@code rows}, in {@code cwd} (null for
     * MainFrame's own directory).
     */
    TerminalView(long id, Gui gui, GlyphLayout glyphs, String commandLine, String cwd, int cols, int rows,
                 float fontPx, Hooks hooks) {
        this.id = id;
        this.gui = gui;
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
                .onFit(pty::resize)
                .pseudoconsole();
        this.node = screen.node();
        this.frame = gui.column().width(Length.FILL).height(Length.FILL).children(node);
        wire();
        Pump.start(Pump.of(pty), term, () -> {
            lastOutput = System.nanoTime();
            screen.wrote();
        }, () -> {
            exited = true;
            hooks.wake();
        }, "pty-reader-" + id);
    }

    Node node() {
        return frame;
    }

    /** The cell grid: the node that holds the keyboard, and where the terminal's commands are claimed. */
    Node grid() {
        return node;
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

    /** What this tab was opened as: which workspace's environment its shell sourced, and which row's. */
    Terminals.Opening opening() {
        return opening;
    }

    void opening(Terminals.Opening o) {
        opening = o;
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
        // Tab is typing, not a command: claimed only to outrank the framework's focus traversal.
        gui.claimUi(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> send("\t"));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> send("\u001b[Z"));
        gui.onContextClick(node, c -> {
            if (screen.hasSelection()) {
                screen.copySelection();
            } else {
                paste();
            }
        });
    }

    /** Any key no claim took: to the shell, as the terminal spells it. */
    private void key(KeyEvent e) {
        String out = screen.keySequence(e.key(), e.has(Modifier.CONTROL), e.has(Modifier.ALT),
                e.has(Modifier.SHIFT));
        if (out != null) {
            send(out);
        }
    }

    // -- what the key map's terminal commands do -------------------------------------------------------------

    void copy() {
        screen.copySelection();
    }

    /** The interrupt a shell's Ctrl+C sends, for when Ctrl+C is copy: the legacy control character, 0x03. */
    void interrupt() {
        send("\u0003");
    }

    /** Scroll back ({@code by} &gt; 0) or on through the scrollback, a page at a time. */
    void page(int by) {
        screen.page(by);
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

    void paste() {
        String text = gui.clipboard().get();
        if (text == null || text.isEmpty()) {
            return;
        }
        send(screen.pasteText(text));
    }
}
