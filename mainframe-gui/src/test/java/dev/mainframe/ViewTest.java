package dev.mainframe;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.Header;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.Retitled;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages the frame and the screens send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, exactly as in the
 * window. No window opens, so the framework never says {@code WindowOpened} for the main one, and the frame starts no
 * tab and no shell: the test plays the frame's part by publishing what it would. The view runs in frames, and there is
 * no loop to run them, so the test runs them itself ({@code VexelApplication.frame}): this thread is the GUI thread.
 */
class ViewTest {

    private Shell shell;
    private MainFrameAppWiring wiring;
    private final List<Object> heard = new CopyOnWriteArrayList<>();

    /**
     * What changed on screen in one frame while a test ran, which every test here then fails on: nothing this
     * application shows changes in one frame. The framework fades it all (a colour, an opacity, words, an image, a node
     * arriving, leaving, hidden or shown) as long as the view keeps its nodes and changes them. A cut found here means
     * a node was rebuilt where it should have been changed, or something stepped round the framework. Fix that; do not
     * take this watch out. If the framework cannot do it, that is a framework note (docs/framework-notes.md).
     */
    private Cuts cuts;

    @BeforeEach
    void build() {
        wiring = new MainFrameAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        shell.bus().subscribe(Resized.TOPIC, heard::add);
        shell.bus().subscribe(Typed.TOPIC, heard::add);
        shell.bus().subscribe(Retitled.TOPIC, heard::add);
        shell.bus().subscribe(Shown.TOPIC, heard::add);
        cuts = Cuts.watch(shell.gui());
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = cuts.found();
        cuts.close();
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    /** Run frames until {@code test} holds: the view hears only in a frame, and this thread runs them. */
    private void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        VexelApplication.frame(shell);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
            VexelApplication.frame(shell);
        }
    }

    private TerminalScreen tab(long id) throws InterruptedException {
        shell.bus().publish(AddTab.TOPIC, new AddTab(id, "work"));
        shell.bus().publish(Front.TOPIC, new Front(id));
        eventually("the tab's screen", () -> wiring.view().screen(id) != null);
        return wiring.view().screen(id);
    }

    @Test
    void aTabsScreenSaysItsSizeWithTheTabsId() throws Exception {
        tab(7);
        eventually("the size, as the tab's", () -> heard.stream().anyMatch(m -> m instanceof Resized r && r.id() == 7
                && r.cols() > 0 && r.rows() > 0));
        assertEquals(1, wiring.ui().tabs().count());
    }

    @Test
    void whatTheProgramWritesIsShownInItsTab() throws Exception {
        TerminalScreen screen = tab(3);
        shell.bus().publish(Output.TOPIC, new Output(3, "hello from the shell"));
        eventually("the text on the screen", () -> screen.allText().contains("hello from the shell"));
    }

    @Test
    void whatIsFedIsSaidToBeShownSoTheShellSendsMoreEvenForATabGone() throws Exception {
        TerminalScreen screen = tab(8);
        shell.bus().publish(Output.TOPIC, new Output(8, "drawn"));
        eventually("shown, as the tab's", () -> heard.contains(new Shown(8)));
        assertTrue(screen.allText().contains("drawn"), "fed before it is said to be shown");
        shell.bus().publish(Output.TOPIC, new Output(99, "for no tab"));
        eventually("shown, for a tab with no screen", () -> heard.contains(new Shown(99)));
    }

    @Test
    void whatTheScreenSendsGoesToTheTabsShell() throws Exception {
        TerminalScreen screen = tab(4);
        shell.bus().publish(TerminalScreen.SENT, new TerminalScreen.Sent(screen.address(), "dir\r", true));
        eventually("the keys, as the tab's", () -> heard.contains(new Typed(4, "dir\r")));
    }

    @Test
    void escInterruptsTheTabInFront() throws Exception {
        tab(5);
        shell.bus().publish(Intent.INTENTS, new Intent(Intent.Command.INTERRUPT));
        eventually("0x03, as the tab's", () -> heard.contains(new Typed(5, "\u0003")));
    }

    @Test
    void theProgramsTitleGoesToTheFrameAsTheTabs() throws Exception {
        TerminalScreen screen = tab(6);
        shell.bus().publish(TerminalScreen.TITLED, new TerminalScreen.Titled(screen.address(), "pwsh"));
        eventually("the title, as the tab's", () -> heard.contains(new Retitled(6, "pwsh")));
    }

    /** A new header is the same node with new words, which the framework fades: the dispose's cut watch says so. */
    @Test
    void aHeaderIsChangedInPlace() throws Exception {
        tab(2);
        shell.bus().publish(Header.TOPIC, new Header(2, "pwsh"));
        for (int i = 0; i < 5; i++) {
            VexelApplication.frame(shell);
        }
        assertEquals(1, wiring.ui().tabs().count());
    }

    @Test
    void aClosedTabLeavesTheBar() throws Exception {
        tab(1);
        tab(2);
        shell.bus().publish(CloseTab.TOPIC, new CloseTab(1));
        eventually("one tab left", () -> wiring.view().screen(1) == null && wiring.ui().tabs().count() == 1);
        assertTrue(wiring.view().screen(2) != null);
    }

    /**
     * The commands are on the framework's keymap, under their ids and with their default chords, which is what puts
     * the key button in the title bar and what the person's changes are stored against.
     */
    @Test
    void theCommandsAreOnTheKeymapWithTheirDefaults() {
        Keymap keys = shell.keymap();
        Action copy = keys.action("terminal.copy").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.C, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.C, Modifier.CONTROL)),
                keys.chords(copy));
        Action interrupt = keys.action("terminal.interrupt").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.ESCAPE)), keys.chords(interrupt));
    }
}
