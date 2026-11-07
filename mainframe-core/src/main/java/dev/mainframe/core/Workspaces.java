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
import dev.mainframe.core.Messages.Turn;
import dev.mainframe.core.Messages.Undo;
import dev.mainframe.core.Messages.WorkspaceNames;
import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Withers;
import dev.vexelray.framework.core.WindowCommand;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Workspaces window's controller: which workspaces there are, which is shown, every change the person makes to
 * them, and the journal the window's history undoes (docs/workspaces-page.md). The frame is about tabs; this is about
 * files, so it is a component of its own, on the logic lane beside the frame, and the window's view only shows what it
 * says ({@link Showing}) and tells it what the person did.
 *
 * <h2>The disk, one thing at a time</h2>
 *
 * <p>Every change goes to the disk through a job, and jobs are in no order, so a rename and a save of the same file
 * could pass each other. So there is one job in flight, and the rest wait in {@link State#queue} in the order they
 * were asked for. A save waits as the workspace's name, not its rows: the rows written are the ones there when its turn
 * comes, so several changes in a row are one write, and a rename that lands first takes the waiting save with it.
 *
 * <h2>Steps, and the journal</h2>
 *
 * <p>Every change is a step in the window's history. A change of a workspace's folders is decided here and shown at
 * once. A change of the set of workspaces (made, renamed, deleted) is shown once its job has landed, and only then is
 * it a step, so a rename the store refused leaves nothing to undo. Each step is an entry of {@link State#journal}, and
 * the view records its number ({@link Recorded}); the history's {@link Undo} and {@link Redo} hold only that number,
 * Vexplore's pattern. The history is a stack, so an entry is always taken back with every later one already taken
 * back, and the names it holds are the names there are.
 */
@Component
@Hears({EditWorkspaces.class, Gathered.class, Prefs.class, Select.class, NewWorkspace.class, RenameWorkspace.class,
        DeleteWorkspace.class, AddRow.class, RemoveRow.class, MoveRow.class, RenameRow.class, RepointRow.class,
        Undo.class, Redo.class, Written.class, Made.class, Moved.class, Stashed.class, Restored.class})
@Publishes({Gather.class, Prune.class, Showing.class, NameWanted.class, Recorded.class, WorkspaceNames.class,
        ChangePrefs.class, WindowCommand.class, Write.class, Make.class, Move.class, Stash.class, Restore.class})
public final class Workspaces {

    static final int CAPACITY = 256;

    /** What a new workspace is called, then with " 2", " 3", ... while that is taken. */
    static final String NEW_NAME = "New workspace";

    // ------------------------------------------------------------------ the state

    /**
     * Everything the window's controller knows.
     *
     * @param all      every workspace, in name order
     * @param selected the one on the right; blank while there is none
     * @param prefs    what Settings holds: its start workspace is the one marked, and follows a rename
     * @param problem  why the last name typed was refused; blank when it was not
     * @param said     the footer's line
     * @param journal  every step the history can take, by entry
     * @param entries  the last entry number given out
     * @param queue    the disk work waiting for the one in flight
     * @param busy     a job is in flight
     * @param pruned   what deleting put aside long ago has been cleared this run
     * @param making   a new workspace was asked for with the window, and is named once the workspaces are read
     */
    @Withers
    record State(List<Workspace> all, String selected, Preferences prefs, String problem, String said,
                 Map<Long, Change> journal, long entries, List<Op> queue, boolean busy, boolean pruned,
                 boolean making)
            implements WorkspacesStateWithers {

        State {
            all = List.copyOf(all);
            journal = Map.copyOf(journal);
            queue = List.copyOf(queue);
        }

        static State initial() {
            return new State(List.of(), "", Preferences.DEFAULT, "", "", Map.of(), 0, List.of(), false, false, false);
        }

        Optional<Workspace> workspace(String name) {
            return all.stream().filter(w -> w.name().equals(name)).findFirst();
        }

        boolean taken(String name, String except) {
            return all.stream().anyMatch(w -> w.name().equalsIgnoreCase(name) && !w.name().equals(except));
        }

        /** {@code w} in place of the workspace of its name, or added; in name order. */
        State put(Workspace w) {
            List<Workspace> next = new ArrayList<>();
            for (Workspace o : all) {
                if (!o.name().equals(w.name())) next.add(o);
            }
            next.add(w);
            next.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
            return withAll(next);
        }

        State drop(String name) {
            List<Workspace> next = new ArrayList<>(all);
            next.removeIf(w -> w.name().equals(name));
            return withAll(next);
        }

        /** The workspace a delete of {@code name} leaves shown: the next one down, or else the one above. */
        String neighbour(String name) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).name().equals(name)) {
                    if (i + 1 < all.size()) return all.get(i + 1).name();
                    return i > 0 ? all.get(i - 1).name() : "";
                }
            }
            return selected;
        }

        State with(Change c, long entry) {
            Map<Long, Change> next = new HashMap<>(journal);
            next.put(entry, c);
            return withJournal(next);
        }

        Showing showing() {
            return new Showing(all, selected, prefs.startWorkspace(), problem, said);
        }

        List<String> names() {
            return all.stream().map(Workspace::name).toList();
        }
    }

    /** A change in the journal: what taking it back and doing it again ask. */
    interface Change {
        /** What the footer and the history say of it. */
        String words();

        State apply(State s, long entry, Turn turn, Out out);
    }

    /** A workspace's folders, before and after: added, removed, renamed, moved or given another folder. */
    record Rows(String workspace, List<Workspace.Entry> before, List<Workspace.Entry> after, String words)
            implements Change {
        Rows {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }

        @Override
        public State apply(State s, long entry, Turn turn, Out out) {
            Optional<Workspace> w = s.workspace(workspace);
            if (w.isEmpty()) {
                return s.withSaid(turn.refused + "there is no workspace called " + workspace + " now.");
            }
            List<Workspace.Entry> rows = turn == Turn.UNDO ? before : after;
            return save(s.put(w.get().withEntries(rows)).withSelected(workspace).withProblem("")
                    .withSaid(turn.landed + words), workspace, out);
        }
    }

    /** A workspace renamed from {@code from} to {@code to}. */
    record Renamed(String from, String to) implements Change {
        @Override
        public String words() {
            return from + " renamed to " + to + ".";
        }

        @Override
        public State apply(State s, long entry, Turn turn, Out out) {
            Move m = turn == Turn.UNDO ? new Move(to, from, entry, turn) : new Move(from, to, entry, turn);
            return queue(s, new Send<>(Move.TOPIC, m), out);
        }
    }

    /**
     * A workspace that came to be or went: {@code made} when the step made it, else deleted it. Where it waits while
     * it is gone is {@code stash}, learned when it went.
     */
    record Presence(String name, boolean made, String stash) implements Change {
        @Override
        public String words() {
            return made ? name + " made." : name + " deleted.";
        }

        @Override
        public State apply(State s, long entry, Turn turn, Out out) {
            boolean goes = made == (turn == Turn.UNDO);
            Op op = goes ? new Send<>(Stash.TOPIC, new Stash(name, entry, turn))
                    : new Send<>(Restore.TOPIC, new Restore(stash, name, entry, turn));
            return queue(s, op, out);
        }
    }

    // ------------------------------------------------------------------ the queue

    /** Disk work waiting its turn: sent when it comes, or nothing, when what it was for has gone. */
    interface Op {
        /** Sends the work and says so, or says there is none to send. */
        boolean send(State s, Out out);

        /** The same work, with the workspace {@code from} called {@code to}. */
        Op renamed(String from, String to);

        /** Whether it is the save of {@code name}. */
        boolean saves(String name);
    }

    /** A save of workspace {@code name}, its rows as they are when its turn comes. */
    record Save(String name) implements Op {
        @Override
        public boolean send(State s, Out out) {
            Optional<Workspace> w = s.workspace(name);
            w.ifPresent(ws -> out.send(Write.TOPIC, new Write(ws)));
            return w.isPresent();
        }

        @Override
        public Op renamed(String from, String to) {
            return name.equals(from) ? new Save(to) : this;
        }

        @Override
        public boolean saves(String n) {
            return name.equals(n);
        }
    }

    /** A request as it was asked for. */
    record Send<T>(Topic<T> topic, T request) implements Op {
        @Override
        public boolean send(State s, Out out) {
            out.send(topic, request);
            return true;
        }

        @Override
        public Op renamed(String from, String to) {
            return this;
        }

        @Override
        public boolean saves(String name) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the steps

    private final Ledger<State> ledger;

    public Workspaces(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.initial());
        Step<State, EditWorkspaces> edit = b.step("edit", Workspaces::edit);
        Step<State, Gathered> gathered = b.step("gathered", Workspaces::gathered);
        Step<State, Prefs> prefs = b.step("prefs", Workspaces::prefs);
        Step<State, Select> select = b.step("select", Workspaces::select);
        Step<State, NewWorkspace> make = b.step("make", Workspaces::make);
        Step<State, RenameWorkspace> rename = b.step("rename", Workspaces::rename);
        Step<State, DeleteWorkspace> delete = b.step("delete", Workspaces::delete);
        Step<State, AddRow> add = b.step("addRow", Workspaces::addRow);
        Step<State, RemoveRow> remove = b.step("removeRow", Workspaces::removeRow);
        Step<State, MoveRow> move = b.step("moveRow", Workspaces::moveRow);
        Step<State, RenameRow> retitle = b.step("renameRow", Workspaces::renameRow);
        Step<State, RepointRow> repoint = b.step("repointRow", Workspaces::repointRow);
        Step<State, Undo> undo = b.step("undo", Workspaces::undo);
        Step<State, Redo> redo = b.step("redo", Workspaces::redo);
        Step<State, Written> written = b.step("written", Workspaces::written);
        Step<State, Made> made = b.step("made", Workspaces::made);
        Step<State, Moved> moved = b.step("moved", Workspaces::moved);
        Step<State, Stashed> stashed = b.step("stashed", Workspaces::stashed);
        Step<State, Restored> restored = b.step("restored", Workspaces::restored);
        b.on(EditWorkspaces.TOPIC, edit)
                .on(Gathered.TOPIC, gathered)
                .on(Prefs.TOPIC, prefs)
                .on(Select.TOPIC, select)
                .on(NewWorkspace.TOPIC, make)
                .on(RenameWorkspace.TOPIC, rename)
                .on(DeleteWorkspace.TOPIC, delete)
                .on(AddRow.TOPIC, add)
                .on(RemoveRow.TOPIC, remove)
                .on(MoveRow.TOPIC, move)
                .on(RenameRow.TOPIC, retitle)
                .on(RepointRow.TOPIC, repoint)
                .on(Undo.TOPIC, undo)
                .on(Redo.TOPIC, redo)
                .on(Written.TOPIC, written)
                .on(Made.TOPIC, made)
                .on(Moved.TOPIC, moved)
                .on(Stashed.TOPIC, stashed)
                .on(Restored.TOPIC, restored);
        ledger = b.build(inbox, "workspaces", CAPACITY);
    }

    /** The state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    /**
     * The window is opening: read the workspaces again, since another MainFrame may have changed them. The read waits
     * its turn behind this one's own disk work, so it reads what that wrote; shown, and the window opened, when it
     * arrives. A new workspace asked for with it is named and made after it, so its name is one not taken.
     */
    private static State edit(State s, EditWorkspaces e, Out out) {
        State next = s;
        if (!s.pruned()) {
            out.send(Prune.TOPIC, new Prune());
            next = next.withPruned(true);
        }
        if (!e.select().isBlank()) {
            next = next.withSelected(e.select());
        }
        return queue(next.withMaking(next.making() || e.make()), new Send<>(Gather.TOPIC, new Gather()), out);
    }

    /** The workspaces as they are on disk: shown, and the window opened on them. */
    private static State gathered(State s, Gathered g, Out out) {
        State next = s.withBusy(false);
        if (next.queue().stream().noneMatch(op -> op instanceof Save)) {
            next = next.withAll(g.all());   // else a change was made while it was read: what this one knows is newer
        }
        next = pump(shown(next, out), out);
        return next.making() ? make(next.withMaking(false), new NewWorkspace(), out) : next;
    }

    private static State shown(State s, Out out) {
        State next = s.workspace(s.selected()).isPresent() || s.all().isEmpty() ? s
                : s.withSelected(s.all().getFirst().name());
        out.send(Showing.TOPIC, next.showing());
        out.send(WorkspaceNames.TOPIC, new WorkspaceNames(next.names()));
        out.send(WindowCommand.TOPIC, WindowCommand.show(Messages.WORKSPACES_WINDOW));
        return next;
    }

    /** What Settings holds: the one marked as opening at launch. */
    private static State prefs(State s, Prefs p, Out out) {
        if (p.prefs().equals(s.prefs())) return s;
        State next = s.withPrefs(p.prefs());
        if (!next.prefs().startWorkspace().equals(s.prefs().startWorkspace())) {
            out.send(Showing.TOPIC, next.showing());
        }
        return next;
    }

    private static State select(State s, Select m, Out out) {
        if (m.name().equals(s.selected()) || s.workspace(m.name()).isEmpty()) return s;
        return show(s.withSelected(m.name()).withProblem(""), out);
    }

    /** Says the state to the view: every change ends here. */
    private static State show(State s, Out out) {
        out.send(Showing.TOPIC, s.showing());
        return s;
    }

    // ------------------------------------------------------------------ the set of workspaces

    /** <i>New workspace</i>, then <i>New workspace 2</i>, ...: made on disk, and shown once it is. */
    private static State make(State s, NewWorkspace m, Out out) {
        String name = NEW_NAME;
        for (int i = 2; s.taken(name, ""); i++) {
            name = NEW_NAME + " " + i;
        }
        return queue(s, new Send<>(Make.TOPIC, new Make(name, Messages.NO_ENTRY, Turn.DO)), out);
    }

    /** A name typed: refused, and said, when it cannot be a file's or is taken; else renamed on disk. */
    private static State rename(State s, RenameWorkspace r, Out out) {
        String to = r.to().strip();
        if (to.equals(r.from()) || s.workspace(r.from()).isEmpty()) {
            return s.problem().isBlank() ? s : show(s.withProblem(""), out);
        }
        String why = WorkspaceStore.problem(to);
        if (why == null && s.taken(to, r.from())) {
            why = "There is already a workspace called " + to + ".";
        }
        if (why != null) {
            return show(s.withProblem(why), out);
        }
        return queue(s.withProblem(""), new Send<>(Move.TOPIC, new Move(r.from(), to, Messages.NO_ENTRY, Turn.DO)), out);
    }

    /** Deleted without a question: put aside, where Ctrl+Z finds it. */
    private static State delete(State s, DeleteWorkspace d, Out out) {
        if (s.workspace(d.name()).isEmpty()) return s;
        return queue(s, new Send<>(Stash.TOPIC, new Stash(d.name(), Messages.NO_ENTRY, Turn.DO)), out);
    }

    private static State made(State s, Made m, Out out) {
        State next = pump(s.withBusy(false), out);
        if (!m.problem().isBlank()) {
            return show(next.withSaid(m.turn().refused + m.problem()), out);
        }
        next = next.put(new Workspace(m.name(), List.of())).withSelected(m.name());
        next = landed(next, new Presence(m.name(), true, ""), m.entry(), m.turn(), out);
        if (m.turn() == Turn.DO) {
            out.send(NameWanted.TOPIC, new NameWanted(m.name()));
        }
        return next;
    }

    private static State moved(State s, Moved m, Out out) {
        State next = s.withBusy(false);
        if (!m.problem().isBlank()) {
            next = m.turn() == Turn.DO ? next.withProblem(m.problem()) : next.withSaid(m.turn().refused + m.problem());
            return show(pump(next, out), out);
        }
        Optional<Workspace> w = next.workspace(m.from());
        List<Op> queue = next.queue().stream().map(op -> op.renamed(m.from(), m.to())).toList();
        next = next.withQueue(queue).drop(m.from());
        if (w.isPresent()) {
            next = next.put(w.get().withName(m.to()));
        }
        if (next.selected().equals(m.from()) || m.turn() != Turn.DO) {
            next = next.withSelected(m.to());
        }
        if (next.prefs().startWorkspace().equals(m.from())) {
            Preferences p = next.prefs().withStartWorkspace(m.to());
            next = next.withPrefs(p);
            out.send(ChangePrefs.TOPIC, new ChangePrefs(p));
        }
        next = pump(next, out);
        Change c = m.turn() == Turn.UNDO ? new Renamed(m.to(), m.from()) : new Renamed(m.from(), m.to());
        return landed(next, c, m.entry(), m.turn(), out);
    }

    private static State stashed(State s, Stashed m, Out out) {
        State next = s.withBusy(false);
        if (!m.problem().isBlank()) {
            return show(pump(next.withSaid(m.turn().refused + m.problem()), out), out);
        }
        List<Op> queue = new ArrayList<>(next.queue());
        queue.removeIf(op -> op.saves(m.name()));
        String shown = next.selected().equals(m.name()) ? next.neighbour(m.name()) : next.selected();
        next = pump(next.withQueue(queue).drop(m.name()).withSelected(shown), out);
        Change was = next.journal().get(m.entry());
        boolean made = was instanceof Presence p && p.made();
        return landed(next, new Presence(m.name(), made, m.stash()), m.entry(), m.turn(), out);
    }

    private static State restored(State s, Restored m, Out out) {
        State next = pump(s.withBusy(false), out);
        if (!m.problem().isBlank() || m.workspace().isEmpty()) {
            String why = m.problem().isBlank() ? m.name() + " could not be read." : m.problem();
            return show(next.withSaid(m.turn().refused + why), out);
        }
        next = next.put(m.workspace().get()).withSelected(m.name());
        Change was = next.journal().get(m.entry());
        Change now = was instanceof Presence p ? new Presence(p.name(), p.made(), "") : was;
        return landed(next, now, m.entry(), m.turn(), out);
    }

    /**
     * A change of the set landed: a new step when it was made just now, or the history's entry brought up to date (a
     * deleted workspace waits somewhere new each time); said, shown, and Settings told the names.
     */
    private static State landed(State s, Change c, long entry, Turn turn, Out out) {
        State next;
        String words = c.words();
        if (turn == Turn.DO) {
            long e = s.entries() + 1;
            next = s.withEntries(e).with(c, e);
            out.send(Recorded.TOPIC, new Recorded(e, words));
            if (c instanceof Presence p && !p.made()) {
                words = p.name() + " deleted. Ctrl+Z brings it back.";
            }
        } else {
            Change kept = s.journal().get(entry);
            next = kept == null ? s : s.with(c instanceof Presence ? c : kept, entry);
            words = turn.landed + (kept == null ? words : kept.words());
        }
        out.send(WorkspaceNames.TOPIC, new WorkspaceNames(next.names()));
        return show(next.withSaid(words), out);
    }

    // ------------------------------------------------------------------ a workspace's folders

    private static State addRow(State s, AddRow a, Out out) {
        Optional<Workspace> w = s.workspace(a.workspace());
        if (w.isEmpty()) return s;
        List<Workspace.Entry> rows = new ArrayList<>(w.get().entries());
        Set<String> taken = new HashSet<>();
        for (Workspace.Entry e : rows) taken.add(e.env().toLowerCase(Locale.ROOT));
        Workspace.Entry e = new Workspace.Entry("", a.dir().toString());
        rows.add(e.withEnv(Workspace.freshKey(e.title(), taken)));
        return rows(s, w.get(), rows, e.title() + " added to " + a.workspace() + ".", out);
    }

    private static State removeRow(State s, RemoveRow r, Out out) {
        Optional<Workspace> w = s.workspace(r.workspace());
        if (w.isEmpty() || r.at() < 0 || r.at() >= w.get().entries().size()) return s;
        List<Workspace.Entry> rows = new ArrayList<>(w.get().entries());
        Workspace.Entry gone = rows.remove(r.at());
        return rows(s, w.get(), rows, gone.title() + " removed from " + r.workspace() + ".", out);
    }

    private static State moveRow(State s, MoveRow m, Out out) {
        Optional<Workspace> w = s.workspace(m.workspace());
        if (w.isEmpty()) return s;
        int n = w.get().entries().size();
        if (m.from() == m.to() || m.from() < 0 || m.from() >= n || m.to() < 0 || m.to() >= n) return s;
        List<Workspace.Entry> rows = new ArrayList<>(w.get().entries());
        Workspace.Entry moving = rows.remove(m.from());
        rows.add(m.to(), moving);
        return rows(s, w.get(), rows, moving.title() + " moved.", out);
    }

    private static State renameRow(State s, RenameRow r, Out out) {
        Optional<Workspace> w = s.workspace(r.workspace());
        if (w.isEmpty() || r.at() < 0 || r.at() >= w.get().entries().size()) return s;
        Workspace.Entry was = w.get().entries().get(r.at());
        String name = r.name().strip();
        if (name.equals(was.name().strip())) return s;
        Workspace.Entry now = was.withName(name);
        if (now.withName("").title().equals(name)) {
            now = was.withName("");   // the folder's own name: blank says so, and follows the folder
            if (now.equals(was)) return s;
        }
        List<Workspace.Entry> rows = new ArrayList<>(w.get().entries());
        rows.set(r.at(), now);
        String words = name.isEmpty() ? was.title() + " named after its folder again." : was.title() + " renamed to " + now.title() + ".";
        return rows(s, w.get(), rows, words, out);
    }

    private static State repointRow(State s, RepointRow r, Out out) {
        Optional<Workspace> w = s.workspace(r.workspace());
        if (w.isEmpty() || r.at() < 0 || r.at() >= w.get().entries().size()) return s;
        Workspace.Entry was = w.get().entries().get(r.at());
        String path = r.dir().toString();
        if (path.equals(was.path())) return s;
        List<Workspace.Entry> rows = new ArrayList<>(w.get().entries());
        rows.set(r.at(), was.withPath(path));
        return rows(s, w.get(), rows, was.title() + " now opens " + path + ".", out);
    }

    /** A workspace's folders changed: shown, a step, and saved. */
    private static State rows(State s, Workspace w, List<Workspace.Entry> rows, String words, Out out) {
        long e = s.entries() + 1;
        State next = s.put(w.withEntries(rows)).withSelected(w.name()).withProblem("").withSaid(words)
                .withEntries(e).with(new Rows(w.name(), w.entries(), rows, words), e);
        out.send(Recorded.TOPIC, new Recorded(e, words));
        return save(next, w.name(), out);
    }

    /** A save of {@code name} queued, unless one already waits, and the state shown. */
    private static State save(State s, String name, Out out) {
        State next = s.queue().stream().anyMatch(op -> op.saves(name)) ? s : queue(s, new Save(name), out);
        return show(next, out);
    }

    // ------------------------------------------------------------------ the history

    private static State undo(State s, Undo u, Out out) {
        Change c = s.journal().get(u.entry());
        return c == null ? s : c.apply(s, u.entry(), Turn.UNDO, out);
    }

    private static State redo(State s, Redo r, Out out) {
        Change c = s.journal().get(r.entry());
        return c == null ? s : c.apply(s, r.entry(), Turn.REDO, out);
    }

    // ------------------------------------------------------------------ the disk

    private static State written(State s, Written w, Out out) {
        State next = pump(s.withBusy(false), out);
        return w.problem().isBlank() ? next : show(next.withSaid(w.problem()), out);
    }

    /** {@code op} sent now if nothing is in flight, else after what waits. */
    private static State queue(State s, Op op, Out out) {
        List<Op> queue = new ArrayList<>(s.queue());
        queue.add(op);
        return pump(s.withQueue(queue), out);
    }

    /** With nothing in flight, the next thing waiting that still has anything to send is sent. */
    private static State pump(State s, Out out) {
        if (s.busy()) return s;
        List<Op> queue = new ArrayList<>(s.queue());
        while (!queue.isEmpty()) {
            Op op = queue.removeFirst();
            if (op.send(s, out)) {
                return s.withQueue(queue).withBusy(true);
            }
        }
        return s.withQueue(queue);
    }
}
