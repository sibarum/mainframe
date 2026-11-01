package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.Caption;
import dev.mainframe.core.Messages.Close;
import dev.mainframe.core.Messages.CloseTabs;
import dev.mainframe.core.Messages.Create;
import dev.mainframe.core.Messages.Created;
import dev.mainframe.core.Messages.Edited;
import dev.mainframe.core.Messages.FileRead;
import dev.mainframe.core.Messages.Find;
import dev.mainframe.core.Messages.Flush;
import dev.mainframe.core.Messages.Found;
import dev.mainframe.core.Messages.FrontTab;
import dev.mainframe.core.Messages.Go;
import dev.mainframe.core.Messages.Kind;
import dev.mainframe.core.Messages.Open;
import dev.mainframe.core.Messages.Read;
import dev.mainframe.core.Messages.Recall;
import dev.mainframe.core.Messages.Recalled;
import dev.mainframe.core.Messages.Remember;
import dev.mainframe.core.Messages.Retitle;
import dev.mainframe.core.Messages.Save;
import dev.mainframe.core.Messages.SaveFailed;
import dev.mainframe.core.Messages.ShowFolder;
import dev.mainframe.core.Messages.Status;
import dev.mainframe.core.Messages.Why;
import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowOpened;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller, alone: no window and no jobs. The {@link Editor} owns an inbox on a tick of this test's, as it will
 * own one on the framework's, and each test publishes what the view or a job would and runs the tick, then reads what
 * the editor sent. The jobs' replies are published by hand, which is what makes the interesting orders (a write that
 * fails, an answer to a question already answered) as easy to test as the common one.
 *
 * <p>The close tests are the witness of {@code docs/plans/chains.md}: closing a document with unsaved work is one
 * chain, from the message that asks to its end, whichever way the person answers.
 */
class EditorTest {

    @TempDir
    Path dir;

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Editor editor;
    /** Everything the editor sent, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        tick = bus.tick(null);
        editor = new Editor(tick.inbox("editor"));
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(Messages.SHOW_FOLDER_TOPIC, Messages.ADD_TAB_TOPIC, Messages.FRONT_TAB_TOPIC,
                Messages.CLOSE_TABS_TOPIC, Messages.RETITLE_TOPIC, Messages.CAPTION_TOPIC, Messages.STATUS_TOPIC,
                Ask.TOPIC, Messages.FLUSH_TOPIC, Messages.SAVE_FAILED_TOPIC, CloseAnswer.TOPIC,
                Messages.READ_TOPIC, Messages.WRITE_TOPIC, Messages.FIND_TOPIC, Messages.CREATE_TOPIC,
                Messages.RECALL_TOPIC, Messages.REMEMBER_TOPIC)) {
            listen(heard, t);
        }
    }

    @AfterEach
    void restoreTheFatalPolicy() {
        Atchung.onFatal(previous);
    }

    private <T> void listen(Inbox heard, Topic<T> topic) {
        heard.subscribe(topic, sent::add, 256);
    }

    /** Publish {@code message} as its sender would, and run the tick until nothing is left to deliver. */
    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    private Path file(String name) {
        return dir.resolve(name).toAbsolutePath().normalize();
    }

    /** Open {@code name} as the reader would answer it, and return the new tab's id. */
    private long opened(String name, String text) {
        Path file = file(name);
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(file, Why.OPEN, true, text, false, "", ""));
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        return added.getFirst().id();
    }

    /** Open {@code name} and type into it. */
    private long unsaved(String name) {
        long id = opened(name, name);
        tell(Messages.EDITED_TOPIC, new Edited(id, true));
        sent.clear();
        return id;
    }

    // ------------------------------------------------------------------ opening

    @Test
    void openingReadsTheFileThenAddsATabInFront() {
        Path file = file("a.txt");
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(file, Why.OPEN, true, "hello", false, "", ""));
        assertTrue(sent.contains(new AddTab(1, file, "hello", true)));
        assertTrue(sent.contains(new Caption("a.txt")));
        assertTrue(sent.contains(new Status("Opened a.txt", false)));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanReadingItAgain() {
        long a = opened("a.txt", "a");
        opened("b.txt", "b");
        tell(Messages.OPEN_TOPIC, new Open(dir.resolve("a.txt"), true));
        assertEquals(List.of(new FrontTab(a, true)), took(FrontTab.class));
        assertTrue(took(Read.class).isEmpty());
    }

    @Test
    void aSecondClickWhileTheFirstIsBeingReadReadsNothingMore() {
        Path file = file("a.txt");
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(1, took(Read.class).size());
    }

    @Test
    void aFileThatCannotBeOpenedIsRefusedOnTheStatusLine() {
        Path blob = file("blob.bin");
        tell(Messages.OPEN_TOPIC, new Open(blob, true));
        tell(Messages.FILE_READ_TOPIC, new FileRead(blob, Why.OPEN, true, "", false, "", "it is binary"));
        assertTrue(sent.contains(new Status("Could not open blob.bin: it is binary", true)));
        assertTrue(took(AddTab.class).isEmpty());
        tell(Messages.OPEN_TOPIC, new Open(blob, true));
        assertEquals(1, took(Read.class).size(), "a refusal does not leave the file marked as being read");
    }

    @Test
    void goingUnsavedRetitlesTheTabAndTheCaption() {
        long a = opened("a.txt", "a");
        tell(Messages.EDITED_TOPIC, new Edited(a, true));
        assertTrue(sent.contains(new Retitle(a, "• a.txt")));
        assertTrue(sent.contains(new Caption("• a.txt")));
    }

    @Test
    void thePathBarCreatesANameThatIsNotThereYetInTheFolderShown() {
        tell(Messages.RECALLED_TOPIC, new Recalled(Optional.of(dir), List.of(), Optional.empty(), List.of(), dir));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        sent.clear();
        tell(Messages.GO_TOPIC, new Go("new.txt"));
        Path wanted = file("new.txt");
        assertEquals(List.of(new Find(wanted, Why.GO)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(wanted, Why.GO, Kind.NEW));
        assertEquals(List.of(new Create(wanted)), took(Create.class));
        tell(Messages.CREATED_TOPIC, new Created(wanted, true, ""));
        assertTrue(sent.contains(new ShowFolder(dir)), "the folder is listed again");
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        tell(Messages.SAVE_TOPIC, new Save(added.getFirst().id(), "x", true));
        assertTrue(took(Write.class).getFirst().crlf(), "a new file has the line endings its job said");
    }

    // ------------------------------------------------------------------ closing: the witness

    @Test
    void closingASavedTabClosesItWithoutAsking() {
        long a = opened("a.txt", "a");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        assertEquals(List.of(new CloseTabs(List.of(a))), sent.stream().filter(CloseTabs.class::isInstance).toList());
        assertTrue(took(Ask.class).isEmpty());
        assertTrue(editor.state().closings().isEmpty(), "nothing is left waiting");
    }

    @Test
    void closingAnUnsavedTabAsksAndNothingClosesWhileThePersonDecides() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Unsaved changes", ask.title());
        assertEquals("Save the changes to a.txt before closing it?", ask.message());
        assertEquals(List.of("Save", "Don't save", "Cancel"), ask.buttons(), "default first, cancel last");
        assertEquals(1, editor.state().closings().size());
        assertTrue(editor.state().doc().entry(a) != null, "the tab is still open");
    }

    @Test
    void dontSaveClosesWithoutWriting() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 1));
        assertTrue(sent.contains(new CloseTabs(List.of(a))));
        assertTrue(took(Write.class).isEmpty(), "Don't save does not save");
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void saveWritesThenClosesOnceTheWriteHasLanded() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 0));
        assertEquals(List.of(new Flush(List.of(a))), took(Flush.class));
        tell(Messages.SAVE_TOPIC, new Save(a, "typed", true));
        Write w = took(Write.class).getFirst();
        assertEquals("typed", w.text());
        assertTrue(editor.state().doc().entry(a) != null, "nothing closes before the write lands");
        tell(Messages.WRITTEN_TOPIC, new Written(a, w.path(), ""));
        assertTrue(sent.contains(new CloseTabs(List.of(a))));
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void cancelClosesNothingAndForgetsTheQuestion() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        long question = took(Ask.class).getFirst().id();
        tell(Answer.TOPIC, new Answer(question, 2));
        assertTrue(took(CloseTabs.class).isEmpty());
        assertTrue(editor.state().closings().isEmpty());
        tell(Answer.TOPIC, new Answer(question, 1));
        assertTrue(took(CloseTabs.class).isEmpty(), "an answer to a question no longer asked does nothing");
    }

    @Test
    void severalTabsAreOneMessageAndOneQuestionAboutTheUnsavedOnes() {
        long a = opened("a.txt", "a");
        long b = unsaved("b.txt");
        long c = unsaved("c.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a, b, c)));
        List<Ask> asked = took(Ask.class);
        assertEquals(1, asked.size());
        assertEquals("2 files have unsaved changes: b.txt, c.txt.", asked.getFirst().message());
        tell(Answer.TOPIC, new Answer(asked.getFirst().id(), 1));
        assertEquals(List.of(new CloseTabs(List.of(a, b, c))), took(CloseTabs.class));
    }

    @Test
    void aSecondAnswerWhileTheSavesAreUnderWayChangesNothing() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        long question = took(Ask.class).getFirst().id();
        tell(Answer.TOPIC, new Answer(question, 0));
        tell(Answer.TOPIC, new Answer(question, 1));
        assertTrue(took(CloseTabs.class).isEmpty(), "the run waits on the write, not on another answer");
    }

    // ------------------------------------------------------------------ quitting: the same chain, another way in

    @Test
    void quittingWithNothingUnsavedProceedsWithoutAsking() {
        opened("a.txt", "a");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 7));
        assertEquals(List.of(new CloseAnswer(7, true)), sent.stream().filter(CloseAnswer.class::isInstance).toList());
        assertTrue(took(Ask.class).isEmpty());
    }

    @Test
    void saveAllThenQuitProceedsOnceEveryWriteHasLanded() {
        long a = unsaved("a.txt");
        long c = unsaved("c.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 9));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Quit with unsaved changes?", ask.title());
        tell(Answer.TOPIC, new Answer(ask.id(), 0));
        assertEquals(List.of(new Flush(List.of(a, c))), took(Flush.class));

        tell(Messages.SAVE_TOPIC, new Save(a, "1a", true));
        tell(Messages.SAVE_TOPIC, new Save(c, "2c", true));
        List<Write> writes = took(Write.class);
        assertEquals(List.of("1a", "2c"), writes.stream().map(Write::text).toList());
        tell(Messages.WRITTEN_TOPIC, new Written(a, writes.get(0).path(), ""));
        assertTrue(took(CloseAnswer.class).isEmpty(), "one of two has landed");
        tell(Messages.WRITTEN_TOPIC, new Written(c, writes.get(1).path(), ""));
        assertEquals(List.of(new CloseAnswer(9, true)), took(CloseAnswer.class));
        assertTrue(editor.state().doc().entry(a) != null, "a quit closes the window, not the tabs");
    }

    @Test
    void aWriteThatFailsCancelsTheQuitAndSaysWhy() {
        long a = unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 3));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 0));
        tell(Messages.SAVE_TOPIC, new Save(a, "x", true));
        Write w = took(Write.class).getFirst();
        tell(Messages.WRITTEN_TOPIC, new Written(a, w.path(), "disk full"));
        assertTrue(sent.contains(new SaveFailed(a)));
        assertTrue(sent.contains(new Status("Could not save a.txt: disk full", true)));
        assertEquals(List.of(new CloseAnswer(3, false)), took(CloseAnswer.class));
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void cancellingAQuitKeepsTheWindow() {
        unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 4));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 2));
        assertEquals(List.of(new CloseAnswer(4, false)), took(CloseAnswer.class));
    }

    @Test
    void anotherWindowClosesWithoutAskingWhateverIsUnsaved() {
        unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested("about", 5));
        assertEquals(List.of(new CloseAnswer(5, true)), took(CloseAnswer.class));
        assertTrue(took(Ask.class).isEmpty(), "closing the About window asked about the editor's files");
        assertTrue(editor.state().closings().isEmpty());
    }

    // ------------------------------------------------------------------ the session

    /** The tabs come back as they were: one file asked for at a time, the next when the last is answered. */
    @Test
    void theSessionComesBackInOrderAndIsRememberedOnlyAfterwards() {
        Path a = file("a.txt");
        Path b = file("b.txt");
        tell(WindowOpened.TOPIC, new WindowOpened("about"));
        assertTrue(took(Recall.class).isEmpty(), "another window opening brought the session back");
        tell(WindowOpened.TOPIC, new WindowOpened(CloseRequested.MAIN));
        assertEquals(List.of(new Recall()), took(Recall.class));

        tell(Messages.RECALLED_TOPIC, new Recalled(Optional.of(dir), List.of(a, b), Optional.of(a), List.of(), dir));
        assertEquals(List.of(new Find(dir, Why.RESTORE_FOLDER)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        assertEquals(List.of(new Read(a, Why.RESTORE_FILE, true)), took(Read.class), "only the first file");
        tell(Messages.FILE_READ_TOPIC, new FileRead(a, Why.RESTORE_FILE, true, "a", false, "", ""));
        assertFalse(sent.stream().anyMatch(Remember.class::isInstance), "nothing is remembered mid-restore");
        assertEquals(List.of(new Read(b, Why.RESTORE_FILE, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(b, Why.RESTORE_FILE, true, "b", false, "", ""));
        assertEquals(1, sent.stream().filter(FrontTab.class::isInstance).count(),
                "the remembered front comes back to the front");
        assertEquals(List.of(new Remember(Optional.of(dir), List.of(a, b), Optional.of(a))), took(Remember.class));
    }

    @Test
    void anUnchangedSessionIsNotRememberedTwice() {
        tell(Messages.RECALLED_TOPIC, new Recalled(Optional.empty(), List.of(), Optional.empty(), List.of(), dir));
        assertEquals(1, took(Remember.class).size());
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.GO, Kind.FOLDER));
        assertTrue(took(Remember.class).isEmpty(), "the same folder again is the same session");
    }
}
