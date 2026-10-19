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
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final AppSettings settings;
    private final WorkspaceStore store;
    private final Dialogs dialogs;
    private final Motion motion;
    /** Each tab's sheet, and the page on it: what a tab change moves. */
    private final Map<Node, Node> onSheet = new HashMap<>();
    /** The start workspace's drop-down: "" is none. Its options are the saved workspaces, re-read as they change. */
    private final Select<String> startWorkspace;

    SettingsWindow(Window window, AppSettings settings, WorkspaceStore store, Dialogs dialogs,
                   Motion motion) {
        this.window = window;
        this.gui = window.gui();
        this.settings = settings;
        this.store = store;
        this.dialogs = dialogs;
        this.motion = motion;
        tabs =new Tabs(gui).closable(false);
        workspaces = new WorkspacesPage(gui, store, dialogs, motion);
        startWorkspace = new Select<String>(gui, name -> name.isEmpty() ? "None" : name);
        startWorkspace.onCommit(chosen -> settings.startWorkspace(chosen.isEmpty() ? null : chosen.iterator().next()));
        gui.landmark(Landmarks.SETTINGS_START_WORKSPACE, startWorkspace.node());
        workspaces.onRename(this::workspaceRenamed);
        workspaces.onCreate(this::offerStart);
        refreshStartWorkspaces();
        tabs.add("General", sheet(general(settings)));
        tabs.add("Workspaces", sheet(workspaces.node()));
        gui.landmark(Landmarks.SETTINGS_GENERAL, tabs.header(0));
        gui.landmark(Landmarks.SETTINGS_WORKSPACES, tabs.header(1));
        tabs.select(0);
        applyMotion();
        settings.onChange(this::applyMotion);
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.CHROME))
                .children(window.titleBar().node(), tabs.node().width(Length.FILL).height(Length.grow(1)));
    }

    /** Opens the window, or raises it if it is open already. */
    void show() {
        refreshStartWorkspaces();   // a workspace may have come or gone on disk since
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

    /**
     * A page under its tab: the page with a rule along its top edge, under the whole row of tabs. The tabs are
     * meant to read as physical ones, tabs on the edge of a sheet, and without the edge the page and the bar are
     * one surface with the headers floating on it. The rule is the selected tab's own colour, so the tab in front
     * runs straight into the edge of its sheet. Part of the page rather than the bar, which the panel keeps to
     * itself; so a tab change moves what is on the sheet and not the sheet ({@link #applyMotion}), and the rule
     * never moves.
     */
    private Node sheet(Node page) {
        Node sheet = gui.column().width(Length.FILL).height(Length.FILL).children(
                gui.box().width(Length.FILL).height(Length.dp(1)).background(gui.theme().color(Role.LINE)),
                page.width(Length.FILL).height(Length.grow(1)));
        onSheet.put(sheet, page);
        return sheet;
    }

    /**
     * The tab change the Tab animation setting asks for, the main window's own, played on what is on the two sheets
     * rather than on the sheets. Handed the sheets, the rule along the top would slide in from the side and dim to
     * half through the dissolve, and the selected tab would stop running into its edge for the length of it. The
     * sheets hold still, both shown and solid with their rules in one place, while their pages move.
     *
     * <p>{@code Tabs} puts back what it gave the transition, the sheets, and nothing under them, so the pages are put
     * back here when the change is done.
     */
    private void applyMotion() {
        Tabs.TabTransition change = switch (settings.motion()) {
            case SLIDE -> Tabs.slide(motion.change);
            case FADE -> Tabs.crossfade(motion.change);
            case NONE -> Tabs.TabTransition.NONE;
        };
        tabs.transition((sheets, done) -> {
            Node out = onSheet.get(sheets.outgoing());
            Node in = onSheet.get(sheets.incoming());
            change.run(new Tabs.Change(out, in, sheets.incomingOnTop()), () -> {
                out.opacity(1f).translate(0f, 0f);
                in.opacity(1f).translate(0f, 0f);
                done.run();
            });
        });
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

        return gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.rem(1)).gap(Length.rem(0.5f))
                .children(
                        label("Font size"), font.node(),
                        label("Tab animation"), motion.node(),
                        label("Shell for new tabs"), shell.node(),
                        label("New tab directory"), tabDir(settings),
                        label("Workspace to open at start"), startWorkspace.node().width(Length.rem(16)),
                        note("Font size and animation apply at once; a new shell and directory apply to tabs opened "
                                + "from now on, and the workspace to the next MainFrame started without one named."));
    }

    /**
     * The folder plain tabs start in, with a button to choose another and one to go back to the profile, which is
     * where they start until something else is chosen.
     */
    private Node tabDir(AppSettings settings) {
        Node path = gui.text(settings.tabDir().toString()).width(Length.grow(1)).height(Length.rem(2))
                .font(Type.UI).textSize(Type.BODY).textColor(gui.theme().color(Role.INK))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        Button reset = new Button(gui, "Use profile");
        reset.enabled(!settings.tabDirIsDefault());
        Runnable shown = () -> {
            path.text(settings.tabDir().toString());
            reset.enabled(!settings.tabDirIsDefault());
        };
        Button choose = new Button(gui, "Change…").onPress(() -> dialogs.openFolder(settings.tabDir(), dir -> {
            settings.tabDir(dir);
            shown.run();
        }, () -> { }));
        reset.onPress(() -> {
            settings.tabDir(null);
            shown.run();
        });
        gui.landmark(Landmarks.SETTINGS_TAB_DIR, choose.node());
        return gui.row().width(Length.FILL).gap(Length.rem(0.5f)).alignItems(AlignItems.CENTER)
                .children(path, choose.node(), reset.node());
    }

    /** The drop-down's options: none, then every saved workspace; and what it shows, the saved choice. */
    private void refreshStartWorkspaces() {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(store.names());
        String chosen = settings.startWorkspace();
        startWorkspace.options(options).show(chosen != null && options.contains(chosen) ? chosen : "");
    }

    /**
     * A new workspace, while there is no start workspace, is offered as it: asked once, as New makes it. A start
     * workspace that names no saved one counts as none, as the drop-down shows it. Either answer gives the name field
     * back the keyboard, where New left it.
     */
    private void offerStart(String name) {
        String start = settings.startWorkspace();
        if (start != null && store.exists(start)) return;
        dialogs.ask(Modal.of("Open this workspace at start?",
                        "No workspace opens when MainFrame starts. This one can, whenever MainFrame is launched "
                                + "without folders or a workspace named. The General tab can change it later.")
                .defaultButton("Open at start", () -> {
                    settings.startWorkspace(name);
                    refreshStartWorkspaces();
                    workspaces.editName();
                })
                .cancelButton("Not now", workspaces::editName));
    }

    /** The start workspace follows its own renaming, and goes back to none when it is deleted. */
    private void workspaceRenamed(String from, String to) {
        if (from != null && from.equals(settings.startWorkspace())) {
            settings.startWorkspace(to);
        }
        refreshStartWorkspaces();
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
    static Node button(Gui gui, Runnable open) {
        Node b = gui.row().role("settings").width(Length.dp(36)).height(Length.dp(32))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE)).scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE)
                .children(IconView.of(gui, Icon.SETTINGS).size(Length.dp(16)).tint(Role.INK).node());
        gui.onClick(b, open);
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        new Tooltip(gui).attach(b, "Settings");
        gui.landmark(Landmarks.SETTINGS, b);
        return b;
    }
}
