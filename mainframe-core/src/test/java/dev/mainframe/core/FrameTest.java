package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.LaunchWorkspace;
import dev.mainframe.core.Messages.Load;
import dev.mainframe.core.Messages.Tiles;
import dev.mainframe.core.Messages.WorkspacesWanted;
import dev.mainframe.core.Messages.Touched;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.Launched;
import dev.mainframe.core.Messages.Loaded;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseShell;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Submitted;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Notice;
import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.mainframe.core.Messages.TabSpec;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.PrefsSaved;
import dev.mainframe.core.Messages.SavePrefs;
import dev.mainframe.core.Messages.ShellChoice;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame, alone: no window, no view and no shell. It owns an inbox on a tick of this test's, as it will own one on
 * the framework's; each test publishes what the view, the job or a shell would, runs the tick, and reads what the frame
 * sent. Replies are published by hand, which makes the odd orders (a shell that ends before its tab is shown, a size
 * for a tab already gone) as easy to test as the common one.
 */
class FrameTest {

    private static final Path WORK = Path.of("C:/work/api");
    private static final Path WEB = Path.of("C:/work/web");

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Frame frame;
    /** Everything the frame sent, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        tick = bus.tick(null).alarm(new HandClock(bus)).whenOverdue(Tick.Overdue.FAIL);
        frame = new Frame(tick.inbox("frame"));
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(StartupWanted.TOPIC, AddTab.TOPIC, Front.TOPIC, Header.TOPIC, OpenShell.TOPIC, Output.TOPIC,
                Prefs.TOPIC, SavePrefs.TOPIC, Prepare.TOPIC, Notice.TOPIC, WorkspacesWanted.TOPIC, Tiles.TOPIC,
                Load.TOPIC, LaunchWorkspace.TOPIC,
                CloseTab.TOPIC, WindowCommand.TOPIC, CloseShell.TOPIC, Ask.TOPIC, CloseAnswer.TOPIC)) {
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
        if (preparing) {
            prepareAsked();
        }
    }

    /** Whether {@link #tell} answers every {@link Prepare} as the job would, with the line and folder unchanged. */
    private boolean preparing = true;

    /** Answer each {@link Prepare} the frame sent and nobody answered yet, as {@code PrepareShell} would with no scripts. */
    private void prepareAsked() {
        for (Prepare p : took(Prepare.class)) {
            bus.publish(Prepared.TOPIC, new Prepared(p.id(), p.dir(), p.line(), ""));
        }
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    /** What was sent of one kind, in order, and forget those; the other kinds stay to be taken. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.removeIf(kind::isInstance);
        return out;
    }

    private static final Path HOME = Path.of("C:/Users/me");
    private static final List<ShellChoice> SHELLS = List.of(
            new ShellChoice("powershell", "Windows PowerShell", "powershell.exe -NoLogo"),
            new ShellChoice("cmd", "Command Prompt", "cmd.exe"));

    private static List<TabSpec> specs(Path... dirs) {
        return java.util.Arrays.stream(dirs).map(d -> new TabSpec(d, "", "")).toList();
    }

    /** A launch naming {@code dirs} with a command line of its own, which every tab runs. */
    private void started(Path... dirs) {
        tell(Startup.TOPIC, new Startup(specs(dirs), "powershell.exe", Preferences.DEFAULT, SHELLS, HOME, "", List.of()));
    }

    /** A launch with no command line of its own, so the chosen shell runs, and these settings. */
    private void launched(Preferences prefs, Path... dirs) {
        tell(Startup.TOPIC, new Startup(specs(dirs), "", prefs, SHELLS, HOME, "", List.of()));
    }

    @Test
    void theMainWindowOpeningAsksWhatTheLaunchWanted() {
        tell(WindowOpened.TOPIC, new WindowOpened("settings"));
        assertTrue(took(StartupWanted.class).isEmpty(), "another window opening starts nothing");
        tell(WindowOpened.TOPIC, new WindowOpened(CloseRequested.MAIN));
        assertEquals(1, took(StartupWanted.class).size());
    }

    @Test
    void aTabPerFolderTheFirstInFrontNamedForItsFolder() {
        started(WORK, WEB);
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new AddTab(1, "api"), new AddTab(2, "web")), took(AddTab.class));
        assertTrue(all.contains(new Front(1)));
        assertEquals(2, frame.state().tabs().size());
    }

    @Test
    void aTabsShellStartsAtTheSizeItsScreenWasFirstLaidOutAtAndOnlyOnce() {
        started(WORK);
        sent.clear();
        tell(Resized.TOPIC, new Resized(1, 120, 33));
        assertEquals(List.of(new OpenShell(1, "powershell.exe", WORK, 120, 33)), took(OpenShell.class));
        tell(Resized.TOPIC, new Resized(1, 100, 30));
        assertTrue(took(OpenShell.class).isEmpty(), "a later size is the shell's to hear, not a second start");
    }

    @Test
    void aSizeForNoTabStartsNothing() {
        tell(Resized.TOPIC, new Resized(9, 80, 24));
        assertTrue(took(OpenShell.class).isEmpty());
    }

    @Test
    void aShellEndingClosesItsTabAndTheNextComesToTheFront() {
        started(WORK, WEB);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertTrue(all.contains(new Front(2)));
        assertTrue(all.stream().noneMatch(m -> m instanceof WindowCommand), "a tab is left, so the window stays");
    }

    @Test
    void theLastShellEndingClosesTheWindow() {
        started(WORK);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        assertEquals(List.of(WindowCommand.close(CloseRequested.MAIN)), took(WindowCommand.class));
        assertTrue(frame.state().tabs().isEmpty());
    }

    @Test
    void aProgramThatWouldNotStartKeepsItsTabAndSaysWhyInIt() {
        started(WORK);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, -1, "Could not start nosuch.exe: not found"));
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new Output(1, "\r\nCould not start nosuch.exe: not found\r\n")), took(Output.class));
        assertTrue(all.stream().noneMatch(m -> m instanceof CloseTab || m instanceof WindowCommand));
        assertEquals(1, frame.state().tabs().size());
    }

    @Test
    void aShellEndingForATabAlreadyGoneIsIgnored() {
        started(WORK, WEB);
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aTabsHeaderFollowsItsProgramsTitleShortenedAndOnlyWhenItChanges() {
        started(WORK, WEB);
        tell(Retitled.TOPIC, new Retitled(1, "C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
        assertEquals(List.of(new Header(1, "powershell")), took(Header.class));
        tell(Retitled.TOPIC, new Retitled(1, "powershell"));
        assertTrue(took(Header.class).isEmpty(), "the same header is not said again");
        tell(Retitled.TOPIC, new Retitled(2, "vim notes.md"));
        assertEquals(List.of(new Header(2, "vim notes.md")), took(Header.class));
    }

    @Test
    void aClearedTitleHandsTheHeaderBackToTheFolder() {
        started(WORK);
        tell(Retitled.TOPIC, new Retitled(1, "build"));
        took(Header.class);
        tell(Retitled.TOPIC, new Retitled(1, "  "));
        assertEquals(List.of(new Header(1, "api")), took(Header.class));
    }

    @Test
    void aTitleForNoTabSaysNothing() {
        tell(Retitled.TOPIC, new Retitled(9, "ghost"));
        assertTrue(took(Header.class).isEmpty());
    }

    // ------------------------------------------------------------------ M2: tabs

    @Test
    void aNewTabIsInTheChosenFolderRunningTheChosenShellAndComesToTheFront() {
        launched(Preferences.DEFAULT.withShell("cmd").withDir(Optional.of(WEB)), WORK);
        sent.clear();
        tell(NewTab.TOPIC, new NewTab());
        assertEquals(List.of(new AddTab(2, "web")), took(AddTab.class));
        assertEquals(List.of(new Front(2)), took(Front.class));
        tell(Resized.TOPIC, new Resized(2, 80, 24));
        assertEquals(List.of(new OpenShell(2, "cmd.exe", WEB, 80, 24)), took(OpenShell.class));
    }

    @Test
    void aNameOutranksTheTitleAndABlankOneHandsTheHeaderBack() {
        started(WORK);
        tell(Retitled.TOPIC, new Retitled(1, "pwsh"));
        took(Header.class);
        tell(Rename.TOPIC, new Rename(1, "  build  "));
        assertEquals(List.of(new Header(1, "build")), took(Header.class));
        tell(Retitled.TOPIC, new Retitled(1, "vim"));
        assertTrue(took(Header.class).isEmpty(), "the name stays while the program retitles");
        tell(Rename.TOPIC, new Rename(1, ""));
        assertEquals(List.of(new Header(1, "vim")), took(Header.class));
    }

    @Test
    void aTabNeverSubmittedToClosesWithoutAskingAndItsShellEnds() {
        started(WORK, WEB);
        tell(CloseAsked.TOPIC, new CloseAsked(1, true));
        assertTrue(took(Ask.class).isEmpty());
        assertEquals(List.of(new CloseShell(1)), took(CloseShell.class));
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
    }

    @Test
    void closingTheFrontTabFrontsTheOneThatTookItsPlace() {
        started(WORK, WEB, Path.of("C:/work/db"));
        tell(Fronted.TOPIC, new Fronted(2));
        took(Front.class);
        tell(CloseAsked.TOPIC, new CloseAsked(2, false));
        assertEquals(List.of(new Front(3)), took(Front.class));
        tell(CloseAsked.TOPIC, new CloseAsked(3, false));
        assertEquals(List.of(new Front(1)), took(Front.class), "the last closed: the one before it");
    }

    @Test
    void aSubmittedTabAsksAndCloseClosesIt() {
        started(WORK);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseAsked.TOPIC, new CloseAsked(1, true));
        List<Ask> asked = took(Ask.class);
        assertEquals(1, asked.size());
        assertEquals("Close api?", asked.getFirst().title());
        assertTrue(took(CloseTab.class).isEmpty(), "nothing closes before the answer");
        tell(Answer.TOPIC, new Answer(asked.getFirst().id(), 0));
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertTrue(frame.state().closings().isEmpty());
    }

    @Test
    void cancelKeepsTheTab() {
        started(WORK);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseAsked.TOPIC, new CloseAsked(1, true));
        Ask asked = took(Ask.class).getFirst();
        tell(Answer.TOPIC, new Answer(asked.id(), 1));
        assertTrue(took(CloseTab.class).isEmpty());
        assertTrue(took(CloseShell.class).isEmpty());
        assertEquals(1, frame.state().tabs().size());
        assertTrue(frame.state().closings().isEmpty());
    }

    @Test
    void closeWithoutAskingClosesASubmittedTabAtOnce() {
        started(WORK, WEB);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseAsked.TOPIC, new CloseAsked(1, false));
        assertTrue(took(Ask.class).isEmpty());
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
    }

    @Test
    void theWindowClosesAtOnceWhenNoTabWasSubmittedTo() {
        started(WORK, WEB);
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 7));
        assertTrue(took(Ask.class).isEmpty());
        assertEquals(List.of(new CloseAnswer(7, true)), took(CloseAnswer.class));
    }

    @Test
    void theWindowAsksAboutEveryBusyTabAndCancelKeepsIt() {
        started(WORK, WEB);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(Submitted.TOPIC, new Submitted(2));
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 8));
        Ask asked = took(Ask.class).getFirst();
        assertEquals(List.of("Close all", "Cancel"), asked.buttons());
        assertTrue(asked.message().contains("api, web"), asked.message());
        assertTrue(took(CloseAnswer.class).isEmpty(), "unanswered while the person decides");
        tell(Answer.TOPIC, new Answer(asked.id(), 1));
        assertEquals(List.of(new CloseAnswer(8, false)), took(CloseAnswer.class));
    }

    @Test
    void theWindowClosesOnTheAnswer() {
        started(WORK);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 9));
        Ask asked = took(Ask.class).getFirst();
        tell(Answer.TOPIC, new Answer(asked.id(), 0));
        assertEquals(List.of(new CloseAnswer(9, true)), took(CloseAnswer.class));
    }

    @Test
    void anotherWindowClosingNeverAsks() {
        started(WORK);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseRequested.TOPIC, new CloseRequested("about", 4));
        assertTrue(took(Ask.class).isEmpty());
        assertEquals(List.of(new CloseAnswer(4, true)), took(CloseAnswer.class));
    }

    @Test
    void closingTheLastTabClosesTheWindowWhichThenAsksNothing() {
        started(WORK);
        tell(Submitted.TOPIC, new Submitted(1));
        tell(CloseAsked.TOPIC, new CloseAsked(1, false));
        assertEquals(List.of(WindowCommand.close(CloseRequested.MAIN)), took(WindowCommand.class));
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 5));
        assertEquals(List.of(new CloseAnswer(5, true)), took(CloseAnswer.class));
    }

    // ------------------------------------------------------------------ M3: settings

    @Test
    void theSettingsAreSaidAtLaunchAndALaunchWithNoFolderStartsInTheChosenOne() {
        Preferences prefs = Preferences.DEFAULT.withFontPx(20).withDir(Optional.of(WEB));
        launched(prefs);
        assertEquals(List.of(new Prefs(prefs, SHELLS, "", List.of())), took(Prefs.class));
        assertEquals(List.of(new AddTab(1, "web")), took(AddTab.class));
        tell(Resized.TOPIC, new Resized(1, 80, 24));
        assertEquals(List.of(new OpenShell(1, "powershell.exe -NoLogo", WEB, 80, 24)), took(OpenShell.class),
                "no shell chosen: the platform's first");
    }

    @Test
    void withNoFolderChosenATabStartsAtHome() {
        launched(Preferences.DEFAULT);
        tell(Resized.TOPIC, new Resized(1, 80, 24));
        assertEquals(HOME, took(OpenShell.class).getFirst().dir());
    }

    @Test
    void theLaunchCommandLineWinsOverTheChosenShellAndIsSaid() {
        tell(Startup.TOPIC, new Startup(List.of(), "pwsh -NoProfile", Preferences.DEFAULT.withShell("cmd"), SHELLS,
                HOME, "", List.of()));
        assertEquals("pwsh -NoProfile", took(Prefs.class).getFirst().override());
        tell(NewTab.TOPIC, new NewTab());
        tell(Resized.TOPIC, new Resized(2, 80, 24));
        assertEquals("pwsh -NoProfile", took(OpenShell.class).getFirst().line());
    }

    @Test
    void aChangeIsSaidAndSavedAndOneWhileSavingWaitsForTheAnswer() {
        launched(Preferences.DEFAULT);
        took(Prefs.class);
        Preferences bigger = Preferences.DEFAULT.withFontPx(20);
        tell(ChangePrefs.TOPIC, new ChangePrefs(bigger));
        assertEquals(List.of(new Prefs(bigger, SHELLS, "", List.of())), took(Prefs.class));
        assertEquals(List.of(new SavePrefs(1, bigger)), took(SavePrefs.class));

        Preferences biggest = bigger.withFontPx(24);
        Preferences faded = biggest.withMotion(TabMotion.FADE);
        tell(ChangePrefs.TOPIC, new ChangePrefs(biggest));
        tell(ChangePrefs.TOPIC, new ChangePrefs(faded));
        assertEquals(2, took(Prefs.class).size(), "every change is shown at once");
        assertTrue(took(SavePrefs.class).isEmpty(), "but none saved while one is in flight");

        tell(PrefsSaved.TOPIC, new PrefsSaved(1, ""));
        assertEquals(List.of(new SavePrefs(2, faded)), took(SavePrefs.class), "then the latest, once");
        tell(PrefsSaved.TOPIC, new PrefsSaved(2, ""));
        assertTrue(took(SavePrefs.class).isEmpty());
        assertTrue(!frame.state().saving());
    }

    @Test
    void aChangeToWhatIsAlreadySetSavesNothing() {
        launched(Preferences.DEFAULT);
        took(Prefs.class);
        tell(ChangePrefs.TOPIC, new ChangePrefs(Preferences.DEFAULT));
        assertTrue(took(Prefs.class).isEmpty());
        assertTrue(took(SavePrefs.class).isEmpty());
    }

    @Test
    void theSizesStepAlongTheFiveAndStopAtTheEnds() {
        Preferences p = Preferences.DEFAULT;
        assertEquals(20, p.stepped(1).fontPx());
        assertEquals(24, p.stepped(1).stepped(1).stepped(1).fontPx());
        assertEquals(14, p.withFontPx(14).stepped(-1).fontPx());
        assertEquals(18, p.withFontPx(24).stepped(0).fontPx());
    }

    // ------------------------------------------------------------------ M4.1: workspaces at launch

    /** A launch of workspace {@code day} with two rows, the second named. */
    private void day() {
        tell(Startup.TOPIC, new Startup(List.of(new TabSpec(WORK, "", "api-1"), new TabSpec(WEB, "front", "web-2")),
                "", Preferences.DEFAULT, SHELLS, HOME, "day", List.of("day", "night")));
    }

    @Test
    void aWorkspacesTabsAreNamedAndPreparedWithItsScripts() {
        preparing = false;
        day();
        assertEquals(List.of(new AddTab(1, "api"), new AddTab(2, "front")), took(AddTab.class));
        assertEquals(List.of(new Prepare(1, "powershell.exe -NoLogo", WORK, "day", "api-1"),
                new Prepare(2, "powershell.exe -NoLogo", WEB, "day", "web-2")), took(Prepare.class));
        assertEquals(List.of("day", "night"), took(Prefs.class).getFirst().workspaces());
    }

    @Test
    void aShellStartsOnceBothItsLineAndItsSizeAreInWhicheverIsLast() {
        preparing = false;
        day();
        took(Prepare.class);
        tell(Resized.TOPIC, new Resized(1, 100, 30));
        assertTrue(took(OpenShell.class).isEmpty(), "sized but not prepared");
        tell(Prepared.TOPIC, new Prepared(1, WORK, "powershell.exe -NoExit -Command x", ""));
        assertEquals(List.of(new OpenShell(1, "powershell.exe -NoExit -Command x", WORK, 100, 30)),
                took(OpenShell.class));

        tell(Prepared.TOPIC, new Prepared(2, HOME, "powershell.exe -NoLogo", ""));
        assertTrue(took(OpenShell.class).isEmpty(), "prepared but not sized");
        tell(Resized.TOPIC, new Resized(2, 90, 25));
        assertEquals(List.of(new OpenShell(2, "powershell.exe -NoLogo", HOME, 90, 25)), took(OpenShell.class),
                "in the folder the job found: home, when the one asked for had gone");
        tell(Resized.TOPIC, new Resized(2, 80, 20));
        assertTrue(took(OpenShell.class).isEmpty(), "a later size is the shell's");
    }

    @Test
    void scriptsThatDidNotRunAreSaidAboveTheTerminal() {
        preparing = false;
        day();
        tell(Prepared.TOPIC, new Prepared(1, WORK, "cmd.exe", "This tab's environment scripts did not run: cmd."));
        assertEquals(List.of(new Notice(1, "This tab's environment scripts did not run: cmd.")), took(Notice.class));
        tell(Prepared.TOPIC, new Prepared(2, WEB, "powershell.exe", ""));
        assertTrue(took(Notice.class).isEmpty());
    }

    @Test
    void aNewTabInAWorkspaceWindowSourcesTheWorkspacesScriptOnly() {
        preparing = false;
        day();
        took(Prepare.class);
        tell(NewTab.TOPIC, new NewTab());
        assertEquals(List.of(new Prepare(3, "powershell.exe -NoLogo", HOME, "day", "")), took(Prepare.class));
    }

    @Test
    void aWindowWithNoWorkspaceSourcesNothing() {
        preparing = false;
        launched(Preferences.DEFAULT, WORK);
        assertEquals("", took(Prepare.class).getFirst().workspace());
    }

    // ------------------------------------------------------------------ M4.2: Ctrl+O

    private static final Path DB = Path.of("C:/work/db");

    private static Workspace night() {
        return new Workspace("night", List.of(new Workspace.Entry("", DB.toString()).withEnv("db-1"),
                new Workspace.Entry("logs", WEB.toString()).withEnv("logs-2")));
    }

    /** A plain launch: one tab in the chosen folder (home), spare. */
    private void plain() {
        launched(Preferences.DEFAULT);
        sent.clear();
    }

    @Test
    void ctrlOListsTheWorkspacesForTheDialogAndSettings() {
        plain();
        tell(OpenAsked.TOPIC, new OpenAsked());
        assertEquals(List.of(new WorkspacesWanted()), took(WorkspacesWanted.class));
        tell(Listed.TOPIC, new Listed(List.of(night())));
        assertEquals(List.of(new Tiles(List.of(night()))), took(Tiles.class));
        assertEquals(List.of("night"), took(Prefs.class).getFirst().workspaces(), "Settings' list follows");
    }

    @Test
    void aWorkspaceOpenedHereReplacesTheSpareTabAndSourcesItsScripts() {
        preparing = false;
        plain();
        tell(OpenWorkspace.TOPIC, new OpenWorkspace("night", true));
        assertEquals(List.of(new Load("night")), took(Load.class));
        tell(Loaded.TOPIC, new Loaded("night", java.util.Optional.of(night())));
        assertEquals(List.of(new AddTab(2, "db"), new AddTab(3, "logs")), took(AddTab.class));
        assertEquals(List.of(new Prepare(2, "powershell.exe -NoLogo", DB, "night", "db-1"),
                new Prepare(3, "powershell.exe -NoLogo", WEB, "night", "logs-2")), took(Prepare.class));
        assertEquals(List.of(new CloseShell(1)), took(CloseShell.class));
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertEquals(2, took(Front.class).getLast().id());
        assertTrue(took(WindowCommand.class).isEmpty(), "the window stays: it has the workspace now");
    }

    @Test
    void aWorkspaceOpenedElsewhereClosesAWindowHoldingOnlyItsSpareTab() {
        plain();
        tell(OpenWorkspace.TOPIC, new OpenWorkspace("night", false));
        assertEquals(List.of(new LaunchWorkspace("night")), took(LaunchWorkspace.class));
        tell(Launched.TOPIC, new Launched("night", ""));
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertEquals(List.of(WindowCommand.close(CloseRequested.MAIN)), took(WindowCommand.class));
    }

    @Test
    void aTouchedTabIsNoLongerSpareSoOpeningElsewhereLeavesItAlone() {
        plain();
        tell(Touched.TOPIC, new Touched(1));
        tell(Launched.TOPIC, new Launched("night", ""));
        assertTrue(took(CloseTab.class).isEmpty());
        assertTrue(took(WindowCommand.class).isEmpty());
    }

    @Test
    void aLaunchThatNamedFoldersHasNoSpareTab() {
        started(WORK);
        sent.clear();
        tell(Launched.TOPIC, new Launched("night", ""));
        assertTrue(took(CloseTab.class).isEmpty());
    }

    @Test
    void aFolderPickedReplacesTheSpareTab() {
        plain();
        tell(OpenFolder.TOPIC, new OpenFolder(DB));
        assertEquals(List.of(new AddTab(2, "db")), took(AddTab.class));
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertEquals(2, took(Front.class).getLast().id());
    }

    @Test
    void aNewTabEndsTheSpareTab() {
        plain();
        tell(NewTab.TOPIC, new NewTab());
        tell(OpenFolder.TOPIC, new OpenFolder(DB));
        assertTrue(took(CloseTab.class).isEmpty(), "with two tabs, neither is the launch's plain one alone");
    }

    @Test
    void aLaunchThatFailedSaysWhyAndClosesNothing() {
        plain();
        tell(Launched.TOPIC, new Launched("night", "A new MainFrame did not start: no java"));
        Ask told = took(Ask.class).getFirst();
        assertEquals(Frame.TELL, told.id());
        assertEquals("Could not open night", told.title());
        assertTrue(took(CloseTab.class).isEmpty());
    }

    @Test
    void aWorkspaceGoneSinceItWasListedIsSaid() {
        plain();
        tell(Loaded.TOPIC, new Loaded("night", java.util.Optional.empty()));
        assertEquals("No workspace called night", took(Ask.class).getFirst().title());
        assertTrue(took(AddTab.class).isEmpty());
    }
}
