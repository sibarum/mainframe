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
import dev.mainframe.core.Messages.EditEnv;
import dev.mainframe.core.Messages.EditName;
import dev.mainframe.core.Messages.Find;
import dev.mainframe.core.Messages.Focus;
import dev.mainframe.core.Messages.Found;
import dev.mainframe.core.Messages.Gone;
import dev.mainframe.core.Messages.IdeOpened;
import dev.mainframe.core.Messages.Insert;
import dev.mainframe.core.Messages.InsertChecked;
import dev.mainframe.core.Messages.Launch;
import dev.mainframe.core.Messages.Launched;
import dev.mainframe.core.Messages.ListAt;
import dev.mainframe.core.Messages.Listing;
import dev.mainframe.core.Messages.NewTab;
import dev.mainframe.core.Messages.OpenAsked;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.OpenInIde;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.PickFolder;
import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.mainframe.core.Messages.Prune;
import dev.mainframe.core.Messages.Renamed;
import dev.mainframe.core.Messages.Resolve;
import dev.mainframe.core.Messages.Resolved;
import dev.mainframe.core.Messages.Script;
import dev.mainframe.core.Messages.ScriptMade;
import dev.mainframe.core.Messages.Tell;
import dev.mainframe.core.Messages.Tile;
import dev.mainframe.core.Messages.Who;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * The controller: every decision MainFrame makes about its tabs, on a lane of its own, reached only by messages.
 *
 * <h2>One thread, so no locks</h2>
 *
 * <p>Everything here runs on the {@code frame} lane, one message at a time, in the order each mailbox received them.
 * The session (which tabs there are and what each was opened as, which one is spare, which workspace the window is)
 * is in fields nothing else can reach, and so needs no lock. Reaching across lanes is a compile error (T2.3); a message
 * is the only way in, and what goes out is a message too ({@link Messages}). The shells themselves are the view's: a
 * pseudoconsole and the screen it feeds cannot cross a lane, so the view starts each one when told to
 * ({@link AddTab}) and says when it has gone ({@link Gone}).
 *
 * <h2>What it decides</h2>
 *
 * <ul>
 *   <li>Which tabs a launch opens: what the command line names, else the start workspace, else one plain tab.</li>
 *   <li>Which tab is spare, and whether what is opened takes its place: the plain tab the launch opened, while it is
 *       the only one and nobody has used it. The view says whether it has been used; this says whether it is the plain
 *       one. With a workspace opened in a window of its own, the spare tab closes, and with it this window.</li>
 *   <li>Which workspace the window is, whose script a tab opened with + sources.</li>
 *   <li>Which shell a tab starts, and in which folder; whether closing one asks first; that the window closes with
 *       its last tab.</li>
 *   <li>What follows from a workspace changed in the settings window: its tabs follow its new name, the start
 *       workspace follows it or goes with it, and a new one is offered as the start workspace.</li>
 * </ul>
 *
 * <p>The disk and other processes are the jobs': this sends a request and acts on the reply, which comes back here
 * like any other message. A slow disk is a slow answer, never a stalled lane.
 *
 * <h2>Order</h2>
 *
 * <p>Each mailbox is in order, and two mailboxes are not in order with each other. So a set of tabs is prepared
 * whole and then sent to the view's one {@code AddTab} mailbox in order, and the spare tab is closed by the tab that
 * replaces it ({@link AddTab#replaces}) rather than by a message of its own, which could arrive first and close the
 * window. Jobs are not in order at all, so the launch's sources are resolved one at a time.
 */
@Component(lane = "frame")
@Publishes({Messages.ADD_TAB, Messages.CLOSE_TAB, Messages.FOCUS, Messages.CLOSE_WINDOW, Messages.ASK, Messages.TELL,
        Messages.TILE, Messages.PICK_FOLDER, Messages.EDIT_NAME, Messages.RESOLVE, Messages.PREPARE,
        Messages.CHECK_INSERT, Messages.LAUNCH, Messages.MAKE_SCRIPT, Messages.OPEN_IN_IDE, Messages.LIST,
        Messages.FIND, Messages.PRUNE})
public final class Frame {

    private static final Log LOG = Log.of("mainframe.frame");

    /**
     * What a tab was opened as: the folder its shell starts in (null for the new tab folder), its name (blank for
     * whatever its shell calls itself), and the workspace whose scripts it sources and the key of its row there (each
     * blank for none). A tab opened with + in a workspace's window is of the workspace and no row of it.
     */
    record Tab(Path dir, String title, String workspace, String env) {

        static final Tab PLAIN = new Tab(null, "", "", "");

        /** A tab named after its folder, unless it is given a name. */
        static Tab of(Path dir, String title, String workspace, String env) {
            String name = title.isBlank() && dir != null
                    ? (dir.getFileName() == null ? dir.toString() : dir.getFileName().toString()) : title.strip();
            return new Tab(dir, name, workspace, env);
        }
    }

    /** A set of tabs being prepared: what each will be, in order, and the command each starts once it is known. */
    private static final class Opening {
        final List<Long> ids = new ArrayList<>();
        final Map<Long, Tab> tabs = new HashMap<>();
        final Map<Long, Prepared> prepared = new HashMap<>();
        long replaces = Messages.NO_TAB;
        String type = "";
        String notice = "";
    }

    private final Atchung bus;
    private final AppSettings settings;

    /** The tabs the view has been told to open and has not said are gone, in the order they were opened. */
    private final Map<Long, Tab> open = new LinkedHashMap<>();
    /** The sets being prepared, by each of their tabs' ids. */
    private final Map<Long, Opening> preparing = new HashMap<>();
    /** What each resolve that has been asked for is for. */
    private final Map<Long, Consumer<Resolved>> resolving = new HashMap<>();
    /** The questions on screen, by number: what each answer does. */
    private final Map<Long, Consumer<Boolean>> asked = new HashMap<>();

    private long ids;
    private long questions;
    private long sequence;
    /** The Open dialog's listing in flight; a reply to an older one is dropped. */
    private long listing;
    /** The plain tab the launch opened because it named none, while it may still be spare. */
    private long spare = Messages.NO_TAB;
    /** The workspace this window was opened as, whose script a tab opened with + sources; blank for none. */
    private String workspace = "";
    /** Whether the launch's tabs have been asked for: before that, no tab is no reason to close. */
    private boolean begun;

    public Frame(Atchung bus, AppSettings settings) {
        this.bus = bus;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ the launch

    /**
     * The window exists: open the tabs the command line names, or the start workspace's, or one plain tab. A command
     * line after the options replaces the shell for this run, and {@code --insert-path} types into the first tab. The
     * sources are resolved one at a time, in order, so the tabs come up in the order they were named.
     */
    @Subscribe(topic = Messages.BEGIN)
    public void begin(Begin begin) {
        Command command = Command.launched();
        String line = command.shellLine();
        if (line != null) {
            settings.overrideShell(line);
        }
        command.notes().forEach(note -> LOG.warn("{}", note));
        bus.publish(Messages.PRUNE_TOPIC, new Prune());

        Queue<Command.Source> sources = new ArrayDeque<>(command.sources());
        String start = settings.startWorkspace();
        if (sources.isEmpty() && start != null) {
            sources.add(new Command.Source(Command.Kind.WORKSPACE, start));
        }
        List<Tab> tabs = new ArrayList<>();
        Runnable done = () -> {
            begun = true;
            boolean plain = tabs.isEmpty();
            if (plain) {
                tabs.add(Tab.PLAIN);
            } else {
                workspace = tabs.getFirst().workspace();
            }
            if (command.insertPath() == null) {
                open(tabs, Messages.NO_TAB, "", "", plain);
                return;
            }
            // Which shell the tab runs, as far as quoting goes: none MainFrame can vouch for if the command line chose it.
            InsertPath.Dialect dialect = line != null ? null : InsertPath.Dialect.of(settings.shell().id()).orElse(null);
            insertion = checked -> open(tabs, Messages.NO_TAB, checked.type(), checked.notice(), plain);
            bus.publish(Messages.CHECK_INSERT_TOPIC, new Insert(command.insertPath(), tabs.getFirst().dir(), dialect));
        };
        resolveEach(sources, tabs, done);
    }

    /** What the insert check's answer opens: the launch's tabs, with what to type into the first. */
    private Consumer<InsertChecked> insertion = checked -> { };

    @Subscribe(topic = Messages.INSERT_CHECKED)
    public void insertChecked(InsertChecked checked) {
        Consumer<InsertChecked> then = insertion;
        insertion = c -> { };
        then.accept(checked);
    }

    /** Resolve {@code sources} in order into {@code tabs}, one at a time, then run {@code done}. */
    private void resolveEach(Queue<Command.Source> sources, List<Tab> tabs, Runnable done) {
        Command.Source next = sources.poll();
        if (next == null) {
            done.run();
            return;
        }
        resolve(next, found -> {
            tabs.addAll(found);
            resolveEach(sources, tabs, done);
        });
    }

    private void resolve(Command.Source source, Consumer<List<Tab>> then) {
        long seq = ++sequence;
        resolving.put(seq, r -> {
            Workspace w = Workspace.parse(r.workspace(), r.text());
            List<Tab> found = new ArrayList<>();
            for (Workspace.Entry e : w.entries()) {
                found.add(Tab.of(Path.of(e.path()), e.name(), r.workspace(), e.env()));
            }
            then.accept(found);
        });
        bus.publish(Messages.RESOLVE_TOPIC, new Resolve(seq, source.kind(), source.value()));
    }

    @Subscribe(topic = Messages.RESOLVED)
    public void resolved(Resolved r) {
        Consumer<Resolved> then = resolving.remove(r.seq());
        if (then != null) {
            then.accept(r);
        }
    }

    // ------------------------------------------------------------------ opening tabs

    /**
     * Open {@code tabs} in order, the first of them in front: each one's shell line is prepared off this lane, and
     * when all are, the view is told them in order. {@code replaces} closes once any of them is open; {@code type}
     * and {@code notice} are the first tab's. {@code plain}: the launch's one plain tab, which is spare until used.
     */
    private void open(List<Tab> tabs, long replaces, String type, String notice, boolean plain) {
        Opening o = new Opening();
        o.replaces = replaces;
        o.type = type;
        o.notice = notice;
        String line = settings.shellCommand();
        Path chosen = settings.tabDirChosen();
        for (Tab t : tabs) {
            long id = ++ids;
            o.ids.add(id);
            o.tabs.put(id, t);
            preparing.put(id, o);
        }
        spare = plain ? o.ids.getFirst() : Messages.NO_TAB;   // any tab after the first, and the first is no longer spare
        for (long id : o.ids) {
            Tab t = o.tabs.get(id);
            bus.publish(Messages.PREPARE_TOPIC, new Prepare(id, line, t.dir(), chosen, t.title(), t.workspace(), t.env()));
        }
    }

    @Subscribe(topic = Messages.PREPARED, capacity = Messages.SET)
    public void prepared(Prepared p) {
        Opening o = preparing.remove(p.id());
        if (o == null) {
            return;
        }
        o.prepared.put(p.id(), p);
        if (o.prepared.size() < o.ids.size()) {
            return;
        }
        for (int i = 0; i < o.ids.size(); i++) {
            long id = o.ids.get(i);
            Tab t = o.tabs.get(id);
            Prepared ready = o.prepared.get(id);
            boolean first = i == 0;
            String notice = first && !o.notice.isBlank() ? o.notice
                    : ready.refused().isBlank() ? "" : "This tab's environment scripts did not run: " + ready.refused() + ".";
            open.put(id, t);
            bus.publish(Messages.ADD_TAB_TOPIC, new AddTab(id, ready.dir(), t.title(), ready.command(), notice,
                    first ? o.type : "", !t.workspace().isBlank(), !t.env().isBlank(), first, o.replaces));
        }
    }

    /** The + button or Ctrl+T. In a workspace's window, + sources the workspace's script: it is every tab's. */
    @Subscribe(topic = Messages.NEW_TAB)
    public void newTab(NewTab m) {
        open(List.of(new Tab(null, "", workspace, "")), Messages.NO_TAB, "", "", false);
    }

    @Subscribe(topic = Messages.OPEN_FOLDER)
    public void openFolder(OpenFolder m) {
        open(List.of(Tab.of(m.dir(), "", "", "")), spareOf(m.spare()), "", "", false);
    }

    /**
     * A workspace from the Open dialog. Here: its tabs, taking the spare tab's place, and the window becomes the
     * workspace's if it had nothing else in it. In a window of its own: a MainFrame started for it, and the spare
     * tab, with this window, closes once it has.
     */
    @Subscribe(topic = Messages.OPEN_WORKSPACE)
    public void openWorkspace(OpenWorkspace m) {
        long replaces = spareOf(m.spare());
        if (!m.here()) {
            bus.publish(Messages.LAUNCH_TOPIC, new Launch(m.name(), replaces));
            return;
        }
        resolve(new Command.Source(Command.Kind.WORKSPACE, m.name()), tabs -> {
            if (tabs.isEmpty()) {
                bus.publish(Messages.FOCUS_TOPIC, new Focus(Messages.NO_TAB));   // none of its folders is there
                return;
            }
            if (workspace.isBlank() && (replaces != Messages.NO_TAB || open.isEmpty())) {
                workspace = tabs.getFirst().workspace();
            }
            open(tabs, replaces, "", "", false);
        });
    }

    @Subscribe(topic = Messages.LAUNCHED)
    public void launched(Launched m) {
        if (!m.problem().isBlank()) {
            bus.publish(Messages.TELL_TOPIC, new Tell("Could not open " + m.name(), m.problem()));
        } else if (m.spare() != Messages.NO_TAB) {
            bus.publish(Messages.CLOSE_TAB_TOPIC, new CloseTab(m.spare()));   // the last tab: the new window stands in
        }
    }

    /** The tab the view says may be replaced, if it is the plain one the launch opened; else none. */
    private long spareOf(long word) {
        return word != Messages.NO_TAB && word == spare && open.size() == 1 ? word : Messages.NO_TAB;
    }

    // ------------------------------------------------------------------ the Open dialog

    /** Ctrl+O: the Open dialog, with a tile per workspace; or with none to choose from, straight to the folder picker. */
    @Subscribe(topic = Messages.OPEN_ASKED)
    public void openAsked(OpenAsked m) {
        bus.publish(Messages.LIST_TOPIC, new ListAt(Who.OPEN, ++listing, 0));
    }

    @Subscribe(topic = Messages.LISTED, capacity = Messages.SET)
    public void listed(Listing l) {
        if (l.who() != Who.OPEN || l.batch() != listing) {
            return;
        }
        if (l.of() == 0) {
            bus.publish(Messages.PICK_FOLDER_TOPIC, new PickFolder());
            return;
        }
        if (l.index() == 0) {
            for (int i = 1; i < l.of(); i++) {
                bus.publish(Messages.LIST_TOPIC, new ListAt(Who.OPEN, l.batch(), i));
            }
        }
        String detail = l.name().isBlank() ? "" : Workspace.parse(l.name(), l.text()).describe();
        bus.publish(Messages.TILE_TOPIC, new Tile(l.batch(), l.index(), l.of(), l.name(), detail));
    }

    // ------------------------------------------------------------------ closing

    /** Ctrl+W asks first, as closing a tab ends its shell and whatever it was running; Cancel goes back to it. */
    @Subscribe(topic = Messages.CLOSE_ASKED)
    public void closeAsked(CloseAsked m) {
        if (!open.containsKey(m.id())) {
            return;
        }
        if (!m.ask()) {
            bus.publish(Messages.CLOSE_TAB_TOPIC, new CloseTab(m.id()));
            return;
        }
        ask("Close " + m.title() + "?", "Its shell ends, and anything running in it.", "Close", "Cancel", close -> {
            if (close) {
                bus.publish(Messages.CLOSE_TAB_TOPIC, new CloseTab(m.id()));
            } else {
                bus.publish(Messages.FOCUS_TOPIC, new Focus(m.id()));
            }
        });
    }

    /** A tab has gone. The last one closes the window, unless more are on their way. */
    @Subscribe(topic = Messages.GONE, capacity = Messages.SET)
    public void gone(Gone m) {
        open.remove(m.id());
        if (spare == m.id()) {
            spare = Messages.NO_TAB;
        }
        if (begun && open.isEmpty() && preparing.isEmpty() && resolving.isEmpty()) {
            bus.publish(Messages.CLOSE_WINDOW_TOPIC, new CloseWindow());
        }
    }

    @Subscribe(topic = Messages.ANSWER)
    public void answer(Answer m) {
        Consumer<Boolean> then = asked.remove(m.question());
        if (then != null) {
            then.accept(m.confirm());
        }
    }

    private void ask(String title, String message, String confirm, String cancel, Consumer<Boolean> then) {
        long q = ++questions;
        asked.put(q, then);
        bus.publish(Messages.ASK_TOPIC, new Ask(q, title, message, confirm, cancel));
    }

    // ------------------------------------------------------------------ the environment scripts

    /**
     * A tab's menu: its own script, or its workspace's, made if need be and opened in VexelRay IDE. Its row is found
     * again by its key, and the workspace by the name it has now, which follows renames ({@link #renamed}).
     */
    @Subscribe(topic = Messages.EDIT_ENV)
    public void editEnv(EditEnv m) {
        Tab t = open.get(m.id());
        if (t == null || t.workspace().isBlank() || (m.tab() && t.env().isBlank())) {
            return;
        }
        String title = t.title().isBlank() ? "This tab" : t.title();
        bus.publish(Messages.MAKE_SCRIPT_TOPIC, new Script(t.workspace(), m.tab() ? t.env() : "", title, "",
                EnvScripts.dialect(settings.shellCommand())));
    }

    /** Whoever asked for a script, a tab's menu or the settings window, it opens in VexelRay IDE once it is made. */
    @Subscribe(topic = Messages.SCRIPT_MADE)
    public void scriptMade(ScriptMade m) {
        if (m.script() == null) {
            bus.publish(Messages.TELL_TOPIC, new Tell(m.title(), m.problem()));
        } else {
            bus.publish(Messages.OPEN_IN_IDE_TOPIC, new OpenInIde(m.script()));
        }
    }

    @Subscribe(topic = Messages.IDE_OPENED)
    public void ideOpened(IdeOpened m) {
        if (!m.problem().isBlank()) {
            bus.publish(Messages.TELL_TOPIC, new Tell(m.title(), m.problem()));
        }
    }

    // ------------------------------------------------------------------ what follows from the settings window

    /**
     * A workspace was renamed: the tabs of it here follow, so their menus edit its scripts under the new name, and so
     * does the window's and the start workspace.
     */
    @Subscribe(topic = Messages.RENAMED)
    public void renamed(Renamed m) {
        if (!m.problem().isBlank()) {
            return;
        }
        open.replaceAll((id, t) -> t.workspace().equals(m.from()) ? new Tab(t.dir(), t.title(), m.to(), t.env()) : t);
        if (workspace.equals(m.from())) {
            workspace = m.to();
        }
        if (m.from().equals(settings.startWorkspace())) {
            settings.startWorkspace(m.to());
        }
    }

    /**
     * A workspace was deleted: the start workspace goes back to none if it was it. Its tabs here are left as they
     * were, for an undo to bring the workspace back under them.
     */
    @Subscribe(topic = Messages.DELETED)
    public void deleted(Deleted m) {
        if (m.problem().isBlank() && m.name().equals(settings.startWorkspace())) {
            settings.startWorkspace(null);
        }
    }

    /**
     * New made a workspace: while there is no start workspace, it is offered as it, asked once. A start workspace that
     * names no saved one counts as none, as the settings window shows it, so that is looked at first.
     */
    @Subscribe(topic = Messages.CREATED)
    public void created(Created m) {
        if (!m.problem().isBlank()) {
            bus.publish(Messages.TELL_TOPIC, new Tell("Could not make " + m.name(), m.problem()));
            return;
        }
        String start = settings.startWorkspace();
        if (start == null) {
            offerStart(m.name());
        } else {
            bus.publish(Messages.FIND_TOPIC, new Find(start, m.name()));
        }
    }

    @Subscribe(topic = Messages.FOUND)
    public void found(Found m) {
        if (m.there()) {
            bus.publish(Messages.EDIT_NAME_TOPIC, new EditName());
        } else {
            offerStart(m.offer());
        }
    }

    /** Either answer gives the settings window's name field back the keyboard, where New left it. */
    private void offerStart(String name) {
        ask("Open this workspace at start?", "No workspace opens when MainFrame starts. This one can, whenever "
                        + "MainFrame is launched without folders or a workspace named. The General tab can change it later.",
                "Open at start", "Not now", yes -> {
                    if (yes) {
                        settings.startWorkspace(name);
                    }
                    bus.publish(Messages.EDIT_NAME_TOPIC, new EditName());
                });
    }
}
