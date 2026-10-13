package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The main window: the framework's title bar, and the tabs filling the rest.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application places the node and supplies
 * every colour in it through {@link Look}.
 */
final class Ui {

    Ui(Gui gui, TitleBar titleBar, Terminals terminals) {
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), terminals.node().width(Length.FILL).height(Length.grow(1f)));
    }
}
