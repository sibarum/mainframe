package dev.mainframe.concordance;

import dev.mainframe.api.MainFrame;

/**
 * Concordance, installed into a MainFrame.
 *
 * <pre>{@code
 * MainFrame shell = MainFrame.builder().build();
 * Concordances.install(shell);
 *
 * shell.execute("^concordance summary");
 * shell.execute("^concordance . usages area");
 * }</pre>
 *
 * <p>One call, for the reason {@code Assistants.install} is one call: a capability
 * that takes six lines to install is a capability people install differently in
 * six places.
 *
 * <p>A program rather than a command, and the caret is the honest part of it. What
 * runs is Concordance's own {@code main} in this JVM: it parses its own line, it
 * prints its own text, and what comes back down the pipe is lines rather than
 * rows. MainFrame is not in charge of what happens in there and the caret says so.
 * The typed version -- {@code usages area | where kind == call} -- is a different
 * piece of work against {@code concordance-index} directly, and this one is not a
 * step towards it so much as the thing worth having while it does not exist.
 */
public final class Concordances {

    private Concordances() {}

    /** Installs {@code ^concordance}. */
    public static void install(MainFrame shell) {
        shell.program(Query.SPEC, new Query());
    }
}
