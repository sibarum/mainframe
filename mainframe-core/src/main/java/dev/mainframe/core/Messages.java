package dev.mainframe.core;

import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Every message MainFrame's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is four kinds of part, and none of them holds another. The {@link Frame} decides: which tabs
 * there are, what each runs, and when its shell starts. The view, in {@code -gui}, shows, and owns the widgets, a
 * {@code TerminalScreen} per tab among them. A tab's {@link Shell} is the outside: it runs the program on a
 * pseudo-terminal, sends what it writes and writes what is typed. {@link ReadStartup} is a job. Each sends a record on
 * a topic and the others hear it on their own lane, so no state is shared and nothing needs a lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). A record that holds a list copies it in its constructor, so no sender can get that wrong.
 *
 * <h2>One tab, end to end</h2>
 *
 * <p>The frame sends {@link AddTab}; the view builds the tab's screen and, once it is laid out, sends its size as
 * {@link Resized}. The frame opens the tab's shell on the first one ({@link OpenShell}), so the program starts at the
 * size it is shown at. What the program writes comes back as {@link Output}, which the view feeds to the screen; what
 * is typed into the screen goes to the shell as {@link Typed}. When the program ends, the shell says
 * {@link ShellEnded} and the frame closes the tab ({@link CloseTab}). docs/shape.md has the picture.
 *
 * <h2>Keys</h2>
 *
 * <p>Every message to a tab's shell is on a topic keyed by the tab's id, so each tab's shell hears its own. The
 * terminal widget's own messages ({@code SENT}, {@code FITTED}) are keyed by window, not by widget, so the view turns
 * them into {@link Typed} and {@link Resized} with the tab's id (docs/framework-notes.md, FN-2).
 */
public final class Messages {

    private Messages() {
    }

    /** The id of no tab. */
    public static final long NO_TAB = -1;

    // ------------------------------------------------------------------ the frame tells the view

    /** A tab: its screen built and put in the bar, under {@code header}. */
    public record AddTab(long id, String header) {
        public static final Topic<AddTab> TOPIC = Topic.edge(AddTab.class);
    }

    /** The tab goes: its shell has ended, or will be ended. */
    public record CloseTab(long id) {
        public static final Topic<CloseTab> TOPIC = Topic.edge(CloseTab.class);
    }

    /** The tab to show, and to give the keyboard. The last one said is the one that counts. */
    public record Front(long id) {
        public static final Topic<Front> TOPIC = Topic.state(Front.class);
    }

    /** What a tab's header says now: its name, else the shell's title, else its folder. The last one said counts. */
    public record Header(long id, String text) {
        public static final Topic<Header> TOPIC = Topic.state(Header.class, Header::id);
    }

    // ------------------------------------------------------------------ the view tells the frame and the shell

    /** A tab's screen is laid out at this many cells. The frame opens its shell on the first; the shell resizes on the rest. */
    public record Resized(long id, int cols, int rows) {
        public static final Topic<Resized> TOPIC = Topic.state(Resized.class, Resized::id);
    }

    /** What goes to a tab's program, every one in order: a key as the terminal spells it, a paste, an answer. */
    public record Typed(long id, String text) {
        public static final Topic<Typed> TOPIC = Topic.edge(Typed.class, Typed::id);
    }

    /** A tab's program gave itself a title (an OSC 0 or 2); blank when it cleared it. The last one said counts. */
    public record Retitled(long id, String title) {
        public static final Topic<Retitled> TOPIC = Topic.state(Retitled.class, Retitled::id);
    }

    /** The view has fed one of a tab's {@link Output}s to its screen: the tab's shell may send another (FN-9). */
    public record Shown(long id) {
        public static final Topic<Shown> TOPIC = Topic.edge(Shown.class, Shown::id);
    }

    /** The person picked a tab (a click on its header, Ctrl+Tab): the frame's front follows. */
    public record Fronted(long id) {
        public static final Topic<Fronted> TOPIC = Topic.state(Fronted.class);
    }

    /**
     * A CR or LF was typed into a tab: something may be running in it now, so closing it asks. Said by the view, not
     * worked out by the frame from every {@link Typed}, so the frame's inbox carries one message per tab, not a key per
     * key; a state topic, so it cannot overflow.
     */
    public record Submitted(long id) {
        public static final Topic<Submitted> TOPIC = Topic.state(Submitted.class, Submitted::id);
    }

    /** A new tab, in the folder of the one in front and running what it runs. */
    public record NewTab() {
        public static final Topic<NewTab> TOPIC = Topic.edge(NewTab.class);
    }

    /** Close a tab: after asking if it was ever submitted to ({@code ask}), or at once. */
    public record CloseAsked(long id, boolean ask) {
        public static final Topic<CloseAsked> TOPIC = Topic.edge(CloseAsked.class);
    }

    /** The person named a tab; blank hands its header back to the program's title. */
    public record Rename(long id, String name) {
        public static final Topic<Rename> TOPIC = Topic.edge(Rename.class);
    }

    // ------------------------------------------------------------------ the frame and the shell

    /** Start a tab's program: {@code line} in {@code dir}, on a pseudo-terminal of {@code cols} by {@code rows}. */
    public record OpenShell(long id, String line, Path dir, int cols, int rows) {
        public static final Topic<OpenShell> TOPIC = Topic.edge(OpenShell.class, OpenShell::id);
    }

    /** End a tab's program, and its terminal with it. */
    public record CloseShell(long id) {
        public static final Topic<CloseShell> TOPIC = Topic.edge(CloseShell.class, CloseShell::id);
    }

    /** What a tab's program wrote, decoded, one read at a time. */
    public record Output(long id, String text) {
        public static final Topic<Output> TOPIC = Topic.edge(Output.class);
    }

    /**
     * A tab's program and its terminal are gone: it exited ({@code code}), or would not start ({@code problem} not
     * blank, as a person would be told it). Every {@link OpenShell} gets one.
     */
    public record ShellEnded(long id, int code, String problem) {
        public static final Topic<ShellEnded> TOPIC = Topic.edge(ShellEnded.class);
    }

    // ------------------------------------------------------------------ the frame and its job

    /** The window is up: what did the launch ask for? */
    public record StartupWanted() {
        public static final Topic<StartupWanted> TOPIC = Topic.edge(StartupWanted.class);
    }

    /**
     * What the launch asked for, and what Settings holds: the tabs to open (none for one tab in the folder chosen in
     * Settings), the command line given at launch (blank for none: the chosen shell runs), the settings, the shells
     * there are to choose from, the home folder (where a new tab starts when none is chosen), the workspace this window
     * was opened as (blank for none: {@code --workspace}, else the one Settings opens at launch), and every
     * workspace's name.
     */
    public record Startup(List<TabSpec> tabs, String line, Preferences prefs, List<ShellChoice> shells, Path home,
                          String workspace, List<String> names) {
        public static final Topic<Startup> TOPIC = Topic.edge(Startup.class);

        public Startup {
            tabs = List.copyOf(tabs);
            names = List.copyOf(names);
            shells = List.copyOf(shells);
        }
    }

    /**
     * A tab the launch or a workspace asks for: its folder, its name (blank: the folder's) and its row's script key
     * (blank: none). A value, not a message.
     */
    public record TabSpec(Path dir, String name, String env) {
    }

    // ------------------------------------------------------------------ a tab's command line (docs/workspaces.md)

    /**
     * Prepare a tab's command line: {@code line} with the environment scripts of {@code workspace} (blank: none) and
     * of its row ({@code env}, blank: none) sourced, starting in {@code dir}.
     */
    public record Prepare(long id, String line, Path dir, String workspace, String env) {
        public static final Topic<Prepare> TOPIC = Topic.edge(Prepare.class);
    }

    /**
     * {@link Prepare}'s reply: the line to run, the folder it starts in (home when the one asked for has gone), and why
     * the scripts did not run, as a person would be told it (blank: they ran, or there were none).
     */
    public record Prepared(long id, Path dir, String line, String refused) {
        public static final Topic<Prepared> TOPIC = Topic.edge(Prepared.class);
    }

    /** What the band above a tab's terminal says; blank for none. The last one said counts. */
    public record Notice(long id, String text) {
        public static final Topic<Notice> TOPIC = Topic.state(Notice.class, Notice::id);
    }

    // ------------------------------------------------------------------ settings (docs/settings.md)

    /**
     * What Settings holds: the terminals' text size, how the tab bar changes tabs, the shell a new tab runs (a
     * {@link ShellChoice}'s id), the folder a new tab starts in (empty for the home folder), and the workspace a launch that
     * names nothing opens (blank for none).
     */
    public record Preferences(int fontPx, TabMotion motion, String shell, Optional<Path> dir, String startWorkspace) {

        /** The text sizes on offer, in px: what the window's segment shows and Ctrl+= and Ctrl+- walk. */
        public static final List<Integer> SIZES = List.of(14, 16, 18, 20, 24);

        /** What a first run has. */
        public static final Preferences DEFAULT = new Preferences(18, TabMotion.SLIDE, "", Optional.empty(), "");

        public Preferences withFontPx(int px) {
            return new Preferences(px, motion, shell, dir, startWorkspace);
        }

        public Preferences withMotion(TabMotion m) {
            return new Preferences(fontPx, m, shell, dir, startWorkspace);
        }

        public Preferences withShell(String id) {
            return new Preferences(fontPx, motion, id, dir, startWorkspace);
        }

        public Preferences withDir(Optional<Path> d) {
            return new Preferences(fontPx, motion, shell, d, startWorkspace);
        }

        public Preferences withStartWorkspace(String name) {
            return new Preferences(fontPx, motion, shell, dir, name);
        }

        /** The next size up ({@code 1}) or down ({@code -1}) of {@link #SIZES}, or the first run's ({@code 0}). */
        public Preferences stepped(int direction) {
            if (direction == 0) {
                return withFontPx(DEFAULT.fontPx());
            }
            int at = 0;
            while (at < SIZES.size() - 1 && SIZES.get(at) < fontPx) {
                at++;
            }
            return withFontPx(SIZES.get(Math.max(0, Math.min(SIZES.size() - 1, at + direction))));
        }
    }

    /** One shell a new tab may run: {@code id} is what Settings keeps, {@code line} what is run. */
    public record ShellChoice(String id, String label, String line) {
    }

    /**
     * What is set now, what the shell could be, and the launch's command line when it overrides the shell for this
     * run (blank for none). The last one said counts.
     */
    public record Prefs(Preferences prefs, List<ShellChoice> shells, String override, List<String> workspaces) {
        public static final Topic<Prefs> TOPIC = Topic.state(Prefs.class);

        public Prefs {
            shells = List.copyOf(shells);
            workspaces = List.copyOf(workspaces);
        }
    }

    /** The person changed a setting: in the settings window, by a chord, or by picking a folder. */
    public record ChangePrefs(Preferences prefs) {
        public static final Topic<ChangePrefs> TOPIC = Topic.edge(ChangePrefs.class);
    }

    /** Write every setting and save. {@code seq} numbers the saves; the frame has one in flight at a time. */
    public record SavePrefs(long seq, Preferences prefs) {
        public static final Topic<SavePrefs> TOPIC = Topic.edge(SavePrefs.class);
    }

    /** {@link SavePrefs}'s reply: blank when it was saved, else why not, as a person would be told it. */
    public record PrefsSaved(long seq, String problem) {
        public static final Topic<PrefsSaved> TOPIC = Topic.edge(PrefsSaved.class);
    }
}
