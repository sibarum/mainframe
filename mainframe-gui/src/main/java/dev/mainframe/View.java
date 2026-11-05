package dev.mainframe;

import dev.mainframe.Intent.Command;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * The view: what the frame decided, shown, and what the person did, told. A {@code @View}, so it runs on the GUI
 * thread, in each frame's widget tick (threading.md, T7.4). For now it has only the zoom chords; the terminals come
 * in M1 (docs/rebuild.md).
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision will be the {@code Frame}'s, in {@code -core}. This turns the frame's messages into widgets and the
 * person's gestures into messages.
 *
 * <h2>Gestures are declared, not handled</h2>
 *
 * <p>A chord, a menu item and a dialog's button each name the message they send ({@link Intent}) through a
 * {@link Sender}; the dispatcher sends it, and nothing of the application's runs at the moment of the click (T7.5).
 * What needs the view comes back to it as a {@code @Subscribe} here.
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, and nothing else to send with, so the processor sees every message
 * the view can send without reading a line of its body (ROADMAP fork 5).
 */
@dev.vexelray.framework.api.View
final class View {

    private final Gui gui;
    private final Ui ui;
    private final Sender<Intent> intents;

    View(Gui gui, Keymap keys, Ui ui, Sender<Intent> intents) {
        this.gui = gui;
        this.ui = ui;
        this.intents = intents;
        commands(keys);
    }

    /**
     * Every command the keyboard reaches, as a declaration: a stable id, what the person calls it, the chords it starts
     * with, and the {@link Intent} it sends. The chords are the person's to change: the framework's key button in the
     * title bar lists these. So a chord is never written anywhere else, and the id is the part to keep stable, since it
     * is what the person's changes are stored under.
     */
    private void commands(Keymap keys) {
        command(keys, "view.zoom-in", "Zoom in", Command.ZOOM_IN,
                Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL));
        command(keys, "view.zoom-out", "Zoom out", Command.ZOOM_OUT,
                Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL));
        command(keys, "view.zoom-reset", "Reset zoom", Command.ZOOM_RESET,
                Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL));
    }

    private void command(Keymap keys, String id, String label, Command command, Shortcut... chords) {
        keys.action(id, label, chords).sends(intents, new Intent(command));
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    /** In ({@code 1}), out ({@code -1}), or back to where it started ({@code 0}). */
    void zoom(int direction) {
        if (direction > 0) {
            gui.zoomIn();
        } else if (direction < 0) {
            gui.zoomOut();
        } else {
            gui.resetZoom();
        }
    }
}
