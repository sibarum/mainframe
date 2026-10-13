package dev.mainframe;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MainFrame's real tree, built headless by the generated wiring: no window, no GPU, and no shell.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and stops, so the recipe that takes the
 * {@code Shell} and starts the first tab does not run. That is the property under test as much as anything: a test
 * that builds the window's contents must not start a process.
 */
class TreeTest {

    private Shell shell;
    private MainFrameAppWiring wiring;

    @BeforeEach
    void build() {
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    @Test
    void theTreeIsBuiltWithNoShellRunning() {
        assertNotNull(wiring.ui());
        assertNotNull(wiring.terminals());
        assertNull(wiring.terminals().current(), "no tab is opened before the start recipe");
        assertNull(wiring.start(), "the start recipe takes the Shell, so a tree stops before it");
    }

    @Test
    void aShellNamesItselfByItsExecutable() {
        assertEquals("pwsh", Terminals.shorten("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
        assertEquals("powershell", Terminals.shorten("  C:/Windows/System32/WindowsPowerShell/v1.0/powershell.EXE "));
        assertEquals("Shell", Terminals.shorten("   "));
        assertEquals("vim notes.md", Terminals.shorten("vim notes.md"));
    }
}
