package dev.mainframe;

import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.Caption;
import dev.mainframe.core.Messages.Go;
import dev.mainframe.core.Messages.Open;
import dev.mainframe.core.Messages.Status;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Tabs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages a user's gestures send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, so the editor and the
 * jobs run on their own lanes here, exactly as in the window. The view runs in frames, and there is no loop to run
 * them, so the test runs them while it waits ({@code VexelApplication.frame}): this thread is the GUI thread. No window
 * opens, so the framework never says {@code WindowOpened} for the main one: no session is restored and no settings are
 * written, and this test leaves the user's own settings alone.
 *
 * <p>The test listens on the bus as one more subscriber, which is how it sees what the editor told the view. Every
 * part answers in its own time, so the test waits for what it asked for, running frames, rather than sleeping.
 */
class ViewTest {

    @TempDir
    Path dir;

    private Shell shell;
    private MainFrameAppWiring wiring;
    private Workspace ws;
    /** Every caption and status line the editor sent, in order. */
    private final List<Object> said = new CopyOnWriteArrayList<>();
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
        shell.bus().subscribe(Messages.CAPTION_TOPIC, said::add);
        shell.bus().subscribe(Messages.STATUS_TOPIC, said::add);
        ws = wiring.ui().workspace();
    }

    /** Dialogs that answer a question with {@link #answer}, and cancel every file picker. */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answer.accept(modal);
        }

        @Override
        public void pick(PickFile request, Sender<Picked> picked) {
            picked.send(Picked.cancelled(request.id()));
        }
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    /**
     * Run frames until {@code test} holds. The view hears only in a frame, and this thread runs them, so it is the GUI
     * thread here as the loop's is in the window: what the test reads of the view is read where the view writes it.
     */
    private void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        VexelApplication.frame(shell);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
            VexelApplication.frame(shell);
        }
    }

    /**
     * Answer every question as pressing the button labelled {@code label} would: send the message the button declares,
     * which a dialog carries as data. Remember what was asked.
     */
    private Consumer<Modal> pressing(String label, List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            Object message = modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow()
                    .message();
            shell.bus().publish(Answer.TOPIC, (Answer) message);
        };
    }

    /**
     * Ask what the framework asks when the main window's close button is pressed, and run {@code proceeded} or
     * {@code cancelled} on the answer, as the framework does with the window's own two.
     */
    private void quit(Runnable proceeded, Runnable cancelled) {
        long ticket = tickets.incrementAndGet();
        shell.bus().subscribe(CloseAnswer.TOPIC, a -> {
            if (a.ticket() == ticket) {
                (a.proceed() ? proceeded : cancelled).run();
            }
        });
        shell.bus().publish(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, ticket));
    }

    /** Far from the framework's own tickets, which start at 1, so its gate ignores these answers. */
    private final AtomicInteger tickets = new AtomicInteger(1_000_000);

    private void tell(Intent.Command command) {
        shell.bus().publish(Intent.INTENTS, new Intent(command));
    }

    private Buffer opened(Path file) throws InterruptedException {
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(file, true));
        eventually("the file to open", () -> ws.find(file) != null);
        return ws.find(file);
    }

    @Test
    void itStartsWithNothingOpen() {
        assertTrue(ws.all().isEmpty());
    }

    @Test
    void aFileOpensThroughTheEditorAndTheReadingJob() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertEquals("hello", b.field.text());
        eventually("the caption", () -> said.contains(new Caption("a.txt")));
    }

    @Test
    void typingMakesAFileUnsavedAndTheCaptionSaysSo() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertFalse(b.dirty());
        b.field.insert("!");
        eventually("the editor to say so", () -> said.contains(new Caption("• a.txt")));
    }

    @Test
    void savingWritesTheFileAndKeepsItsLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        Buffer b = opened(file);
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        eventually("unsaved", b::dirty);
        tell(Intent.Command.SAVE_FRONT);
        // Clean the moment the text is taken, written a moment later: wait for the write to say it landed.
        eventually("the status to say so", () -> said.contains(new Status("Saved crlf.txt", false)));
        assertFalse(b.dirty());
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        opened(a);
        opened(Files.writeString(dir.resolve("b.txt"), "b"));
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(a, true));
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
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        eventually("the folder", () -> dir.toAbsolutePath().equals(wiring.ui().navigator().folder()));
        shell.bus().publish(Messages.GO_TOPIC, new Go("new.txt"));
        Path created = dir.resolve("new.txt").toAbsolutePath().normalize();
        eventually("the file to be created and opened", () -> ws.find(created) != null);
        assertTrue(Files.isRegularFile(created));
    }

    /** The tree asks for the folder shown, a job reads it, and the view answers the tree with its rows. */
    @Test
    void showingAFolderListsItInTheTree() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a").toAbsolutePath().normalize();
        Files.createDirectory(dir.resolve("sub"));
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("a row for the file", () -> nav.hasRow(file));
        assertTrue(nav.isFolder(dir.resolve("sub").toAbsolutePath().normalize()), "the listing said which is a folder");
    }

    /**
     * More tabs than a mailbox holds, all unsaved, closed at once with Save all: one {@code Flush} and one
     * {@code CloseTabs} for the lot, and a retitle per tab that is the tab's last word rather than a backlog. Before
     * those were lists and state, this needed the view's mailboxes 4,096 deep.
     */
    @Test
    void closingMoreUnsavedTabsThanAMailboxHoldsSavesAndClosesThemAll() throws Exception {
        List<Buffer> tabs = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tabs.add(opened(Files.writeString(dir.resolve("f" + i + ".txt"), "")));
        }
        for (Buffer b : tabs) {
            b.field.insert("x");
        }
        eventually("every tab unsaved", () -> tabs.stream().allMatch(Buffer::dirty));
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Save all", asked);
        shell.bus().publish(Messages.CLOSE_TOPIC, new Messages.Close(tabs.stream().map(b -> b.id).toList()));
        eventually("every tab closed", () -> ws.all().isEmpty());
        assertEquals(List.of("Unsaved changes"), asked);
        for (int i = 0; i < 100; i++) {
            assertEquals("x", Files.readString(dir.resolve("f" + i + ".txt")));
        }
    }

    @Test
    void aBinaryFileIsRefusedOnTheStatusLine() throws Exception {
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {0, 1, 2, 3});
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(binary, true));
        eventually("the refusal", () -> said.stream().anyMatch(s -> s instanceof Status st && st.alert()
                && st.message().startsWith("Could not open blob.bin")));
        assertTrue(ws.all().isEmpty());
    }

    @Test
    void closingAnUnsavedTabAsksAndDontSaveCloses() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Don't save", asked);
        shell.bus().publish(Tabs.CHOSEN, new Tabs.Chosen(ws.address(), Workspace.CLOSE, ws.all().indexOf(b)));
        eventually("the tab to close", () -> ws.byId(b.id) == null);
        assertEquals(List.of("Unsaved changes"), asked);
        assertEquals("a", Files.readString(dir.resolve("a.txt")), "Don't save does not save");
    }

    @Test
    void quittingACleanSessionGoesWithoutAsking() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertTrue(asked.isEmpty());
    }

    @Test
    void quittingAsksAndCancelKeepsTheWindow() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        quit(proceeded::incrementAndGet, cancelled::incrementAndGet);
        eventually("the quit to be cancelled", () -> cancelled.get() == 1);
        assertEquals(List.of("Quit with unsaved changes?"), asked);
        assertEquals(0, proceeded.get());
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        Buffer first = opened(a);
        Buffer second = opened(c);
        first.field.insert("1");
        second.field.insert("2");
        eventually("both unsaved", () -> first.dirty() && second.dirty());
        answer = pressing("Save all", new CopyOnWriteArrayList<>());
        AtomicInteger proceeded = new AtomicInteger();
        quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertEquals("1a", Files.readString(a));
        assertEquals("2c", Files.readString(c));
    }
}
