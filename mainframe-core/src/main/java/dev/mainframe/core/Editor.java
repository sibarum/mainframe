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
import dev.mainframe.core.Messages.Fronted;
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
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Chain;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The controller: every decision the editor makes, as declared steps on one state, reached only by messages.
 *
 * <h2>A state, and the steps that change it</h2>
 *
 * <p>Everything the editor knows is one immutable value, a {@link State}, held by a {@link Ledger}. Nothing changes it
 * but a step declared below, and a step is a static method of the shape {@code (state, message, out) → next state}:
 * it reads the state and the message, says what it sends through its {@link Out}, and returns what the state is now.
 * The ledger runs one step at a time on the editor's lane and commits what it returns, so no step sees another
 * half-done, nothing needs a lock, and a step that throws changes nothing and sends nothing. The processor reads each
 * step's body and refuses one that captures, waits or touches the disk (chains.md's rule 1, design F): a step sees its
 * state, its message and its {@code Out}, and that is all.
 *
 * <p>So the editor holds no maps of callbacks, no flags and no counters outside its state: a question waiting for an
 * answer, a set of saves waiting to land and the session being restored are all in the {@link State}, as values.
 *
 * <h2>What it hears</h2>
 *
 * <p>Each message the editor acts on runs one step ({@link Ledger.Builder#on}): what the view reports, and what the
 * jobs reply. The sender publishes a plain message and does not know which step runs; the list in the constructor is
 * the whole of what the editor hears, the way a component's {@code @Subscribe} methods are.
 *
 * <h2>Closing is a chain</h2>
 *
 * <p>Closing tabs and quitting ask the same question and wait on the same saves, so they are one {@link Chain} with two
 * ways in ({@link #closing}): its first step decides whether anything is unsaved, the next asks, the person's answer
 * picks a branch (save, discard, cancel), and the last closes the tabs or answers the close gate. Read top to bottom,
 * it is the whole of what a close does, with every step it takes named; each run has one id, which is the question's
 * number and what a trace groups its steps by. A step that waits (on the person, on the disk) holds the run and keeps
 * the {@link Chain.Resume} in the state; the answer resumes it. Nothing waits on a thread.
 *
 * <h2>Effects are jobs</h2>
 *
 * <p>The disk is the jobs' ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link CreateFile},
 * {@link ReadSession}, {@link WriteSession}): a step sends a request and a later step acts on the reply. A slow disk is
 * a slow answer, never a stalled lane. Jobs are not in order with each other, so the restored session asks for one
 * file at a time and asks for the next when the last is answered.
 *
 * <h2>Where it is built</h2>
 *
 * <p>A {@code @Component} that takes an {@link Inbox} is given its own, on the framework's logic lane: that is where
 * its messages arrive and where its steps run, on one thread with every other such component and never two at once.
 * So it declares no {@code @Subscribe}: what it hears is the list above, declared again as {@code @Hears} for the
 * processor, which cannot read a constructor (the lane checks at startup that the two agree). {@link Core} lists it, so
 * {@code -gui}'s generated wiring builds it; {@code EditorTest} builds it on a tick of its own, which is the same
 * thing without a window.
 */
@Component
@Hears({WindowOpened.class, Recalled.class, Go.class, Open.class, FileRead.class, Found.class,
        Created.class, Fronted.class, Edited.class, Save.class, Written.class, Answer.class,
        Close.class, CloseRequested.class})
@Publishes({ShowFolder.class, AddTab.class, FrontTab.class, CloseTabs.class, Retitle.class,
        Caption.class, Status.class, Ask.class, Flush.class, SaveFailed.class, CloseAnswer.class,
        Read.class, Write.class, Find.class, Create.class, Recall.class, Remember.class})
public final class Editor {

    /**
     * How many of each message the editor's inbox holds before a sender is refused. A person sends one at a time; the
     * most that arrive at once are a closing's saves, a {@code Save} and then a {@code Written} for each unsaved tab,
     * because each file is its own write.
     */
    static final int CAPACITY = 256;

    /** No close gate: the closing is of tabs, not of the window. */
    static final long NO_GATE = -1;

    // ------------------------------------------------------------------ the state

    /**
     * Everything the editor knows, in one value.
     *
     * @param doc       the session's shape: the tabs, the one in front, the folder shown
     * @param ids       the last tab id given out; ids are never reused, so a late message about a closed tab finds none
     * @param reading   files asked of {@link ReadFile} and not yet answered, so a second click opens no second tab
     * @param closings  each close or quit waiting on the person or the disk, by its run's id
     * @param restoring what is left of bringing the last session back, one request at a time
     * @param front     the tab to put in front when the restore is done
     * @param started   the folder the application started in, shown when there is no other
     * @param armed     whether the restore is done: before then a write would overwrite what is being restored
     * @param last      the session last sent to be remembered, so an unchanged one is not written again
     */
    record State(Doc doc, long ids, Set<Path> reading, Map<Long, Closing> closings, List<Restore> restoring,
                 Optional<Path> front, Optional<Path> started, boolean armed, Optional<Remember> last) {

        State {
            reading = Set.copyOf(reading);
            closings = Map.copyOf(closings);
            restoring = List.copyOf(restoring);
        }

        static State initial() {
            return new State(Doc.initial(), 0, Set.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(),
                    false, Optional.empty());
        }

        State withDoc(Doc value) {
            return new State(value, ids, reading, closings, restoring, front, started, armed, last);
        }

        /** A new tab's id, taken. */
        State withNextId() {
            return new State(doc, ids + 1, reading, closings, restoring, front, started, armed, last);
        }

        State reading(Path file, boolean asked) {
            Set<Path> next = new HashSet<>(reading);
            if (asked) {
                next.add(file);
            } else {
                next.remove(file);
            }
            return new State(doc, ids, next, closings, restoring, front, started, armed, last);
        }

        State withClosing(long run, Closing value) {
            Map<Long, Closing> next = new HashMap<>(closings);
            next.put(run, value);
            return new State(doc, ids, reading, next, restoring, front, started, armed, last);
        }

        State withoutClosing(long run) {
            Map<Long, Closing> next = new HashMap<>(closings);
            next.remove(run);
            return new State(doc, ids, reading, next, restoring, front, started, armed, last);
        }

        State withRestoring(List<Restore> steps, Optional<Path> inFront, Path startedIn) {
            return new State(doc, ids, reading, closings, steps, inFront, Optional.of(startedIn), armed, last);
        }

        State withRestoring(List<Restore> steps) {
            return new State(doc, ids, reading, closings, steps, front, started, armed, last);
        }

        State withArmed() {
            return new State(doc, ids, reading, closings, restoring, Optional.empty(), started, true, last);
        }

        State withLast(Remember value) {
            return new State(doc, ids, reading, closings, restoring, front, started, armed, Optional.of(value));
        }
    }

    /**
     * One close or quit in flight.
     *
     * @param tabs    the tabs it closes; none for a quit, which closes the window instead
     * @param unsaved those of them with unsaved work, which the question is about
     * @param gate    the close gate to answer, or {@link #NO_GATE}
     * @param held    the run, held while it waits for the person's answer or for its saves to land
     * @param waiting the tabs whose saves have still to land
     */
    record Closing(List<Long> tabs, List<Long> unsaved, long gate, Optional<Chain.Resume> held, Set<Long> waiting) {

        Closing {
            tabs = List.copyOf(tabs);
            unsaved = List.copyOf(unsaved);
            waiting = Set.copyOf(waiting);
        }

        Closing holding(Chain.Resume run, Set<Long> saves) {
            return new Closing(tabs, unsaved, gate, Optional.of(run), saves);
        }

        Closing landed(long id) {
            Set<Long> next = new HashSet<>(waiting);
            next.remove(id);
            return new Closing(tabs, unsaved, gate, held, next);
        }

        Closing released() {
            return new Closing(tabs, unsaved, gate, Optional.empty(), Set.of());
        }
    }

    /** One request of the restore: a file to read, or a path to look at first. */
    record Restore(Why why, Path path) {
    }

    // ------------------------------------------------------------------ what a closing run goes on with

    /** Something is unsaved: ask. */
    record Unsaved() {
    }

    /** Save, then close. */
    record Confirmed() {
    }

    /** Close without saving. */
    record Declined() {
    }

    /** Everything is saved or let go: close. */
    record Ready() {
    }

    /** What ends a closing without closing: the person cancelled, or a save did not land. */
    interface Stop {
    }

    record Cancelled() implements Stop {
    }

    record Failed() implements Stop {
    }

    /**
     * The buttons of a closing's question, in the order the {@link Ask} lists them: its first, its second, and Cancel,
     * which the framework makes Escape and the close box. Each carries what the run goes on with, so an {@link Answer}
     * becomes the next step without a switch.
     */
    enum Choice {
        CONFIRM(new Confirmed()), OTHER(new Declined()), CANCEL(new Cancelled());

        final Object outcome;

        Choice(Object outcome) {
            this.outcome = outcome;
        }

        /** The question's buttons: {@code confirm}, {@code other}, and Cancel. */
        static List<String> labels(String confirm, String other) {
            return List.of(confirm, other, "Cancel");
        }

        /** The choice the button at {@code index} stands for; anything unknown is a cancel. */
        static Choice of(int index) {
            Choice[] all = values();
            return index >= 0 && index < all.length ? all[index] : CANCEL;
        }
    }

    // ------------------------------------------------------------------ the declarations

    private final Ledger<State> ledger;

    public Editor(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.initial());

        Step<State, WindowOpened> begin = b.step("begin", Editor::begin);
        Step<State, Recalled> recalled = b.step("recalled", Editor::recalled);
        Step<State, Go> go = b.step("go", Editor::go);
        Step<State, Open> open = b.step("open", Editor::open);
        Step<State, FileRead> fileRead = b.step("fileRead", Editor::fileRead);
        Step<State, Found> found = b.step("found", Editor::found);
        Step<State, Created> created = b.step("created", Editor::created);
        Step<State, Fronted> fronted = b.step("fronted", Editor::fronted);
        Step<State, Edited> edited = b.step("edited", Editor::edited);
        Step<State, Save> save = b.step("save", Editor::save);
        Step<State, Written> written = b.step("written", Editor::written);
        Step<State, Answer> answered = b.step("answered", Editor::answered);

        Step<State, Close> closeAsked = b.step("closeAsked", Editor::closeAsked);
        Step<State, CloseRequested> quitAsked = b.step("quitAsked", Editor::quitAsked);
        Step<State, Unsaved> ask = b.step("ask", Editor::ask);
        Step<State, Confirmed> saveThem = b.step("saveThem", Editor::saveThem);
        Step<State, Declined> discard = b.step("discard", Editor::discard);
        Step<State, Ready> finish = b.step("finish", Editor::finish);
        Step<State, Stop> stop = b.step("stop", Editor::stop);

        b.on(WindowOpened.TOPIC, begin)
                .on(Messages.RECALLED_TOPIC, recalled)
                .on(Messages.GO_TOPIC, go)
                .on(Messages.OPEN_TOPIC, open)
                .on(Messages.FILE_READ_TOPIC, fileRead)
                .on(Messages.FOUND_TOPIC, found)
                .on(Messages.CREATED_TOPIC, created)
                .on(Messages.FRONTED_TOPIC, fronted)
                .on(Messages.EDITED_TOPIC, edited)
                .on(Messages.SAVE_TOPIC, save)
                .on(Messages.WRITTEN_TOPIC, written)
                .on(Answer.TOPIC, answered);
        ledger = b.build(inbox, "editor", CAPACITY);

        closing(Chain.start("close tabs", ledger, closeAsked).on(Messages.CLOSE_TOPIC), ask, saveThem, discard, finish,
                stop);
        closing(Chain.start("quit", ledger, quitAsked).on(CloseRequested.TOPIC), ask, saveThem, discard, finish, stop);
    }

    /**
     * The rest of a close, after its first step: ask; then save, discard or stop on the answer; then close, or stop if
     * a save did not land. The same for closing tabs and for quitting, which differ only in how they start and in
     * what {@link #finish} does at the end.
     */
    private <A> void closing(Chain.Builder<State, A> start, Step<State, Unsaved> ask, Step<State, Confirmed> saveThem,
                             Step<State, Declined> discard, Step<State, Ready> finish, Step<State, Stop> stop) {
        start.then(Unsaved.class, ledger, ask)
                .then(Confirmed.class, ledger, saveThem)
                .or(Declined.class, ledger, discard)
                .or(Cancelled.class, ledger, stop)
                .then(Ready.class, ledger, finish)
                .or(Failed.class, ledger, stop)
                .build();
    }

    /** The editor's state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    // ------------------------------------------------------------------ the session, brought back

    /**
     * The main window exists: ask what there is to bring back. Heard from the framework, so a test that builds the tree
     * headless, which never opens a window, restores nothing and writes no settings. Any other window opening (the
     * framework's About) changes nothing here.
     */
    private static State begin(State s, WindowOpened opened, Out out) {
        if (!opened.main()) {
            return s;
        }
        out.send(Messages.RECALL_TOPIC, new Recall());
        return s;
    }

    /**
     * Bring back the folder, then the files that were open, then what the command line named, in that order and one
     * at a time, so the tabs come back as they were. A remembered file that has gone is skipped and a folder that has
     * gone is forgotten; the person moved them, and an editor should not complain about it.
     */
    private static State recalled(State s, Recalled r, Out out) {
        List<Restore> steps = new ArrayList<>();
        State next = s;
        if (r.folder().isPresent()) {
            steps.add(new Restore(Why.RESTORE_FOLDER, r.folder().get()));
        } else {
            next = showFolder(next, r.started(), out);
        }
        for (Path p : r.files()) {
            steps.add(new Restore(Why.RESTORE_FILE, p));
        }
        for (Path p : r.arguments()) {
            steps.add(new Restore(Why.ARGUMENT, p));
        }
        Optional<Path> front = r.arguments().isEmpty() ? r.front() : Optional.empty();
        return next(next.withRestoring(steps, front, r.started()), out);
    }

    /** The restore's next request, or, when there is none left, the front tab and the end of the restore. */
    private static State next(State s, Out out) {
        if (s.armed()) {
            return s;
        }
        if (!s.restoring().isEmpty()) {
            Restore r = s.restoring().getFirst();
            State rest = s.withRestoring(s.restoring().subList(1, s.restoring().size()));
            if (r.why() == Why.RESTORE_FILE) {
                return read(rest, r.path(), Why.RESTORE_FILE, true, out);
            }
            out.send(Messages.FIND_TOPIC, new Find(r.path(), r.why()));
            return rest;
        }
        State done = s;
        Doc.Entry front = s.front().map(p -> s.doc().entry(p)).orElse(null);
        if (front != null) {
            done = frontTab(done, front.id(), true, out);
        }
        return remember(done.withArmed(), out);
    }

    /**
     * Send the session to be remembered if it differs from what was last sent. Unsaved work is not part of it, so
     * this runs when the set of files, the one in front or the folder changes, not on every keystroke.
     */
    private static State remember(State s, Out out) {
        if (!s.armed()) {
            return s;
        }
        Doc d = s.doc();
        Doc.Entry f = d.front();
        Remember now = new Remember(Optional.ofNullable(d.folder()), d.files(),
                f == null ? Optional.empty() : Optional.of(f.path()));
        if (s.last().equals(Optional.of(now))) {
            return s;
        }
        out.send(Messages.REMEMBER_TOPIC, now);
        return s.withLast(now);
    }

    // ------------------------------------------------------------------ opening

    /**
     * What the path bar was given: a folder to show, a file to open, or a name that is not there yet, to create. A
     * relative path is relative to the folder shown.
     */
    private static State go(State s, Go go, Out out) {
        String text = go.text().strip();
        if (text.isEmpty()) {
            return s;
        }
        Path typed;
        try {
            typed = Path.of(text);
        } catch (InvalidPathException e) {
            refuse(out, "Not a path: " + text);
            return s;
        }
        Path base = s.doc().folder();
        Path target = (typed.isAbsolute() || base == null ? typed : base.resolve(typed)).toAbsolutePath().normalize();
        out.send(Messages.FIND_TOPIC, new Find(target, Why.GO));
        return s;
    }

    private static State open(State s, Open open, Out out) {
        Path file = open.path().toAbsolutePath().normalize();
        Doc.Entry already = s.doc().entry(file);
        if (already != null) {
            return frontTab(s, already.id(), open.focus(), out);
        }
        return read(s, file, Why.OPEN, open.focus(), out);
    }

    /** Ask for a file, unless it is open or already asked for; a restore that skips one goes on to the next. */
    private static State read(State s, Path file, Why why, boolean focus, Out out) {
        if (s.doc().entry(file) != null || s.reading().contains(file)) {
            return why == Why.RESTORE_FILE ? next(s, out) : s;
        }
        out.send(Messages.READ_TOPIC, new Read(file, why, focus));
        return s.reading(file, true);
    }

    /** What {@link ReadFile} made of a file: a new tab, or a reason on the status line. */
    private static State fileRead(State s, FileRead r, Out out) {
        State next = s.reading(r.path(), false);
        boolean restoring = r.why() == Why.RESTORE_FILE;
        String name = String.valueOf(r.path().getFileName());
        if (!r.problem().isEmpty()) {
            if (!restoring) {
                refuse(out, "Could not open " + name + ": " + r.problem());
            }
        } else if (next.doc().entry(r.path()) == null) {
            next = addTab(next, r.path(), r.text(), r.crlf(), r.focus(), out);
            say(out, r.notes().isEmpty() ? "Opened " + name : "Opened " + name + " (" + r.notes() + ")");
        }
        return restoring ? next(next, out) : next;
    }

    /** What {@link FindPath} found at a path, acted on for whoever asked. */
    private static State found(State s, Found f, Out out) {
        if (f.why() == Why.RESTORE_FOLDER) {
            Path shown = f.kind() == Kind.FOLDER ? f.path() : s.started().orElse(f.path());
            return next(showFolder(s, shown, out), out);
        }
        if (f.why() == Why.ARGUMENT) {
            if (f.kind() == Kind.FILE) {
                return read(s, f.path(), Why.RESTORE_FILE, true, out);
            }
            return next(f.kind() == Kind.FOLDER ? showFolder(s, f.path(), out) : s, out);
        }
        if (f.kind() == Kind.FOLDER) {
            return showFolder(s, f.path(), out);
        }
        if (f.kind() == Kind.FILE) {
            return open(s, new Open(f.path(), true), out);
        }
        if (f.kind() == Kind.NEW) {
            out.send(Messages.CREATE_TOPIC, new Create(f.path()));
            return s;
        }
        refuse(out, "No such folder: " + f.path().getParent());
        return s;
    }

    /** A file the path bar named into being: list the folder again, and open it empty. */
    private static State created(State s, Created c, Out out) {
        String name = String.valueOf(c.path().getFileName());
        if (!c.problem().isEmpty()) {
            refuse(out, "Could not create " + name + ": " + c.problem());
            return s;
        }
        if (s.doc().folder() != null) {
            out.send(Messages.SHOW_FOLDER_TOPIC, new ShowFolder(s.doc().folder()));
        }
        State next = addTab(s, c.path(), "", c.crlf(), true, out);
        say(out, "Created " + name);
        return next;
    }

    private static State addTab(State s, Path path, String text, boolean crlf, boolean focus, Out out) {
        State next = s.withNextId();
        long id = next.ids();
        next = next.withDoc(next.doc().withTab(new Doc.Entry(id, path, false, crlf)).withActive(id));
        out.send(Messages.ADD_TAB_TOPIC, new AddTab(id, path, text, focus));
        caption(next, out);
        return remember(next, out);
    }

    private static State frontTab(State s, long id, boolean focus, Out out) {
        State next = s.withDoc(s.doc().withActive(id));
        out.send(Messages.FRONT_TAB_TOPIC, new FrontTab(id, focus));
        caption(next, out);
        return remember(next, out);
    }

    private static State showFolder(State s, Path folder, Out out) {
        State next = s.withDoc(s.doc().withFolder(folder));
        out.send(Messages.SHOW_FOLDER_TOPIC, new ShowFolder(folder));
        return remember(next, out);
    }

    // ------------------------------------------------------------------ what the view reports

    private static State fronted(State s, Fronted f, Out out) {
        if (s.doc().entry(f.id()) == null || s.doc().active() == f.id()) {
            return s;
        }
        State next = s.withDoc(s.doc().withActive(f.id()));
        caption(next, out);
        return remember(next, out);
    }

    private static State edited(State s, Edited e, Out out) {
        Doc.Entry entry = s.doc().entry(e.id());
        if (entry == null || entry.dirty() == e.dirty()) {
            return s;
        }
        State next = s.withDoc(s.doc().withDirty(e.id(), e.dirty()));
        out.send(Messages.RETITLE_TOPIC, new Retitle(e.id(), next.doc().entry(e.id()).title()));
        if (next.doc().active() == e.id()) {
            caption(next, out);
        }
        return next;
    }

    // ------------------------------------------------------------------ saving

    /** Write a tab's text, whether the view sent it of its own accord (Ctrl+S) or because a close asked for it. */
    private static State save(State s, Save save, Out out) {
        Doc.Entry entry = s.doc().entry(save.id());
        if (!save.open() || entry == null) {
            // The tab went while its text was asked for: there is nothing left to write, and nothing lost by not.
            return landed(s, save.id(), out);
        }
        out.send(Messages.WRITE_TOPIC, new Write(save.id(), entry.path(), save.text(), entry.crlf()));
        return s;
    }

    private static State written(State s, Written w, Out out) {
        String name = String.valueOf(w.path().getFileName());
        if (!w.problem().isEmpty()) {
            out.send(Messages.SAVE_FAILED_TOPIC, new SaveFailed(w.id()));
            refuse(out, "Could not save " + name + ": " + w.problem());
            return failed(s, w.id(), out);
        }
        say(out, "Saved " + name);
        return landed(s, w.id(), out);
    }

    /** A save landed: the closing waiting on it goes on once it waits on nothing else. */
    private static State landed(State s, long id, Out out) {
        for (Map.Entry<Long, Closing> e : s.closings().entrySet()) {
            Closing c = e.getValue();
            if (c.waiting().contains(id)) {
                Closing next = c.landed(id);
                if (next.waiting().isEmpty()) {
                    out.resume(c.held().orElseThrow(), new Ready());
                    next = next.released();
                }
                return s.withClosing(e.getKey(), next);
            }
        }
        return s;
    }

    /** A save did not land: the closing waiting on it stops, and nothing it would have closed is closed. */
    private static State failed(State s, long id, Out out) {
        for (Map.Entry<Long, Closing> e : s.closings().entrySet()) {
            Closing c = e.getValue();
            if (c.waiting().contains(id)) {
                out.resume(c.held().orElseThrow(), new Failed());
                return s.withClosing(e.getKey(), c.released());
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ closing and quitting: the chain's steps

    /** Close these tabs: at once if none is unsaved, else ask. */
    private static State closeAsked(State s, Close close, Out out) {
        List<Long> tabs = new ArrayList<>();
        List<Long> unsaved = new ArrayList<>();
        for (long id : close.tabs()) {
            Doc.Entry entry = s.doc().entry(id);
            if (entry != null) {
                tabs.add(id);
                if (entry.dirty()) {
                    unsaved.add(id);
                }
            }
        }
        Closing c = new Closing(tabs, unsaved, NO_GATE, Optional.empty(), Set.of());
        if (unsaved.isEmpty()) {
            return close(s, c, out);
        }
        out.result(new Unsaved());
        return s.withClosing(out.run(), c);
    }

    /**
     * The framework's question before a window closes: let it, or keep it. The window is still open and drawing while
     * it is unanswered, and every way through the chain answers exactly once. Only the main window's close is a quit;
     * any other (the framework's About) holds nothing of the editor's, so it closes at once.
     */
    private static State quitAsked(State s, CloseRequested quit, Out out) {
        if (!quit.main()) {
            out.send(CloseAnswer.TOPIC, new CloseAnswer(quit.ticket(), true));
            return s;
        }
        List<Long> unsaved = new ArrayList<>();
        for (Doc.Entry e : s.doc().tabs()) {
            if (e.dirty()) {
                unsaved.add(e.id());
            }
        }
        Closing c = new Closing(List.of(), unsaved, quit.ticket(), Optional.empty(), Set.of());
        if (unsaved.isEmpty()) {
            return close(s, c, out);
        }
        out.result(new Unsaved());
        return s.withClosing(out.run(), c);
    }

    /** Ask about the unsaved tabs, and hold the run for the answer. The question's number is the run's. */
    private static State ask(State s, Unsaved u, Out out) {
        Closing c = s.closings().get(out.run());
        String names = names(s.doc(), c.unsaved());
        String several = c.unsaved().size() == 1
                ? names + " has unsaved changes."
                : c.unsaved().size() + " files have unsaved changes: " + names + ".";
        Ask question;
        if (c.gate() != NO_GATE) {
            question = new Ask(out.run(), "Quit with unsaved changes?", several,
                    Choice.labels("Save all", "Discard"));
        } else if (c.tabs().size() == 1) {
            question = new Ask(out.run(), "Unsaved changes", "Save the changes to " + names + " before closing it?",
                    Choice.labels("Save", "Don't save"));
        } else {
            question = new Ask(out.run(), "Unsaved changes", several, Choice.labels("Save all", "Discard"));
        }
        out.send(Ask.TOPIC, question);
        return s.withClosing(out.run(), c.holding(out.hold(), Set.of()));
    }

    /**
     * The person answered: the run goes on with what the answer means. Only a question still waiting is answered; once
     * its saves are under way the run waits on them, and a second answer finds nothing to resume.
     */
    private static State answered(State s, Answer a, Out out) {
        Closing c = s.closings().get(a.id());
        if (c == null || c.held().isEmpty() || !c.waiting().isEmpty()) {
            return s;
        }
        out.resume(c.held().get(), Choice.of(a.button()).outcome);
        return s.withClosing(a.id(), c.released());
    }

    /** Ask the view for every unsaved tab's text, in one message, and hold the run until every write has landed. */
    private static State saveThem(State s, Confirmed yes, Out out) {
        Closing c = s.closings().get(out.run());
        out.send(Messages.FLUSH_TOPIC, new Flush(c.unsaved()));
        return s.withClosing(out.run(), c.holding(out.hold(), Set.copyOf(c.unsaved())));
    }

    private static State discard(State s, Declined no, Out out) {
        out.result(new Ready());
        return s;
    }

    private static State finish(State s, Ready ready, Out out) {
        Closing c = s.closings().get(out.run());
        return close(s.withoutClosing(out.run()), c, out);
    }

    /** The person cancelled, or a save did not land: close nothing, and keep the window if it was a quit. */
    private static State stop(State s, Stop why, Out out) {
        Closing c = s.closings().get(out.run());
        if (c.gate() != NO_GATE) {
            out.send(CloseAnswer.TOPIC, new CloseAnswer(c.gate(), false));
        }
        return s.withoutClosing(out.run());
    }

    /** What a closing does at its end: close its tabs, or let the window close. */
    private static State close(State s, Closing c, Out out) {
        if (c.gate() != NO_GATE) {
            out.send(CloseAnswer.TOPIC, new CloseAnswer(c.gate(), true));
            return s;
        }
        State next = s;
        List<Long> closed = new ArrayList<>();
        for (long id : c.tabs()) {
            if (next.doc().entry(id) != null) {
                next = next.withDoc(next.doc().without(id));
                closed.add(id);
            }
        }
        if (!closed.isEmpty()) {
            out.send(Messages.CLOSE_TABS_TOPIC, new CloseTabs(closed));
        }
        caption(next, out);
        return remember(next, out);
    }

    private static String names(Doc doc, List<Long> ids) {
        List<String> names = new ArrayList<>();
        for (long id : ids) {
            Doc.Entry e = doc.entry(id);
            if (e != null) {
                names.add(e.name());
            }
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------ the status line and the caption

    private static void say(Out out, String message) {
        out.send(Messages.STATUS_TOPIC, new Status(message, false));
    }

    /** Say why on the status line, and draw the eye to it. A refusal is not worth a dialog. */
    private static void refuse(Out out, String why) {
        out.send(Messages.STATUS_TOPIC, new Status(why, true));
    }

    private static void caption(State s, Out out) {
        Doc.Entry f = s.doc().front();
        out.send(Messages.CAPTION_TOPIC, new Caption(f == null ? "" : f.title()));
    }
}
