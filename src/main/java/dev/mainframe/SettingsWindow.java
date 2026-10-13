package dev.mainframe;

import dev.mainframe.AppSettings.ShellOption;
import dev.mainframe.AppSettings.TabMotion;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.Toggle;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.text.TextLayout;

/**
 * The settings window: a window of its own, opened by the settings button in the main window's title bar, with a
 * tab per group of settings.
 *
 * <p>Every control applies the moment it is touched and is saved by {@link AppSettings}, so there is nothing to
 * confirm. A named {@link Window}, so asking for it twice raises the one already open, and its tree outlives the
 * window: closing and reopening shows it as it was left. It is a satellite of the main window, above it and minimized
 * with it, because it belongs to it.
 *
 * <p><b>The window and its tree are the framework's.</b> This builds into the {@code Gui} it is handed and places the
 * title bar it is handed; its bus, the look, the mark, the placement and zoom remembered under {@code settings}, and
 * the controls in its bar are all the framework's.
 */
final class SettingsWindow {

    /** The window, as {@link Recipes} declares it: its name is where its placement is remembered. */
    static final NamedWindow DECLARED = NamedWindow.of("settings", "MainFrame Settings", 720, 540).satellite();

    private final Window window;
    private final Gui gui;
    private final Tabs tabs;
    private final WorkspacesPage workspaces;

    SettingsWindow(Window window, AppSettings settings, WorkspaceStore store, Icons icons, Dialogs dialogs) {
        this.window = window;
        this.gui = window.gui();
        tabs = new Tabs(gui).closable(false);
        workspaces = new WorkspacesPage(gui, store, icons, dialogs);
        tabs.add("General", general(settings));
        tabs.add("Workspaces", workspaces.node());
        gui.landmark(Landmarks.SETTINGS_GENERAL, tabs.header(0));
        gui.landmark(Landmarks.SETTINGS_WORKSPACES, tabs.header(1));
        tabs.select(0);
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.CHROME))
                .children(window.titleBar().node(), tabs.node().width(Length.FILL).height(Length.grow(1)));
    }

    /** Opens the window, or raises it if it is open already. */
    void show() {
        window.show();
    }

    /** Opens the window on the Workspaces tab, with the workspace called {@code name} selected. */
    void showWorkspace(String name) {
        tabs.select(1);
        workspaces.select(name);
        show();
    }

    /** Opens the window on the Workspaces tab with a new workspace, its name ready to type over. */
    void newWorkspace() {
        tabs.select(1);
        show();
        workspaces.create();
    }

    // -- the General tab --------------------------------------------------------------------------------------

    private Node general(AppSettings settings) {
        Segment<Float> font = new Segment<>(gui);
        for (float px : AppSettings.FONT_SIZES) {
            font.option(String.valueOf((int) px), px);
        }
        font.show(nearest(settings.fontPx())).onChange(settings::fontPx);

        Segment<TabMotion> motion = new Segment<TabMotion>(gui)
                .option("Slide", TabMotion.SLIDE).option("Fade", TabMotion.FADE).option("None", TabMotion.NONE);
        motion.show(settings.motion()).onChange(settings::motion);

        Segment<ShellOption> shell = new Segment<>(gui);
        for (ShellOption s : settings.shells()) {
            shell.option(s.label(), s);
        }
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
                        note("On: Ctrl+C copies, Ctrl+V pastes, and Esc sends Ctrl+C (interrupt). Ctrl+T opens a tab, "
                                + "Ctrl+W closes one after asking, and Ctrl+O opens a workspace or a folder; the shell "
                                + "gives up those keys (Ctrl+W's delete word is also Ctrl+Backspace). "
                                + "Off: every key goes to the shell as is. Ctrl+Shift+C and Ctrl+Shift+V always copy and "
                                + "paste, and Ctrl+Shift+T and Ctrl+Shift+W always open and close a tab."),
                        note("Font size and animation apply at once; a new shell applies to tabs opened from now on."));
    }

    private static float nearest(float px) {
        float best = AppSettings.FONT_SIZES[0];
        for (float f : AppSettings.FONT_SIZES) {
            if (Math.abs(f - px) < Math.abs(best - px)) {
                best = f;
            }
        }
        return best;
    }

    private Node label(String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(1.5f)).font(Type.UI).textSize(Type.BODY)
                .textColor(gui.theme().color(Role.DIM)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.BOTTOM);
    }

    private Node note(String text) {
        return gui.text(text).width(Length.FILL).font(Type.UI).textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }

    // -- the button -------------------------------------------------------------------------------------------

    /**
     * The button that opens this window, for the main window's title bar: the suite's settings icon, sized and shaded
     * like the bar's own buttons. It sits on the caption, so it declares itself interactive or the click would start
     * a drag.
     */
    static Node button(Gui gui, Icons icons, Runnable open) {
        Node b = gui.row().role("settings").width(Length.dp(36)).height(Length.dp(32))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE)).scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE)
                .children(icons.icon(gui, "settings", 16, gui.theme().color(Role.INK)));
        gui.onClick(b, open);
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        new Tooltip(gui).attach(b, "Settings");
        gui.landmark(Landmarks.SETTINGS, b);
        return b;
    }
}
