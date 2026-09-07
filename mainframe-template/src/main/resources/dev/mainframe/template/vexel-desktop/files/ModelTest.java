package ${packageName};

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state model, which is the part of an application worth testing hardest.
 *
 * <p>No GUI, no window, no device. That is the point of keeping the document a plain value changed through one
 * committer: the interesting behaviour -- that concurrent edits all land, that a listener sees every version,
 * that a snapshot is coherent -- is testable in milliseconds and does not need a machine with a GPU.
 */
class ModelTest {

    @Test
    void startsAtZero() {
        Model model = new Model();
        assertEquals(0, model.doc().count());
    }

    @Test
    void bumpAdvancesTheCountAndTheNote() {
        Model model = new Model();
        model.bump();
        assertEquals(1, model.doc().count());
        assertEquals("counted 1", model.doc().note());
    }

    @Test
    void resetGoesBackToTheBeginning() {
        Model model = new Model();
        model.bump();
        model.bump();
        model.reset();
        assertEquals(Doc.initial(), model.doc());
    }

    @Test
    void everyChangeReachesTheListener() {
        Model model = new Model();
        AtomicInteger seen = new AtomicInteger();
        model.onChange(doc -> seen.incrementAndGet());
        model.bump();
        model.bump();
        assertEquals(2, seen.get());
    }

    /**
     * The one that justifies the relative-edit rule.
     *
     * <p>Sixteen threads counting a hundred times each is sixteen hundred, and it is sixteen hundred because
     * every edit is a function of the current value rather than a value computed from a stale read. Change
     * {@code bump} to read-then-write and this test fails -- intermittently, which is exactly why it is here.
     */
    @Test
    void concurrentEditsAllLand() throws InterruptedException {
        Model model = new Model();
        int threads = 16;
        int each = 100;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < each; i++) model.bump();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "the counting threads should finish");
        }
        assertEquals(threads * each, model.doc().count());
    }
}
