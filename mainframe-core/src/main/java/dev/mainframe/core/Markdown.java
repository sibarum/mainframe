package dev.mainframe.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Markdown's syntax as {@link Span}s: what a text field shows about its text without the text changing.
 *
 * <h2>What a span is for</h2>
 *
 * <p>A span is the application's statement about a range of characters — this is a heading, this is code — laid
 * over the text rather than written into it. The document stays exactly the bytes in the file; the field draws the
 * colours, and moves every span with its text as the user types, so a heading stays a heading while it is being
 * edited. Spans compose, and this uses all three things one can carry: a foreground for headings and emphasis, a
 * background behind code, an underline under a link's text.
 *
 * <p>A pure function of the text: no {@code Gui}, no field, no thread. That is what makes it testable as a list of
 * ranges, and what lets {@code Buffer} run it on a worker and hand the result to the field in one call.
 *
 * <h2>How much Markdown</h2>
 *
 * <p>The common, line-shaped part: ATX headings, block quotes, list markers, rules, fenced code, and inline code,
 * emphasis and links within a line. Not nested emphasis, reference links, setext headings or HTML. It is a
 * highlighter, not a renderer — a construct it does not recognise is left as plain text, which is always a correct
 * way to show text. Nothing here overlaps two foregrounds, so no span ever has to win over another.
 */
public final class Markdown {

    /** The colours, chosen by whoever owns the theme. A background may be null to leave code unmarked. */
    public record Style(Color heading, Color marker, Color emphasis, Color link, Color quote, Color code,
                        Color codeBackground) {
    }

    private Markdown() {
    }

    /** Whether a file of this name is Markdown, by its extension. */
    public static boolean handles(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    /** The spans for {@code text}, in document order. */
    public static List<Span> spans(String text, Style style) {
        List<Span> out = new ArrayList<>();
        boolean fenced = false;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end;
            String line = text.substring(start, end);
            int indent = indent(line);
            if (indent <= 3 && line.startsWith("```", indent)) {
                // The fence itself is furniture; what is between two of them is code, whatever it says.
                fg(out, start + indent, end, style.marker());
                bg(out, start, end, style.codeBackground());
                fenced = !fenced;
            } else if (fenced) {
                fg(out, start, end, style.code());
                bg(out, start, end, style.codeBackground());
            } else {
                block(out, line, start, indent, style);
            }
            start = end + 1;
        }
        return out;
    }

    /** One line outside a fence: its block marker, if any, then whatever is inline in the rest of it. */
    private static void block(List<Span> out, String line, int at, int indent, Style style) {
        if (indent > 3) {
            return;   // four spaces in is an indented code block in Markdown, and not a heading or a list
        }
        int hashes = run(line, indent, '#');
        if (hashes >= 1 && hashes <= 6 && (indent + hashes == line.length() || line.charAt(indent + hashes) == ' ')) {
            fg(out, at + indent, at + indent + hashes, style.marker());
            fg(out, at + indent + hashes, at + line.length(), style.heading());
            return;
        }
        if (line.startsWith(">", indent)) {
            fg(out, at + indent, at + indent + 1, style.marker());
            fg(out, at + indent + 1, at + line.length(), style.quote());
            return;
        }
        String trimmed = line.strip();
        if (trimmed.length() >= 3 && (trimmed.chars().allMatch(c -> c == '-') || trimmed.chars().allMatch(c -> c == '*')
                || trimmed.chars().allMatch(c -> c == '_'))) {
            fg(out, at + indent, at + line.length(), style.marker());
            return;
        }
        int body = indent + listMarker(line, indent);
        if (body > indent) {
            fg(out, at + indent, at + body - 1, style.marker());
        }
        inline(out, line, at, body, style);
    }

    /** Inline code, emphasis and links, left to right, never one inside another. */
    private static void inline(List<Span> out, String line, int at, int from, Style style) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            int next = switch (c) {
                case '`' -> code(out, line, at, i, style);
                case '[' -> link(out, line, at, i, style);
                case '*', '_' -> emphasis(out, line, at, i, style);
                default -> -1;
            };
            i = next > i ? next : i + 1;
        }
    }

    /** {@code `code`}: the run of backticks that opens it is the run that closes it. Returns where it ended. */
    private static int code(List<Span> out, String line, int at, int i, Style style) {
        int ticks = run(line, i, '`');
        int close = line.indexOf("`".repeat(ticks), i + ticks);
        if (close < 0) {
            return -1;
        }
        int end = close + ticks;
        fg(out, at + i, at + i + ticks, style.marker());
        fg(out, at + i + ticks, at + close, style.code());
        fg(out, at + close, at + end, style.marker());
        bg(out, at + i, at + end, style.codeBackground());
        return end;
    }

    /** {@code [text](target)}: the text is coloured and underlined, the punctuation and the target are quiet. */
    private static int link(List<Span> out, String line, int at, int i, Style style) {
        int closeText = line.indexOf("](", i + 1);
        if (closeText < 0) {
            return -1;
        }
        int closeTarget = line.indexOf(')', closeText + 2);
        if (closeTarget < 0) {
            return -1;
        }
        fg(out, at + i, at + i + 1, style.marker());
        if (closeText > i + 1) {
            out.add(new Span(at + i + 1, at + closeText, style.link(), null, true));
        }
        fg(out, at + closeText, at + closeTarget + 1, style.marker());
        return closeTarget + 1;
    }

    /**
     * {@code *em*}, {@code **strong**}, and the same with underscores. The atlas has one weight, so emphasis is a
     * colour rather than a face. An underscore inside a word ({@code snake_case_name}) is not emphasis, and an
     * opening delimiter followed by a space is not one either, which is what keeps a list of {@code a * b} alone.
     */
    private static int emphasis(List<Span> out, String line, int at, int i, Style style) {
        char d = line.charAt(i);
        int n = Math.min(run(line, i, d), 2);
        int open = i + n;
        if (open >= line.length() || line.charAt(open) == ' ' || (d == '_' && i > 0 && wordy(line.charAt(i - 1)))) {
            return -1;
        }
        String delimiter = String.valueOf(d).repeat(n);
        int close = line.indexOf(delimiter, open + 1);
        while (close >= 0 && (line.charAt(close - 1) == ' '
                || (d == '_' && close + n < line.length() && wordy(line.charAt(close + n))))) {
            close = line.indexOf(delimiter, close + 1);
        }
        if (close < 0) {
            return -1;
        }
        fg(out, at + i, at + open, style.marker());
        fg(out, at + open, at + close, style.emphasis());
        fg(out, at + close, at + close + n, style.marker());
        return close + n;
    }

    /** The width of a list marker ({@code - }, {@code * }, {@code + }, {@code 1. }, {@code 1) }) at {@code i}, with its space; 0 for none. */
    private static int listMarker(String line, int i) {
        if (i + 1 < line.length() && "-*+".indexOf(line.charAt(i)) >= 0 && line.charAt(i + 1) == ' ') {
            return 2;
        }
        int digits = 0;
        while (i + digits < line.length() && Character.isDigit(line.charAt(i + digits)) && digits < 9) {
            digits++;
        }
        int after = i + digits;
        if (digits > 0 && after + 1 < line.length() && (line.charAt(after) == '.' || line.charAt(after) == ')')
                && line.charAt(after + 1) == ' ') {
            return digits + 2;
        }
        return 0;
    }

    private static int indent(String line) {
        return run(line, 0, ' ');
    }

    private static int run(String s, int i, char c) {
        int n = 0;
        while (i + n < s.length() && s.charAt(i + n) == c) {
            n++;
        }
        return n;
    }

    private static boolean wordy(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static void fg(List<Span> out, int start, int end, Color color) {
        if (end > start && color != null) {
            out.add(Span.foreground(start, end, color));
        }
    }

    private static void bg(List<Span> out, int start, int end, Color color) {
        if (end > start && color != null) {
            out.add(Span.background(start, end, color));
        }
    }
}
