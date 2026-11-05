package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar over the page the terminals will fill (docs/rebuild.md, M1 and M2). Empty
 * for now.
 *
 * <h2>It holds no state</h2>
 *
 * <p>Every label here will say what the frame last told the view. Nothing is remembered here to be shown later: a label
 * that remembers a value can disagree with the frame.
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application places the node and
 * supplies every colour in it through {@link Look}.
 */
final class Ui {

    private final Node page;

    Ui(Gui gui, Motion motion, TitleBar titleBar) {
        page = gui.column().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.PAGE, page);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), page);
    }

    /** Where the terminals go. */
    Node page() {
        return page;
    }
}
