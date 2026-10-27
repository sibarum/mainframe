package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.Answer;
import dev.mainframe.core.Messages.Ask;
import dev.mainframe.core.Messages.Begin;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.CloseWindow;
import dev.mainframe.core.Messages.Created;
import dev.mainframe.core.Messages.Deleted;
import dev.mainframe.core.Messages.EditName;
import dev.mainframe.core.Messages.Focus;
import dev.mainframe.core.Messages.Gone;
import dev.mainframe.core.Messages.Insert;
import dev.mainframe.core.Messages.InsertChecked;
import dev.mainframe.core.Messages.Launch;
import dev.mainframe.core.Messages.Launched;
import dev.mainframe.core.Messages.ListAt;
import dev.mainframe.core.Messages.Listing;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.PickFolder;
import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.mainframe.core.Messages.Renamed;
import dev.mainframe.core.Messages.Resolve;
import dev.mainframe.core.Messages.Resolved;
import dev.mainframe.core.Messages.Tile;
import dev.mainframe.core.Messages.Who;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller, alone: no window, no lanes, no jobs, no shells. Each test hands the {@link Frame} a message the way
 * its lane would, one at a time on this thread, and reads what it sent back. The jobs' replies and the view's word
 * are handed in by hand, which is what makes the interesting orders (a set prepared out of order, the last tab gone
 * while more are on their way) as easy to test as the common one.
 */
class FrameTest {

    @TempDir
    Path dir;

    private AppSettings settings;
    private Frame frame;
    /** Everything the frame published, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        Atchung bus = Atchung.create();
        settings = new AppSettings(Settings.at(dir.resolve("settings.properties")));
        frame = new Frame(bus, settings);
        for (Topic<?> t : List.of(Messages.ADD_TAB_TOPIC, Messages.CLOSE_TAB_TOPIC, Messages.FOCUS_TOPIC,
                Messages.CLOSE_WINDOW_TOPIC, Messages.ASK_TOPIC, Messages.TELL_TOPIC, Messages.TILE_TOPIC,
                Messages.PICK_FOLDER_TOPIC, Messages.EDIT_NAME_TOPIC, Messages.RESOLVE_TOPIC, Messages.PREPARE_TOPIC,
                Messages.CHECK_INSERT_TOPIC, Messages.LAUNCH_TOPIC, Messages.MAKE_SCRIPT_TOPIC,
                Messages.OPEN_IN_IDE_TOPIC, Messages.LIST_TOPIC, Messages.FIND_TOPIC, Messages.PRUNE_TOPIC)) {
            listen(bus, t);
        }
    }

    @AfterEach
    void forget() {
        Command.launched(Command.NONE);
    }

    private <T> void listen(Atchung bus, Topic<T> topic) {
        bus.subscribe(topic, sent::add);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    /** Answer every {@link Prepare} sent so far, in the order given by {@code order} (indexes into them). */
    private List<AddTab> prepare(int... order) {
        List<Prepare> asked = took(Prepare.class);
        List<Integer> sequence = new ArrayList<>();
        if (order.length == 0) {
            for (int i = 0; i < asked.size(); i++) sequence.add(i);
        } else {
            for (int i : order) sequence.add(i);
        }
        for (int i : sequence) {
            Prepare p = asked.get(i);
            Path where = p.dir() != null ? p.dir() : dir;
            frame.prepared(new Prepared(p.id(), where, p.line() + " #" + p.id(), ""));
        }
        return took(AddTab.class);
    }

    /** The launch with no tabs named: the one plain tab, opened and spare. Its id. */
    private long plain() {
        frame.begin(new Begin());
        List<AddTab> tabs = prepare();
        assertEquals(1, tabs.size());
        return tabs.getFirst().id();
    }

    // -- the launch -------------------------------------------------------------------------------------------

    @Test
    void aLaunchThatNamesNothingOpensOnePlainTabInTheNewTabFolder() {
        frame.begin(new Begin());
        List<Prepare> asked = took(Prepare.class);
        assertEquals(1, asked.size());
        assertNull(asked.getFirst().dir(), "a plain tab starts in the new tab folder, which the job looks at");
        assertEquals(settings.shellCommand(), asked.getFirst().line());
        assertEquals("", asked.getFirst().workspace());

        frame.prepared(new Prepared(asked.getFirst().id(), dir, "pwsh", ""));
        AddTab tab = took(AddTab.class).getFirst();
        assertTrue(tab.front());
        assertEquals("", tab.title(), "the shell names it");
        assertFalse(tab.workspace() || tab.row());
        assertEquals(Messages.NO_TAB, tab.replaces());
    }

    @Test
    void theStartWorkspaceOpensWhenTheLaunchNamesNoTabs() {
        settings.startWorkspace("day");
        frame.begin(new Begin());
        Resolve r = took(Resolve.class).getFirst();
        assertEquals(Command.Kind.WORKSPACE, r.kind());
        assertEquals("day", r.value());

        Workspace day = new Workspace("day", List.of(
                new Workspace.Entry("api", dir.resolve("api").toString()).withEnv("api-000001.ps1"),
                new Workspace.Entry("web", dir.resolve("web").toString()).withEnv("web-000002.ps1")));
        frame.resolved(new Resolved(r.seq(), "day", Workspace.format(day)));
        List<Prepare> asked = took(Prepare.class);
        assertEquals(List.of("api-000001.ps1", "web-000002.ps1"), asked.stream().map(Prepare::env).toList());
        assertTrue(asked.stream().allMatch(p -> p.workspace().equals("day")));
    }

    /** Sources are resolved one at a time, so the tabs come up in the order they were named. */
    @Test
    void theCommandLinesSourcesAreResolvedInOrder() {
        Command.launched(Command.parse(new String[] {"--dir=" + dir.resolve("a"), "--dir=" + dir.resolve("b")}));
        frame.begin(new Begin());
        List<Resolve> first = took(Resolve.class);
        assertEquals(1, first.size(), "the second waits for the first");
        frame.resolved(new Resolved(first.getFirst().seq(), "",
                Workspace.format(new Workspace("", List.of(new Workspace.Entry("", dir.resolve("a").toString()))))));
        Resolve second = took(Resolve.class).getFirst();
        assertEquals(dir.resolve("b").toString(), second.value());
        frame.resolved(new Resolved(second.seq(), "",
                Workspace.format(new Workspace("", List.of(new Workspace.Entry("", dir.resolve("b").toString()))))));

        List<AddTab> tabs = prepare(1, 0);   // the second shell is ready first
        assertEquals(List.of("a", "b"), tabs.stream().map(AddTab::title).toList(), "named after their folders, in order");
        assertEquals(List.of(true, false), tabs.stream().map(AddTab::front).toList(), "the first in front");
    }

    @Test
    void aLaunchWhoseSourcesAreAllGoneStillGetsThePlainTab() {
        Command.launched(Command.parse(new String[] {"--workspace=nope"}));
        frame.begin(new Begin());
        Resolve r = took(Resolve.class).getFirst();
        frame.resolved(new Resolved(r.seq(), "", Workspace.format(new Workspace("", List.of()))));
        assertEquals(1, prepare().size());
    }

    @Test
    void insertPathIsCheckedForTheFirstTabsFolderAndTypedIntoIt() {
        Command.launched(Command.parse(new String[] {"--dir=" + dir, "--insert-path=" + dir.resolve("notes.md")}));
        frame.begin(new Begin());
        Resolve r = took(Resolve.class).getFirst();
        frame.resolved(new Resolved(r.seq(), "",
                Workspace.format(new Workspace("", List.of(new Workspace.Entry("", dir.toString()))))));
        Insert insert = took(Insert.class).getFirst();
        assertEquals(dir, insert.dir());

        frame.insertChecked(new InsertChecked("notes.md", ""));
        AddTab tab = prepare().getFirst();
        assertEquals("notes.md", tab.type());
    }

    // -- the spare tab ----------------------------------------------------------------------------------------

    @Test
    void aFolderOpenedTakesTheUnusedPlainTabsPlace() {
        long spare = plain();
        frame.openFolder(new OpenFolder(dir, spare));
        assertEquals(spare, prepare().getFirst().replaces());
    }

    @Test
    void aTabTheViewSaysWasUsedIsNotReplaced() {
        plain();
        frame.openFolder(new OpenFolder(dir, Messages.NO_TAB));
        assertEquals(Messages.NO_TAB, prepare().getFirst().replaces());
    }

    @Test
    void aPlainTabIsNoLongerSpareOnceAnotherHasOpened() {
        long spare = plain();
        frame.newTab(new NewTab());
        prepare();
        frame.gone(new Gone(spare + 1));
        frame.openFolder(new OpenFolder(dir, spare));
        assertEquals(Messages.NO_TAB, prepare().getFirst().replaces());
    }

    @Test
    void aWorkspaceInAWindowOfItsOwnClosesTheSpareTabOnceItHasStarted() {
        long spare = plain();
        frame.openWorkspace(new OpenWorkspace("day", false, spare));
        Launch launch = took(Launch.class).getFirst();
        assertEquals(spare, launch.spare());
        frame.launched(new Launched("day", launch.spare(), ""));
        assertEquals(List.of(new CloseTab(spare)), took(CloseTab.class));

        frame.launched(new Launched("day", spare, "no such file"));
        assertTrue(took(CloseTab.class).isEmpty(), "one that did not start leaves this window as it is");
    }

    @Test
    void aWorkspaceOpenedHereWithNoFolderThereGivesTheKeyboardBack() {
        plain();
        frame.openWorkspace(new OpenWorkspace("day", true, Messages.NO_TAB));
        Resolve r = took(Resolve.class).getFirst();
        frame.resolved(new Resolved(r.seq(), "day", Workspace.format(new Workspace("day", List.of()))));
        assertEquals(List.of(new Focus(Messages.NO_TAB)), took(Focus.class));
    }

    // -- closing ----------------------------------------------------------------------------------------------

    @Test
    void ctrlWAsksAndCancelGoesBackToTheTab() {
        long id = plain();
        frame.closeAsked(new CloseAsked(id, "pwsh", true));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Close pwsh?", ask.title());
        frame.answer(new Answer(ask.question(), false));
        assertEquals(List.of(new Focus(id)), took(Focus.class));

        frame.closeAsked(new CloseAsked(id, "pwsh", true));
        frame.answer(new Answer(took(Ask.class).getFirst().question(), true));
        assertEquals(List.of(new CloseTab(id)), took(CloseTab.class));
    }

    @Test
    void theLastTabGoneClosesTheWindowUnlessMoreAreOnTheirWay() {
        long id = plain();
        frame.newTab(new NewTab());   // being prepared
        Prepare p = took(Prepare.class).getFirst();
        frame.gone(new Gone(id));
        assertTrue(took(CloseWindow.class).isEmpty());
        frame.prepared(new Prepared(p.id(), dir, "pwsh", ""));
        long next = took(AddTab.class).getFirst().id();
        frame.gone(new Gone(next));
        assertEquals(1, took(CloseWindow.class).size());
    }

    // -- the Open dialog --------------------------------------------------------------------------------------

    @Test
    void openWithNoWorkspacesPicksAFolder() {
        frame.openAsked(new OpenAsked());
        ListAt l = took(ListAt.class).getFirst();
        assertEquals(new ListAt(Who.OPEN, l.batch(), 0), l);
        frame.listed(new Listing(Who.OPEN, l.batch(), 0, 0, "", ""));
        assertEquals(1, took(PickFolder.class).size());
    }

    @Test
    void openListsEveryWorkspaceAsATileAndIgnoresTheSettingsWindowsListing() {
        frame.openAsked(new OpenAsked());
        long batch = took(ListAt.class).getFirst().batch();
        Workspace a = new Workspace("a", List.of(new Workspace.Entry("api", "C:\\api")));
        frame.listed(new Listing(Who.OPEN, batch, 0, 2, "a", Workspace.format(a)));
        assertEquals(List.of(new ListAt(Who.OPEN, batch, 1)), sent.stream().filter(ListAt.class::isInstance).toList());
        assertEquals(List.of(new Tile(batch, 0, 2, "a", "1 folder: api")), took(Tile.class));

        frame.listed(new Listing(Who.SETTINGS, batch, 1, 2, "b", ""));
        assertTrue(took(Tile.class).isEmpty());
    }

    // -- the settings window ----------------------------------------------------------------------------------

    @Test
    void theStartWorkspaceAndTheTabsFollowARename() {
        settings.startWorkspace("day");
        frame.begin(new Begin());
        Resolve r = took(Resolve.class).getFirst();
        frame.resolved(new Resolved(r.seq(), "day", Workspace.format(new Workspace("day",
                List.of(new Workspace.Entry("api", dir.toString()).withEnv("api-000001.ps1"))))));
        long id = prepare().getFirst().id();

        frame.renamed(new Renamed("day", "work", true, ""));
        assertEquals("work", settings.startWorkspace());
        frame.editEnv(new Messages.EditEnv(id, true));
        Messages.Script s = took(Messages.Script.class).getFirst();
        assertEquals("work", s.workspace(), "the tab's menu edits the script under its workspace's new name");
        assertEquals("api-000001.ps1", s.env());

        frame.newTab(new NewTab());
        assertEquals("work", took(Prepare.class).getFirst().workspace(), "+ is still the window's workspace's");

        frame.deleted(new Deleted("work", "work 1", true, ""));
        assertNull(settings.startWorkspace());
    }

    @Test
    void aNewWorkspaceIsOfferedAsTheStartWorkspaceWhileThereIsNone() {
        frame.created(new Created("Workspace", ""));
        Ask ask = took(Ask.class).getFirst();
        frame.answer(new Answer(ask.question(), true));
        assertEquals("Workspace", settings.startWorkspace());
        assertEquals(1, took(EditName.class).size(), "and the name field has the keyboard back");

        frame.created(new Created("Workspace 2", ""));
        assertEquals("Workspace", took(Messages.Find.class).getFirst().name(), "one there is looked for first");
        frame.found(new Messages.Found("Workspace", "Workspace 2", true));
        assertTrue(took(Ask.class).isEmpty());
    }
}
