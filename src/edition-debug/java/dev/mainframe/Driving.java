package dev.mainframe;

import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.krono.KronoGui;

/**
 * The driving socket: the debug edition. The release edition (src/edition-release) has the same class with
 * nothing in it, so the release binary does not contain the automation module at all. Which of the two is
 * compiled is chosen by the pom (property edition.src); see README, "Native builds".
 */
final class Driving {

    private Driving() {
    }

    /**
     * The driving socket, if this launch asked for one: {@code off}, {@code on} for the default port, or a port
     * number (0 is a free one). The line it prints is protocol: {@code ottermate --launch} reads the port from it.
     * A socket that cannot bind leaves the window running undriven.
     */
    static AutoCloseable open(String want, Gui gui, GuiApp app, KronoGui krono) {
        if (want.isBlank() || want.equals("off") || want.equals("false")) return null;
        try {
            int port = want.equals("on") || want.equals("true") ? AutomationServer.DEFAULT_PORT : Integer.parseInt(want);
            AutomationServer server = AutomationServer.start(
                    new Automation(gui, app.controls(), krono::quiescentAtLastTick), port);
            System.out.println("automation: localhost:" + server.port());
            return server::close;
        } catch (java.io.IOException | NumberFormatException e) {
            System.err.println("automation '" + want + "' bound nothing: " + e.getMessage());
            return null;
        }
    }
}
