package dev.mainframe;

import dev.mainframe.AppSettings.Motion;
import dev.mainframe.AppSettings.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Standing;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.nfd.SaveScreenshot;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Toggle;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.Icon;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;

import java.util.List;

/**
 * The settings window: a window of its own, opened by the settings button in the main window's title bar, with a
 * tab per group of settings.
 *
 * <p>Every control applies the moment it is touched and is saved by {@link AppSettings}, so there is nothing to
 * confirm. The window's tree is built once and outlives the window, so closing and reopening it shows it as it was
 * left. It stands above the main window, as a tool window of it.
 */
final class SettingsWindow {

    private static final int WIDTH = 720;
    private static final int HEIGHT = 540;

    private final Gui gui;
    private final TitleBar bar;
    private final WorkspacesPage workspaces;
    private volatile AppWindow window;

    /** Builds the tree; there is no window until {@link #register} is given the application. */
    SettingsWindow(Gui main, AppSettings settings, WorkspaceStore store) {
        this.gui = new Gui();
        gui.theme(main.theme());
        this.bar = new TitleBar(gui, WindowControls.NONE, "MainFrame Settings");
        Tabs tabs = new Tabs(gui).closable(false);
        workspaces = new WorkspacesPage(gui, store);
        tabs.add("General", general(settings));
        tabs.add("Workspaces", workspaces.node());
        gui.landmark("settings.general", tabs.header(0)).landmark("settings.workspaces", tabs.header(1));
        tabs.select(0);
        gui.root().children(gui.column().width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Role.CHROME))
                .children(bar.node(), tabs.node().height(Length.grow(1))));
    }

    /** Names the window to the application, once it exists, and gives its title bar the main window's mark. */
    void register(GuiApp app, Icon mark) {
        bar.icon(app, mark).instruments(List.of(SaveScreenshot.instrument()));
        window = app.window("settings", () -> bar.commands(WindowSpec.of(
                        WindowConfig.of("MainFrame Settings", WIDTH, HEIGHT).decorations(Decorations.CLIENT).icon(mark),
                        gui))
                .standing(Standing.SATELLITE));
        workspaces.attach(app, () -> window);
    }

    /** Opens the window, or raises it if it is open already. Nothing before {@link #register}. */
    void show() {
        AppWindow w = window;
        if (w != null) w.show();
    }

    // -- the General tab --------------------------------------------------------------------------------------

    private Node general(AppSettings settings) {
        Segment<Float> font = new Segment<>(gui);
        for (float px : AppSettings.FONT_SIZES) font.option(String.valueOf((int) px), px);
        font.show(nearest(settings.fontPx())).onChange(settings::fontPx);

        Segment<Motion> motion = new Segment<Motion>(gui)
                .option("Slide", Motion.SLIDE).option("Fade", Motion.FADE).option("None", Motion.NONE);
        motion.show(settings.motion()).onChange(settings::motion);

        Segment<Shell> shell = new Segment<>(gui);
        for (Shell s : settings.shells()) shell.option(s.label(), s);
        shell.show(settings.shell()).onChange(settings::shell);

        Toggle remap = new Toggle(gui, settings.remapKeys());
        remap.onChange(settings::remapKeys);

        return gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.rem(1)).gap(Length.rem(0.5f))
                .children(
                        label("Font size"), font.node(),
                        label("Tab animation"), motion.node(),
                        label("Shell for new tabs"), shell.node(),
                        label("Remap keybindings"), remap.node(),
                        note("On: Ctrl+C copies, Ctrl+V pastes, and Esc sends Ctrl+C (interrupt). "
                                + "Off: every key goes to the shell as is. Ctrl+Shift+C and Ctrl+Shift+V always copy and paste."),
                        note("Font size and animation apply at once; a new shell applies to tabs opened from now on."));
    }

    private static float nearest(float px) {
        float best = AppSettings.FONT_SIZES[0];
        for (float f : AppSettings.FONT_SIZES) {
            if (Math.abs(f - px) < Math.abs(best - px)) best = f;
        }
        return best;
    }

    private Node label(String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(1.5f)).textSize(Length.rem(0.875f))
                .textColor(gui.theme().color(Role.DIM)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.BOTTOM);
    }

    private Node note(String text) {
        return gui.text(text).width(Length.FILL).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }

    // -- the button -------------------------------------------------------------------------------------------

    /**
     * The button that opens this window, for the main window's title bar: the suite's settings icon, sized and shaded
     * like the bar's own buttons. It sits on the caption, so it declares itself interactive or the click would start
     * a drag.
     */
    static Node settingsButton(Gui gui, Icons icons, Runnable open) {
        Node b = gui.row().role("settings").width(Length.dp(36)).height(Length.dp(32))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE)).scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE)
                .children(icons.icon(gui, "settings", 16, gui.theme().color(Role.INK)));
        gui.onClick(b, open);
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        new Tooltip(gui).attach(b, "Settings");
        gui.landmark("settings", b);   // so ottermate can say "click settings"
        return b;
    }
}
