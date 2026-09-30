package dev.mainframe.term;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A VT/xterm-style screen: a grid of cells, a scrollback, and a parser that turns the byte stream a
 * pseudoconsole produces into edits of both. No knowledge of windows or of where the bytes come from.
 *
 * <p>All public methods are synchronized on this: the reader thread feeds, the render thread looks.
 */
public final class Terminal {

    public static final int DEFAULT = -1;
    public static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, INVERSE = 16, STRIKE = 32;

    /** What the terminal needs from whoever owns it. */
    public interface Host {
        /** Bytes the terminal answers with (cursor position reports and the like). */
        void reply(String s);

        void title(String title);
    }

    public static final class Line {
        public final int[] ch, fg, bg, at;

        Line(int width) {
            ch = new int[width];
            fg = new int[width];
            bg = new int[width];
            at = new int[width];
            erase(0, width, DEFAULT);
        }

        void erase(int from, int to, int bgColor) {
            for (int i = from; i < to; i++) {
                ch[i] = ' ';
                fg[i] = DEFAULT;
                bg[i] = bgColor;
                at[i] = 0;
            }
        }

        public int width() {
            return ch.length;
        }
    }

    private enum State { GROUND, ESC, ESC_INT, CSI, OSC, OSC_ESC, STR, STR_ESC }

    private static final int MAX_SCROLLBACK = 10_000;

    private final Host host;
    private int cols, rows;
    private Line[] screen;
    private Line[] mainScreen; // parked while the alternate screen is up
    private boolean alt;
    private final List<Line> scrollback = new ArrayList<>();

    private int cx, cy;
    private boolean wrapPending;
    private int top, bottom;
    private int fg = DEFAULT, bg = DEFAULT, at;
    private int savedX, savedY, savedFg = DEFAULT, savedBg = DEFAULT, savedAt;
    private boolean appCursor, bracketedPaste, cursorVisible = true, autowrap = true;
    private int lastPrinted = ' ';

    private State state = State.GROUND;
    private final StringBuilder buf = new StringBuilder();
    private int[] params = new int[16];
    private int np;
    private char marker;

    public Terminal(int cols, int rows, Host host) {
        this.host = host;
        this.cols = cols;
        this.rows = rows;
        this.screen = blank(rows, cols);
        this.bottom = rows - 1;
    }

    // -- view -------------------------------------------------------------------------------------------------

    public synchronized int cols() { return cols; }
    public synchronized int rows() { return rows; }
    public synchronized int scrollbackSize() { return scrollback.size(); }
    public synchronized int cursorX() { return cx; }
    public synchronized int cursorY() { return cy; }
    public synchronized boolean cursorVisible() { return cursorVisible; }
    public synchronized boolean appCursorKeys() { return appCursor; }
    public synchronized boolean bracketedPaste() { return bracketedPaste; }
    public synchronized boolean altScreen() { return alt; }

    /** Line by absolute index: 0 is the oldest scrollback line, scrollbackSize() is the top of the screen. */
    public synchronized Line lineAt(int index) {
        int sb = scrollback.size();
        if (index < sb) return scrollback.get(index);
        return screen[Math.min(index - sb, rows - 1)];
    }

    /** Text between two absolute positions (inclusive), trailing blanks trimmed per line. */
    public synchronized String text(int r0, int c0, int r1, int c1) {
        if (r0 > r1 || (r0 == r1 && c0 > c1)) {
            int tr = r0, tc = c0;
            r0 = r1; c0 = c1; r1 = tr; c1 = tc;
        }
        StringBuilder sb = new StringBuilder();
        int total = scrollback.size() + rows;
        for (int r = Math.max(r0, 0); r <= Math.min(r1, total - 1); r++) {
            Line l = lineAt(r);
            int from = r == r0 ? c0 : 0;
            int to = Math.min(r == r1 ? c1 : l.width() - 1, l.width() - 1);
            StringBuilder row = new StringBuilder();
            for (int c = Math.max(from, 0); c <= to; c++) {
                if (l.ch[c] != 0) row.appendCodePoint(l.ch[c]);
            }
            int end = row.length();
            while (end > 0 && row.charAt(end - 1) == ' ') end--;
            sb.append(row, 0, end);
            if (r < r1) sb.append('\n');
        }
        return sb.toString();
    }

    // -- input ------------------------------------------------------------------------------------------------

    public synchronized void feed(CharSequence s) {
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            put(cp);
        }
    }

    public synchronized void resize(int newCols, int newRows) {
        if (newCols == cols && newRows == rows) return;
        if (!alt) {
            while (rows > newRows && cy >= newRows) { // keep the cursor's line; the top goes to history
                pushHistory(screen[0]);
                screen = Arrays.copyOfRange(screen, 1, screen.length);
                rows--;
                cy--;
            }
        }
        screen = fit(screen, newRows, newCols);
        if (mainScreen != null) mainScreen = fit(mainScreen, newRows, newCols);
        cols = newCols;
        rows = newRows;
        top = 0;
        bottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = Math.min(cy, rows - 1);
        wrapPending = false;
    }

    // -- parser -----------------------------------------------------------------------------------------------

    private void put(int c) {
        switch (state) {
            case GROUND -> {
                if (c < 0x20) control(c);
                else if (c != 0x7f) print(c);
            }
            case ESC -> escape(c);
            case ESC_INT -> state = State.GROUND; // charset designation and friends: one more byte, ignored
            case CSI -> csiByte(c);
            case OSC -> {
                if (c == 0x07) { oscDone(); }
                else if (c == 0x1b) state = State.OSC_ESC;
                else buf.appendCodePoint(c);
            }
            case OSC_ESC -> {
                oscDone();
                if (c != '\\') { state = State.ESC; escape(c); }
            }
            case STR -> {
                if (c == 0x07) state = State.GROUND;
                else if (c == 0x1b) state = State.STR_ESC;
            }
            case STR_ESC -> state = State.GROUND;
        }
    }

    private void control(int c) {
        switch (c) {
            case 0x08 -> { if (cx > 0) cx--; wrapPending = false; }
            case 0x09 -> { cx = Math.min(((cx / 8) + 1) * 8, cols - 1); wrapPending = false; }
            case 0x0a, 0x0b, 0x0c -> index();
            case 0x0d -> { cx = 0; wrapPending = false; }
            case 0x1b -> state = State.ESC;
            case 0x18, 0x1a -> state = State.GROUND;
            default -> { }
        }
    }

    private void escape(int c) {
        state = State.GROUND;
        switch (c) {
            case '[' -> { state = State.CSI; np = 0; marker = 0; buf.setLength(0); params[0] = -1; }
            case ']' -> { state = State.OSC; buf.setLength(0); }
            case 'P', 'X', '^', '_' -> state = State.STR;
            case '(', ')', '*', '+', '#', '%' -> state = State.ESC_INT;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'D' -> index();
            case 'E' -> { cx = 0; index(); }
            case 'M' -> reverseIndex();
            case 'c' -> reset();
            default -> { if (c < 0x20) control(c); }
        }
    }

    private void csiByte(int c) {
        if (c < 0x20) {
            control(c);
            return;
        }
        if (c >= '0' && c <= '9') {
            if (np == 0) np = 1;
            int i = np - 1;
            params[i] = (params[i] < 0 ? 0 : params[i]) * 10 + (c - '0');
            if (params[i] > 65535) params[i] = 65535;
        } else if (c == ';' || c == ':') {
            if (np == 0) { np = 1; params[0] = 0; }
            if (np < params.length) params[np++] = -1;
        } else if (c >= '<' && c <= '?') {
            marker = (char) c;
        } else if (c >= 0x20 && c <= 0x2f) {
            buf.append((char) c);
        } else if (c >= 0x40 && c <= 0x7e) {
            state = State.GROUND;
            csi((char) c);
        }
    }

    private int raw(int i) { return i < np && params[i] > 0 ? params[i] : 0; }
    private int arg(int i, int def) { int v = raw(i); return v == 0 ? def : v; }

    private void csi(char fin) {
        boolean hasInter = !buf.isEmpty();
        if (hasInter) return; // cursor style (DECSCUSR) and other intermediates: nothing to do
        if (marker == '?') {
            if (fin == 'h' || fin == 'l') for (int i = 0; i < Math.max(np, 1); i++) privateMode(raw(i), fin == 'h');
            return;
        }
        if (marker != 0) return;
        switch (fin) {
            case '@' -> insertChars(arg(0, 1));
            case 'A' -> { cy = Math.max(cy >= top ? top : 0, cy - arg(0, 1)); wrapPending = false; }
            case 'B', 'e' -> { cy = Math.min(cy <= bottom ? bottom : rows - 1, cy + arg(0, 1)); wrapPending = false; }
            case 'C', 'a' -> { cx = Math.min(cols - 1, cx + arg(0, 1)); wrapPending = false; }
            case 'D' -> { cx = Math.max(0, cx - arg(0, 1)); wrapPending = false; }
            case 'E' -> { cx = 0; cy = Math.min(rows - 1, cy + arg(0, 1)); wrapPending = false; }
            case 'F' -> { cx = 0; cy = Math.max(0, cy - arg(0, 1)); wrapPending = false; }
            case 'G', '`' -> { cx = clamp(arg(0, 1) - 1, cols); wrapPending = false; }
            case 'H', 'f' -> { cy = clamp(arg(0, 1) - 1, rows); cx = clamp(arg(1, 1) - 1, cols); wrapPending = false; }
            case 'd' -> { cy = clamp(arg(0, 1) - 1, rows); wrapPending = false; }
            case 'J' -> eraseDisplay(raw(0));
            case 'K' -> eraseLine(raw(0));
            case 'L' -> { if (cy >= top && cy <= bottom) scrollDown(cy, bottom, arg(0, 1)); }
            case 'M' -> { if (cy >= top && cy <= bottom) scrollUp(cy, bottom, arg(0, 1)); }
            case 'P' -> deleteChars(arg(0, 1));
            case 'X' -> { Line l = screen[cy]; l.erase(cx, Math.min(cols, cx + arg(0, 1)), bg); }
            case 'S' -> scrollUp(top, bottom, arg(0, 1));
            case 'T' -> scrollDown(top, bottom, arg(0, 1));
            case 'b' -> { for (int i = arg(0, 1); i > 0; i--) print(lastPrinted); }
            case 'm' -> sgr();
            case 'r' -> {
                int t = clamp(arg(0, 1) - 1, rows), b = clamp(arg(1, rows) - 1, rows);
                if (t < b) { top = t; bottom = b; }
                cx = 0; cy = 0; wrapPending = false;
            }
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 'n' -> {
                if (raw(0) == 5) host.reply("\u001b[0n");
                else if (raw(0) == 6) host.reply("\u001b[" + (cy + 1) + ";" + (cx + 1) + "R");
            }
            case 'c' -> { if (raw(0) == 0) host.reply("\u001b[?1;0c"); }
            default -> { }
        }
    }

    private void privateMode(int mode, boolean on) {
        switch (mode) {
            case 1 -> appCursor = on;
            case 7 -> autowrap = on;
            case 25 -> cursorVisible = on;
            case 47, 1047 -> setAlt(on, false);
            case 1049 -> setAlt(on, true);
            case 2004 -> bracketedPaste = on;
            default -> { }
        }
    }

    private void setAlt(boolean on, boolean saveRestoreCursor) {
        if (on == alt) return;
        if (on) {
            if (saveRestoreCursor) saveCursor();
            mainScreen = screen;
            screen = blank(rows, cols);
            alt = true;
        } else {
            screen = mainScreen;
            mainScreen = null;
            alt = false;
            if (saveRestoreCursor) restoreCursor();
        }
        top = 0;
        bottom = rows - 1;
        wrapPending = false;
    }

    private void sgr() {
        if (np == 0) { resetAttrs(); return; }
        for (int i = 0; i < np; i++) {
            int p = Math.max(params[i], 0);
            switch (p) {
                case 0 -> resetAttrs();
                case 1 -> at |= BOLD;
                case 2 -> at |= DIM;
                case 3 -> at |= ITALIC;
                case 4, 21 -> at |= UNDERLINE;
                case 7 -> at |= INVERSE;
                case 9 -> at |= STRIKE;
                case 22 -> at &= ~(BOLD | DIM);
                case 23 -> at &= ~ITALIC;
                case 24 -> at &= ~UNDERLINE;
                case 27 -> at &= ~INVERSE;
                case 29 -> at &= ~STRIKE;
                case 39 -> fg = DEFAULT;
                case 49 -> bg = DEFAULT;
                case 38, 48 -> {
                    int color = DEFAULT;
                    if (i + 1 < np && params[i + 1] == 5 && i + 2 < np) {
                        color = Palette.xterm(Math.max(params[i + 2], 0));
                        i += 2;
                    } else if (i + 1 < np && params[i + 1] == 2 && i + 4 < np) {
                        color = (clamp8(params[i + 2]) << 16) | (clamp8(params[i + 3]) << 8) | clamp8(params[i + 4]);
                        i += 4;
                    } else {
                        i = np; // malformed; drop the rest rather than misread it
                    }
                    if (color != DEFAULT) { if (p == 38) fg = color; else bg = color; }
                }
                default -> {
                    if (p >= 30 && p <= 37) fg = Palette.xterm(p - 30);
                    else if (p >= 40 && p <= 47) bg = Palette.xterm(p - 40);
                    else if (p >= 90 && p <= 97) fg = Palette.xterm(p - 90 + 8);
                    else if (p >= 100 && p <= 107) bg = Palette.xterm(p - 100 + 8);
                }
            }
        }
    }

    private void oscDone() {
        state = State.GROUND;
        String s = buf.toString();
        int semi = s.indexOf(';');
        if (semi > 0) {
            String code = s.substring(0, semi);
            if (code.equals("0") || code.equals("2")) host.title(s.substring(semi + 1));
        }
    }

    // -- cells ------------------------------------------------------------------------------------------------

    private void print(int cp) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || cp == 0x200d) return;
        int width = wide(cp) ? 2 : 1;
        if (wrapPending || (width == 2 && cx == cols - 1)) {
            if (autowrap) { cx = 0; index(); }
            wrapPending = false;
        }
        if (width == 2 && cols < 2) return;
        Line l = screen[cy];
        set(l, cx, cp);
        if (width == 2) set(l, cx + 1, 0); // the right half; the renderer skips it
        lastPrinted = cp;
        cx += width;
        if (cx >= cols) { cx = cols - 1; wrapPending = true; }
    }

    private void set(Line l, int x, int cp) {
        l.ch[x] = cp;
        l.fg[x] = fg;
        l.bg[x] = bg;
        l.at[x] = at;
    }

    private static boolean wide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F) || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    private void index() {
        if (cy == bottom) scrollUp(top, bottom, 1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        if (cy == top) scrollDown(top, bottom, 1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        for (int k = 0; k < n; k++) {
            if (!alt && from == 0 && to == rows - 1) pushHistory(screen[0]);
            System.arraycopy(screen, from + 1, screen, from, to - from);
            screen[to] = new Line(cols);
            screen[to].erase(0, cols, bg);
        }
    }

    private void scrollDown(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, from, screen, from + 1, to - from);
            screen[from] = new Line(cols);
            screen[from].erase(0, cols, bg);
        }
    }

    private void pushHistory(Line l) {
        scrollback.add(l);
        if (scrollback.size() > MAX_SCROLLBACK + 1000) scrollback.subList(0, 1000).clear();
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                screen[cy].erase(cx, cols, bg);
                for (int r = cy + 1; r < rows; r++) screen[r].erase(0, cols, bg);
            }
            case 1 -> {
                for (int r = 0; r < cy; r++) screen[r].erase(0, cols, bg);
                screen[cy].erase(0, Math.min(cx + 1, cols), bg);
            }
            case 2 -> { for (Line l : screen) l.erase(0, cols, bg); }
            case 3 -> scrollback.clear();
            default -> { }
        }
    }

    private void eraseLine(int mode) {
        Line l = screen[cy];
        switch (mode) {
            case 0 -> l.erase(cx, cols, bg);
            case 1 -> l.erase(0, Math.min(cx + 1, cols), bg);
            case 2 -> l.erase(0, cols, bg);
            default -> { }
        }
    }

    private void insertChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        for (int x = cols - 1; x >= cx + n; x--) copyCell(l, x - n, x);
        l.erase(cx, cx + n, bg);
    }

    private void deleteChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        for (int x = cx; x < cols - n; x++) copyCell(l, x + n, x);
        l.erase(cols - n, cols, bg);
    }

    private static void copyCell(Line l, int from, int to) {
        l.ch[to] = l.ch[from];
        l.fg[to] = l.fg[from];
        l.bg[to] = l.bg[from];
        l.at[to] = l.at[from];
    }

    private void saveCursor() { savedX = cx; savedY = cy; savedFg = fg; savedBg = bg; savedAt = at; }

    private void restoreCursor() {
        cx = Math.min(savedX, cols - 1);
        cy = Math.min(savedY, rows - 1);
        fg = savedFg; bg = savedBg; at = savedAt;
        wrapPending = false;
    }

    private void resetAttrs() { fg = DEFAULT; bg = DEFAULT; at = 0; }

    private void reset() {
        resetAttrs();
        if (alt) setAlt(false, false);
        for (Line l : screen) l.erase(0, cols, DEFAULT);
        cx = cy = 0;
        top = 0;
        bottom = rows - 1;
        wrapPending = false;
        appCursor = bracketedPaste = false;
        cursorVisible = autowrap = true;
    }

    private static Line[] blank(int rows, int cols) {
        Line[] ls = new Line[rows];
        for (int i = 0; i < rows; i++) ls[i] = new Line(cols);
        return ls;
    }

    private static Line[] fit(Line[] src, int rows, int cols) {
        Line[] out = new Line[rows];
        for (int r = 0; r < rows; r++) {
            Line n = new Line(cols);
            if (r < src.length) {
                Line o = src[r];
                int w = Math.min(cols, o.width());
                System.arraycopy(o.ch, 0, n.ch, 0, w);
                System.arraycopy(o.fg, 0, n.fg, 0, w);
                System.arraycopy(o.bg, 0, n.bg, 0, w);
                System.arraycopy(o.at, 0, n.at, 0, w);
            }
            out[r] = n;
        }
        return out;
    }

    private static int clamp(int v, int size) { return Math.max(0, Math.min(v, size - 1)); }
    private static int clamp8(int v) { return Math.max(0, Math.min(v, 255)); }

    /** The 256-colour palette, with the sixteen standard entries in the Windows Terminal "Campbell" scheme. */
    public static final class Palette {
        private static final int[] BASE = {
                0x0C0C0C, 0xC50F1F, 0x13A10E, 0xC19C00, 0x0037DA, 0x881798, 0x3A96DD, 0xCCCCCC,
                0x767676, 0xE74856, 0x16C60C, 0xF9F1A5, 0x3B78FF, 0xB4009E, 0x61D6D6, 0xF2F2F2};

        public static final int DEFAULT_FG = 0xCCCCCC, DEFAULT_BG = 0x0C0C0C;

        public static int xterm(int n) {
            if (n < 16) return BASE[n];
            if (n < 232) {
                int i = n - 16, r = i / 36, g = (i / 6) % 6, b = i % 6;
                return (level(r) << 16) | (level(g) << 8) | level(b);
            }
            int v = 8 + (Math.min(n, 255) - 232) * 10;
            return (v << 16) | (v << 8) | v;
        }

        private static int level(int i) { return i == 0 ? 0 : 55 + i * 40; }

        private Palette() { }
    }
}
