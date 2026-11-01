package dev.mainframe;

import dev.mainframe.core.Markdown;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.SplitPane;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar, the navigator beside the open files, and a status line under both.
 *
 * <h2>It holds no state</h2>
 *
 * <p>Every label here says what the editor last told the view, and the view tells it ({@link #caption},
 * {@link #status}). Nothing is remembered here to be shown later, which is the rule worth keeping as this grows: a
 * label that remembers a value can disagree with the session, and a header that says a file is saved when it is not
 * is the application lying.
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless. Written to from the view's lane, which is the framework's own idiom: a prop written off the GUI
 * thread is queued and applied by the next drain.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application places the node and
 * supplies every colour in it through {@link Look}.
 */
final class Ui {

    /** The navigator's width on a first run. Type-relative, so it grows with the zoom like the text in it. */
    private static final Length NAVIGATOR = Length.rem(16);

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Workspace workspace;
    private final Navigator navigator;
    private final Motion motion;
    private final Color danger;

    Ui(Gui gui, Motion motion, TitleBar titleBar) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_FILE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_POSITION, StatusBar.Side.RIGHT, "")
                .minWidth(Landmarks.STATUS_POSITION, Length.rem(7));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_FILE, Landmarks.STATUS_MESSAGE, Landmarks.STATUS_POSITION}) {
            gui.landmark(slot, status.slot(slot));
        }

        // Markdown's colours are the theme's roles, and one of this application's own (Look.EMPHASIS). Resolved
        // once, here, because a role resolves at the moment it is asked and the theme does not change under us.
        Markdown.Style markup = new Markdown.Style(
                gui.theme().color(Role.ACCENT),
                gui.theme().color(Role.FAINT),
                Look.EMPHASIS,
                gui.theme().color(Role.ACCENT),
                gui.theme().color(Role.DIM),
                gui.theme().color(Role.INK),
                gui.theme().color(Role.PANEL));

        workspace = new Workspace(gui, motion, markup);
        navigator = new Navigator(gui, motion);

        SplitPane split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), workspace.node())
                .size(NAVIGATOR);
        split.node().width(Length.FILL).height(Length.grow(1f));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), split.node(), status.node());
    }

    Workspace workspace() {
        return workspace;
    }

    Navigator navigator() {
        return navigator;
    }

    /** The front file's title, dot and all, in the title bar and the status line; empty when nothing is open. */
    void caption(String file) {
        titleBar.title(file.isEmpty() ? MainFrame.TITLE : file + " — " + MainFrame.TITLE);
        status.text(Landmarks.STATUS_FILE, file);
        if (file.isEmpty()) {
            status.text(Landmarks.STATUS_POSITION, "");
        }
    }

    /** Where the caret is in the front file, on the status line. */
    void position(String where) {
        status.text(Landmarks.STATUS_POSITION, where);
    }

    /** Say something on the status line, and when {@code alert}, draw the eye to it: something was refused or failed. */
    void status(String message, boolean alert) {
        status.text(Landmarks.STATUS_MESSAGE, message);
        if (alert) {
            motion.cues.play(status.node(), Cue.ring(danger, 2));
        }
    }
}
