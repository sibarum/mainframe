package dev.mainframe;

import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.ShellChoice;
import dev.mainframe.core.TabMotion;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.gui.core.text.Ellipsis;

import java.util.List;
import java.util.Objects;

/**
 * The settings window's page: General, the four settings of docs/settings.md, each changed as it is touched.
 *
 * <h2>It holds no state of its own</h2>
 *
 * <p>Every control shows what the frame last said ({@link #show}, with each widget's {@code show}, which is not the
 * person's change and so says nothing), and the person's touches reach {@link SettingsView} as the widgets' own
 * messages, which it turns into {@code ChangePrefs}. Nothing is rebuilt, so nothing changes in one frame: the note
 * about a launch's command line, the home button and a failed save are kept and shown or hidden.
 *
 * <p>Built in the {@code TREE} phase into a window declared by name ({@code Recipes.settings}), as the template's
 * Examples window is. M4.3 puts this page in a {@code Tabs} beside Workspaces.
 */
final class SettingsPage {

    /** The window's name: in the settings file, where its place is remembered, in a {@code WindowCommand}, and to
     * {@code ottermate} ({@code settings/...}). */
    static final String KEY = "settings";

    static final int W = 560;
    static final int H = 420;

    /** What a script may refer to in this window, as {@code settings/<landmark>}. */
    static final String SIZE = "settings.size";
    static final String MOTION = "settings.motion";
    static final String SHELL = "settings.shell";
    static final String DIR = "settings.dir";
    static final String CHOOSE = "settings.choose";
    static final String HOME = "settings.home";
    static final String PROBLEM = "settings.problem";
    static final String START = "settings.start";

    private final Gui gui;
    private final Segment<Integer> size;
    private final Segment<TabMotion> motion;
    private final Select<ShellChoice> shell;
    private final Select<String> start;
    private final Node override;
    private final Node dir;
    private final Tooltip dirTip;
    private final Button choose;
    private final Button home;
    private final Node problem;

    /** What was last shown: what a control's change is made from, and the shells a choice can name. */
    private Prefs shown = new Prefs(Preferences.DEFAULT, List.of(), "", List.of());

    SettingsPage(Window window) {
        this.gui = window.gui();
        size = new Segment<Integer>(gui).name(SIZE);
        for (int px : Preferences.SIZES) {
            size.option(Integer.toString(px), px);
        }
        size.show(Preferences.DEFAULT.fontPx());
        gui.landmark(SIZE, size.node());

        motion = new Segment<TabMotion>(gui).name(MOTION)
                .option("Slide", TabMotion.SLIDE).option("Fade", TabMotion.FADE).option("None", TabMotion.NONE);
        motion.show(TabMotion.SLIDE);
        gui.landmark(MOTION, motion.node());

        shell = new Select<ShellChoice>(gui, ShellChoice::label).name(SHELL);
        shell.node().width(Length.rem(18f));
        gui.landmark(SHELL, shell.node());
        override = small("").visible(false);

        // "" stands for None: no workspace at launch, one plain tab.
        start = new Select<String>(gui, w -> w.isEmpty() ? "None" : w).name(START);
        start.node().width(Length.rem(18f));
        start.options(List.of(""));
        start.show("");
        gui.landmark(START, start.node());

        // A path can be longer than the row: cut in the middle, keeping the drive and the folder, and whole in a tooltip.
        dir = gui.text("").textColor(gui.theme().color(Role.INK)).wordWrap(false).width(Length.FILL)
                .ellipsis(Ellipsis.MIDDLE);
        dirTip = new Tooltip(gui);
        gui.landmark(DIR, dir);
        choose = new Button(gui, "Choose…").name(CHOOSE);
        gui.landmark(CHOOSE, choose.node());
        home = new Button(gui, "Use my home folder").name(HOME);
        home.node().visible(false);
        gui.landmark(HOME, home.node());

        problem = gui.text("").textColor(gui.theme().color(Role.DANGER)).width(Length.FILL).visible(false);
        gui.landmark(PROBLEM, problem);

        Node body = gui.column().gap(Length.rem(1.1f)).padding(Length.rem(1.5f)).width(Length.FILL)
                .height(Length.grow(1f))
                .children(
                        row("Text size", size.node()),
                        row("Tab motion", motion.node()),
                        row("New tabs run", gui.column().gap(Length.dp(4)).children(shell.node(), override)),
                        row("Open at launch", start.node()),
                        row("New tabs start in", gui.column().gap(Length.dp(6)).width(Length.FILL).children(dir,
                                gui.row().gap(Length.dp(8)).children(choose.node(), home.node()))),
                        problem);
        window.titleBar().wordmark(Wordmark.bar(gui));
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), body);
    }

    private Node row(String label, Node control) {
        return gui.row().gap(Length.rem(1f)).alignItems(AlignItems.START).width(Length.FILL).children(
                gui.text(label).textColor(gui.theme().color(Role.DIM)).width(Length.rem(9f)),
                gui.column().width(Length.grow(1f)).alignItems(AlignItems.START).children(control));
    }

    private Node small(String text) {
        return gui.text(text).textSize(Length.rem(0.8125f)).textColor(gui.theme().color(Role.DIM));
    }

    /** Show what the frame says is set, and what could be. */
    void show(Prefs p) {
        Preferences now = p.prefs();
        size.show(now.fontPx());
        motion.show(now.motion());
        if (!p.shells().equals(shown.shells())) {
            shell.options(p.shells());
        }
        p.shells().stream().filter(c -> c.id().equals(now.shell())).findFirst()
                .or(() -> p.shells().stream().findFirst()).ifPresent(shell::show);
        override.text(p.override().isBlank() ? "" : "This run was started with " + p.override()
                + ", which new tabs run instead.").visible(!p.override().isBlank());
        String where = now.dir().map(Object::toString).orElse("Your home folder");
        dir.text(where);
        if (!p.workspaces().equals(shown.workspaces())) {
            List<String> choices = new java.util.ArrayList<>(List.of(""));
            choices.addAll(p.workspaces());
            start.options(choices);
        }
        start.show(p.workspaces().contains(now.startWorkspace()) ? now.startWorkspace() : "");
        dirTip.attach(dir, where);
        home.node().visible(now.dir().isPresent());
        shown = p;
    }

    /** This window's tree: for a test. */
    Gui gui() {
        return gui;
    }

    /** Show why the last save failed, or nothing when it was saved. */
    void problem(String why) {
        problem.text(why).visible(!why.isBlank());
    }

    /** What is set now, as last shown: what the view makes a change from. */
    Preferences prefs() {
        return shown.prefs();
    }

    /** The shell the choice at {@code chosen} names, if it is one of those on offer. */
    java.util.Optional<ShellChoice> shell(Object chosen) {
        return shown.shells().stream().filter(c -> Objects.equals(c, chosen)).findFirst();
    }

    String sizeAddress() {
        return size.address();
    }

    String motionAddress() {
        return motion.address();
    }

    String shellAddress() {
        return shell.address();
    }

    String startAddress() {
        return start.address();
    }

    String chooseAddress() {
        return choose.address();
    }

    String homeAddress() {
        return home.address();
    }
}
