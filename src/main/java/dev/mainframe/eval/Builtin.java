package dev.mainframe.eval;

import dev.mainframe.value.Value;

/**
 * A command that ships with MainFrame.
 *
 * <p>Read-only commands implement {@link #run}. Anything that changes the
 * filesystem implements {@link Planning} instead, so it gets dry-run support and
 * confirmation prompts for free -- and cannot forget to.
 */
public interface Builtin {

    Signature signature();

    default Value run(Args args) {
        throw new UnsupportedOperationException(signature().name() + " is a planning command");
    }

    /** A command that says what it will do before it does it. */
    interface Planning extends Builtin {
        Plan plan(Args args);
    }
}
