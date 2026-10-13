package dev.mainframe;

import dev.vexelray.codec.image.gui.ImageStarter;
import dev.vexelray.framework.api.VexelApp;

/**
 * The release edition's application declaration: {@link MainFrame}'s facts, and only {@link ImageStarter}, which
 * binds the icons' textures to the window. In particular no
 * {@code AutomationStarter}, and the pom drops {@code vexelray-framework-automation} from the class path under
 * {@code -Pnative-release}, so a shipped binary cannot open a driving socket. The debug edition is
 * {@code src/edition-debug}; keep the two annotations identical apart from {@code starters}.
 */
@VexelApp(name = MainFrame.APP, title = MainFrame.TITLE, width = MainFrame.W, height = MainFrame.H,
        icon = MainFrame.ICON, starters = ImageStarter.class)
final class MainFrameApp {

    private MainFrameApp() {
    }
}
