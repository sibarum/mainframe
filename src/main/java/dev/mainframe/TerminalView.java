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
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terminal as a vexelray node: a {@link Terminal} model drawn as a cell grid, a {@link ConPty} feeding it, and
 * the keyboard and mouse wired back to the pty.
 *
 * <p>Threading: the pty reader thread feeds the (synchronized) terminal and raises {@code dirty}; everything else
 * happens on the GUI thread, where {@link #tick} turns a dirty terminal into a new picture.
 */
final class TerminalView {

    private static final Color DEFAULT_BG = Color.rgb(Palette.DEFAULT_BG);
    private static final Color SELECTION = Color.argb(0xA0264F78);

    /** What a tab's terminal asks of the window it lives in. */
    interface Hooks {
        void newTab();
        void closeTab(TerminalView view);
        void cycle(int by);
        void rename(TerminalView view);
    }

    private final Gui gui;
    private final Hooks hooks;
    private final java.util.function.BooleanSupplier remapKeys;
    private final Node node;
    private final Terminal term;
    private final ConPty pty;
    private final GlyphLayout glyphs;
    private volatile float px, cw, lineH, ascent;
    private volatile float boxW, boxH;   // the node's content area as last laid out
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private volatile boolean exited;
    private volatile boolean active;
    private volatile String pendingTitle;
    private volatile String shellTitle = "Shell";
    private volatile String customTitle;
    private sibarum.atchung.Subscription scrollSub;

    private int scrollOffset;
    private boolean hasSel;
    private int anchorRow, anchorCol, headRow, headCol;

    /** {@code cwd} is the directory the shell starts in, or null for MainFrame's own. */
    TerminalView(Gui gui, String commandLine, String cwd, int cols, int rows, float fontPx,
                 java.util.function.BooleanSupplier remapKeys, Hooks hooks) {
        this.gui = gui;
        this.remapKeys = remapKeys;
        this.hooks = hooks;
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json").face(1))
                .glyphLayout();
        measure(fontPx);

        this.pty = ConPty.start(commandLine, cols, rows, cwd);
        this.term = new Terminal(cols, rows, new Terminal.Host() {
            @Override public void reply(String s) { pty.write(s); }
            @Override public void title(String title) { pendingTitle = title; }
        });

        this.node = gui.box().width(Length.FILL).height(Length.FILL).font(1).background(DEFAULT_BG);
        wire();
        startReader();
    }

    Node node() { return node; }

    boolean exited() { return exited; }

    void focus() { gui.focus(node); }

    /** Change the type size: the cell grid is remeasured and the shell is told its new dimensions. */
    void fontPx(float size) {
        if (size == px) return;
        measure(size);
        refit();
        dirty.set(true);
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
        if (boxW < cw * 2 || boxH < lineH) return;
        int cols = Math.max(2, (int) (boxW / cw));
        int rows = Math.max(1, (int) (boxH / lineH));
        if (cols != term.cols() || rows != term.rows()) {
            term.resize(cols, rows);
            pty.resize(cols, rows);
            dirty.set(true);
        }
    }

    /** What the tab's header says: the name the user gave it, else whatever the shell last called itself. */
    String displayTitle() {
        String c = customTitle;
        return c != null ? c : shellTitle;
    }

    void shellTitle(String title) { shellTitle = title; }

    /** A blank name hands the header back to the shell. */
    void customTitle(String title) { customTitle = title == null || title.isBlank() ? null : title.strip(); }

    int cols() { return term.cols(); }

    int rows() { return term.rows(); }

    /** Takes the pending title, if the shell has set one since last asked. */
    String takeTitle() {
        String t = pendingTitle;
        pendingTitle = null;
        return t;
    }

    /** Only the tab on show draws; a background tab keeps reading its shell and draws when it comes back. */
    void active(boolean on) {
        active = on;
        if (on) dirty.set(true);
    }

    void close() {
        if (scrollSub != null) scrollSub.close();
        pty.close();
    }

    /** On the GUI thread, once a frame. */
    void tick() {
        if (active && dirty.getAndSet(false)) render();
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
                dirty.set(true);
            }
            exited = true;
        }, "pty-reader");
        t.setDaemon(true);
        t.start();
    }

    private void render() {
        Sketch s = new Sketch();
        synchronized (term) {
            int sb = term.scrollbackSize(), rows = term.rows(), cols = term.cols();
            scrollOffset = Math.max(0, Math.min(scrollOffset, sb));
            boolean cursor = scrollOffset == 0 && term.cursorVisible();
            int[] sel = selection();
            for (int r = 0; r < rows; r++) {
                int abs = sb + r - scrollOffset;
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
                    while (e < w && bg == cellBg(l, e, cursorRow && e == term.cursorX())) e++;
                    if (bg != Terminal.DEFAULT) s.fill(c * cw, y, (e - c) * cw, lineH, Color.rgb(bg));
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
                        if (run.isEmpty()) { runStart = c; runFg = fg; runAt = at; }
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
        if ((at & Terminal.BOLD) != 0) s.text(str, x + 0.7f, y + ascent, px, color); // one mono face: embolden by doubling
        if ((at & Terminal.UNDERLINE) != 0) s.fill(x, y + ascent + 2, str.length() * cw, 1, color);
        if ((at & Terminal.STRIKE) != 0) s.fill(x, y + lineH / 2, str.length() * cw, 1, color);
    }

    /** Resolved background as 0xRRGGBB, or DEFAULT when the window's own background shows through. */
    private static int cellBg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        if (!inv) return l.bg[c];
        return l.fg[c] == Terminal.DEFAULT ? Palette.DEFAULT_FG : l.fg[c];
    }

    private static int cellFg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        int fg = inv ? (l.bg[c] == Terminal.DEFAULT ? Palette.DEFAULT_BG : l.bg[c])
                : (l.fg[c] == Terminal.DEFAULT ? Palette.DEFAULT_FG : l.fg[c]);
        if ((l.at[c] & Terminal.DIM) != 0) fg = blend(fg, Palette.DEFAULT_BG);
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
            if (!l.present()) return;
            boxW = l.content().w();
            boxH = l.content().h();
            refit();
        });
        gui.onCharUi(node, cp -> {
            if (cp < 0x20 || cp == 0x7f) return;
            snapToBottom();
            pty.write(new String(Character.toChars(cp)));
        });
        gui.onKeyUi(node, this::key);
        gui.claimUi(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> send("\t"));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> send("\u001b[Z"));
        gui.onDragUi(node, d -> {
            int sb = term.scrollbackSize();
            int col = Math.max(0, Math.min(term.cols() - 1, (int) ((d.x() - d.nodeX()) / cw)));
            int row = Math.max(0, Math.min(term.rows() - 1, (int) ((d.y() - d.nodeY()) / lineH)));
            int abs = sb + row - scrollOffset;
            switch (d.phase()) {
                case START -> { anchorRow = headRow = abs; anchorCol = headCol = col; hasSel = false; }
                default -> {
                    headRow = abs;
                    headCol = col;
                    hasSel = anchorRow != headRow || anchorCol != headCol;
                }
            }
            dirty.set(true);
        });
        gui.onContextClick(node, c -> {
            if (hasSel) copySelection(); else paste();
        });
        gui.claimUi(node, Shortcut.of(Key.T, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED, hooks::newTab);
        gui.claimUi(node, Shortcut.of(Key.W, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED, () -> hooks.closeTab(this));
        gui.claimUi(node, Shortcut.of(Key.R, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED, () -> hooks.rename(this));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.CONTROL), ClaimScope.FOCUSED, () -> hooks.cycle(1));
        gui.claimUi(node, Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED, () -> hooks.cycle(-1));
        scrollSub = gui.bus().subscribe(InputTopics.INPUT, (InputEvent e) -> {
            if (active && e instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                scrollOffset += s.yOffset() > 0 ? 3 : -3;
                dirty.set(true);
            }
        });
    }

    private void key(KeyEvent e) {
        Key k = e.key();
        boolean ctrl = e.has(Modifier.CONTROL), alt = e.has(Modifier.ALT), shift = e.has(Modifier.SHIFT);
        String name = k.name();
        if (ctrl && shift && k == Key.C) { copySelection(); return; }
        if (ctrl && shift && k == Key.V) { paste(); return; }
        if (remapKeys.getAsBoolean() && !alt && !shift) {
            // Ctrl+C and Ctrl+V are the clipboard's, which leaves the shell without its interrupt: Esc sends it.
            if (ctrl && k == Key.C) { copySelection(); return; }
            if (ctrl && k == Key.V) { paste(); return; }
            if (!ctrl && k == Key.ESCAPE) { send("\u0003"); return; }
        }
        if (shift && k == Key.PAGE_UP) { scrollOffset += term.rows() - 1; dirty.set(true); return; }
        if (shift && k == Key.PAGE_DOWN) { scrollOffset -= term.rows() - 1; dirty.set(true); return; }

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
        if (out == null && name.length() == 1 && name.charAt(0) >= 'A' && name.charAt(0) <= 'Z') {
            if (ctrl && !alt) out = String.valueOf((char) (name.charAt(0) - 'A' + 1));
            else if (alt && !ctrl) out = "\u001b"; // the letter itself follows as a typed character
        }
        if (out != null) send(out);
    }

    private String cursorKey(char f, int mod) {
        if (mod > 1) return "\u001b[1;" + mod + f;
        return term.appCursorKeys() ? "\u001bO" + f : "\u001b[" + f;
    }

    private static String tilde(int n, int mod) {
        return mod > 1 ? "\u001b[" + n + ";" + mod + "~" : "\u001b[" + n + "~";
    }

    private void send(String s) {
        snapToBottom();
        pty.write(s);
    }

    private void snapToBottom() {
        if (scrollOffset != 0 || hasSel) {
            scrollOffset = 0;
            hasSel = false;
            dirty.set(true);
        }
    }

    private void copySelection() {
        int[] sel = selection();
        if (sel == null) return;
        gui.clipboard().set(term.text(sel[0], sel[1], sel[2], sel[3]));
        hasSel = false;
        dirty.set(true);
    }

    private void paste() {
        String text = gui.clipboard().get();
        if (text == null || text.isEmpty()) return;
        text = text.replace("\r\n", "\r").replace('\n', '\r');
        send(term.bracketedPaste() ? "\u001b[200~" + text + "\u001b[201~" : text);
    }

    /** {firstRow, firstCol, lastRow, lastCol} in absolute coordinates, or null. */
    private int[] selection() {
        if (!hasSel) return null;
        boolean forward = anchorRow < headRow || (anchorRow == headRow && anchorCol <= headCol);
        return forward ? new int[] {anchorRow, anchorCol, headRow, headCol}
                : new int[] {headRow, headCol, anchorRow, anchorCol};
    }
}
