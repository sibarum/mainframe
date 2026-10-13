package dev.mainframe;

import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's real tree, built headless by the generated wiring — no window, no GPU — and driven through the same
 * objects a user's keystrokes reach.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and stops, so everything {@code Recipes} builds
 * before the window exists is here, and {@code wiring.ui()} and {@code wiring.actions()} return exactly those. The
 * last recipe, the one that takes the {@code Shell}, does not run: no session is restored and no settings are
 * written, so this test leaves the user's own settings alone.
 */
class EditorTest {

    @TempDir
    Path dir;

    private Shell shell;
    private MainFrameAppWiring wiring;
    /** How this test answers a question: by default, it fails the test, since nothing should be asked unexpectedly. */
    private volatile Consumer<Modal> answer = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };

    @BeforeEach
    void build() {
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        // This test's dialogs in place of the framework's, which would hold every question for a window that never
        // comes. Taken back as late as WINDOW, so handing them over after the tree is built is in time.
        shell.dialogs(new Answering());
    }

    /** Dialogs that answer a question with {@link #answer}, and cancel every file picker. */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answer.accept(modal);
        }

        @Override
        public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }

        @Override
        public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }

        @Override
        public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                             Runnable cancelled) {
            cancelled.run();
        }
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    /** Handlers run on workers, so the test waits for what it asked for rather than sleeping. */
    private static void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /** Answer every question by pressing the button labelled {@code label}, and remember what was asked. */
    private static Consumer<Modal> pressing(String label, List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow().action().run();
        };
    }

    private Buffer opened(Path file) throws InterruptedException {
        wiring.actions().load(file, true);
        Workspace ws = wiring.ui().workspace();
        eventually("the file to open", () -> ws.find(file) != null);
        return ws.find(file);
    }

    @Test
    void itStartsWithNothingOpen() {
        assertTrue(wiring.ui().workspace().all().isEmpty());
        assertNull(wiring.model().doc().front());
    }

    @Test
    void typingMakesAFileUnsavedAndTheTitleSaysSo() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertFalse(b.dirty());
        b.field.insert("!");
        eventually("the model to hear", () -> wiring.model().doc().entry(b.id).dirty());
        assertEquals("• a.txt", wiring.model().doc().entry(b.id).title());
    }

    @Test
    void savingWritesTheFileAndKeepsItsLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        Buffer b = opened(file);
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        eventually("unsaved", b::dirty);
        wiring.actions().saveFront();
        // Clean the moment the bytes are taken, written a moment later: wait for the write to say it landed.
        eventually("the status to say so", () -> wiring.model().doc().status().startsWith("Saved"));
        assertFalse(b.dirty());
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        opened(a);
        opened(Files.writeString(dir.resolve("b.txt"), "b"));
        wiring.actions().open(a, true);
        Workspace ws = wiring.ui().workspace();
        eventually("a in front", () -> ws.front() != null && ws.front().path.equals(a.toAbsolutePath().normalize()));
        assertEquals(2, ws.all().size());
    }

    @Test
    void aMarkdownFileIsGivenSpansAndATextFileIsNot() throws Exception {
        Buffer md = opened(Files.writeString(dir.resolve("notes.md"), "# Title\n\nSome *emphasis*."));
        Buffer txt = opened(Files.writeString(dir.resolve("notes.txt"), "# Title\n\nSome *emphasis*."));
        eventually("the markdown to be marked", () -> !md.field.spans().isEmpty());
        assertTrue(txt.field.spans().isEmpty());
    }

    @Test
    void thePathBarCreatesAFileThatIsNotThereYet() throws Exception {
        wiring.actions().showFolder(dir);
        eventually("the folder", () -> dir.toAbsolutePath().equals(wiring.ui().navigator().folder()));
        wiring.actions().go("new.txt");
        Path created = dir.resolve("new.txt").toAbsolutePath().normalize();
        eventually("the file to be created and opened", () -> wiring.ui().workspace().find(created) != null);
        assertTrue(Files.isRegularFile(created));
    }

    @Test
    void aBinaryFileIsRefusedOnTheStatusLine() throws Exception {
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {0, 1, 2, 3});
        wiring.actions().load(binary, true);
        eventually("the refusal", () -> wiring.model().doc().status().startsWith("Could not open blob.bin"));
        assertTrue(wiring.ui().workspace().all().isEmpty());
    }

    @Test
    void closingAnUnsavedTabAsksAndDontSaveCloses() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new ArrayList<>();
        answer = pressing("Don't save", asked);
        wiring.actions().close(b);
        assertEquals(List.of("Unsaved changes"), asked);
        assertNull(wiring.ui().workspace().byId(b.id));
        assertEquals("a", Files.readString(dir.resolve("a.txt")), "Don't save does not save");
    }

    @Test
    void theCloseGateLetsACleanSessionGoWithoutAsking() {
        List<String> asked = new ArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        wiring.actions().guardClose(proceeded::incrementAndGet, () -> { });
        assertEquals(1, proceeded.get());
        assertTrue(asked.isEmpty());
    }

    @Test
    void theCloseGateAsksAndCancelKeepsTheWindow() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new ArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        wiring.actions().guardClose(proceeded::incrementAndGet, cancelled::incrementAndGet);
        assertEquals(List.of("Quit with unsaved changes?"), asked);
        assertEquals(0, proceeded.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        opened(a).field.insert("1");
        opened(c).field.insert("2");
        eventually("both unsaved", () -> wiring.model().doc().unsaved().size() == 2);
        answer = pressing("Save all", new ArrayList<>());
        AtomicInteger proceeded = new AtomicInteger();
        wiring.actions().guardClose(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertEquals("1a", Files.readString(a));
        assertEquals("2c", Files.readString(c));
    }
}
