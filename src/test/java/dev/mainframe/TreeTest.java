package dev.mainframe;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** The settings and key map the wiring opens, kept out of the user's own ({@code AppHome}'s override). */
    @TempDir
    Path home;

    @BeforeEach
    void build() {
        System.setProperty(HOME, home.toString());
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
        System.clearProperty(HOME);
    }

    private static final String HOME = MainFrame.APP + ".home";

    @Test
    void theTreeIsBuiltWithNoShellRunning() {
        assertNotNull(wiring.ui());
        assertNotNull(wiring.terminals());
        assertNull(wiring.terminals().current(), "no tab is opened before the start recipe");
        assertNull(wiring.start(), "the start recipe takes the Shell, so a tree stops before it");
    }

    @Test
    void everyCommandIsOnTheKeyMapWithItsDefaults() {
        Keymap keys = wiring.keys();
        assertEquals(List.of(ctrlShift(Key.T), ctrl(Key.T)), keys.action("tab.new").orElseThrow().defaults());
        assertEquals(List.of(ctrl(Key.O)), keys.action("file.open").orElseThrow().defaults());
        assertEquals(List.of(ctrlShift(Key.V), ctrl(Key.V)), keys.action("term.paste").orElseThrow().defaults());
        assertEquals(List.of(Shortcut.of(Key.ESCAPE)), keys.action("term.interrupt").orElseThrow().defaults());
        for (Action a : keys.actions()) {
            assertTrue(a.label() != null && !a.label().equals(a.id()), a.id() + " has a label of its own");
        }
    }

    /** What turning the old Remap keybindings switch off did, done once to the key map for whoever had it off. */
    @Test
    void handingTheShellItsKeysBackUnbindsExactlyThose() {
        Keymap keys = new Keymap(shell.gui());   // not persisted: nothing here reaches the user's own key map
        Action tab = keys.action("tab.new", "New tab", ctrlShift(Key.T), ctrl(Key.T)).on(() -> { });
        Action interrupt = keys.action("term.interrupt", "Interrupt", Shortcut.of(Key.ESCAPE)).on(() -> { });
        Action zoom = keys.action("ui.zoom-in", "Larger", ctrl(Key.EQUAL)).on(() -> { });

        Terminals.handShellKeysBack(keys);

        assertEquals(List.of(ctrlShift(Key.T)), tab.chords());
        assertEquals(List.of(), interrupt.chords());
        assertEquals(List.of(ctrl(Key.EQUAL)), zoom.chords());
        assertFalse(zoom.customized(), "an action with none of the shell's keys is left on its defaults");
    }

    @Test
    void whoeverHadRemapKeybindingsOffKeepsTheirKeysOnce() throws Exception {
        shell.disposer().close();
        Files.writeString(home.resolve("settings.properties"), "keys.remap=false\n");
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);

        Keymap keys = wiring.keys();
        assertEquals(List.of(ctrlShift(Key.T)), keys.action("tab.new").orElseThrow().chords());
        assertEquals(List.of(), keys.action("term.interrupt").orElseThrow().chords());
        assertFalse(Files.readString(home.resolve("settings.properties")).contains("keys.remap"),
                "asked once: the old switch is gone from the settings");
        List<String> stored = Settings.at(home.resolve("keys.properties")).getList(Keymap.SETTINGS_KEY);
        assertTrue(stored.stream().anyMatch(line -> line.startsWith("term.interrupt")),
                "and what it meant is in the key map's own store: " + stored);
    }

    private static Shortcut ctrl(Key k) {
        return Shortcut.of(k, Modifier.CONTROL);
    }

    private static Shortcut ctrlShift(Key k) {
        return Shortcut.of(k, Modifier.CONTROL, Modifier.SHIFT);
    }

    @Test
    void aShellNamesItselfByItsExecutable() {
        assertEquals("pwsh", Terminals.shorten("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
        assertEquals("powershell", Terminals.shorten("  C:/Windows/System32/WindowsPowerShell/v1.0/powershell.EXE "));
        assertEquals("Shell", Terminals.shorten("   "));
        assertEquals("vim notes.md", Terminals.shorten("vim notes.md"));
    }
}
