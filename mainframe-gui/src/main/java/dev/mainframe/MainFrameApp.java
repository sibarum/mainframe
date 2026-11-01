package dev.mainframe;

import dev.mainframe.core.Core;
import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The application declaration: {@link MainFrame}'s facts, and what the application is built out of beyond
 * {@link Recipes} and the {@link View}.
 *
 * <ul>
 *   <li>{@link Core}: the {@code -core} module's editor and the jobs that touch the disk. The processor sees only this
 *       module's sources, so those are class files to it; {@code Core} lists them, and naming it here is what gets
 *       them built. Without it the window opens and nothing answers it.</li>
 *   <li>{@link AutomationStarter}: the automation sockets (remote control and diagnostics), in every build, the release
 *       one included. Off unless the launch asks ({@code --automation}, {@code VEXELRAY_AUTOMATION}) or a person
 *       switches them on from the title bar's plug, and loopback-only when they are on, because remote control hands
 *       whoever reaches it full control of the application's input. The plug's lamps show each one's port open or not
 *       and anything connected now or earlier; {@code --automation.available=none} leaves them out of a run
 *       altogether.</li>
 * </ul>
 *
 * <p>The processor generates {@code MainFrameAppWiring} from this. {@code icon} is the mark the window wears: the
 * same file {@code src/main/rc/mainframe.rc} links into the executable, which the pom puts on the class path.
 * Draw your own, render it to an {@code .ico}, and replace that one file; see the README, <i>The icon</i>.
 */
@VexelApp(name = MainFrame.APP, title = MainFrame.TITLE, width = MainFrame.W, height = MainFrame.H,
        icon = MainFrame.ICON, starters = {AutomationStarter.class, Core.class})
final class MainFrameApp {

    private MainFrameApp() {
    }
}
