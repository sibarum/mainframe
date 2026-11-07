package dev.mainframe;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.AddRow;
import dev.mainframe.core.Messages.DeleteWorkspace;
import dev.mainframe.core.Messages.EditWorkspaces;
import dev.mainframe.core.Messages.MoveRow;
import dev.mainframe.core.Messages.NewWorkspace;
import dev.mainframe.core.Messages.Recorded;
import dev.mainframe.core.Messages.RenameRow;
import dev.mainframe.core.Messages.RenameWorkspace;
import dev.mainframe.core.Messages.Select;
import dev.mainframe.core.Messages.Showing;
import dev.mainframe.core.Messages.Undo;
import dev.vexelray.gui.widget.Deck;
import dev.vexelray.gui.widget.Placement;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.ShellChoice;
import dev.mainframe.core.TabMotion;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.widget.Segment;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.Tiles;
import dev.mainframe.core.Messages.Touched;
import dev.mainframe.core.Workspace;
import dev.vexelray.gui.widget.ListView;
import dev.mainframe.core.Messages.Notice;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Submitted;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages the frame and the screens send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, exactly as in the
 * window. No window opens, so the framework never says {@code WindowOpened} for the main one, and the frame starts no
 * tab and no shell: the test plays the frame's part by publishing what it would. The view runs in frames, and there is
 * no loop to run them, so the test runs them itself ({@code VexelApplication.frame}): this thread is the GUI thread.
 */
class ViewTest {

    private Shell shell;
    private MainFrameAppWiring wiring;
    private final List<Object> heard = new CopyOnWriteArrayList<>();

    /**
     * What changed on screen in one frame while a test ran, which every test here then fails on: nothing this
     * application shows changes in one frame. The framework fades it all (a colour, an opacity, words, an image, a node
     * arriving, leaving, hidden or shown) as long as the view keeps its nodes and changes them. A cut found here means
     * a node was rebuilt where it should have been changed, or something stepped round the framework. Fix that; do not
     * take this watch out. If the framework cannot do it, that is a framework note (docs/framework-notes.md).
     */
    private Cuts cuts;
    /** The same, on the settings window's tree. */
    private Cuts settingsCuts;
    private Cuts openCuts;
    private Cuts workspacesCuts;

    private <T> void subscribe(sibarum.atchung.Topic<T> topic) {
        shell.bus().subscribe(topic, heard::add);
    }

    @BeforeEach
    void build() {
        // The tree runs the real frame and jobs, which save settings and start processes: never on the real home.
        assertTrue(System.getProperty("user.home").replace('\\', '/').endsWith("target/test-home"),
                "tests run on a home of their own (the parent pom's surefire): " + System.getProperty("user.home"));
        assertEquals("off", System.getProperty("mainframe.launch"), "and start no process");
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        shell.bus().subscribe(Resized.TOPIC, heard::add);
        shell.bus().subscribe(Typed.TOPIC, heard::add);
        shell.bus().subscribe(Retitled.TOPIC, heard::add);
        shell.bus().subscribe(Shown.TOPIC, heard::add);
        shell.bus().subscribe(Submitted.TOPIC, heard::add);
        shell.bus().subscribe(Fronted.TOPIC, heard::add);
        shell.bus().subscribe(NewTab.TOPIC, heard::add);
        shell.bus().subscribe(CloseAsked.TOPIC, heard::add);
        shell.bus().subscribe(Rename.TOPIC, heard::add);
        shell.bus().subscribe(ChangePrefs.TOPIC, heard::add);
        shell.bus().subscribe(PickFile.TOPIC, heard::add);
        shell.bus().subscribe(OpenWorkspace.TOPIC, heard::add);
        shell.bus().subscribe(OpenFolder.TOPIC, heard::add);
        shell.bus().subscribe(Touched.TOPIC, heard::add);
        shell.bus().subscribe(OpenAsked.TOPIC, heard::add);
        for (var t : List.of(Select.TOPIC, NewWorkspace.TOPIC, RenameWorkspace.TOPIC, DeleteWorkspace.TOPIC, AddRow.TOPIC,
                MoveRow.TOPIC, RenameRow.TOPIC, Undo.TOPIC, EditWorkspaces.TOPIC)) {
            subscribe(t);
        }
        cuts = Cuts.watch(shell.gui());
        settingsCuts = Cuts.watch(wiring.settings().gui());
        openCuts = Cuts.watch(wiring.open().gui());
        workspacesCuts = Cuts.watch(wiring.workspaces().gui());
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = new java.util.ArrayList<>(cuts.found());
        found.addAll(settingsCuts.found());
        found.addAll(openCuts.found());
        found.addAll(workspacesCuts.found());
        workspacesCuts.close();
        cuts.close();
        settingsCuts.close();
        openCuts.close();
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    /** Run frames until {@code test} holds: the view hears only in a frame, and this thread runs them. */
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

    private TerminalScreen tab(long id) throws InterruptedException {
        shell.bus().publish(AddTab.TOPIC, new AddTab(id, "work"));
        shell.bus().publish(Front.TOPIC, new Front(id));
        eventually("the tab's screen", () -> wiring.view().screen(id) != null);
        return wiring.view().screen(id);
    }

    @Test
    void aTabsScreenSaysItsSizeWithTheTabsId() throws Exception {
        tab(7);
        eventually("the size, as the tab's", () -> heard.stream().anyMatch(m -> m instanceof Resized r && r.id() == 7
                && r.cols() > 0 && r.rows() > 0));
        assertEquals(1, wiring.ui().tabs().count());
    }

    @Test
    void whatTheProgramWritesIsShownInItsTab() throws Exception {
        TerminalScreen screen = tab(3);
        shell.bus().publish(Output.TOPIC, new Output(3, "hello from the shell"));
        eventually("the text on the screen", () -> screen.allText().contains("hello from the shell"));
    }

    @Test
    void whatIsFedIsSaidToBeShownSoTheShellSendsMoreEvenForATabGone() throws Exception {
        TerminalScreen screen = tab(8);
        shell.bus().publish(Output.TOPIC, new Output(8, "drawn"));
        eventually("shown, as the tab's", () -> heard.contains(new Shown(8)));
        assertTrue(screen.allText().contains("drawn"), "fed before it is said to be shown");
        shell.bus().publish(Output.TOPIC, new Output(99, "for no tab"));
        eventually("shown, for a tab with no screen", () -> heard.contains(new Shown(99)));
    }

    @Test
    void whatTheScreenSendsGoesToTheTabsShell() throws Exception {
        TerminalScreen screen = tab(4);
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "dir\r", true));
        eventually("the keys, as the tab's", () -> heard.contains(new Typed(4, "dir\r")));
    }

    @Test
    void escInterruptsTheTabInFront() throws Exception {
        tab(5);
        shell.bus().publish(Intent.INTENTS, new Intent(Intent.Command.INTERRUPT));
        eventually("0x03, as the tab's", () -> heard.contains(new Typed(5, "\u0003")));
    }

    @Test
    void theProgramsTitleGoesToTheFrameAsTheTabs() throws Exception {
        TerminalScreen screen = tab(6);
        shell.bus().publish(TerminalScreen.TITLED, new TerminalScreen.Titled(screen.address(), "pwsh"));
        eventually("the title, as the tab's", () -> heard.contains(new Retitled(6, "pwsh")));
    }

    /** A new header is the same node with new words, which the framework fades: the dispose's cut watch says so. */
    @Test
    void aHeaderIsChangedInPlace() throws Exception {
        tab(2);
        shell.bus().publish(Header.TOPIC, new Header(2, "pwsh"));
        for (int i = 0; i < 5; i++) {
            VexelApplication.frame(shell);
        }
        assertEquals(1, wiring.ui().tabs().count());
    }

    @Test
    void aClosedTabLeavesTheBar() throws Exception {
        tab(1);
        tab(2);
        shell.bus().publish(CloseTab.TOPIC, new CloseTab(1));
        eventually("one tab left", () -> wiring.view().screen(1) == null && wiring.ui().tabs().count() == 1);
        assertTrue(wiring.view().screen(2) != null);
    }

    // ------------------------------------------------------------------ M2

    private void intent(Intent.Command command) {
        shell.bus().publish(Intent.INTENTS, new Intent(command));
    }

    @Test
    void theTabCommandsTellTheFrameAboutTheTabInFront() throws Exception {
        // Ids the frame in the tree will not give out; and the new tab last, since the frame in the tree answers it
        // with a tab of its own, which comes to the front.
        tab(101);
        tab(102);
        intent(Intent.Command.CLOSE_TAB);
        eventually("closing the front tab, asking", () -> heard.contains(new CloseAsked(102, true)));
        intent(Intent.Command.CLOSE_NOW);
        eventually("closing it without asking", () -> heard.contains(new CloseAsked(102, false)));
        intent(Intent.Command.NEW_TAB);
        eventually("a new tab asked for", () -> heard.contains(new NewTab()));
    }

    @Test
    void thePlusAsksForANewTab() throws Exception {
        tab(1);
        shell.bus().publish(Button.PRESSED, new Button.Pressed(wiring.ui().plus().address()));
        eventually("a new tab asked for", () -> heard.contains(new NewTab()));
    }

    @Test
    void nextTabGoesRoundTheEndAndTellsTheFrame() throws Exception {
        tab(1);
        tab(2);
        intent(Intent.Command.NEXT_TAB);
        eventually("the first tab fronted", () -> heard.contains(new Fronted(1)));
        assertEquals(0, wiring.ui().tabs().selected());
    }

    @Test
    void anEnterTypedIsSubmittedButAnAnswerTheScreenSendsIsNot() throws Exception {
        TerminalScreen screen = tab(4);
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "\u001b[1;1R\r", false));
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "x", true));
        eventually("the typed key", () -> heard.contains(new Typed(4, "x")));
        assertFalse(heard.contains(new Submitted(4)), "a reply is not the person submitting");
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "\r", true));
        eventually("submitted", () -> heard.contains(new Submitted(4)));
    }

    @Test
    void theHeaderMenuRenamesAndEnterNamesTheTab() throws Exception {
        tab(3);
        shell.bus().publish(Tabs.CHOSEN, new Tabs.Chosen(wiring.ui().tabs().address(), View.RENAME.id(), 0));
        eventually("the rename field up", () -> wiring.view().renaming() == 3);
        wiring.ui().rename().text("build");
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(wiring.ui().rename().address(), "build"));
        eventually("the name, as the tab's", () -> heard.contains(new Rename(3, "build")));
        assertEquals(dev.mainframe.core.Messages.NO_TAB, wiring.view().renaming());
    }

    @Test
    void escapeInTheRenameFieldNamesNothing() throws Exception {
        tab(3);
        intent(Intent.Command.RENAME);
        eventually("the rename field up", () -> wiring.view().renaming() == 3);
        intent(Intent.Command.RENAME_CANCEL);
        eventually("the field away", () -> wiring.view().renaming() == dev.mainframe.core.Messages.NO_TAB);
        assertTrue(heard.stream().noneMatch(m -> m instanceof Rename));
    }

    @Test
    void theHeaderMenuClosesAfterAsking() throws Exception {
        tab(1);
        tab(2);
        shell.bus().publish(Tabs.CHOSEN, new Tabs.Chosen(wiring.ui().tabs().address(), View.CLOSE.id(), 0));
        eventually("closing tab 1, asking", () -> heard.contains(new CloseAsked(1, true)));
    }

    @Test
    void passthroughLetsGoOfEveryChordAndThePillTakesThemBack() throws Exception {
        tab(1);
        intent(Intent.Command.PASSTHROUGH);
        eventually("the key map paused", () -> shell.keymap().paused());
        shell.bus().publish(Button.PRESSED, new Button.Pressed(wiring.ui().pill().address()));
        eventually("the key map resumed", () -> !shell.keymap().paused());
    }

    // ------------------------------------------------------------------ M3

    private static final List<ShellChoice> SHELLS = List.of(
            new ShellChoice("powershell", "Windows PowerShell", "powershell.exe"),
            new ShellChoice("cmd", "Command Prompt", "cmd.exe"));

    /** Play the frame saying what is set. */
    private void said(Preferences prefs) {
        shell.bus().publish(Prefs.TOPIC, new Prefs(prefs, SHELLS, "", List.of("day", "night")));
    }

    @Test
    void aSizeChordAsksTheFrameForTheNextSizeFromWhatItLastSaid() throws Exception {
        tab(1);
        said(Preferences.DEFAULT.withFontPx(24).withMotion(TabMotion.NONE));
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        intent(Intent.Command.SMALLER);
        eventually("the next size down from 24",
                () -> heard.contains(new ChangePrefs(Preferences.DEFAULT.withFontPx(20).withMotion(TabMotion.NONE))));
    }

    /** Whether some change the view sent holds {@code test}. */
    private boolean changed(java.util.function.Predicate<Preferences> test) {
        return heard.stream().anyMatch(m -> m instanceof ChangePrefs c && test.test(c.prefs()));
    }

    @Test
    void theSettingsWindowsControlsChangeWhatIsSet() throws Exception {
        Preferences now = Preferences.DEFAULT.withDir(java.util.Optional.of(java.nio.file.Path.of("C:/work")));
        said(now);
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        SettingsPage page = wiring.settings();
        shell.bus().publish(Segment.CHANGED, new Segment.Changed(page.sizeAddress(), 14));
        // The frame in the tree answers each change with its own settings (it heard no Startup), so each check is of
        // the one setting its control changed.
        eventually("the size", () -> changed(p -> p.fontPx() == 14));
        shell.bus().publish(Segment.CHANGED, new Segment.Changed(page.motionAddress(), TabMotion.FADE));
        eventually("the motion", () -> changed(p -> p.motion() == TabMotion.FADE));
        shell.bus().publish(Button.PRESSED, new Button.Pressed(page.homeAddress()));
        eventually("home", () -> changed(p -> p.dir().isEmpty()));
    }

    @Test
    void chooseAsksThePickerAndTheFolderPickedIsWhereTabsStart() throws Exception {
        said(Preferences.DEFAULT);
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        shell.bus().publish(Button.PRESSED, new Button.Pressed(wiring.settings().chooseAddress()));
        eventually("the picker asked", () -> heard.contains(PickFile.folder(SettingsView.PICK, "")));
        shell.bus().publish(Picked.TOPIC, Picked.cancelled(SettingsView.PICK));
        java.nio.file.Path work = java.nio.file.Path.of("C:/work").toAbsolutePath();
        shell.bus().publish(Picked.TOPIC, new Picked(SettingsView.PICK, work.toString()));
        eventually("the folder", () -> heard.contains(new ChangePrefs(Preferences.DEFAULT.withDir(
                java.util.Optional.of(work)))));
        assertEquals(1, heard.stream().filter(m -> m instanceof ChangePrefs).count(), "a cancel changes nothing");
    }

    // ------------------------------------------------------------------ M4.1

    @Test
    void aNoticeIsShownAboveItsTabsTerminalAndTheCrossPutsItAway() throws Exception {
        tab(5);
        shell.bus().publish(Notice.TOPIC, new Notice(5, "This tab's environment scripts did not run: cmd."));
        eventually("the band shown", () -> wiring.view().noticing(5));
        shell.bus().publish(Button.PRESSED, new Button.Pressed(wiring.view().noticeClose(5)));
        eventually("the band put away", () -> !wiring.view().noticing(5));
    }

    @Test
    void openAtLaunchChangesTheWorkspaceALaunchOpens() throws Exception {
        said(Preferences.DEFAULT);
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        shell.bus().publish(dev.vexelray.gui.widget.Select.COMMITTED,
                new dev.vexelray.gui.widget.Select.Committed(wiring.settings().startAddress(), java.util.Set.of("day")));
        eventually("the workspace", () -> changed(p -> p.startWorkspace().equals("day")));
    }

    // ------------------------------------------------------------------ M4.2

    private static final Workspace NIGHT = new Workspace("night",
            List.of(new Workspace.Entry("", "C:/work/db"), new Workspace.Entry("logs", "C:/work/web")));

    @Test
    void ctrlOAsksTheFrame() throws Exception {
        tab(1);
        shell.keymap().action("app.open").orElseThrow().run();
        eventually("asked", () -> heard.contains(new OpenAsked()));
    }

    @Test
    void withNoWorkspaceSavedCtrlOGoesStraightToTheFolderPicker() throws Exception {
        shell.bus().publish(Tiles.TOPIC, new Tiles(List.of()));
        eventually("the picker asked", () -> heard.contains(PickFile.folder(OpenView.PICK, "")));
        java.nio.file.Path work = java.nio.file.Path.of("C:/work").toAbsolutePath();
        shell.bus().publish(Picked.TOPIC, new Picked(OpenView.PICK, work.toString()));
        eventually("a tab for it", () -> heard.contains(new OpenFolder(work)));
    }

    @Test
    void enterOpensAWorkspaceInANewWindowAndTheRowMenuOpensItHere() throws Exception {
        shell.bus().publish(Tiles.TOPIC, new Tiles(List.of(NIGHT)));
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        assertFalse(heard.stream().anyMatch(m -> m instanceof PickFile), "with a workspace, the dialog, not the picker");
        String list = wiring.open().listAddress();
        OpenPage.Row night = new OpenPage.Row("night", NIGHT.describe());
        shell.bus().publish(ListView.ACTIVATED, new ListView.Activated(list, night));
        eventually("a new window", () -> heard.contains(new OpenWorkspace("night", false)));
        shell.bus().publish(ListView.CHOSEN, new ListView.Chosen(list, OpenPage.HERE.id(), night));
        eventually("this window", () -> heard.contains(new OpenWorkspace("night", true)));
        shell.bus().publish(ListView.ACTIVATED, new ListView.Activated(list, OpenPage.Row.FOLDER));
        eventually("the picker", () -> heard.contains(PickFile.folder(OpenView.PICK, "")));
    }

    @Test
    void ctrlEnterOpensTheSelectedWorkspaceHere() throws Exception {
        shell.bus().publish(Tiles.TOPIC, new Tiles(List.of(NIGHT)));
        String list = wiring.open().listAddress();
        OpenPage.Row night = new OpenPage.Row("night", NIGHT.describe());
        shell.bus().publish(ListView.SELECTED, new ListView.Selected(list, java.util.Set.of(night)));
        for (int i = 0; i < 3; i++) {
            VexelApplication.frame(shell);
        }
        shell.keymap().action("open.here").orElseThrow().run();
        eventually("this window", () -> heard.contains(new OpenWorkspace("night", true)));
    }

    @Test
    void theFirstKeyTypedIntoATabTouchesItOnce() throws Exception {
        TerminalScreen screen = tab(6);
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "\u001b[0n", false));
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "l", true));
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "s", true));
        eventually("both keys", () -> heard.contains(new Typed(6, "s")));
        assertEquals(1, heard.stream().filter(m -> m instanceof Touched).count(), "once, and not for a reply");
    }

    /**
     * The commands are on the framework's keymap, under their ids and with their default chords, which is what puts
     * the key button in the title bar and what the person's changes are stored against.
     */
    @Test
    void theCommandsAreOnTheKeymapWithTheirDefaults() {
        Keymap keys = shell.keymap();
        Action copy = keys.action("terminal.copy").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.C, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.C, Modifier.CONTROL)),
                keys.chords(copy));
        Action interrupt = keys.action("terminal.interrupt").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.ESCAPE)), keys.chords(interrupt));
    }

    // ------------------------------------------------------------------ the Workspaces window

    private static final Workspace DAY = new Workspace("day", List.of(new Workspace.Entry("", "C:/work/api")));

    /** The component's word: day and night, night on the right, day opening at launch. */
    private void showNight() throws InterruptedException {
        shell.bus().publish(Showing.TOPIC, new Showing(List.of(DAY, NIGHT), "night", "day", "", "night shown."));
        eventually("night's cards", () -> wiring.workspaces().cards().size() == 2);
    }

    @Test
    void showingFillsTheListAndTheBoard() throws Exception {
        showNight();
        assertEquals(List.of("#0", "#1"), wiring.workspaces().cards());
        shell.bus().publish(Showing.TOPIC, new Showing(List.of(DAY, NIGHT), "day", "day", "", ""));
        eventually("day's one card", () -> wiring.workspaces().cards().size() == 1);
    }

    @Test
    void theListsGesturesTellTheComponent() throws Exception {
        showNight();
        String list = wiring.workspaces().listAddress();
        WorkspacesPage.Row day = new WorkspacesPage.Row("day", true);
        shell.bus().publish(ListView.SELECTED, new ListView.Selected(list, java.util.Set.of(day)));
        eventually("picked", () -> heard.contains(new Select("day")));
        shell.bus().publish(ListView.CHOSEN, new ListView.Chosen(list, WorkspacesPage.HERE.id(), day));
        eventually("opened here", () -> heard.contains(new OpenWorkspace("day", true)));
        shell.bus().publish(ListView.CHOSEN, new ListView.Chosen(list, WorkspacesPage.DELETE.id(), day));
        eventually("deleted", () -> heard.contains(new DeleteWorkspace("day")));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(wiring.workspaces().nameAddress(), "evening"));
        eventually("renamed", () -> heard.contains(new RenameWorkspace("night", "evening")));
    }

    @Test
    void aCardDroppedIsAMoveAndItsMenuRenamesItsTab() throws Exception {
        showNight();
        shell.bus().publish(Deck.PLACED, new Deck.Placed(wiring.workspaces().deckAddress(), "#1",
                Placement.before("#0"), dev.vexelray.gui.core.drop.DropEffect.MOVE));
        eventually("moved", () -> heard.contains(new MoveRow("night", 1, 0)));

        shell.bus().publish(WorkspacesPage.CardAsk.TOPIC, new WorkspacesPage.CardAsk("#1", false));
        eventually("the field", () -> "#1".equals(wiring.workspacesView().card()));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(wiring.workspaces().retitleAddress(), "web"));
        eventually("renamed", () -> heard.contains(new RenameRow("night", 1, "web")));
        assertEquals(null, wiring.workspacesView().card());
    }

    @Test
    void addFolderPicksOneAndItIsACard() throws Exception {
        showNight();
        shell.bus().publish(Button.PRESSED, new Button.Pressed(wiring.workspaces().addAddress()));
        eventually("the picker", () -> heard.contains(PickFile.folder(WorkspacesView.ADD_PICK, "")));
        java.nio.file.Path docs = java.nio.file.Path.of("C:/work/docs").toAbsolutePath();
        shell.bus().publish(Picked.TOPIC, new Picked(WorkspacesView.ADD_PICK, docs.toString()));
        eventually("added", () -> heard.contains(new AddRow("night", docs)));
    }

    @Test
    void aStepIsOneHistoryEntryWhoseUndoAsksTheComponent() throws Exception {
        showNight();
        shell.bus().publish(Recorded.TOPIC, new Recorded(7, "web added to night."));
        dev.vexelray.gui.core.edit.History history = wiring.workspaces().gui().history();
        eventually("recorded", history::canUndo);
        history.undo();
        eventually("asked", () -> heard.contains(new Undo(7)));
    }

    @Test
    void theOpenDialogsEditAndNewRowsOpenTheWorkspacesWindow() throws Exception {
        shell.bus().publish(Tiles.TOPIC, new Tiles(List.of(NIGHT)));
        String list = wiring.open().listAddress();
        shell.bus().publish(ListView.CHOSEN, new ListView.Chosen(list, OpenPage.EDIT.id(),
                new OpenPage.Row("night", NIGHT.describe())));
        eventually("edit night", () -> heard.contains(new EditWorkspaces("night", false)));
        shell.bus().publish(ListView.ACTIVATED, new ListView.Activated(list, OpenPage.Row.NEW));
        eventually("a new one", () -> heard.contains(new EditWorkspaces("", true)));
    }
}
