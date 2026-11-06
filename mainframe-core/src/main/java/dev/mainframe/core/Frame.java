package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Withers;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The controller: which tabs there are, what each runs, and when its shell starts, as declared steps on one state,
 * reached only by messages.
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
 * <p>The window opens: ask what the launch wanted ({@link StartupWanted}), and add a tab per folder it names
 * ({@link AddTab}). The view lays each tab's screen out and says its size ({@link Resized}); on the first, the frame
 * starts the tab's shell at that size ({@link OpenShell}), so the program never starts at a guessed size. When the
 * program ends ({@link ShellEnded}) the tab closes ({@link CloseTab}), and with the last tab, the window. A program
 * that would not start keeps its tab open, saying why in it, so a mistyped command line is read rather than flashed.
 *
 * <h2>Where it is built</h2>
 *
 * <p>A {@code @Component} that takes an {@link Inbox} runs on the framework's logic lane, with every other such
 * component and never two at once, so it declares no {@code @Subscribe}: what it hears is the list in the
 * constructor, declared again as {@code @Hears} for the processor. {@link Core} lists it; {@code FrameTest} builds it
 * on a tick of its own.
 */
@Component
@Hears({WindowOpened.class, Startup.class, Resized.class, ShellEnded.class})
@Publishes({StartupWanted.class, AddTab.class, Front.class, OpenShell.class, Output.class, CloseTab.class,
        WindowCommand.class})
public final class Frame {

    /** How many of each message the frame's inbox holds. A resize is the busiest, and is a state topic. */
    static final int CAPACITY = 256;

    // ------------------------------------------------------------------ the state

    /**
     * Everything the frame knows, in one value.
     *
     * @param tabs  the tabs, in the bar's order
     * @param front the tab shown, or {@link Messages#NO_TAB}
     * @param ids   the last tab id given out; ids are never reused, so a late message about a closed tab finds none
     */
    @Withers
    record State(List<Tab> tabs, long front, long ids) implements FrameStateWithers {

        State {
            tabs = List.copyOf(tabs);
        }

        static State initial() {
            return new State(List.of(), Messages.NO_TAB, 0);
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
    }

    /**
     * One tab.
     *
     * @param id     its id
     * @param dir    where its program starts
     * @param line   the command line it runs
     * @param opened whether its {@link OpenShell} has been sent
     */
    record Tab(long id, Path dir, String line, boolean opened) {

        Tab started() {
            return new Tab(id, dir, line, true);
        }

        /** What the bar says until the program names itself: its folder's name, or the folder for a drive's root. */
        String header() {
            Path name = dir.getFileName();
            return name == null ? dir.toString() : name.toString();
        }
    }

    // ------------------------------------------------------------------ the declarations

    private final Ledger<State> ledger;

    public Frame(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.initial());

        Step<State, WindowOpened> begin = b.step("begin", Frame::begin);
        Step<State, Startup> started = b.step("started", Frame::started);
        Step<State, Resized> resized = b.step("resized", Frame::resized);
        Step<State, ShellEnded> ended = b.step("ended", Frame::ended);

        b.on(WindowOpened.TOPIC, begin)
                .on(Startup.TOPIC, started)
                .on(Resized.TOPIC, resized)
                .on(ShellEnded.TOPIC, ended);
        ledger = b.build(inbox, "frame", CAPACITY);
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

    /** A tab per folder the launch named, the first in front. */
    private static State started(State s, Startup startup, Out out) {
        State next = s;
        long first = Messages.NO_TAB;
        for (Path dir : startup.dirs()) {
            next = next.withIds(next.ids() + 1);
            Tab tab = new Tab(next.ids(), dir, startup.line(), false);
            next = next.withTabs(append(next.tabs(), tab));
            out.send(AddTab.TOPIC, new AddTab(tab.id(), tab.header()));
            if (first == Messages.NO_TAB) {
                first = tab.id();
            }
        }
        if (first != Messages.NO_TAB) {
            out.send(Front.TOPIC, new Front(first));
            next = next.withFront(first);
        }
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
        State next = s.without(e.id());
        out.send(CloseTab.TOPIC, new CloseTab(e.id()));
        if (next.tabs().isEmpty()) {
            out.send(WindowCommand.TOPIC, WindowCommand.close(CloseRequested.MAIN));
            return next.withFront(Messages.NO_TAB);
        }
        if (next.front() == e.id()) {
            long front = next.tabs().getFirst().id();
            out.send(Front.TOPIC, new Front(front));
            return next.withFront(front);
        }
        return next;
    }

    private static List<Tab> append(List<Tab> tabs, Tab tab) {
        List<Tab> next = new ArrayList<>(tabs);
        next.add(tab);
        return next;
    }
}
