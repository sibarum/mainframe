package dev.mainframe.core;

import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.List;

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

    // ------------------------------------------------------------------ the view tells the frame and the shell

    /** A tab's screen is laid out at this many cells. The frame opens its shell on the first; the shell resizes on the rest. */
    public record Resized(long id, int cols, int rows) {
        public static final Topic<Resized> TOPIC = Topic.state(Resized.class, Resized::id);
    }

    /** What goes to a tab's program, every one in order: a key as the terminal spells it, a paste, an answer. */
    public record Typed(long id, String text) {
        public static final Topic<Typed> TOPIC = Topic.edge(Typed.class, Typed::id);
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
     * What the launch asked for: the folders to open a tab in (none for one tab where MainFrame started), and the
     * command line each runs.
     */
    public record Startup(List<Path> dirs, String line) {
        public static final Topic<Startup> TOPIC = Topic.edge(Startup.class);

        public Startup {
            dirs = List.copyOf(dirs);
        }
    }
}
