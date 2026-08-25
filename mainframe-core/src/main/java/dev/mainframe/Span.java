package dev.mainframe;

/** A location in the source text, used to point at the exact thing that went wrong. */
public record Span(int line, int col, int length) {
    public static final Span NONE = new Span(0, 0, 0);

    public boolean known() { return line > 0; }

    public Span through(Span end) {
        if (!known()) return end;
        if (!end.known() || end.line != line) return this;
        return new Span(line, col, Math.max(length, end.col + end.length - col));
    }
}
