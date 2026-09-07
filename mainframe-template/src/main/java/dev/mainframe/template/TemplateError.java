package dev.mainframe.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Something wrong with a template, or with what somebody asked to be done with one.
 *
 * <p>Carries hints as well as a message, because the engine is where the reason is
 * actually known and the adapter is not going to be able to invent it later.
 * MainFrame's own error type would have been the natural thing to throw, and is
 * deliberately not thrown: it lives in the shell, and this package does not know
 * there is a shell. The adapter re-throws these as {@code MfError} with a code,
 * which is the one place the two vocabularies meet.
 */
public final class TemplateError extends RuntimeException {

    private final List<String> hints;

    private TemplateError(String message, List<String> hints, Throwable cause) {
        super(message, cause);
        this.hints = List.copyOf(hints);
    }

    public static Builder of(String message) { return new Builder(message); }

    /** What to do about it. */
    public List<String> hints() { return hints; }

    public static final class Builder {

        private final String message;
        private final List<String> hints = new ArrayList<>();
        private Throwable cause;

        private Builder(String message) { this.message = message; }

        public Builder hint(String hint) {
            if (hint != null && !hint.isBlank()) hints.add(hint);
            return this;
        }

        public Builder because(Throwable cause) { this.cause = cause; return this; }

        public TemplateError build() { return new TemplateError(message, hints, cause); }
    }
}
