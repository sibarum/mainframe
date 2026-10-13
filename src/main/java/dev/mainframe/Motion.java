package dev.mainframe;

import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

/**
 * The application's one tempo, on the framework's clock.
 *
 * <h2>Motion is opt-in, so it is easy to lose</h2>
 *
 * <p>A widget animates only when it is handed a {@link Ramp} — {@code Tabs.slide}, {@code TreeView.motion},
 * {@code Cues} — and without one it cuts. So leaving the clock out costs no error and no warning, only a stiffer
 * window. That is deliberate in the widget shelf (vexelray-gui-widget knows nothing about time, and reduced motion
 * is simply not handing one over), and it is why the ramps are made here, in one place, from one clock.
 *
 * <p>Two durations, and they differ on purpose. A change — a tab arriving, a folder opening — should be over before
 * Ctrl+Tab held down could want the next one. A cue has to be <em>noticed</em>, and once its attack and release
 * are taken out a cue as short as a change has too little visible motion left.
 *
 * <p>{@code settle} on the automation socket waits these out, so a script's photograph is of the window at rest.
 */
final class Motion {

    /** What a change is worth. */
    static final Dur CHANGE = Dur.ms(160);

    /** What being seen costs. */
    static final Dur CUE = Dur.ms(240);

    /** A change, linear: {@code Tabs.slide} eases its own travel, and a dissolve has no place to arrive at. */
    final Ramp change;

    /** A change that arrives somewhere — rows opening under a folder — eased into place. */
    final Ramp arrival;

    /** One-shot pictures over a node: this was already open, this did not work. */
    final Cues cues;

    Motion(KronoGui krono) {
        // Ramp is written out at the use site in an application with a Ramp of its own (a colour map, say): a
        // single-type import silently shadows a type of the same name in this package. See Ramp's own note.
        this.change = (progress, done) -> krono.ramp(CHANGE, Ease.LINEAR, progress, done);
        this.arrival = (progress, done) -> krono.ramp(CHANGE, Ease.OUT_CUBIC, progress, done);
        this.cues = new Cues((progress, done) -> krono.ramp(CUE, Ease.LINEAR, progress, done));
    }
}
