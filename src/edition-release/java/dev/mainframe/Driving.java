package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.krono.KronoGui;

/**
 * The release edition: no driving socket. A shipped binary must not be able to open one, so this does not
 * depend on the automation module and --automation is ignored. The debug edition is src/edition-debug.
 */
final class Driving {

    private Driving() {
    }

    static AutoCloseable open(String want, Gui gui, GuiApp app, KronoGui krono) {
        return null;
    }
}
