package dev.mainframe;

import java.util.ArrayList;
import java.util.List;

/**
 * The only exception MainFrame shows a user. Every one carries a code, the span
 * that caused it and at least one actionable hint -- "no silent failures, every
 * error explains what to do next" is a hard rule, not a nice-to-have.
 */
public class MfError extends RuntimeException {

    private final String code;
    private final Span span;
    private final List<String> hints;

    public MfError(String code, String message, Span span, List<String> hints) {
        super(message);
        this.code = code;
        this.span = span == null ? Span.NONE : span;
        this.hints = List.copyOf(hints);
    }

    public static Builder of(String code, String message) { return new Builder(code, message); }

    public String code() { return code; }
    public Span span() { return span; }
    public List<String> hints() { return hints; }

    /** Returns a copy carrying {@code span}, for when only the caller knows the location. */
    public MfError at(Span s) {
        return span.known() || s == null || !s.known() ? this : new MfError(code, getMessage(), s, hints);
    }

    public static final class Builder {
        private final String code;
        private final String message;
        private Span span = Span.NONE;
        private final List<String> hints = new ArrayList<>();

        private Builder(String code, String message) { this.code = code; this.message = message; }

        public Builder at(Span s) { this.span = s == null ? Span.NONE : s; return this; }
        public Builder hint(String h) { if (h != null) hints.add(h); return this; }
        public MfError build() { return new MfError(code, message, span, hints); }
        public <T> T raise() { throw build(); }
    }
}
