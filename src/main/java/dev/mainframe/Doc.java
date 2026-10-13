package dev.mainframe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Everything the application knows about its session, in one immutable value: which files are open, in what
 * order, which one is in front, which have unsaved work, which folder the navigator shows, and the last thing the
 * status line was told.
 *
 * <h2>Why a record, and why one</h2>
 *
 * <p>A snapshot rather than a set of fields somebody mutates. The title bar, the status line, the close gate and
 * the remembered session all read this from different threads while handlers write it from workers, and a record
 * handed over by a single reference swap is the whole synchronisation story: there is no instant at which a reader
 * sees half an edit. Add fields here rather than adding state elsewhere.
 *
 * <h2>What it does not hold is the text</h2>
 *
 * <p>Each document's text is already one versioned value — the {@code State<Document>} inside its
 * {@code TextField} — so copying it here would be a second place it lives, and the two would disagree for a frame
 * every keystroke. This is the part nothing else owns: the shape of the session.
 *
 * <p>A tab is named by an {@code id}, not an index. An index is a fact about the tab bar at one instant, and a
 * close landing between a read and a write turns it into the neighbour's.
 */
record Doc(List<Entry> tabs, long active, Path folder, String status) {

    /** No tab is in front. */
    static final long NONE = -1;

    /**
     * One open file.
     *
     * @param id    stable for the life of the tab
     * @param path  where it is on disk; every document here has one
     * @param dirty whether it differs from what was last loaded or saved
     */
    record Entry(long id, Path path, boolean dirty) {

        /** The tab's label: the file name, with a bullet in front while it is unsaved. */
        String title() {
            String name = String.valueOf(path.getFileName());
            return dirty ? "• " + name : name;
        }

        Entry withDirty(boolean value) {
            return new Entry(id, path, value);
        }
    }

    Doc {
        tabs = List.copyOf(tabs);
    }

    /** Nothing open. */
    static Doc initial() {
        return new Doc(List.of(), NONE, null, "");
    }

    /** The entry with {@code id}, or null if it has gone. */
    Entry entry(long id) {
        for (Entry e : tabs) {
            if (e.id() == id) {
                return e;
            }
        }
        return null;
    }

    /** The document in front, or null. */
    Entry front() {
        return entry(active);
    }

    /** Every document with unsaved work, in tab order. */
    List<Entry> unsaved() {
        return tabs.stream().filter(Entry::dirty).toList();
    }

    /** Every open file, in tab order — what a session remembers. */
    List<Path> files() {
        return tabs.stream().map(Entry::path).toList();
    }

    Doc withTab(Entry entry) {
        List<Entry> next = new ArrayList<>(tabs);
        next.add(entry);
        return new Doc(next, active, folder, status);
    }

    Doc without(long id) {
        List<Entry> next = new ArrayList<>(tabs);
        next.removeIf(e -> e.id() == id);
        return new Doc(next, active == id ? NONE : active, folder, status);
    }

    /** Change one entry, whatever it currently is. A no-op on one that has gone. */
    Doc withEntry(long id, UnaryOperator<Entry> change) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.id() == id ? change.apply(e) : e);
        }
        return new Doc(next, active, folder, status);
    }

    Doc withActive(long id) {
        return new Doc(tabs, id, folder, status);
    }

    Doc withFolder(Path value) {
        return new Doc(tabs, active, value, status);
    }

    Doc withStatus(String value) {
        return new Doc(tabs, active, folder, value);
    }
}
