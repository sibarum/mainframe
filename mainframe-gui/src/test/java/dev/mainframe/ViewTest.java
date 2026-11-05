package dev.mainframe;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages a user's gestures send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, exactly as in the
 * window. The view runs in frames, and there is no loop to run them, so the test runs them itself
 * ({@code VexelApplication.frame}): this thread is the GUI thread.
 */
class ViewTest {

    private Shell shell;
    private MainFrameAppWiring wiring;

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
        cuts = Cuts.watch(shell.gui());
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = cuts.found();
        cuts.close();
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    @Test
    void thePageIsLandmarkedForScripts() {
        VexelApplication.frame(shell);
        assertEquals(java.util.Optional.of(wiring.ui().page().id()),
                shell.gui().landmarkNode(Landmarks.PAGE).map(n -> n.id()));
    }

    /**
     * The commands are on the framework's keymap, under their ids and with their default chords, which is what puts
     * the key button in the title bar and what the person's changes are stored against.
     */
    @Test
    void theZoomCommandsAreOnTheKeymapWithTheirDefaults() {
        Keymap keys = shell.keymap();
        Action in = keys.action("view.zoom-in").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL)),
                keys.chords(in));
    }
}
