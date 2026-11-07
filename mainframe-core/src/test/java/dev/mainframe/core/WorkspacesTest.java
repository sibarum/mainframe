package dev.mainframe.core;

import dev.mainframe.core.Messages.AddRow;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.DeleteWorkspace;
import dev.mainframe.core.Messages.EditWorkspaces;
import dev.mainframe.core.Messages.Gather;
import dev.mainframe.core.Messages.Gathered;
import dev.mainframe.core.Messages.Make;
import dev.mainframe.core.Messages.Made;
import dev.mainframe.core.Messages.Move;
import dev.mainframe.core.Messages.MoveRow;
import dev.mainframe.core.Messages.Moved;
import dev.mainframe.core.Messages.NameWanted;
import dev.mainframe.core.Messages.NewWorkspace;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.Prune;
import dev.mainframe.core.Messages.Recorded;
import dev.mainframe.core.Messages.Redo;
import dev.mainframe.core.Messages.RemoveRow;
import dev.mainframe.core.Messages.RenameRow;
import dev.mainframe.core.Messages.RenameWorkspace;
import dev.mainframe.core.Messages.RepointRow;
import dev.mainframe.core.Messages.Restore;
import dev.mainframe.core.Messages.Restored;
import dev.mainframe.core.Messages.Select;
import dev.mainframe.core.Messages.Showing;
import dev.mainframe.core.Messages.Stash;
import dev.mainframe.core.Messages.Stashed;
import dev.mainframe.core.Messages.Undo;
import dev.mainframe.core.Messages.WorkspaceNames;
import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import dev.vexelray.framework.core.WindowCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.HandClock;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Workspaces window's controller, alone, on a tick of this test's. The jobs are answered by a disk kept in a map
 * ({@link #disk}), as the real ones would answer, unless a test holds them ({@link #answering}) to see what waits.
 */
class WorkspacesTest {

    private static final Path API = Path.of("C:/work/api");
    private static final Path WEB = Path.of("C:/work/web");
    private static final Path DOCS = Path.of("C:/work/docs");

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Workspaces workspaces;
    private final List<Object> sent = new ArrayList<>();

    /** The workspaces folder: files by name, and what deleting put aside, by stash. */
    private final Map<String, Workspace> disk = new HashMap<>();
    private final Map<String, Workspace> aside = new HashMap<>();
    private int stashes;
    /** Whether the jobs answer as soon as they are asked. */
    private boolean answering = true;
    /** Every job asked, answered or not, in order. */
    private final List<Object> asked = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        tick = bus.tick(null).alarm(new HandClock(bus)).whenOverdue(Tick.Overdue.FAIL);
        workspaces = new Workspaces(tick.inbox("workspaces"));
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(Gather.TOPIC, Prune.TOPIC, Showing.TOPIC, NameWanted.TOPIC, Recorded.TOPIC,
                WorkspaceNames.TOPIC, ChangePrefs.TOPIC, WindowCommand.TOPIC, Write.TOPIC, Make.TOPIC, Move.TOPIC,
                Stash.TOPIC, Restore.TOPIC)) {
            heard.subscribe(t, sent::add, 256);
        }
    }

    @AfterEach
    void restoreTheFatalPolicy() {
        Atchung.onFatal(previous);
    }

    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        run();
    }

    private void run() {
        tick.run(System.nanoTime() + 60_000_000_000L);
        if (answering) answer();
    }

    /** Answers every job asked and not answered, as the real ones would on {@link #disk}, until none is left. */
    private void answer() {
        boolean any = true;
        while (any) {
            any = false;
            for (Object job : new ArrayList<>(sent)) {
                if (!isJob(job)) continue;
                sent.remove(job);
                asked.add(job);
                bus.publish(reply(job), replyTo(job));
                any = true;
            }
            tick.run(System.nanoTime() + 60_000_000_000L);
        }
    }

    private static boolean isJob(Object m) {
        return m instanceof Gather || m instanceof Write || m instanceof Make || m instanceof Move
                || m instanceof Stash || m instanceof Restore;
    }

    @SuppressWarnings("unchecked")
    private static <T> Topic<T> reply(Object job) {
        Topic<?> t = job instanceof Gather ? Gathered.TOPIC : job instanceof Write ? Written.TOPIC
                : job instanceof Make ? Made.TOPIC : job instanceof Move ? Moved.TOPIC
                : job instanceof Stash ? Stashed.TOPIC : Restored.TOPIC;
        return (Topic<T>) t;
    }

    @SuppressWarnings("unchecked")
    private <T> T replyTo(Object job) {
        Object r;
        if (job instanceof Gather) {
            r = new Gathered(disk.values().stream()
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name())).toList());
        } else if (job instanceof Write w) {
            disk.put(w.workspace().name(), w.workspace());
            r = new Written(w.workspace().name(), "");
        } else if (job instanceof Make m) {
            boolean there = disk.containsKey(m.name());
            if (!there) disk.put(m.name(), new Workspace(m.name(), List.of()));
            r = new Made(m.name(), m.entry(), m.turn(), there ? "taken" : "");
        } else if (job instanceof Move m) {
            Workspace w = disk.remove(m.from());
            disk.put(m.to(), w.withName(m.to()));
            r = new Moved(m.from(), m.to(), m.entry(), m.turn(), "");
        } else if (job instanceof Stash s) {
            String stash = s.name() + " " + (++stashes);
            aside.put(stash, disk.remove(s.name()));
            r = new Stashed(s.name(), stash, s.entry(), s.turn(), "");
        } else {
            Restore re = (Restore) job;
            Workspace w = aside.remove(re.stash());
            if (w != null) disk.put(re.name(), w);
            r = new Restored(re.name(), Optional.ofNullable(w), re.entry(), re.turn(), w == null ? "gone" : "");
        }
        return (T) r;
    }

    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.removeIf(kind::isInstance);
        return out;
    }

    private <T> List<T> wasAsked(Class<T> kind) {
        List<T> out = asked.stream().filter(kind::isInstance).map(kind::cast).toList();
        asked.removeIf(kind::isInstance);
        return out;
    }

    private Showing shown() {
        List<Showing> s = took(Showing.class);
        assertFalse(s.isEmpty(), "nothing was shown");
        return s.getLast();
    }

    private Workspace on(String name) {
        return workspaces.state().workspace(name).orElseThrow();
    }

    private static List<String> paths(Workspace w) {
        return w.entries().stream().map(Workspace.Entry::path).toList();
    }

    /** Two workspaces on disk, and the window opened. */
    private void opened() {
        disk.put("day", new Workspace("day", List.of(new Workspace.Entry("", API.toString()))));
        disk.put("night", new Workspace("night", List.of()));
        tell(EditWorkspaces.TOPIC, new EditWorkspaces("", false));
    }

    private long lastEntry() {
        return took(Recorded.class).getLast().entry();
    }

    @Test
    void openingReadsTheDiskShowsTheFirstAndOpensTheWindowAndClearsOldDeletesOnce() {
        opened();
        assertEquals(1, took(Prune.class).size());
        assertEquals(1, wasAsked(Gather.class).size());
        Showing s = shown();
        assertEquals(List.of("day", "night"), s.all().stream().map(Workspace::name).toList());
        assertEquals("day", s.selected());
        assertEquals(List.of(WindowCommand.show(Messages.WORKSPACES_WINDOW)), took(WindowCommand.class));
        assertEquals(List.of("day", "night"), took(WorkspaceNames.class).getLast().names());

        tell(EditWorkspaces.TOPIC, new EditWorkspaces("night", false));
        assertTrue(took(Prune.class).isEmpty(), "once a run");
        assertEquals("night", shown().selected());
    }

    @Test
    void theOneOpeningAtLaunchIsSaid() {
        opened();
        tell(Prefs.TOPIC, new Prefs(Preferences.DEFAULT.withStartWorkspace("night"), List.of(), "", List.of()));
        assertEquals("night", shown().launch());
    }

    @Test
    void newIsMadeOnDiskThenShownSelectedAsAStepWithTheKeyboardInItsName() {
        opened();
        sent.clear();
        tell(NewWorkspace.TOPIC, new NewWorkspace());
        assertEquals("New workspace", wasAsked(Make.class).getFirst().name());
        assertEquals("New workspace", shown().selected());
        assertEquals(1, took(Recorded.class).size());
        assertEquals(List.of(new NameWanted("New workspace")), took(NameWanted.class));
        tell(NewWorkspace.TOPIC, new NewWorkspace());
        assertEquals("New workspace 2", wasAsked(Make.class).getFirst().name());
    }

    @Test
    void theOpenDialogsNewRowMakesOneAfterReadingTheDisk() {
        disk.put("day", new Workspace("day", List.of()));
        disk.put("New workspace", new Workspace("New workspace", List.of()));
        tell(EditWorkspaces.TOPIC, new EditWorkspaces("", true));
        assertEquals("New workspace 2", wasAsked(Make.class).getFirst().name(), "named once the disk is read");
        assertEquals("New workspace 2", shown().selected());
    }

    @Test
    void aFolderAddedIsACardAtTheEndWithAScriptKeyAStepAndASave() {
        opened();
        sent.clear();
        tell(AddRow.TOPIC, new AddRow("day", WEB));
        Workspace day = on("day");
        assertEquals(List.of(API.toString(), WEB.toString()), paths(day));
        assertTrue(WorkspaceStore.validKey(day.entries().get(1).env()));
        assertEquals(1, took(Recorded.class).size());
        assertEquals(day, wasAsked(Write.class).getLast().workspace());
        assertEquals("web added to day.", shown().said());
    }

    @Test
    void oneSaveInFlightAndTheLatestAfterIt() {
        opened();
        answering = false;
        sent.clear();
        tell(AddRow.TOPIC, new AddRow("day", WEB));
        tell(AddRow.TOPIC, new AddRow("day", DOCS));
        tell(RemoveRow.TOPIC, new RemoveRow("day", 0));
        List<Write> writes = took(Write.class);
        assertEquals(1, writes.size(), "one in flight");
        assertEquals(2, paths(writes.getFirst().workspace()).size());
        tell(Written.TOPIC, new Written("day", ""));
        writes = took(Write.class);
        assertEquals(1, writes.size(), "the two waiting are one write");
        assertEquals(List.of(WEB.toString(), DOCS.toString()), paths(writes.getFirst().workspace()));
    }

    @Test
    void cardsMoveRenameAndRepointAndEachUndoesAndRedoes() {
        opened();
        tell(AddRow.TOPIC, new AddRow("day", WEB));
        tell(AddRow.TOPIC, new AddRow("day", DOCS));
        sent.clear();

        tell(MoveRow.TOPIC, new MoveRow("day", 2, 0));
        long moved = lastEntry();
        assertEquals(List.of(DOCS.toString(), API.toString(), WEB.toString()), paths(on("day")));

        tell(RenameRow.TOPIC, new RenameRow("day", 0, "  manual "));
        long renamed = lastEntry();
        assertEquals("manual", on("day").entries().getFirst().title());

        tell(RepointRow.TOPIC, new RepointRow("day", 1, Path.of("C:/work/api2")));
        long repointed = lastEntry();
        assertEquals("C:\\work\\api2", on("day").entries().get(1).path().replace('/', '\\'));

        String key = on("day").entries().get(1).env();
        tell(Undo.TOPIC, new Undo(repointed));
        assertEquals(API.toString(), on("day").entries().get(1).path());
        assertEquals(key, on("day").entries().get(1).env(), "the script stays the tab's");
        tell(Undo.TOPIC, new Undo(renamed));
        assertEquals("docs", on("day").entries().getFirst().title());
        tell(Undo.TOPIC, new Undo(moved));
        assertEquals(List.of(API.toString(), WEB.toString(), DOCS.toString()), paths(on("day")));
        assertTrue(shown().said().startsWith("Undone: "));
        assertEquals(paths(on("day")), paths(disk.get("day")), "and saved");

        tell(Redo.TOPIC, new Redo(moved));
        tell(Redo.TOPIC, new Redo(renamed));
        assertEquals(List.of("manual", "api", "web"),
                on("day").entries().stream().map(Workspace.Entry::title).toList());
        assertTrue(took(Recorded.class).isEmpty(), "taking a step back and forward is no new step");
    }

    @Test
    void aTabNamedAsItsFolderIsBlankAgain() {
        opened();
        tell(RenameRow.TOPIC, new RenameRow("day", 0, "other"));
        tell(RenameRow.TOPIC, new RenameRow("day", 0, "api"));
        assertEquals("", on("day").entries().getFirst().name());
    }

    @Test
    void aNameThatCannotBeAFilesOrIsTakenIsSaidAndIsNoStep() {
        opened();
        sent.clear();
        tell(RenameWorkspace.TOPIC, new RenameWorkspace("day", "Night"));
        assertEquals("There is already a workspace called Night.", shown().problem());
        tell(RenameWorkspace.TOPIC, new RenameWorkspace("day", "a/b"));
        assertTrue(shown().problem().contains("\"/\""));
        assertTrue(wasAsked(Move.class).isEmpty());
        assertTrue(took(Recorded.class).isEmpty());
        tell(Select.TOPIC, new Select("night"));
        assertEquals("", shown().problem(), "picking another clears it");
    }

    @Test
    void aRenameLandsThenIsAStepTakingTheSaveWaitingWithItAndSettingsStartFollowing() {
        opened();
        tell(Prefs.TOPIC, new Prefs(Preferences.DEFAULT.withStartWorkspace("day"), List.of(), "", List.of()));
        answering = false;
        sent.clear();
        tell(AddRow.TOPIC, new AddRow("day", WEB));          // its write is in flight
        tell(RenameWorkspace.TOPIC, new RenameWorkspace("day", "morning"));
        tell(AddRow.TOPIC, new AddRow("day", DOCS));         // a save of day waits behind the rename
        assertEquals(1, took(Write.class).size());
        tell(Written.TOPIC, new Written("day", ""));
        Move m = took(Move.class).getFirst();
        assertEquals(new Move("day", "morning", Messages.NO_ENTRY, Messages.Turn.DO), m);
        tell(Moved.TOPIC, new Moved("day", "morning", m.entry(), m.turn(), ""));
        Write w = took(Write.class).getFirst();
        assertEquals("morning", w.workspace().name(), "the waiting save went with the rename");
        assertEquals(3, w.workspace().entries().size());
        Showing s = shown();
        assertEquals("morning", s.selected());
        assertEquals("day renamed to morning.", s.said());
        assertEquals("morning", took(ChangePrefs.class).getLast().prefs().startWorkspace());
        assertEquals(3, took(Recorded.class).size());
    }

    @Test
    void aDeleteIsPutAsideAndUndoBringsItBackWhereItWaits() {
        opened();
        sent.clear();
        tell(DeleteWorkspace.TOPIC, new DeleteWorkspace("day"));
        long entry = lastEntry();
        Showing s = shown();
        assertEquals(List.of("night"), s.all().stream().map(Workspace::name).toList());
        assertEquals("night", s.selected());
        assertEquals("day deleted. Ctrl+Z brings it back.", s.said());
        assertFalse(disk.containsKey("day"));

        tell(Undo.TOPIC, new Undo(entry));
        Restore r = wasAsked(Restore.class).getLast();
        assertEquals("day 1", r.stash());
        assertEquals("day", shown().selected());
        assertEquals(1, on("day").entries().size());

        tell(Redo.TOPIC, new Redo(entry));
        assertFalse(disk.containsKey("day"));
        tell(Undo.TOPIC, new Undo(entry));
        assertEquals("day 2", wasAsked(Restore.class).getLast().stash(), "brought back from where it went last");
        assertTrue(disk.containsKey("day"));
    }

    @Test
    void undoingANewWorkspacePutsItAsideAndRedoBringsItBack() {
        opened();
        tell(NewWorkspace.TOPIC, new NewWorkspace());
        long entry = lastEntry();
        tell(Undo.TOPIC, new Undo(entry));
        assertFalse(disk.containsKey("New workspace"));
        tell(Redo.TOPIC, new Redo(entry));
        assertTrue(disk.containsKey("New workspace"));
        assertEquals("New workspace", shown().selected());
    }

    @Test
    void undoWalksBackAcrossTwoWorkspacesSelectingEach() {
        opened();
        tell(AddRow.TOPIC, new AddRow("day", WEB));
        long a = lastEntry();
        tell(AddRow.TOPIC, new AddRow("night", DOCS));
        long b = lastEntry();
        tell(RenameWorkspace.TOPIC, new RenameWorkspace("night", "evening"));
        long c = lastEntry();
        tell(AddRow.TOPIC, new AddRow("evening", WEB));
        long d = lastEntry();

        tell(Undo.TOPIC, new Undo(d));
        tell(Undo.TOPIC, new Undo(c));
        assertEquals("night", shown().selected());
        assertTrue(disk.containsKey("night"));
        tell(Undo.TOPIC, new Undo(b));
        tell(Undo.TOPIC, new Undo(a));
        assertEquals("day", shown().selected());
        assertEquals(1, disk.get("day").entries().size());
        assertTrue(disk.get("night").entries().isEmpty());

        for (long e : List.of(a, b, c, d)) tell(Redo.TOPIC, new Redo(e));
        assertEquals(List.of(DOCS.toString(), WEB.toString()), paths(disk.get("evening")));
        assertEquals(2, disk.get("day").entries().size());
    }
}
