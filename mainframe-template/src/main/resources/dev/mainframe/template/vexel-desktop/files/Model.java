package ${packageName};

import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Doc}, and the only way to change it.
 *
 * <h2>Relative edits, not absolute writes</h2>
 *
 * <p>Every change here is a <em>function applied to whatever the current value turns out to be</em>, committed
 * through {@link State}. The alternative -- a control that reads the value, computes a whole new one, and
 * writes it back -- produces something perfectly coherent with one change missing whenever two handlers
 * overlap, and <b>nothing reports it</b>. Under contention this costs a CAS retry; it can never cost an edit.
 *
 * <p>That is why the mutations are declared as {@link Committer}s rather than as a sealed edit type. Atchung
 * wants named commands: the names are what {@code State.mutationNames()} publishes, they are the hook a
 * replicated peer would bind to, and each carries its own payload type so the compiler checks the pairing.
 *
 * <h2>Handlers run on workers; this is the serialization point</h2>
 *
 * <p>Every control's {@code onChange} and {@code onClick} fires on the handler executor, so several can be in
 * flight at once. They all arrive here, and the CAS is what puts them in an order. <b>Nothing else in this
 * application needs a lock</b>, and if something starts to, that is the signal that state has escaped this
 * class.
 */
final class Model {

    private final State<Doc> state;

    /**
     * One committer for "apply this function".
     *
     * <p>A single generic mutation rather than one per control, and the trade is worth stating: the names are
     * the vocabulary a remote peer would bind to, so collapsing them to one costs that. It buys not having to
     * declare a committer per control before knowing what the controls are -- and when this state is worth
     * replicating, splitting one committer into named ones is a mechanical change the compiler drives. A
     * decision with a trigger, rather than a shortcut.
     */
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

    /** Apply a change to whatever the document currently is. */
    void change(UnaryOperator<Doc> change) {
        state.commit(edit, change);
    }

    /** One more. Relative, so two clicks landing at once are two. */
    void bump() {
        change(d -> d.withCount(d.count() + 1).withNote("counted " + (d.count() + 1)));
    }

    /** Back to the beginning. */
    void reset() {
        change(d -> Doc.initial());
    }

    /**
     * React to every change, on the committing thread.
     *
     * <p>Inline delivery, deliberately: the listener redraws what is derived from the document, and that is
     * already off the GUI thread because handlers are. Handing it to another executor would add a hop and an
     * ordering question for no gain.
     */
    void onChange(Consumer<Doc> listener) {
        state.onCommit(v -> listener.accept(v.value()));
    }

    /** The version counter -- what a test or an automation driver waits on to know a change landed. */
    long version() {
        return state.version();
    }
}
