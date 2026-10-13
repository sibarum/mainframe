package dev.mainframe;

import dev.mainframe.pty.ConPty;
import dev.mainframe.term.Terminal;
import dev.mainframe.term.Terminal.Line;
import dev.mainframe.term.Terminal.Palette;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.GlyphLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One tab's terminal as a vexelray node: a {@link Terminal} drawn as a cell grid, a {@link ConPty} feeding it, and
 * the keyboard and mouse wired back to the pty.
 *
 * <h2>Threads</h2>
 *
 * <p>The pty's reader thread feeds the terminal (whose methods are synchronized) and marks the picture stale; the
 * frame loop's {@link #tick}, through {@link Terminals}' {@code @BeforeFrame}, turns a stale picture into a new one.
 * The loop parks when nothing is due, so <b>every way the picture goes stale also wakes it</b> ({@link #invalidate}):
 * a shell that writes while the window is idle would otherwise be drawn on the next mouse move.
 */
final class TerminalView implements AutoCloseable {

    private static final Color DEFAULT_BG = Color.rgb(Palette.DEFAULT_BG);
    private static final Color SELECTION = Color.argb(0xA0264F78);
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
    private final Node node;
    /** The tab's page: the grid, with a notice above it while there is one. */
    private final Node frame;
    private volatile Node notice;
    private final Terminal term;
    private final ConPty pty;
    private final GlyphLayout glyphs;
    private volatile float px, cw, lineH, ascent;
    /** The node's content area as last laid out. */
    private volatile float boxW, boxH;
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private volatile boolean exited;
    private volatile boolean active;
    private volatile String pendingTitle;
    private volatile String shellTitle = "Shell";
    private volatile String customTitle;
    private final Subscription scrolls;
    /** When the shell last wrote, in nanos; zero until it first does. */
    private volatile long lastOutput;
    /** Text to type once the shell has drawn its prompt (see {@link #typeWhenReady}); null when there is none. */
    private volatile String pendingType;

    private volatile int scrollOffset;
    private volatile boolean hasSel;
    private volatile int anchorRow, anchorCol, headRow, headCol;

    /**
     * Starts {@code commandLine} on a pseudoconsole of {@code cols} by {@code rows}, in {@code cwd} (null for
     * MainFrame's own directory).
     */
    TerminalView(long id, Gui gui, GlyphLayout glyphs, String commandLine, String cwd, int cols, int rows,
                 float fontPx, BooleanSupplier remapKeys, Hooks hooks) {
        this.id = id;
        this.gui = gui;
        this.glyphs = glyphs;
        this.remapKeys = remapKeys;
        this.hooks = hooks;
        measure(fontPx);

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

        this.node = gui.box().width(Length.FILL).height(Length.grow(1)).font(Type.MONO).background(DEFAULT_BG);
        this.frame = gui.column().width(Length.FILL).height(Length.FILL).children(node);
        wire();
        scrolls = gui.bus().subscribe(InputTopics.INPUT, (InputEvent e) -> {
            if (active && e instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                scrollOffset += s.yOffset() > 0 ? 3 : -3;
                invalidate();
            }
        });
        startReader();
    }

    Node node() {
        return frame;
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
        write(cursorKey('H', 1));
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
        if (size == px) {
            return;
        }
        measure(size);
        refit();
        invalidate();
    }

    private void measure(float size) {
        px = size;
        cw = glyphs.advance('M', size);
        ascent = glyphs.ascent(size);
        lineH = glyphs.lineHeight(size);
    }

    /** Make the terminal as many cells as the content area holds. */
    private void refit() {
        // A tab that is not on show is laid out at no size at all. Resizing the shell to that would shrink its
        // screen to nothing, and a shell does not repaint what it lost when it grows back.
        if (boxW < cw * 2 || boxH < lineH) {
            return;
        }
        int cols = Math.max(2, (int) (boxW / cw));
        int rows = Math.max(1, (int) (boxH / lineH));
        if (cols != term.cols() || rows != term.rows()) {
            term.resize(cols, rows);
            pty.resize(cols, rows);
            invalidate();
        }
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
        return term.cols();
    }

    int rows() {
        return term.rows();
    }

    /** Takes the pending title, if the shell has set one since last asked. */
    String takeTitle() {
        String t = pendingTitle;
        pendingTitle = null;
        return t;
    }

    /** Only the tab on show draws; a background tab keeps reading its shell and draws when it comes back. */
    void active(boolean on) {
        active = on;
        if (on) {
            invalidate();
        }
    }

    @Override
    public void close() {
        scrolls.close();
        pty.close();
    }

    /** On the main thread, once a frame. */
    void tick() {
        if (active && dirty.getAndSet(false)) {
            render();
        }
    }

    /** The picture is stale: say so, and wake the loop to redraw it. Any thread. */
    private void invalidate() {
        dirty.set(true);
        hooks.wake();
    }

    // -- output -----------------------------------------------------------------------------------------------

    private void startReader() {
        Thread t = new Thread(() -> {
            var dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            byte[] chunk = new byte[16384];
            ByteBuffer in = ByteBuffer.allocate(chunk.length + 8);
            CharBuffer out = CharBuffer.allocate(chunk.length + 8);
            int n;
            while ((n = pty.read(chunk)) > 0) {
                in.put(chunk, 0, n).flip();
                dec.decode(in, out, false);
                in.compact();
                out.flip();
                term.feed(out);
                out.clear();
                lastOutput = System.nanoTime();
                invalidate();
            }
            exited = true;
            hooks.wake();
        }, "pty-reader-" + id);
        t.setDaemon(true);
        t.start();
    }

    private void render() {
        Sketch s = new Sketch();
        synchronized (term) {
            int sb = term.scrollbackSize(), rows = term.rows(), cols = term.cols();
            int offset = Math.max(0, Math.min(scrollOffset, sb));
            scrollOffset = offset;
            boolean cursor = offset == 0 && term.cursorVisible();
            int[] sel = selection();
            for (int r = 0; r < rows; r++) {
                int abs = sb + r - offset;
                Line l = term.lineAt(abs);
                int w = Math.min(cols, l.width());
                float y = r * lineH;
                boolean cursorRow = cursor && r == term.cursorY();
                int selFrom = -1, selTo = -2;
                if (sel != null && abs >= sel[0] && abs <= sel[2]) {
                    selFrom = abs == sel[0] ? sel[1] : 0;
                    selTo = abs == sel[2] ? sel[3] : cols - 1;
                }
                // backgrounds, merged into runs
                for (int c = 0; c < w; ) {
                    int bg = cellBg(l, c, cursorRow && c == term.cursorX());
                    int e = c + 1;
                    while (e < w && bg == cellBg(l, e, cursorRow && e == term.cursorX())) {
                        e++;
                    }
                    if (bg != Terminal.DEFAULT) {
                        s.fill(c * cw, y, (e - c) * cw, lineH, Color.rgb(bg));
                    }
                    c = e;
                }
                if (selTo >= selFrom) {
                    s.fill(selFrom * cw, y, (Math.min(selTo, cols - 1) - selFrom + 1) * cw, lineH, SELECTION);
                }
                // glyphs, merged into runs of one colour; anything outside Latin-1 is placed on its own cell
                StringBuilder run = new StringBuilder();
                int runStart = 0, runFg = 0, runAt = 0;
                for (int c = 0; c <= w; c++) {
                    int ch = c < w ? l.ch[c] : 0;
                    int fg = c < w ? cellFg(l, c, cursorRow && c == term.cursorX()) : 0;
                    int at = c < w ? l.at[c] & (Terminal.BOLD | Terminal.UNDERLINE | Terminal.STRIKE) : 0;
                    boolean latin = ch > 0 && ch < 0x100;
                    if (!run.isEmpty() && (!latin || fg != runFg || at != runAt)) {
                        text(s, run.toString(), runStart, y, runFg, runAt);
                        run.setLength(0);
                    }
                    if (latin) {
                        if (run.isEmpty()) {
                            runStart = c;
                            runFg = fg;
                            runAt = at;
                        }
                        run.append((char) ch);
                    } else if (CellArt.handles(ch)) {
                        CellArt.draw(s, ch, c * cw, y, cw, lineH, fg);
                    } else if (ch > 0) {
                        text(s, new String(Character.toChars(ch)), c, y, fg, at);
                    }
                }
            }
        }
        node.picture(s.picture());
    }

    private void text(Sketch s, String str, int col, float y, int rgb, int at) {
        Color color = Color.rgb(rgb);
        float x = col * cw;
        s.text(str, x, y + ascent, px, color);
        if ((at & Terminal.BOLD) != 0) {
            s.text(str, x + 0.7f, y + ascent, px, color);   // one mono face: embolden by doubling
        }
        if ((at & Terminal.UNDERLINE) != 0) {
            s.fill(x, y + ascent + 2, str.length() * cw, 1, color);
        }
        if ((at & Terminal.STRIKE) != 0) {
            s.fill(x, y + lineH / 2, str.length() * cw, 1, color);
        }
    }

    /** Resolved background as 0xRRGGBB, or DEFAULT when the window's own background shows through. */
    private static int cellBg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        if (!inv) {
            return l.bg[c];
        }
        return l.fg[c] == Terminal.DEFAULT ? Palette.DEFAULT_FG : l.fg[c];
    }

    private static int cellFg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        int fg = inv ? (l.bg[c] == Terminal.DEFAULT ? Palette.DEFAULT_BG : l.bg[c])
                : (l.fg[c] == Terminal.DEFAULT ? Palette.DEFAULT_FG : l.fg[c]);
        if ((l.at[c] & Terminal.DIM) != 0) {
            fg = blend(fg, Palette.DEFAULT_BG);
        }
        return fg;
    }

    private static int blend(int a, int b) {
        int r = (((a >> 16) & 255) * 6 + ((b >> 16) & 255) * 4) / 10;
        int g = (((a >> 8) & 255) * 6 + ((b >> 8) & 255) * 4) / 10;
        int bl = ((a & 255) * 6 + (b & 255) * 4) / 10;
        return (r << 16) | (g << 8) | bl;
    }

    // -- input ------------------------------------------------------------------------------------------------

    private void wire() {
        gui.onResizeUi(node, l -> {
            if (!l.present()) {
                return;
            }
            boxW = l.content().w();
            boxH = l.content().h();
            refit();
        });
        gui.onCharUi(node, cp -> {
            if (cp < 0x20 || cp == 0x7f) {
                return;
            }
            send(new String(Character.toChars(cp)));
        });
        gui.onKeyUi(node, this::key);
        gui.claimUi(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> send("\t"));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> send("\u001b[Z"));
        gui.onDragUi(node, d -> {
            int sb = term.scrollbackSize();
            int col = Math.max(0, Math.min(term.cols() - 1, (int) ((d.x() - d.nodeX()) / cw)));
            int row = Math.max(0, Math.min(term.rows() - 1, (int) ((d.y() - d.nodeY()) / lineH)));
            int abs = sb + row - scrollOffset;
            if (d.phase() == dev.vexelray.gui.core.input.DragEvent.Phase.START) {
                anchorRow = headRow = abs;
                anchorCol = headCol = col;
                hasSel = false;
            } else {
                headRow = abs;
                headCol = col;
                hasSel = anchorRow != headRow || anchorCol != headCol;
            }
            invalidate();
        });
        gui.onContextClick(node, c -> {
            if (hasSel) {
                copySelection();
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
    }

    private void key(KeyEvent e) {
        Key k = e.key();
        boolean ctrl = e.has(Modifier.CONTROL), alt = e.has(Modifier.ALT), shift = e.has(Modifier.SHIFT);
        if (ctrl && shift && k == Key.C) {
            copySelection();
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
            scrollOffset += term.rows() - 1;
            invalidate();
            return;
        }
        if (shift && k == Key.PAGE_DOWN) {
            scrollOffset -= term.rows() - 1;
            invalidate();
            return;
        }
        String out = sequence(k, ctrl, alt, shift);
        if (out != null) {
            send(out);
        }
    }

    /**
     * With <i>Remap keybindings</i> on: Ctrl+C and Ctrl+V are the clipboard's, which leaves the shell without its
     * interrupt, so Esc sends it. Ctrl+T, Ctrl+W and Ctrl+O are the window's, as in a browser; the shell gives up
     * transpose, delete word and operate-and-get-next, and Ctrl+Backspace still deletes a word. True when the key was
     * one of these.
     */
    private boolean remapped(Key k, boolean ctrl) {
        if (ctrl && k == Key.C) {
            copySelection();
        } else if (ctrl && k == Key.V) {
            paste();
        } else if (!ctrl && k == Key.ESCAPE) {
            send("\u0003");
        } else if (ctrl && k == Key.T) {
            hooks.newTab();
        } else if (ctrl && k == Key.W) {
            hooks.askClose(this);
        } else if (ctrl && k == Key.O) {
            hooks.open();
        } else {
            return false;
        }
        return true;
    }

    /** What {@code k} sends the shell, or null for nothing. A typed character arrives separately, as a char. */
    private String sequence(Key k, boolean ctrl, boolean alt, boolean shift) {
        int mod = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        String out = switch (k) {
            case ENTER -> "\r";
            case BACKSPACE -> ctrl ? "\b" : "\u007f";
            case ESCAPE -> "\u001b";
            case UP -> cursorKey('A', mod);
            case DOWN -> cursorKey('B', mod);
            case RIGHT -> cursorKey('C', mod);
            case LEFT -> cursorKey('D', mod);
            case HOME -> cursorKey('H', mod);
            case END -> cursorKey('F', mod);
            case INSERT -> tilde(2, mod);
            case DELETE -> tilde(3, mod);
            case PAGE_UP -> tilde(5, mod);
            case PAGE_DOWN -> tilde(6, mod);
            case F1 -> "\u001bOP";
            case F2 -> "\u001bOQ";
            case F3 -> "\u001bOR";
            case F4 -> "\u001bOS";
            case F5 -> tilde(15, mod);
            case F6 -> tilde(17, mod);
            case F7 -> tilde(18, mod);
            case F8 -> tilde(19, mod);
            case F9 -> tilde(20, mod);
            case F10 -> tilde(21, mod);
            case F11 -> tilde(23, mod);
            case F12 -> tilde(24, mod);
            default -> null;
        };
        String name = k.name();
        if (out == null && name.length() == 1 && name.charAt(0) >= 'A' && name.charAt(0) <= 'Z') {
            if (ctrl && !alt) {
                out = String.valueOf((char) (name.charAt(0) - 'A' + 1));
            } else if (alt && !ctrl) {
                out = "\u001b";   // the letter itself follows as a typed character
            }
        }
        return out;
    }

    private String cursorKey(char f, int mod) {
        if (mod > 1) {
            return "\u001b[1;" + mod + f;
        }
        return term.appCursorKeys() ? "\u001bO" + f : "\u001b[" + f;
    }

    private static String tilde(int n, int mod) {
        return mod > 1 ? "\u001b[" + n + ";" + mod + "~" : "\u001b[" + n + "~";
    }

    /** What the user typed or pasted: it wins over anything waiting to be typed, and Enter puts a notice away. */
    private void send(String s) {
        pendingType = null;
        if (s.indexOf('\r') >= 0) {
            dismissNotice();
        }
        write(s);
    }

    private void write(String s) {
        snapToBottom();
        pty.write(s);
    }

    private void snapToBottom() {
        if (scrollOffset != 0 || hasSel) {
            scrollOffset = 0;
            hasSel = false;
            invalidate();
        }
    }

    private void copySelection() {
        int[] sel = selection();
        if (sel == null) {
            return;
        }
        gui.clipboard().set(term.text(sel[0], sel[1], sel[2], sel[3]));
        hasSel = false;
        invalidate();
    }

    private void paste() {
        String text = gui.clipboard().get();
        if (text == null || text.isEmpty()) {
            return;
        }
        text = text.replace("\r\n", "\r").replace('\n', '\r');
        send(term.bracketedPaste() ? "\u001b[200~" + text + "\u001b[201~" : text);
    }

    /** {firstRow, firstCol, lastRow, lastCol} in absolute coordinates, or null. */
    private int[] selection() {
        if (!hasSel) {
            return null;
        }
        boolean forward = anchorRow < headRow || (anchorRow == headRow && anchorCol <= headCol);
        return forward ? new int[] {anchorRow, anchorCol, headRow, headCol}
                : new int[] {headRow, headCol, anchorRow, anchorCol};
    }
}
