package ${packageName};

/**
 * Everything the application knows, in one immutable value.
 *
 * <h2>Why a record, and why one</h2>
 *
 * <p>A snapshot rather than a set of fields somebody mutates. The frame loop reads this from the GUI thread
 * while handlers are writing it from workers, and a record handed over by a single reference swap is the whole
 * of the synchronisation story -- there is no instant at which a reader can see half an edit. Two mutable
 * fields updated one after the other have exactly that instant, and it is a frame in which the window shows a
 * state that never existed.
 *
 * <p>So: add fields here rather than adding state elsewhere. The moment a second place remembers something,
 * the two can disagree, and the disagreement will be a frame long and impossible to reproduce.
 *
 * <p>This one holds a counter and a note, which is a placeholder for whatever the application is actually
 * about. It is deliberately trivial and deliberately real: it goes through {@link Model}'s committer like
 * anything else would, so the pattern is already correct when the first real field arrives.
 */
record Doc(int count, String note) {

    /** What it is before anybody has touched it. */
    static Doc initial() {
        return new Doc(0, "click, or press R to reset");
    }

    Doc withCount(int value) {
        return new Doc(value, note);
    }

    Doc withNote(String value) {
        return new Doc(count, value);
    }
}
