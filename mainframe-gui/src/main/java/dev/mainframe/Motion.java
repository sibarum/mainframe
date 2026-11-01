package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.widget.Cues;

/**
 * The application's one tempo, on the framework's clock.
 *
 * <h2>What the window does already, and what is handed over here</h2>
 *
 * <p>Every window the framework builds already fades its colours, slides a node that layout moved, and fades a node
 * up as it is added and down before it is removed (the framework's {@code WindowMotion}). Nothing here has to ask for
 * that. What it asks of this code is to keep the nodes and change them: a list rebuilt from new nodes is every row
 * leaving and every row arriving, so use {@code Node.keyed} for anything drawn from a list.
 *
 * <p>A widget's own motion is different: it animates only when it is handed a {@link Timing} ({@code Tabs.slide},
 * {@code TreeView.motion}, {@code Cues}), and without one it cuts. A timing is a tempo, stated as data; the window's
 * clock runs it, so the tempi are said here, in one place.
 *
 * <p>Two durations, and they differ on purpose. A change — a tab arriving, a folder opening — should be over before
 * Ctrl+Tab held down could want the next one. A cue has to be <em>noticed</em>, and once its attack and release
 * are taken out a cue as short as a change has too little visible motion left.
 *
 * <p>{@code settle} on the automation socket waits these out, so a script's photograph is of the window at rest.
 */
final class Motion {

    /** What a change is worth, in milliseconds. */
    static final long CHANGE = 160;

    /** What being seen costs, in milliseconds. */
    static final long CUE = 240;

    /** A change, linear: {@code Tabs.slide} eases its own travel, and a dissolve has no place to arrive at. */
    final Timing change = Timing.millis(CHANGE, Timing.Curve.LINEAR);

    /** A change that arrives somewhere — rows opening under a folder — eased into place. */
    final Timing arrival = Timing.millis(CHANGE, Timing.Curve.OUT_CUBIC);

    /** One-shot pictures over a node: this was already open, this did not work. */
    final Cues cues;

    Motion(Gui gui) {
        this.cues = new Cues(gui, Timing.millis(CUE, Timing.Curve.LINEAR));
    }
}
