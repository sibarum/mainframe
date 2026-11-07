package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.LaunchWorkspace;
import dev.mainframe.core.Messages.Load;
import dev.mainframe.core.Messages.Tiles;
import dev.mainframe.core.Messages.WorkspacesWanted;
import dev.mainframe.core.Messages.Touched;
import dev.mainframe.core.Messages.TypeInto;
import dev.mainframe.core.Messages.EditEnv;
import dev.mainframe.core.Messages.EditScript;
import dev.mainframe.core.Messages.ScriptOpened;
import dev.mainframe.core.Messages.WorkspaceNames;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.Launched;
import dev.mainframe.core.Messages.Loaded;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseShell;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.Notice;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.PrefsSaved;
import dev.mainframe.core.Messages.SavePrefs;
import dev.mainframe.core.Messages.ShellChoice;
import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.mainframe.core.Messages.Submitted;
import dev.mainframe.core.Messages.TabSpec;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Withers;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Chain;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The controller: which tabs there are, what each runs, when its shell starts, and when closing asks first, as
 * declared steps on one state, reached only by messages.
 *
 * <h2>A state, and the steps that change it</h2>
 *
 * <p>Everything the frame knows is one immutable {@link State} on a {@link Ledger}. Nothing changes it but a step
 * declared below, a static method {@code (state, message, out) → next state} that reads its state and its message,
 * says what it sends through its {@link Out}, and returns what the state is now. The processor refuses a step that
 * captures, waits or touches the disk, so what the launch asked for comes from a job ({@link ReadStartup}).
 *
 * <h2>A tab's life</h2>
 *
 * <p>The window opens: ask what the launch wanted ({@link StartupWanted}), and add a tab per folder it names, or one
 * in the folder chosen in Settings ({@link AddTab}); {@link NewTab} adds one more there. The settings are the
 * frame's too, since it decides with two of them: a change ({@link ChangePrefs}) is said to the views ({@link Prefs})
 * and saved ({@link SavePrefs}) (docs/settings.md). The view lays each tab's screen out and says its size
 * ({@link Resized}); on the first, the frame starts the tab's shell at that size ({@link OpenShell}), so the program
 * never starts at a guessed size. When the program ends ({@link ShellEnded}) the tab closes ({@link CloseTab}), and
 * with the last tab, the window. A program that would not start keeps its tab open, saying why in it, so a mistyped
 * command line is read rather than flashed.
 *
 * <h2>Closing is a chain</h2>
 *
 * <p>Closing a tab and closing the window ask the same question, so they are one {@link Chain} with two ways in: a
 * tab that was ever submitted to ({@link Submitted}) may have a program running in it, so closing it asks, and the
 * person's answer closes or cancels. Every way out of the window (×, Alt+F4, the taskbar) is the framework's
 * {@link CloseRequested}, answered exactly once with {@link CloseAnswer}; closing the last tab closes the window,
 * which asks nothing more, since no tab is left to ask about. The question is held for the person
 * ({@code holdForPerson}), whose dialog always answers, so nothing waits on a thread.
 *
 * <h2>Where it is built</h2>
 *
 * <p>A {@code @Component} that takes an {@link Inbox} runs on the framework's logic lane, with every other such
 * component and never two at once, so it declares no {@code @Subscribe}: what it hears is the list in the
 * constructor, declared again as {@code @Hears} for the processor. {@link Core} lists it; {@code FrameTest} builds it
 * on a tick of its own.
 */
@Component
@Hears({WindowOpened.class, Startup.class, Resized.class, Retitled.class, ShellEnded.class, Fronted.class,
        Submitted.class, NewTab.class, Rename.class, CloseAsked.class, CloseRequested.class, Answer.class,
        ChangePrefs.class, PrefsSaved.class, Prepared.class, OpenAsked.class, Listed.class, OpenWorkspace.class,
        Loaded.class, Launched.class, OpenFolder.class, Touched.class, WorkspaceNames.class,
        EditEnv.class, ScriptOpened.class})
@Publishes({StartupWanted.class, AddTab.class, Front.class, Header.class, OpenShell.class, Output.class, CloseTab.class,
        CloseShell.class, WindowCommand.class, Ask.class, CloseAnswer.class, Prefs.class, SavePrefs.class, Prepare.class,
        Notice.class, WorkspacesWanted.class, Tiles.class, Load.class, LaunchWorkspace.class, TypeInto.class, EditScript.class})
public final class Frame {

    /** How many of each message the frame's inbox holds. A resize is the busiest, and is a state topic. */
    static final int CAPACITY = 256;

    /** No close gate: the closing is of a tab, not of the window. */
    static final long NO_GATE = -1;

    // ------------------------------------------------------------------ the state

    /**
     * Everything the frame knows, in one value.
     *
     * @param tabs     the tabs, in the bar's order
     * @param front    the tab shown, or {@link Messages#NO_TAB}
     * @param ids      the last tab id given out; ids are never reused, so a late message about a closed tab finds none
     * @param closings each close waiting on the person, by its run's id
     * @param prefs    what Settings holds
     * @param shells   the shells there are to choose from
     * @param override the launch's command line, which runs instead of the chosen shell this run; blank for none
     * @param home     where a new tab starts when no folder is chosen
     * @param saves    the last {@link SavePrefs} number given out
     * @param saving   a save is in flight: jobs are in no order, so the next waits for its answer
     * @param unsaved  a change was made while it was, and is saved when it answers
     * @param workspace  the workspace this window was opened as, whose script every tab sources; blank for none
     * @param workspaces every workspace's name, for Settings
     * @param spare    the launch's one plain tab while nothing has been typed into it, which what Ctrl+O opens
     *                 replaces; {@link Messages#NO_TAB} for none
     */
    @Withers
    record State(List<Tab> tabs, long front, long ids, Map<Long, Closing> closings, Preferences prefs,
                 List<ShellChoice> shells, String override, Path home, long saves, boolean saving,
                 boolean unsaved, String workspace, List<String> workspaces, long spare)
            implements FrameStateWithers {

        State {
            tabs = List.copyOf(tabs);
            closings = Map.copyOf(closings);
            shells = List.copyOf(shells);
            workspaces = List.copyOf(workspaces);
        }

        static State initial() {
            return new State(List.of(), Messages.NO_TAB, 0, Map.of(), Preferences.DEFAULT, List.of(), "",
                    Path.of("."), 0, false, false, "", List.of(), Messages.NO_TAB);
        }

        /** What a new tab runs: the launch's command line, else the chosen shell, else this platform's first. */
        String line() {
            if (!override.isBlank()) {
                return override;
            }
            return shells.stream().filter(c -> c.id().equals(prefs.shell())).findFirst()
                    .or(() -> shells.stream().findFirst()).map(ShellChoice::line).orElse("");
        }

        /** Where a new tab starts: the folder chosen in Settings, else home. */
        Path dir() {
            return prefs.dir().orElse(home);
        }

        Prefs said() {
            return new Prefs(prefs, shells, override, workspaces);
        }

        Optional<Tab> tab(long id) {
            return tabs.stream().filter(t -> t.id() == id).findFirst();
        }

        State with(Tab tab) {
            List<Tab> next = new ArrayList<>(tabs);
            next.replaceAll(t -> t.id() == tab.id() ? tab : t);
            return withTabs(next);
        }

        State without(long id) {
            List<Tab> next = new ArrayList<>(tabs);
            next.removeIf(t -> t.id() == id);
            return withTabs(next);
        }

        State withClosing(long run, Closing c) {
            Map<Long, Closing> next = new HashMap<>(closings);
            next.put(run, c);
            return withClosings(next);
        }

        State withoutClosing(long run) {
            Map<Long, Closing> next = new HashMap<>(closings);
            next.remove(run);
            return withClosings(next);
        }
    }

    /**
     * One tab.
     *
     * @param id        its id
     * @param dir       where its program starts: as asked, then as {@link Prepared} found it
     * @param line      the command line it runs: as asked, then with its scripts sourced, once {@code prepared}
     * @param opened    whether its {@link OpenShell} has been sent
     * @param title     what its program last called itself, shortened ({@link #shorten}); blank for nothing
     * @param name      what the person or its workspace row named it; blank for none
     * @param submitted whether a CR or LF was ever typed into it, so a program may be running: closing it asks
     * @param env       its workspace row's script key; blank for none
     * @param prepared  whether its {@link Prepared} has come, so {@code line} and {@code dir} are final
     * @param cols      its screen's first width in cells, or 0 until it is laid out
     * @param rows      its screen's first height in cells, or 0 until it is laid out
     * @param notice    what the band above its terminal is to say once its shell is prepared, beside why its
     *                  scripts did not run: {@code --insert-path}'s refusal; blank for nothing
     */
    @Withers
    record Tab(long id, Path dir, String line, boolean opened, String title, String name, boolean submitted,
               String env, boolean prepared, int cols, int rows, String notice) implements FrameTabWithers {

        static Tab of(long id, Path dir, String line, String name, String env) {
            return new Tab(id, dir, line, false, "", name.strip(), false, env, false, 0, 0, "");
        }

        Tab titled(String title) {
            return withTitle(shorten(title));
        }

        Tab named(String name) {
            return withName(name.strip());
        }

        /** Its shell may start: its line is prepared and its size known, and it has not started yet. */
        boolean ready() {
            return prepared && cols > 0 && !opened;
        }

        /**
         * What the bar says: the person's name for it; else the program's title; and until it gives one (or when it
         * clears it), the folder's name, or the folder for a drive's root.
         */
        String header() {
            if (!name.isEmpty()) {
                return name;
            }
            if (!title.isEmpty()) {
                return title;
            }
            Path n = dir.getFileName();
            return n == null ? dir.toString() : n.toString();
        }

        /**
         * A title as a tab's header can hold it: conhost titles a pseudoconsole with its program's whole path
         * ({@code C:\WINDOWS\...\powershell.exe}), so a path keeps its last name, without {@code .exe}. As 0.1 did.
         */
        static String shorten(String title) {
            String t = title.strip();
            int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
            if (slash >= 0 && slash < t.length() - 1) {
                t = t.substring(slash + 1);
            }
            if (t.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                t = t.substring(0, t.length() - 4);
            }
            return t.strip();
        }
    }

    /**
     * One close in flight.
     *
     * @param tabs the tabs it closes; none for the window's, which closes the window instead
     * @param busy those of them that were submitted to, which the question is about
     * @param gate the close gate to answer, or {@link #NO_GATE}
     * @param held the run, held while it waits for the person's answer
     */
    record Closing(List<Long> tabs, List<Long> busy, long gate, Optional<Chain.Resume> held) {

        Closing {
            tabs = List.copyOf(tabs);
            busy = List.copyOf(busy);
        }

        Closing holding(Chain.Resume run) {
            return new Closing(tabs, busy, gate, Optional.of(run));
        }

        Closing released() {
            return new Closing(tabs, busy, gate, Optional.empty());
        }
    }

    // ------------------------------------------------------------------ what a closing run goes on with

    /** Something may be running: ask. */
    record Busy() {
    }

    /** Close it. */
    record Confirmed() {
    }

    /** Keep it: the person cancelled. */
    record Cancelled() {
    }

    // ------------------------------------------------------------------ the declarations

    private final Ledger<State> ledger;

    public Frame(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.initial());

        Step<State, WindowOpened> begin = b.step("begin", Frame::begin);
        Step<State, Startup> started = b.step("started", Frame::started);
        Step<State, Resized> resized = b.step("resized", Frame::resized);
        Step<State, Prepared> prepared = b.step("prepared", Frame::prepared);
        Step<State, OpenAsked> openAsked = b.step("openAsked", Frame::openAsked);
        Step<State, Listed> listed = b.step("listed", Frame::listed);
        Step<State, OpenWorkspace> openWorkspace = b.step("openWorkspace", Frame::openWorkspace);
        Step<State, Loaded> loaded = b.step("loaded", Frame::loaded);
        Step<State, Launched> launched = b.step("launched", Frame::launched);
        Step<State, OpenFolder> openFolder = b.step("openFolder", Frame::openFolder);
        Step<State, Touched> touched = b.step("touched", Frame::touched);
        Step<State, EditEnv> editEnv = b.step("editEnv", Frame::editEnv);
        Step<State, ScriptOpened> scriptOpened = b.step("scriptOpened", Frame::scriptOpened);
        Step<State, WorkspaceNames> named = b.step("workspacesNamed", Frame::workspacesNamed);
        Step<State, Retitled> retitled = b.step("retitled", Frame::retitled);
        Step<State, ShellEnded> ended = b.step("ended", Frame::ended);
        Step<State, Fronted> fronted = b.step("fronted", Frame::fronted);
        Step<State, Submitted> submitted = b.step("submitted", Frame::submitted);
        Step<State, NewTab> newTab = b.step("newTab", Frame::newTab);
        Step<State, Rename> renamed = b.step("renamed", Frame::renamed);
        Step<State, Answer> answered = b.step("answered", Frame::answered);
        Step<State, ChangePrefs> changed = b.step("changed", Frame::changed);
        Step<State, PrefsSaved> saved = b.step("saved", Frame::saved);

        Step<State, CloseAsked> closeAsked = b.step("closeAsked", Frame::closeAsked);
        Step<State, CloseRequested> quitAsked = b.step("quitAsked", Frame::quitAsked);
        Step<State, Busy> ask = b.step("ask", Frame::ask);
        Step<State, Confirmed> finish = b.step("finish", Frame::finish);
        Step<State, Cancelled> stop = b.step("stop", Frame::stop);

        b.on(WindowOpened.TOPIC, begin)
                .on(Startup.TOPIC, started)
                .on(Resized.TOPIC, resized)
                .on(Prepared.TOPIC, prepared)
                .on(OpenAsked.TOPIC, openAsked)
                .on(Listed.TOPIC, listed)
                .on(OpenWorkspace.TOPIC, openWorkspace)
                .on(Loaded.TOPIC, loaded)
                .on(Launched.TOPIC, launched)
                .on(OpenFolder.TOPIC, openFolder)
                .on(Touched.TOPIC, touched)
                .on(EditEnv.TOPIC, editEnv)
                .on(ScriptOpened.TOPIC, scriptOpened)
                .on(WorkspaceNames.TOPIC, named)
                .on(Retitled.TOPIC, retitled)
                .on(ShellEnded.TOPIC, ended)
                .on(Fronted.TOPIC, fronted)
                .on(Submitted.TOPIC, submitted)
                .on(NewTab.TOPIC, newTab)
                .on(Rename.TOPIC, renamed)
                .on(Answer.TOPIC, answered)
                .on(ChangePrefs.TOPIC, changed)
                .on(PrefsSaved.TOPIC, saved);
        ledger = b.build(inbox, "frame", CAPACITY);

        closing(Chain.start("close tab", ledger, closeAsked).on(CloseAsked.TOPIC), ask, finish, stop);
        closing(Chain.start("quit", ledger, quitAsked).on(CloseRequested.TOPIC), ask, finish, stop);
    }

    /** The rest of a close, after its first step: ask; then close or keep on the answer. */
    private <A> void closing(Chain.Builder<State, A> start, Step<State, Busy> ask, Step<State, Confirmed> finish,
                             Step<State, Cancelled> stop) {
        start.then(Busy.class, ledger, ask)
                .then(Confirmed.class, ledger, finish)
                .or(Cancelled.class, ledger, stop)
                .build();
    }

    /** The frame's state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    // ------------------------------------------------------------------ the steps

    /**
     * The main window exists: ask what the launch wanted. Heard from the framework, so a test that builds the tree
     * headless, which never opens a window, starts no shell. Any other window opening changes nothing here.
     */
    private static State begin(State s, WindowOpened opened, Out out) {
        if (opened.main()) {
            out.send(StartupWanted.TOPIC, new StartupWanted());
        }
        return s;
    }

    /**
     * The settings, said to the views; then a tab per folder the launch named, or one in the folder chosen in Settings,
     * the first in front.
     */
    private static State started(State s, Startup startup, Out out) {
        State next = s.withPrefs(startup.prefs()).withShells(startup.shells()).withOverride(startup.line())
                .withHome(startup.home()).withWorkspace(startup.workspace()).withWorkspaces(startup.names());
        out.send(Prefs.TOPIC, next.said());
        List<TabSpec> tabs = startup.tabs().isEmpty() ? List.of(new TabSpec(next.dir(), "", "")) : startup.tabs();
        long first = Messages.NO_TAB;
        for (TabSpec t : tabs) {
            next = add(next, t.dir(), t.name(), t.env(), next.workspace(), out);
            if (first == Messages.NO_TAB) {
                first = next.ids();
            }
        }
        // --insert-path types into the first tab once its prompt is drawn, or says in it why it types nothing.
        if (!startup.insert().isBlank()) {
            out.send(TypeInto.TOPIC, new TypeInto(first, startup.insert()));
        }
        if (!startup.insertNotice().isBlank()) {
            Optional<Tab> t = next.tab(first);
            if (t.isPresent()) {
                next = next.with(t.get().withNotice(startup.insertNotice()));
            }
        }
        // A launch that named nothing opened one plain tab: spare until something is typed into it.
        next = next.withSpare(startup.tabs().isEmpty() ? first : Messages.NO_TAB);
        return front(next, first, out);
    }

    /**
     * One more tab, in the folder chosen in Settings and running the shell chosen there, put in front. In a workspace
     * window it sources the workspace's script, and no row's.
     */
    private static State newTab(State s, NewTab m, Out out) {
        State next = add(s.withSpare(Messages.NO_TAB), s.dir(), "", "", s.workspace(), out);
        return front(next, next.ids(), out);
    }

    /** The person changed a setting: everyone shows it, and it is saved. Tabs already open keep what they run. */
    private static State changed(State s, ChangePrefs c, Out out) {
        if (c.prefs().equals(s.prefs())) {
            return s;
        }
        State next = s.withPrefs(c.prefs());
        out.send(Prefs.TOPIC, next.said());
        return next.saving() ? next.withUnsaved(true) : save(next, out);
    }

    /** A save answered: if the settings changed meanwhile, save them again, else nothing is in flight. */
    private static State saved(State s, PrefsSaved p, Out out) {
        return s.unsaved() ? save(s.withUnsaved(false), out) : s.withSaving(false);
    }

    private static State save(State s, Out out) {
        State next = s.withSaves(s.saves() + 1).withSaving(true);
        out.send(SavePrefs.TOPIC, new SavePrefs(next.saves(), next.prefs()));
        return next;
    }

    /**
     * A tab's screen is laid out: its first size is kept, and its program starts at it once its line is prepared too.
     * Later sizes are the shell's.
     */
    private static State resized(State s, Resized r, Out out) {
        Optional<Tab> tab = s.tab(r.id());
        if (tab.isEmpty() || tab.get().opened() || tab.get().cols() > 0) {
            return s;
        }
        return start(s, tab.get().withCols(r.cols()).withRows(r.rows()), out);
    }

    /**
     * A tab's command line is prepared: kept, with the folder it starts in, and said above its terminal if its scripts
     * did not run; its program starts once its size is known too.
     */
    private static State prepared(State s, Prepared p, Out out) {
        Optional<Tab> tab = s.tab(p.id());
        if (tab.isEmpty() || tab.get().prepared()) {
            return s;
        }
        String notice = String.join(" ", java.util.stream.Stream.of(p.refused(), tab.get().notice())
                .filter(n -> !n.isBlank()).toList());
        if (!notice.isBlank()) {
            out.send(Notice.TOPIC, new Notice(p.id(), notice));
        }
        return start(s, tab.get().withLine(p.line()).withDir(p.dir()).withPrepared(true), out);
    }

    /** Keep {@code t}, and start its program if it is ready: prepared and sized, whichever came last. */
    private static State start(State s, Tab t, Out out) {
        if (!t.ready()) {
            return s.with(t);
        }
        out.send(OpenShell.TOPIC, new OpenShell(t.id(), t.line(), t.dir(), t.cols(), t.rows()));
        return s.with(t.withOpened(true));
    }

    /** A tab's program named itself: its header follows, and is said only when what it says changes. */
    private static State retitled(State s, Retitled r, Out out) {
        return s.tab(r.id()).map(t -> retab(s, t, t.titled(r.title()), out)).orElse(s);
    }

    /** The person named a tab, or cleared its name. */
    private static State renamed(State s, Rename r, Out out) {
        return s.tab(r.id()).map(t -> retab(s, t, t.named(r.name()), out)).orElse(s);
    }

    /** The person picked a tab. The view shows it already; this only keeps the frame's front in step. */
    private static State fronted(State s, Fronted f, Out out) {
        return s.tab(f.id()).isPresent() ? s.withFront(f.id()) : s;
    }

    /** Something was submitted in a tab: closing it asks from now on. */
    private static State submitted(State s, Submitted m, Out out) {
        return s.tab(m.id()).filter(t -> !t.submitted()).map(t -> s.with(t.withSubmitted(true))).orElse(s);
    }

    /**
     * A tab's program is gone. If it ran, its tab closes, and the window with the last. If it never started, the tab
     * stays and says why, so the person reads it; closing it is theirs.
     */
    private static State ended(State s, ShellEnded e, Out out) {
        if (s.tab(e.id()).isEmpty()) {
            return s;
        }
        if (!e.problem().isBlank()) {
            out.send(Output.TOPIC, new Output(e.id(), "\r\n" + e.problem() + "\r\n"));
            return s;
        }
        return remove(s, e.id(), out);
    }


    // ------------------------------------------------------------------ Ctrl+O (docs/workspaces.md, slice 2)

    /** The Ask id of a dialog that only tells: no closing has it, so its answer finds nothing to resume. */
    static final long TELL = -2;

    /** Ctrl+O: ask what workspaces there are. */
    private static State openAsked(State s, OpenAsked m, Out out) {
        out.send(WorkspacesWanted.TOPIC, new WorkspacesWanted());
        return s;
    }

    /** The workspaces, for the Open dialog; and for Settings, whose list follows. */
    private static State listed(State s, Listed l, Out out) {
        out.send(Tiles.TOPIC, new Tiles(l.workspaces()));
        return names(s, l.workspaces().stream().map(Workspace::name).toList(), out);
    }

    /** The Workspaces window changed the set: Settings' list follows. */
    private static State workspacesNamed(State s, WorkspaceNames n, Out out) {
        return names(s, n.names(), out);
    }

    /** Every workspace's name, said to Settings when it is news. */
    private static State names(State s, List<String> names, Out out) {
        if (names.equals(s.workspaces())) {
            return s;
        }
        State next = s.withWorkspaces(names);
        out.send(Prefs.TOPIC, next.said());
        return next;
    }

    /** A workspace chosen: its tabs here, or another MainFrame on it. */
    private static State openWorkspace(State s, OpenWorkspace o, Out out) {
        if (o.here()) {
            out.send(Load.TOPIC, new Load(o.name()));
        } else {
            out.send(LaunchWorkspace.TOPIC, new LaunchWorkspace(o.name()));
        }
        return s;
    }

    /** A workspace to open here: its rows as tabs, each sourcing its scripts, in place of the spare tab. */
    private static State loaded(State s, Loaded l, Out out) {
        if (l.workspace().isEmpty()) {
            out.send(Ask.TOPIC, Ask.info(TELL, "No workspace called " + l.name(),
                    "Its file is not in the workspaces folder any more."));
            return s;
        }
        State next = s;
        long first = Messages.NO_TAB;
        for (Workspace.Entry e : l.workspace().get().entries()) {
            next = add(next, Path.of(e.path()), e.title(), e.env(), l.name(), out);
            if (first == Messages.NO_TAB) {
                first = next.ids();
            }
        }
        return first == Messages.NO_TAB ? s : opened(next, first, out);
    }

    /** Another MainFrame started, or did not: with it started, a window holding only its spare tab closes. */
    private static State launched(State s, Launched l, Out out) {
        if (!l.problem().isBlank()) {
            out.send(Ask.TOPIC, Ask.info(TELL, "Could not open " + l.workspace(), l.problem()));
            return s;
        }
        return dropSpare(s, out);
    }

    /** A folder picked: a tab for it here, in place of the spare tab. */
    private static State openFolder(State s, OpenFolder o, Out out) {
        State next = add(s, o.dir(), "", "", "", out);
        return opened(next, next.ids(), out);
    }

    /**
     * <i>Edit environment…</i> on a tab's header: its row's script, or its workspace's, made if need be and opened in
     * VexelRay IDE. A tab of no workspace, or a + tab asking for a row's script it has not got, is told why not.
     */
    private static State editEnv(State s, EditEnv e, Out out) {
        Optional<Tab> tab = s.tab(e.id());
        if (tab.isEmpty()) {
            return s;
        }
        Tab t = tab.get();
        if (s.workspace().isBlank()) {
            out.send(Ask.TOPIC, Ask.info(TELL, "No environment to edit",
                    "This window is not a workspace's, so its tabs source no environment script. Open a workspace with "
                            + "Ctrl+O, or make one in the Workspaces window."));
            return s;
        }
        if (e.tab() && t.env().isBlank()) {
            out.send(Ask.TOPIC, Ask.info(TELL, "This tab has no script of its own",
                    "A tab added with + sources " + s.workspace() + "'s script only. Edit workspace environment… opens that."));
            return s;
        }
        String title = t.header();
        out.send(EditScript.TOPIC, new EditScript(s.workspace(), e.tab() ? t.env() : "", title, t.dir().toString(),
                Optional.of(EnvScripts.dialect(t.line()))));
        return s;
    }

    /** The script did not open: said, with where it is when it was made. */
    private static State scriptOpened(State s, ScriptOpened o, Out out) {
        if (!o.problem().isBlank()) {
            out.send(Ask.TOPIC, Ask.info(TELL, o.title(), o.problem()));
        }
        return s;
    }

    /** Something was typed into the spare tab: it is the person's now. */
    private static State touched(State s, Touched t, Out out) {
        return t.id() == s.spare() ? s.withSpare(Messages.NO_TAB) : s;
    }

    /** Tabs were opened, {@code first} the first of them: the spare tab goes, and {@code first} comes to the front. */
    private static State opened(State s, long first, Out out) {
        return front(dropSpare(s, out), first, out);
    }

    /** The spare tab closes, if there is one; with nothing else open, so does the window. */
    private static State dropSpare(State s, Out out) {
        long spare = s.spare();
        State next = s.withSpare(Messages.NO_TAB);
        if (spare == Messages.NO_TAB || next.tab(spare).isEmpty()) {
            return next;
        }
        out.send(CloseShell.TOPIC, new CloseShell(spare));
        return remove(next, spare, out);
    }
    // ------------------------------------------------------------------ closing: the chain's steps

    /** Close a tab: at once if it was never submitted to or the person said not to ask, else ask. */
    private static State closeAsked(State s, CloseAsked close, Out out) {
        Optional<Tab> tab = s.tab(close.id());
        if (tab.isEmpty()) {
            return s;
        }
        Closing c = new Closing(List.of(close.id()), List.of(close.id()), NO_GATE, Optional.empty());
        if (!close.ask() || !tab.get().submitted()) {
            return close(s, c, out);
        }
        out.result(new Busy());
        return s.withClosing(out.run(), c);
    }

    /**
     * The framework's question before a window closes: let it, or keep it. Only the main window's close is a quit; any
     * other (the framework's About) holds nothing of the frame's, so it closes at once.
     */
    private static State quitAsked(State s, CloseRequested quit, Out out) {
        List<Long> busy = quit.main()
                ? s.tabs().stream().filter(Tab::submitted).map(Tab::id).toList()
                : List.of();
        Closing c = new Closing(List.of(), busy, quit.ticket(), Optional.empty());
        if (busy.isEmpty()) {
            return close(s, c, out);
        }
        out.result(new Busy());
        return s.withClosing(out.run(), c);
    }

    /** Ask, and hold the run for the answer. The question's number is the run's. */
    private static State ask(State s, Busy b, Out out) {
        Closing c = s.closings().get(out.run());
        String names = names(s, c.busy());
        Ask question;
        if (c.gate() != NO_GATE) {
            question = c.busy().size() == 1
                    ? new Ask(out.run(), "Close MainFrame?",
                            "Something may still be running in " + names + ". Its shell ends with the window.",
                            List.of("Close", "Cancel"))
                    : new Ask(out.run(), "Close MainFrame?",
                            "Something may still be running in " + c.busy().size() + " tabs: " + names
                                    + ". Their shells end with the window.",
                            List.of("Close all", "Cancel"));
        } else {
            question = new Ask(out.run(), "Close " + names + "?",
                    "Its shell ends, and anything still running in it.", List.of("Close", "Cancel"));
        }
        out.send(Ask.TOPIC, question);
        return s.withClosing(out.run(), c.holding(out.holdForPerson()));
    }

    /** The person answered: the first button closes, anything else (Cancel, Escape, the dialog's ×) keeps. */
    private static State answered(State s, Answer a, Out out) {
        Closing c = s.closings().get(a.id());
        if (c == null || c.held().isEmpty()) {
            return s;
        }
        out.resume(c.held().get(), a.button() == 0 ? new Confirmed() : new Cancelled());
        return s.withClosing(a.id(), c.released());
    }

    private static State finish(State s, Confirmed yes, Out out) {
        Closing c = s.closings().get(out.run());
        return close(s.withoutClosing(out.run()), c, out);
    }

    /** The person kept it: close nothing, and keep the window if it was the window's close. */
    private static State stop(State s, Cancelled no, Out out) {
        Closing c = s.closings().get(out.run());
        if (c.gate() != NO_GATE) {
            out.send(CloseAnswer.TOPIC, new CloseAnswer(c.gate(), false));
        }
        return s.withoutClosing(out.run());
    }

    /** What a closing does at its end: let the window close, or end its tabs' shells and close the tabs. */
    private static State close(State s, Closing c, Out out) {
        if (c.gate() != NO_GATE) {
            out.send(CloseAnswer.TOPIC, new CloseAnswer(c.gate(), true));
            return s;
        }
        State next = s;
        for (long id : c.tabs()) {
            if (next.tab(id).isPresent()) {
                out.send(CloseShell.TOPIC, new CloseShell(id));
                next = remove(next, id, out);
            }
        }
        return next;
    }

    // ------------------------------------------------------------------ what the steps share

    /**
     * A tab, put in the bar, and its command line asked for: the line a new tab runs, with the scripts of this
     * window's workspace and of the tab's row ({@code env}) to be sourced.
     */
    private static State add(State s, Path dir, String name, String env, String workspace, Out out) {
        State next = s.withIds(s.ids() + 1);
        Tab tab = Tab.of(next.ids(), dir, s.line(), name, env);
        out.send(Prepare.TOPIC, new Prepare(tab.id(), tab.line(), dir, workspace, env));
        List<Tab> tabs = new ArrayList<>(next.tabs());
        tabs.add(tab);
        out.send(AddTab.TOPIC, new AddTab(tab.id(), tab.header()));
        return next.withTabs(tabs);
    }

    private static State front(State s, long id, Out out) {
        out.send(Front.TOPIC, new Front(id));
        return s.withFront(id);
    }

    /**
     * A tab goes from the bar. With the last, the window closes; with the one in front, the one that took its place
     * comes to the front (the next, or the previous when it was the last).
     */
    private static State remove(State s, long id, Out out) {
        int at = 0;
        while (at < s.tabs().size() && s.tabs().get(at).id() != id) {
            at++;
        }
        State next = s.without(id);
        out.send(CloseTab.TOPIC, new CloseTab(id));
        if (next.tabs().isEmpty()) {
            out.send(WindowCommand.TOPIC, WindowCommand.close(CloseRequested.MAIN));
            return next.withFront(Messages.NO_TAB);
        }
        if (next.front() == id) {
            return front(next, next.tabs().get(Math.min(at, next.tabs().size() - 1)).id(), out);
        }
        return next;
    }

    /** {@code was} becomes {@code now}, and the bar is told when what its header says changed. */
    private static State retab(State s, Tab was, Tab now, Out out) {
        if (!now.header().equals(was.header())) {
            out.send(Header.TOPIC, new Header(now.id(), now.header()));
        }
        return s.with(now);
    }

    private static String names(State s, List<Long> ids) {
        List<String> names = new ArrayList<>();
        for (long id : ids) {
            s.tab(id).ifPresent(t -> names.add(t.header()));
        }
        return String.join(", ", names);
    }
}
