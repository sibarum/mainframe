package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The main window: the framework's title bar with the settings button in it, and the tabs filling the rest.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application adds its one button at the
 * leading edge and supplies every colour through {@link Look}.
 */
final class Ui {

    Ui(Gui gui, TitleBar titleBar, Terminals terminals, Icons icons, SettingsWindow settings) {
        // The settings window comes forward, and the keyboard stays with the terminal.
        titleBar.addLeading(SettingsWindow.button(gui, icons, () -> {
            settings.show();
            terminals.focus();
        }));
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), terminals.node().width(Length.FILL).height(Length.grow(1f)));
    }
}
