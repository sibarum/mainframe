package dev.mainframe;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session model: which files, which in front, which unsaved. No GUI, no window, no device — the shape of the
 * session is a plain value changed through one committer, which is what makes the interesting behaviour testable
 * in milliseconds: that concurrent changes all land, and that a listener finishes on the newest.
 */
class ModelTest {

    private static Doc.Entry entry(long id, String path) {
        return new Doc.Entry(id, Path.of(path), false);
    }

    @Test
    void startsEmpty() {
        Model model = new Model();
        assertTrue(model.doc().tabs().isEmpty());
        assertNull(model.doc().front());
    }

    @Test
    void openingPutsAFileInFront() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.opened(entry(2, "b.txt"));
        assertEquals(2, model.doc().tabs().size());
        assertEquals(2, model.doc().active());
    }

    @Test
    void closingTheFrontFileLeavesNoneInFrontUntilTheBarSaysWhich() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.opened(entry(2, "b.txt"));
        model.closed(2);
        assertEquals(Doc.NONE, model.doc().active());
        model.front(1);
        assertEquals(1, model.doc().active());
    }

    @Test
    void frontingAFileThatHasGoneChangesNothing() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.front(7);
        assertEquals(1, model.doc().active());
    }

    @Test
    void unsavedShowsInTheTitleAndInTheUnsavedList() {
        Model model = new Model();
        model.opened(entry(1, "dir/a.txt"));
        model.opened(entry(2, "b.md"));
        model.dirty(2, true);
        assertEquals("a.txt", model.doc().entry(1).title());
        assertEquals("• b.md", model.doc().entry(2).title());
        assertEquals(List.of(2L), model.doc().unsaved().stream().map(Doc.Entry::id).toList());
    }

    /**
     * The one that justifies relative edits: files opening and going unsaved on many workers at once. Every open
     * lands, and each file ends in the state its last change left it in. Change {@code Model.opened} to
     * read-then-write and this fails — intermittently, which is exactly why it is here.
     */
    @Test
    void concurrentChangesAllLand() throws InterruptedException {
        Model model = new Model();
        int threads = 8;
        int each = 50;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int base = t * each;
                pool.execute(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < each; i++) {
                            long id = base + i;
                            model.opened(entry(id, "f" + id));
                            model.dirty(id, true);
                            model.dirty(id, id % 2 == 0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "the threads should finish");
        }
        assertEquals(threads * each, model.doc().tabs().size());
        assertEquals(threads * each / 2, model.doc().unsaved().size());
    }

    @Test
    void theListenerFinishesOnTheNewestSession() throws InterruptedException {
        Model model = new Model();
        AtomicInteger last = new AtomicInteger();
        model.onChange(doc -> last.set(doc.tabs().size()));
        CountDownLatch done = new CountDownLatch(4);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int t = 0; t < 4; t++) {
                int base = t * 100;
                pool.execute(() -> {
                    for (int i = 0; i < 100; i++) {
                        model.opened(entry(base + i, "f" + (base + i)));
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        assertEquals(400, last.get());
    }

    @Test
    void theSessionRemembersFilesAndFolderButNotWhatIsUnsaved() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt")).withActive(1).withFolder(Path.of("proj"));
        Session.Saved saved = Session.Saved.of(doc);
        assertEquals(List.of("a.txt"), saved.files());
        assertEquals("proj", saved.folder());
        assertEquals("a.txt", saved.front());
        // Unsaved work and the status line are not remembered, so changing them causes no write.
        assertEquals(saved, Session.Saved.of(doc.withEntry(1, e -> e.withDirty(true)).withStatus("hi")));
    }
}
