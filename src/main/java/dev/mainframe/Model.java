package dev.mainframe;

import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Doc}, and the only way to change it.
 *
 * <h2>Relative edits, not absolute writes</h2>
 *
 * <p>Every change here is a <em>function applied to whatever the current value turns out to be</em>, committed
 * through {@link State}. A tab closing on one worker and a document going dirty on another both land, in some
 * order, and neither is lost. The alternative — read the value, compute a whole new one, write it back — produces
 * something perfectly coherent with one change missing whenever two handlers overlap, and nothing reports it.
 *
 * <p>One generic committer rather than one per command. The names would be the vocabulary a replicated peer binds
 * to, and nothing replicates this; splitting it is a mechanical change the compiler drives when something does.
 *
 * <h2>Handlers run on workers; this is the serialisation point</h2>
 *
 * <p>Nothing else in this application needs a lock for the session's shape, and if something starts to, that is
 * the signal that state has escaped this class. ({@link Workspace} has a lock, and it guards the widgets' tab
 * order, not this.)
 */
final class Model {

    private final State<Doc> state;
    private final Committer<Doc, UnaryOperator<Doc>> edit;

    Model() {
        State.Builder<Doc> builder = State.of(Doc.initial());
        // Declared before build, held as a handle: State refuses a committer it was not built with, which is
        // what stops an unrelated component minting its own way to write this.
        this.edit = builder.mutation("doc.edit", (current, change) -> change.apply(current));
        this.state = builder.build();
    }

    /** The latest coherent snapshot. Lock-free, safe from any thread. */
    Doc doc() {
        return state.value();
    }

    /** Apply a change to whatever the session currently is. */
    void change(UnaryOperator<Doc> change) {
        state.commit(edit, change);
    }

    void opened(Doc.Entry entry) {
        change(d -> d.withTab(entry).withActive(entry.id()));
    }

    void closed(long id) {
        change(d -> d.without(id));
    }

    void front(long id) {
        change(d -> d.entry(id) == null ? d : d.withActive(id));
    }

    void dirty(long id, boolean dirty) {
        change(d -> d.withEntry(id, e -> e.withDirty(dirty)));
    }

    void folder(Path folder) {
        change(d -> d.withFolder(folder));
    }

    /** Say something on the status line. */
    void say(String message) {
        change(d -> d.withStatus(message));
    }

    /**
     * React to every change, in order, one at a time, always finishing on the newest.
     *
     * <p>{@code State.onCommit} delivers on the committing thread <em>after</em> its compare-and-set, and handlers
     * commit from a pool — so two finishing together reach a listener as version 6 then 5, and a listener that
     * redraws from the document would end on a stale one. {@code onCommitLatest} serialises delivery and drops a
     * snapshot older than the last delivered.
     *
     * <p><b>It serialises by making a committer wait</b> for any delivery already running on another thread. So
     * never commit here while holding a lock the listener also takes: that is two threads each waiting for the
     * other, with nothing thrown. {@link Workspace} is written around exactly that rule.
     *
     * <p>And the order it keeps is among its own deliveries only. A listener that is also called directly, from
     * somewhere else, can be overtaken by an older delivery; read {@link #doc()} inside it rather than trusting the
     * snapshot it was handed, as {@link Session#remember} does.
     */
    void onChange(Consumer<Doc> listener) {
        state.onCommitLatest(v -> listener.accept(v.value()));
    }

    /** The version counter — what a test waits on to know a change landed. */
    long version() {
        return state.version();
    }
}
