package dev.mainframe.core;

import sibarum.atchung.Topic;

import java.nio.file.Path;

/**
 * Every message MainFrame's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is three kinds of part, and none of them holds another. The {@link Frame} decides; it runs on a
 * lane of its own and owns the session: which tabs there are, what each was opened as, which one nobody has used yet.
 * The views, in the {@code -gui} module, show: the main window's ({@code View}) and the settings window's
 * ({@code SettingsView}), each on a lane of its own. The jobs touch the disk and start processes, on the offload lane.
 * Each sends a record on a topic and the others hear it on their own thread, so no state is shared and nothing needs
 * a lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4), and so nothing here holds a list. Where a set is many values it is a message each, saying which
 * of how many it is ({@link Listing}, {@link AddTab}). Where it is one value with a text form of its own it crosses
 * as that text: a {@link Workspace} is a CSV file, and its rows cross as the file's text ({@code text} below,
 * {@link Workspace#format} and {@link Workspace#parse}), which round-trips exactly, columns this build does not know
 * included.
 *
 * <h2>Who asks the jobs</h2>
 *
 * <p>The frame asks for whatever opens tabs. The settings window's Workspaces page edits workspace files, which needs
 * no decision but the ones the page makes as it is edited, so it asks the workspace jobs itself and hears their
 * replies. The frame hears them too ({@link Renamed}, {@link Deleted}, {@link Created}, {@link Restored}), for what
 * follows from them: the tabs of a renamed workspace follow its name, the start workspace follows it, and a new one
 * is offered as the start workspace.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside the name of its topic. A topic name is a string the processor checks
 * against every sender and receiver it can see (T4.1, T4.4): two topics with one spelling and two types are a compile
 * error. The {@code Topic} constants are what a sender publishes on. Text that can be absent is blank rather than
 * null; a {@code Path} that can be absent is null.
 */
public final class Messages {

    private Messages() {
    }

    /** The id of no tab: nothing is meant, or the one in front. */
    public static final long NO_TAB = -1;

    /**
     * Per-item mailboxes take a whole set at once: a workspace is a message per tab, and a full mailbox ends the
     * application (components.md, ruling 5). Far more than a person keeps open, so a burst never meets it.
     */
    public static final int SET = 4096;

    // ------------------------------------------------------------------ the view asks the frame

    /** The window exists: open what the command line names, or the start workspace, or one plain tab. */
    public static final String BEGIN = "frame.begin";
    public static final Topic<Begin> BEGIN_TOPIC = Topic.of(BEGIN, Begin.class);

    public record Begin() {
    }

    /** The + button or Ctrl+T: a plain tab, in the window's workspace if it has one. */
    public static final String NEW_TAB = "frame.new-tab";
    public static final Topic<NewTab> NEW_TAB_TOPIC = Topic.of(NEW_TAB, NewTab.class);

    public record NewTab() {
    }

    /**
     * A folder was picked, to open as a tab here. {@code spare} is the view's word on the tab whatever opens may take
     * the place of: the only tab, while nobody has sent its shell anything; {@link #NO_TAB} when there is none. Only
     * the frame knows whether that tab is the plain one the launch opened, so the two together decide.
     */
    public static final String OPEN_FOLDER = "frame.open-folder";
    public static final Topic<OpenFolder> OPEN_FOLDER_TOPIC = Topic.of(OPEN_FOLDER, OpenFolder.class);

    public record OpenFolder(Path dir, long spare) {
    }

    /** A workspace was chosen in the Open dialog: its tabs here, or a MainFrame of its own. {@code spare} as above. */
    public static final String OPEN_WORKSPACE = "frame.open-workspace";
    public static final Topic<OpenWorkspace> OPEN_WORKSPACE_TOPIC = Topic.of(OPEN_WORKSPACE, OpenWorkspace.class);

    public record OpenWorkspace(String name, boolean here, long spare) {
    }

    /** Ctrl+O: the Open dialog, or with no workspaces to choose from, the folder picker. */
    public static final String OPEN_ASKED = "frame.open-asked";
    public static final Topic<OpenAsked> OPEN_ASKED_TOPIC = Topic.of(OPEN_ASKED, OpenAsked.class);

    public record OpenAsked() {
    }

    /**
     * Close a tab: after asking ({@code ask}, Ctrl+W), or at once. {@code title} is what its header says now, which
     * only the view knows, since the shell renames it as it likes.
     */
    public static final String CLOSE_ASKED = "frame.close-asked";
    public static final Topic<CloseAsked> CLOSE_ASKED_TOPIC = Topic.of(CLOSE_ASKED, CloseAsked.class);

    public record CloseAsked(long id, String title, boolean ask) {
    }

    /** A tab has left the bar: closed, its shell ended, or its shell would not start. */
    public static final String GONE = "frame.gone";
    public static final Topic<Gone> GONE_TOPIC = Topic.of(GONE, Gone.class);

    public record Gone(long id) {
    }

    /** How an {@link Ask} was answered. */
    public static final String ANSWER = "frame.answer";
    public static final Topic<Answer> ANSWER_TOPIC = Topic.of(ANSWER, Answer.class);

    public record Answer(long question, boolean confirm) {
    }

    /** A tab's menu: edit its own environment script ({@code tab}), or its workspace's. */
    public static final String EDIT_ENV = "frame.edit-env";
    public static final Topic<EditEnv> EDIT_ENV_TOPIC = Topic.of(EDIT_ENV, EditEnv.class);

    public record EditEnv(long id, boolean tab) {
    }

    // ------------------------------------------------------------------ the frame tells the view

    /**
     * Open a tab: start {@code command} in {@code dir} and show it. {@code title} is its name, blank for whatever the
     * shell calls itself. {@code notice} is said over it until put away, and {@code type} is typed on its input line
     * once the prompt is drawn ({@code --insert-path}); both blank for none. {@code workspace} and {@code row}: whether
     * it is a tab of a workspace, and a row of one, which is what its menu offers to edit. {@code front}: selected and
     * given the keyboard. {@code replaces}: a tab to close once this one is open, or {@link #NO_TAB}.
     *
     * <p>A set of tabs is a message each, sent in order to one mailbox, so they arrive in order.
     */
    public static final String ADD_TAB = "view.add-tab";
    public static final Topic<AddTab> ADD_TAB_TOPIC = Topic.of(ADD_TAB, AddTab.class);

    public record AddTab(long id, Path dir, String title, String command, String notice, String type,
                         boolean workspace, boolean row, boolean front, long replaces) {
    }

    /** Close a tab; the frame has asked whatever had to be asked. */
    public static final String CLOSE_TAB = "view.close-tab";
    public static final Topic<CloseTab> CLOSE_TAB_TOPIC = Topic.of(CLOSE_TAB, CloseTab.class);

    public record CloseTab(long id) {
    }

    /** Give a tab the keyboard: {@code id}, or the one in front for {@link #NO_TAB}. */
    public static final String FOCUS = "view.focus";
    public static final Topic<Focus> FOCUS_TOPIC = Topic.of(FOCUS, Focus.class);

    public record Focus(long id) {
    }

    /** The last tab has gone: close the window, which ends MainFrame. */
    public static final String CLOSE_WINDOW = "view.close-window";
    public static final Topic<CloseWindow> CLOSE_WINDOW_TOPIC = Topic.of(CLOSE_WINDOW, CloseWindow.class);

    public record CloseWindow() {
    }

    /** Ask the person: a button and Cancel. The answer comes back as an {@link Answer} with this question. */
    public static final String ASK = "view.ask";
    public static final Topic<Ask> ASK_TOPIC = Topic.of(ASK, Ask.class);

    public record Ask(long question, String title, String message, String confirm, String cancel) {
    }

    /** Tell the person something went wrong: a dialog with OK. */
    public static final String TELL = "view.tell";
    public static final Topic<Tell> TELL_TOPIC = Topic.of(TELL, Tell.class);

    public record Tell(String title, String message) {
    }

    /**
     * One tile of the Open dialog: workspace {@code index} of {@code of}, named and described. The dialog opens once
     * every tile of its {@code batch} is in.
     */
    public static final String TILE = "view.tile";
    public static final Topic<Tile> TILE_TOPIC = Topic.of(TILE, Tile.class);

    public record Tile(long batch, int index, int of, String name, String detail) {
    }

    /** There are no workspaces to choose from: ask the OS for a folder instead of opening the dialog. */
    public static final String PICK_FOLDER = "view.pick-folder";
    public static final Topic<PickFolder> PICK_FOLDER_TOPIC = Topic.of(PICK_FOLDER, PickFolder.class);

    public record PickFolder() {
    }

    // ------------------------------------------------------------------ to the settings window

    /**
     * Open the settings window: on the Workspaces tab with {@code workspace} shown, or with a new one ({@code create}),
     * or as it was left when {@code workspace} is blank. From the Open dialog's tiles.
     */
    public static final String SHOW_SETTINGS = "settings.show";
    public static final Topic<ShowSettings> SHOW_SETTINGS_TOPIC = Topic.of(SHOW_SETTINGS, ShowSettings.class);

    public record ShowSettings(String workspace, boolean create) {
    }

    /** The question New raised is answered: the name field has the keyboard again, to type over. */
    public static final String EDIT_NAME = "settings.edit-name";
    public static final Topic<EditName> EDIT_NAME_TOPIC = Topic.of(EDIT_NAME, EditName.class);

    public record EditName() {
    }

    // ------------------------------------------------------------------ the frame and the jobs: tabs

    /** What a source of tabs names ({@link ResolveTabs}). {@code seq}: its place among the launch's sources. */
    public static final String RESOLVE = "tabs.resolve";
    public static final Topic<Resolve> RESOLVE_TOPIC = Topic.of(RESOLVE, Resolve.class);

    public record Resolve(long seq, Command.Kind kind, String value) {
    }

    /**
     * What {@link Resolve} found: the tabs, as the text of a workspace file whose rows are the folders that are
     * there, and {@code workspace}, the workspace they are of, blank for folders.
     */
    public static final String RESOLVED = "tabs.resolved";

    public record Resolved(long seq, String workspace, String text) {
    }

    /**
     * The command line a tab's shell starts with ({@link PrepareShell}): {@code line}, with the scripts of
     * {@code workspace} (blank for none) and of its row {@code env} (blank for none) sourced. {@code title} and
     * {@code dir} are the row's, for its script's region; a null {@code dir} is a plain tab, which starts in
     * {@code chosen}, the folder chosen for new tabs, while it is there, and else in the profile.
     */
    public static final String PREPARE = "shell.prepare";
    public static final Topic<Prepare> PREPARE_TOPIC = Topic.of(PREPARE, Prepare.class);

    public record Prepare(long id, String line, Path dir, Path chosen, String title, String workspace, String env) {
    }

    /**
     * What {@link Prepare} made: the folder the shell starts in, the command, and why the scripts are not sourced
     * ({@code refused}, blank if they are).
     */
    public static final String PREPARED = "shell.prepared";

    public record Prepared(long id, Path dir, String command, String refused) {
    }

    /**
     * What {@code --insert-path} may type into the first tab, whose shell starts in {@code dir} (null when the launch
     * opened no tab of its own) ({@link CheckInsert}). {@code dialect}: how its shell quotes, null when MainFrame
     * cannot tell.
     */
    public static final String CHECK_INSERT = "insert.check";
    public static final Topic<Insert> CHECK_INSERT_TOPIC = Topic.of(CHECK_INSERT, Insert.class);

    public record Insert(String spec, Path dir, InsertPath.Dialect dialect) {
    }

    /** What {@link CheckInsert} decided: what to type and what to say, each blank for nothing. */
    public static final String INSERT_CHECKED = "insert.checked";

    public record InsertChecked(String type, String notice) {
    }

    /** Start a MainFrame of its own for workspace {@code name} ({@link LaunchWindow}). {@code spare} as {@link OpenFolder}'s. */
    public static final String LAUNCH = "window.launch";
    public static final Topic<Launch> LAUNCH_TOPIC = Topic.of(LAUNCH, Launch.class);

    public record Launch(String name, long spare) {
    }

    public static final String LAUNCHED = "window.launched";

    public record Launched(String name, long spare, String problem) {
    }

    // ------------------------------------------------------------------ the environment scripts

    /**
     * Make an environment script, or bring it up to date ({@link MakeScript}): workspace {@code workspace}'s, or with
     * {@code env} its row's, in {@code dialect}. {@code tab} is what the row is called. With {@code path} blank, the row
     * is found again in the workspace's file by its key, and said to be gone if it is not there: a tab's menu, whose
     * tab may have left its workspace since it opened. With {@code path}, the row is the one given: the settings
     * window's, which has it on show and may not have saved it yet.
     */
    public static final String MAKE_SCRIPT = "script.make";
    public static final Topic<Script> MAKE_SCRIPT_TOPIC = Topic.of(MAKE_SCRIPT, Script.class);

    public record Script(String workspace, String env, String tab, String path, EnvScripts.Dialect dialect) {
    }

    /** The script, to open in VexelRay IDE; or null, and why not as a dialog would say it. */
    public static final String SCRIPT_MADE = "script.made";

    public record ScriptMade(Path script, String title, String problem) {
    }

    /** Open a file in VexelRay IDE, a process of its own ({@code -gui}'s {@code OpenInIde}). */
    public static final String OPEN_IN_IDE = "script.open";
    public static final Topic<OpenInIde> OPEN_IN_IDE_TOPIC = Topic.of(OPEN_IN_IDE, OpenInIde.class);

    public record OpenInIde(Path file) {
    }

    /** What {@link OpenInIde} did: {@code problem} blank when the editor started, else as a dialog would say it. */
    public static final String IDE_OPENED = "script.opened";

    public record IdeOpened(Path file, String title, String problem) {
    }

    // ------------------------------------------------------------------ the workspace files

    /** Who asked for a listing, so each hears only its own. */
    public enum Who {
        /** The frame, for the Open dialog's tiles. */
        OPEN,
        /** The settings window: its list of workspaces and its start workspace's choices. */
        SETTINGS
    }

    /**
     * The workspace at {@code index} of those there are, in alphabetical order ({@link ListWorkspaces}). A listing is
     * one request per workspace: index 0 first, whose answer says how many there are.
     */
    public static final String LIST = "workspaces.list";
    public static final Topic<ListAt> LIST_TOPIC = Topic.of(LIST, ListAt.class);

    public record ListAt(Who who, long batch, int index) {
    }

    /**
     * Workspace {@code index} of {@code of}: its name and its file's text. {@code of} is 0, and the name blank, when
     * there are none; the name is blank too when the listing changed under it.
     */
    public static final String LISTED = "workspaces.listed";

    public record Listing(Who who, long batch, int index, int of, String name, String text) {
    }

    /** Read a workspace, giving each row its script's key ({@link LoadWorkspace}). */
    public static final String LOAD = "workspace.load";
    public static final Topic<Load> LOAD_TOPIC = Topic.of(LOAD, Load.class);

    public record Load(String name) {
    }

    /** What {@link Load} found: its text, or {@code found} false and no rows. */
    public static final String LOADED = "workspace.loaded";

    public record Loaded(String name, String text, boolean found) {
    }

    /** Write a workspace's file, whole ({@link SaveWorkspace}). */
    public static final String SAVE = "workspace.save";
    public static final Topic<Save> SAVE_TOPIC = Topic.of(SAVE, Save.class);

    public record Save(String name, String text) {
    }

    public static final String SAVED = "workspace.saved";

    public record Saved(String name, String problem) {
    }

    /**
     * Rename a workspace, its file and its scripts ({@link RenameWorkspace}). {@code record}: whether the rename is a
     * new step in the settings window's history once it lands, as one typed into the name is; an undo's or a redo's
     * is already there.
     */
    public static final String RENAME = "workspace.rename";
    public static final Topic<Rename> RENAME_TOPIC = Topic.of(RENAME, Rename.class);

    public record Rename(String from, String to, boolean record) {
    }

    public static final String RENAMED = "workspace.renamed";

    public record Renamed(String from, String to, boolean record, String problem) {
    }

    /**
     * Put a workspace aside into {@code stash}, a folder its caller names, where {@link Restore} finds it again
     * ({@link DeleteWorkspace}). {@code record} as {@link Rename}'s.
     */
    public static final String DELETE = "workspace.delete";
    public static final Topic<Delete> DELETE_TOPIC = Topic.of(DELETE, Delete.class);

    public record Delete(String name, String stash, boolean record) {
    }

    public static final String DELETED = "workspace.deleted";

    public record Deleted(String name, String stash, boolean record, String problem) {
    }

    /** Bring back what a {@link Delete} put aside ({@link RestoreWorkspace}): the undo of a delete, and the redo of a New. */
    public static final String RESTORE = "workspace.restore";
    public static final Topic<Restore> RESTORE_TOPIC = Topic.of(RESTORE, Restore.class);

    public record Restore(String name, String stash) {
    }

    public static final String RESTORED = "workspace.restored";

    public record Restored(String name, String stash, String problem) {
    }

    /** The New button: an empty workspace, under the first free name of "Workspace", "Workspace 2", ... ({@link CreateWorkspace}). */
    public static final String CREATE = "workspace.create";
    public static final Topic<Create> CREATE_TOPIC = Topic.of(CREATE, Create.class);

    public record Create() {
    }

    public static final String CREATED = "workspace.created";

    public record Created(String name, String problem) {
    }

    /**
     * Whether workspace {@code name} is there ({@link FindWorkspace}). {@code offer}: the new workspace that may be
     * offered as the start workspace if it is not.
     */
    public static final String FIND = "workspace.find";
    public static final Topic<Find> FIND_TOPIC = Topic.of(FIND, Find.class);

    public record Find(String name, String offer) {
    }

    public static final String FOUND = "workspace.found";

    public record Found(String name, String offer, boolean there) {
    }

    /** Clear what deleting workspaces put aside long enough ago that no undo can want it ({@link PruneDeleted}). */
    public static final String PRUNE = "workspaces.prune";
    public static final Topic<Prune> PRUNE_TOPIC = Topic.of(PRUNE, Prune.class);

    public record Prune() {
    }
}
