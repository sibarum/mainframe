package ${packageName};

import dev.vexelray.framework.automation.Driver;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * What this application builds, and in which phase.
 *
 * <h2>What is not here is the point</h2>
 *
 * <p>Opening an input backend and settling its coordinate space, installing a clipboard, remembering where the
 * window was, attaching a clock, wiring the frame loop with its wakes and its pacing, parsing the command line,
 * installing the dialogs, and closing everything in the right order — every one of those used to be written out
 * in {@link ${className}}, and all of them are {@code vexelray-framework}'s now. What is left below is only
 * what is actually about <em>this</em> application.
 *
 * <p>Each method is one {@code Phase}, called in order, and a phase is a <b>correctness</b> constraint rather
 * than a scheduling detail: the look has to be settled before the first widget resolves a role, the clock has
 * to exist before a widget that animates is constructed, and the window handle does not exist until
 * {@code WINDOW}. {@link Shell}'s accessors refuse to hand over what does not exist yet, so putting something
 * in the wrong phase is a message naming the phase rather than a null three frames later.
 *
 * <p>A component's phase is decided by <b>what it needs</b>, which is worth knowing before adding one: the
 * latest phase of anything it depends on. Something taking only the model can go early; something taking
 * {@code shell.app()} cannot exist before {@code WINDOW}. Watch out for the case that is easy to read past — an
 * announcement depends on its <em>listeners</em>, so seeding state belongs after the things it wakes up rather
 * than beside the model it edits.
 */
final class ${className}Wiring extends Wiring {

    /**
     * The facts the shell cannot infer, as a constant.
     *
     * <p>{@code APP} is the settings directory, so it is the one value here that, changed after a release,
     * loses a user's window placement. The other three are first-run defaults that window memory overwrites the
     * moment there is anything remembered.
     */
    private static final AppInfo INFO =
            new AppInfo(${className}.APP, ${className}.TITLE, ${className}.W, ${className}.H);

    private Model model;
    private Ui ui;

    @Override
    public AppInfo info() {
        return INFO;
    }

    /**
     * The look, and the smallest window this UI is still coherent in — a floor, not the design size.
     *
     * <p>Both are values, so they are settled before there is a {@code Gui} to apply them to. The framework
     * applies them at the moment it creates one, which is what makes "the theme is applied before the first
     * widget" structural rather than a comment somebody has to keep obeying.
     */
    @Override
    public void config(Shell shell) {
        shell.appearance(Appearance.of(Look.THEME,
                Length.em(${className}.MIN_W_EM), Length.em(${className}.MIN_H_EM)));
    }

    /** The one authoritative state, built before the tree because every control is a view onto it. */
    @Override
    public void model(Shell shell) {
        model = new Model();
    }

    /**
     * The tree. Needs the {@code Gui}, which exists by now, and no window.
     *
     * <p>That last part is a claim worth keeping true as this grows: a tree that can be built without a window
     * is a tree {@link Capture} can photograph headlessly. It is also why the initial {@code show} is here
     * rather than later — the tree a capture gets should be the tree a user gets, already carrying the
     * document rather than whatever a node was constructed with.
     *
     * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the
     * screenshot instrument in it means the same thing in every window on the desk; this application places
     * the node and supplies every colour in it.
     */
    @Override
    public void tree(Shell shell) {
        ui = new Ui(shell.gui(), model, shell.titleBar());
        // Every change to the state redraws what is derived from it, on the committing thread -- which is a
        // worker, because every control's handler is. The GUI thread never reads the model.
        model.onChange(ui::show);
        ui.show(model.doc());
        zoomShortcuts(shell.gui());
        keys(shell.gui(), model);
    }

    /**
     * Everything that needed the window to exist.
     *
     * <p>Nothing does yet, apart from the driving socket. As this grows, this is the phase for anything reading
     * {@code shell.app()} — a render target, a storage buffer — and for {@code shell.onClose} if the
     * application ever has unsaved state worth asking about.
     */
    @Override
    public void attach(Shell shell) {
        // Off unless -Dautomation or --automation asks for it, and loopback-only when it is: this hands anyone
        // who can reach it full control of the application's input, so it is a debugging instrument and not a
        // service. Was thirty lines at the edge of every application on this stack.
        shell.disposer().register(Driver.open(shell));
    }

    /**
     * Every single-key control, as a {@code GLOBAL} claim.
     *
     * <p>Claims rather than handlers, which is how this framework does preemption: a text field outranks these
     * by claiming the same key at {@code FOCUSED} scope, so typing {@code r} into a field types an {@code r}
     * rather than firing the shortcut. Nothing here has to know the field exists, and the field needs no list
     * of keys to avoid.
     */
    private static void keys(Gui gui, Model model) {
        gui.shortcut(Key.R, model::reset);
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three.
     *
     * <p>An application decision, which is why it is here: which chord zooms, or whether zooming exists at all,
     * is not something a framework should choose. <b>How far the zoom goes is</b>, and it is not stated here —
     * that is {@code Appearance.ZoomRange}, applied by the framework before the first widget, so every window
     * on the desk agrees about it.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }
}
