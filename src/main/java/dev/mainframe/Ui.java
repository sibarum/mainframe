package dev.mainframe;

import dev.mainframe.text.Markdown;
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
 * <p>{@link #show} takes a whole {@link Doc} and writes everything derived from it — the window title, the tab
 * headers, the status line — which is the rule worth keeping as this grows: a label that remembers a value can
 * disagree with the session, and a header that says a file is saved when it is not is the application lying.
 *
 * <p>{@code show} is called from the committing thread, which is a worker. That is correct and is the framework's
 * own idiom: a prop written off the GUI thread is queued and applied by the next drain. What is <em>not</em>
 * allowed is the other direction — reading the model from inside the frame loop.
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

    Ui(Gui gui, Motion motion, Model model, TitleBar titleBar) {
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

        workspace = new Workspace(gui, model, motion, markup,
                p -> status.text(Landmarks.STATUS_POSITION, "Ln " + p.line() + ", Col " + p.column()));
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

    /** Draw the eye to the status line: something was refused or failed, and the message there says what. */
    void alert() {
        motion.cues.play(status.node(), Cue.ring(danger, 2));
    }

    /** Write everything derived from the session. One method taking the whole value, so no two fields can disagree. */
    void show(Doc doc) {
        Doc.Entry front = doc.front();
        titleBar.title(front == null ? MainFrame.TITLE : front.title() + " — " + MainFrame.TITLE);
        status.text(Landmarks.STATUS_FILE, front == null ? "" : front.title());
        status.text(Landmarks.STATUS_MESSAGE, doc.status());
        if (front == null) {
            status.text(Landmarks.STATUS_POSITION, "");
        }
        workspace.retitle(doc);
    }
}
