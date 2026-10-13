package dev.mainframe;

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The debug edition's application declaration: {@link MainFrame}'s facts and {@link AutomationStarter}, the
 * driving socket: off unless {@code --automation} or {@code -Dautomation} asks, and loopback-only when it is, because
 * it hands whoever reaches it full control of the application's input.
 *
 * <p>The processor generates {@code MainFrameAppWiring} from this, and it is what {@code mvn compile exec:exec},
 * the tests and {@code -Pnative} compile. The release edition, {@code src/edition-release}, declares the same
 * application without the automation starter, and {@code -Pnative-release} compiles that one instead and drops the automation
 * modules from the class path, so the binary that ships links no socket at all. Keep the two annotations identical
 * apart from {@code starters}.
 *
 * <p>{@code icon} is the mark the window wears: the same file {@code src/main/rc/mainframe.rc} links into the
 * executable, which the pom puts on the class path. Draw your own, render it to an {@code .ico}, and replace that
 * one file; see the README, <i>The icon</i>.
 */
@VexelApp(name = MainFrame.APP, title = MainFrame.TITLE, width = MainFrame.W, height = MainFrame.H,
        icon = MainFrame.ICON, starters = AutomationStarter.class)
final class MainFrameApp {

    private MainFrameApp() {
    }
}
