package dev.mainframe.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session as a value: which files, which in front, which unsaved. No thread and no window. */
class DocTest {

    private static Doc.Entry entry(long id, String path) {
        return new Doc.Entry(id, Path.of(path), false, false);
    }

    @Test
    void startsEmpty() {
        Doc doc = Doc.initial();
        assertTrue(doc.tabs().isEmpty());
        assertNull(doc.front());
    }

    @Test
    void closingTheFrontFileLeavesNoneInFront() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt")).withTab(entry(2, "b.txt")).withActive(2).without(2);
        assertEquals(Doc.NONE, doc.active());
        assertEquals(List.of(Path.of("a.txt")), doc.files());
    }

    @Test
    void unsavedShowsInTheTitle() {
        Doc doc = Doc.initial().withTab(entry(1, "dir/a.txt")).withTab(entry(2, "b.md"))
                .withDirty(2, true);
        assertEquals("a.txt", doc.entry(1).title());
        assertEquals("• b.md", doc.entry(2).title());
    }

    @Test
    void changingAnEntryThatHasGoneChangesNothing() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt"));
        assertEquals(doc, doc.withDirty(7, true));
    }

    @Test
    void anOpenFileIsFoundByItsPath() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt"));
        assertEquals(1, doc.entry(Path.of("a.txt")).id());
        assertNull(doc.entry(Path.of("b.txt")));
    }
}
