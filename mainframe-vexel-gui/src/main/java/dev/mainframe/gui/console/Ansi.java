package dev.mainframe.gui.console;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * MainFrame's colour, translated onto a monochrome tube. Everything MainFrame prints goes through its
 * {@code Renderer}, which paints with exactly seven SGR codes — {@code 0m} reset, {@code 1m} bold, {@code 2m} dim
 * and four colours — so this handles those and <em>discards</em> every other escape sequence rather than letting
 * it reach the atlas as garbage glyphs.
 *
 * <p><b>Seven codes, three intensities.</b> A phosphor screen has one colour and a beam that can be turned up or
 * down, so the four hues do not survive the trip: red, yellow and cyan all mean "this line is worth looking at",
 * which on a green screen is the same instruction as bold. What is lost is real — an error and a heading now look
 * alike in the scrollback — and what pays for it is the message line, which is where a 5250 put an error in the
 * first place, in reverse video where no amount of hue would have been louder.
 *
 * <p>The three levels come from the theme rather than from here, so a window themed some other way gets its own
 * ink out of the same code. Nothing in this file names a colour.
 *
 * <p>Attributes do not nest: a code replaces the one before it and {@code 0m} clears both slots. That matches how
 * the renderer emits them (each helper closes its own span) and keeps the state to two fields.
 */
final class Ansi {

    /** One scrollback line: the text with escapes stripped, and the spans their positions became. */
    record Line(String text, List<Span> spans) {
        static Line plain(String text) {
            return new Line(text, List.of());
        }
    }

    /** Full beam: bold, and every hue the tube cannot draw. */
    private final Color hot;
    /** The resting intensity of the phosphor — what an unattributed line is drawn in. */
    private final Color normal;
    /** The beam turned down: {@code 2m}, and anything the renderer meant as secondary. */
    private final Color soft;

    private Ansi(Color hot, Color normal, Color soft) {
        this.hot = hot;
        this.normal = normal;
        this.soft = soft;
    }

    /** The three intensities this theme's ink ladder gives. */
    static Ansi of(Theme theme) {
        return new Ansi(theme.color(Phosphor.HOT), theme.color(Role.INK), theme.color(Role.DIM));
    }

    /** What a line with nothing said about it is drawn in. */
    Color normal() {
        return normal;
    }

    /** Full beam — the terminal's own chrome uses it for the echoed prompt. */
    Color hot() {
        return hot;
    }

    /** Split {@code raw} into printable text plus the spans its escape codes asked for. */
    Line parse(String raw) {
        if (raw.indexOf(0x1b) < 0) {
            return Line.plain(raw);
        }
        StringBuilder text = new StringBuilder(raw.length());
        List<Span> spans = new ArrayList<>();
        Color colour = null;
        boolean bold = false;
        int runStart = 0;

        for (int i = 0; i < raw.length(); ) {
            char c = raw.charAt(i);
            if (c != 0x1b) {
                text.append(c);
                i++;
                continue;
            }
            // An escape closes the run before it, whatever the sequence turns out to mean.
            close(spans, runStart, text.length(), colour, bold);
            int end = skip(raw, i);
            if (i + 1 < raw.length() && raw.charAt(i + 1) == '[' && end <= raw.length() && end > 0
                    && raw.charAt(end - 1) == 'm') {
                String body = raw.substring(i + 2, end - 1);
                for (String code : body.split(";", -1)) {
                    switch (code) {
                        case "", "0" -> {
                            colour = null;
                            bold = false;
                        }
                        case "1" -> bold = true;
                        case "2" -> colour = soft;
                        // Green is the tube's own colour, so 32m is the resting intensity rather than a shift.
                        case "32" -> colour = normal;
                        // Red, yellow and cyan: three ways of saying "brighter" to a screen with one colour.
                        case "31", "33", "36" -> colour = hot;
                        default -> {
                            // A code MainFrame does not emit. Dropping it beats guessing at it.
                        }
                    }
                }
            }
            runStart = text.length();
            i = end;
        }
        close(spans, runStart, text.length(), colour, bold);
        return new Line(text.toString(), List.copyOf(spans));
    }

    private void close(List<Span> spans, int start, int end, Color colour, boolean bold) {
        if (end <= start) {
            return;
        }
        Color ink = colour != null ? colour : bold ? hot : null;
        if (ink != null) {
            spans.add(Span.foreground(start, end, ink));
        }
    }

    /** The index just past the escape sequence starting at {@code i}. */
    private static int skip(String raw, int i) {
        int j = i + 1;
        if (j < raw.length() && raw.charAt(j) == '[') {
            j++;
            while (j < raw.length() && raw.charAt(j) >= 0x20 && raw.charAt(j) <= 0x3f) {
                j++;   // parameter and intermediate bytes
            }
            return Math.min(j + 1, raw.length());   // the final byte
        }
        if (j < raw.length() && (raw.charAt(j) == ']' || raw.charAt(j) == 'P')) {
            // OSC / DCS: runs to BEL or ST. Consumed whole; nothing here wants a window title.
            while (j < raw.length() && raw.charAt(j) != 0x07 && raw.charAt(j) != 0x1b) {
                j++;
            }
            return Math.min(j + 1, raw.length());
        }
        return Math.min(j + 1, raw.length());
    }
}
