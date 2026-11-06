package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar over the tab bar, whose pages are the tabs' terminals.
 *
 * <h2>It holds no state</h2>
 *
 * <p>The tabs are the view's to add and remove, as the frame says; this only builds the regions once and keeps them.
 * Nothing is rebuilt, so nothing on screen changes in one frame: a tab arriving slides in ({@link Motion#change}).
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application places the node and
 * supplies every colour in it through {@link Look}.
 */
final class Ui {

    private final Tabs tabs;

    Ui(Gui gui, Motion motion, TitleBar titleBar) {
        // Always shown, even with one tab: the + and the passthrough switch live in it (docs/shape.md, ruled).
        tabs = new Tabs(gui).name("tabs").closable(false).slide(motion.change);
        tabs.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TABS, tabs.node());

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), tabs.node());
    }

    Tabs tabs() {
        return tabs;
    }
}
