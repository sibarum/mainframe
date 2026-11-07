package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseShell;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Preferences;
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
        ChangePrefs.class, PrefsSaved.class})
@Publishes({StartupWanted.class, AddTab.class, Front.class, Header.class, OpenShell.class, Output.class, CloseTab.class,
        CloseShell.class, WindowCommand.class, Ask.class, CloseAnswer.class, Prefs.class, SavePrefs.class})
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
     */
    @Withers
    record State(List<Tab> tabs, long front, long ids, Map<Long, Closing> closings, Preferences prefs,
                 List<ShellChoice> shells, String override, Path home, long saves, boolean saving,
                 boolean unsaved) implements FrameStateWithers {

        State {
            tabs = List.copyOf(tabs);
            closings = Map.copyOf(closings);
            shells = List.copyOf(shells);
        }

        static State initial() {
            return new State(List.of(), Messages.NO_TAB, 0, Map.of(), Preferences.DEFAULT, List.of(), "",
                    Path.of("."), 0, false, false);
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
            return new Prefs(prefs, shells, override);
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
     * @param dir       where its program starts
     * @param line      the command line it runs
     * @param opened    whether its {@link OpenShell} has been sent
     * @param title     what its program last called itself, shortened ({@link #shorten}); blank for nothing
     * @param name      what the person named it; blank for none
     * @param submitted whether a CR or LF was ever typed into it, so a program may be running: closing it asks
     */
    record Tab(long id, Path dir, String line, boolean opened, String title, String name, boolean submitted) {

        static Tab of(long id, Path dir, String line) {
            return new Tab(id, dir, line, false, "", "", false);
        }

        Tab started() {
            return new Tab(id, dir, line, true, title, name, submitted);
        }

        Tab titled(String title) {
            return new Tab(id, dir, line, opened, shorten(title), name, submitted);
        }

        Tab named(String name) {
            return new Tab(id, dir, line, opened, title, name.strip(), submitted);
        }

        Tab submittedTo() {
            return new Tab(id, dir, line, opened, title, name, true);
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
                .withHome(startup.home());
        out.send(Prefs.TOPIC, next.said());
        List<Path> dirs = startup.dirs().isEmpty() ? List.of(next.dir()) : startup.dirs();
        long first = Messages.NO_TAB;
        for (Path dir : dirs) {
            next = add(next, dir, next.line(), out);
            if (first == Messages.NO_TAB) {
                first = next.ids();
            }
        }
        return front(next, first, out);
    }

    /** One more tab, in the folder chosen in Settings and running the shell chosen there, put in front. */
    private static State newTab(State s, NewTab m, Out out) {
        State next = add(s, s.dir(), s.line(), out);
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

    /** A tab's screen is laid out: on the first size, start its program at it. Later sizes are the shell's. */
    private static State resized(State s, Resized r, Out out) {
        Optional<Tab> tab = s.tab(r.id());
        if (tab.isEmpty() || tab.get().opened()) {
            return s;
        }
        Tab t = tab.get();
        out.send(OpenShell.TOPIC, new OpenShell(t.id(), t.line(), t.dir(), r.cols(), r.rows()));
        return s.with(t.started());
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
        return s.tab(m.id()).filter(t -> !t.submitted()).map(t -> s.with(t.submittedTo())).orElse(s);
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

    private static State add(State s, Path dir, String line, Out out) {
        State next = s.withIds(s.ids() + 1);
        Tab tab = Tab.of(next.ids(), dir, line);
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
