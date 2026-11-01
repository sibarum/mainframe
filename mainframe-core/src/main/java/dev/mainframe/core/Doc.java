package dev.mainframe.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the session, in one immutable value: which files are open, in what order, which one is in front,
 * which have unsaved work, and which folder the navigator shows.
 *
 * <h2>Whose it is</h2>
 *
 * <p>The {@link Editor}'s, and nobody else's. It is part of the editor's {@code State}, which only the editor's
 * declared steps change, and the view hears about a change as a message rather than by reading this. So it needs no
 * lock: a step runs on the editor's lane, one at a time, and returns the next value rather than changing this one.
 * What the session is goes here; what the editor is in the middle of (a question, a save, a restore) goes in the
 * {@code State}.
 *
 * <h2>What it does not hold is the text</h2>
 *
 * <p>Each document's text is already one versioned value, the {@code State<Document>} inside its {@code TextField} in
 * the view, so copying it here would be a second place it lives, and the two would disagree every keystroke. What is
 * here is the part nothing else owns: the shape of the session.
 *
 * <p>A tab is named by an {@code id}, not an index. An index is a fact about the tab bar at one instant, and a close
 * landing between a read and a write turns it into the neighbour's.
 */
record Doc(List<Entry> tabs, long active, Path folder) {

    /** No tab: none is in front. */
    static final long NONE = Messages.NO_TAB;

    /**
     * One open file.
     *
     * @param id    stable for the life of the tab
     * @param path  where it is on disk; every document here has one
     * @param dirty whether it differs from what was last loaded or saved
     * @param crlf  whether the file used {@code \r\n}, which a save puts back
     */
    record Entry(long id, Path path, boolean dirty, boolean crlf) {

        String name() {
            return String.valueOf(path.getFileName());
        }

        /** The tab's label: the file name, with a bullet in front while it is unsaved. */
        String title() {
            return dirty ? "• " + name() : name();
        }

        Entry withDirty(boolean value) {
            return new Entry(id, path, value, crlf);
        }
    }

    Doc {
        tabs = List.copyOf(tabs);
    }

    /** Nothing open. */
    static Doc initial() {
        return new Doc(List.of(), NONE, null);
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

    /** The open tab for {@code path}, or null. */
    Entry entry(Path path) {
        for (Entry e : tabs) {
            if (e.path().equals(path)) {
                return e;
            }
        }
        return null;
    }

    /** The document in front, or null. */
    Entry front() {
        return entry(active);
    }

    /** Every open file, in tab order: what a session remembers. */
    List<Path> files() {
        return tabs.stream().map(Entry::path).toList();
    }

    Doc withTab(Entry entry) {
        List<Entry> next = new ArrayList<>(tabs);
        next.add(entry);
        return new Doc(next, active, folder);
    }

    Doc without(long id) {
        List<Entry> next = new ArrayList<>(tabs);
        next.removeIf(e -> e.id() == id);
        return new Doc(next, active == id ? NONE : active, folder);
    }

    /** Mark one entry unsaved or saved. A no-op on one that has gone. */
    Doc withDirty(long id, boolean dirty) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.id() == id ? e.withDirty(dirty) : e);
        }
        return new Doc(next, active, folder);
    }

    Doc withActive(long id) {
        return new Doc(tabs, id, folder);
    }

    Doc withFolder(Path value) {
        return new Doc(tabs, active, value);
    }
}
