package dev.mainframe;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 */
final class Landmarks {

    /** The tab bar and its pages. */
    static final String TABS = "tabs";

    /** The + after the tab headers. */
    static final String NEW_TAB = "new-tab";

    /** The switch after the +: every chord to the shell while it is on. */
    static final String PASSTHROUGH = "passthrough";

    /** The notice over a terminal: why {@code --insert-path} typed nothing, say. */
    static final String NOTICE = "notice";

    /** The settings button in the main window's title bar. */
    static final String SETTINGS = "settings";

    /** The settings window's General tab. */
    static final String SETTINGS_GENERAL = "settings.general";

    /** The settings window's Workspaces tab. The page's own parts are {@code workspaces.*}: see {@link WorkspacesPage}. */
    static final String SETTINGS_WORKSPACES = "settings.workspaces";

    /** General's Change… button for the new tab directory. */
    static final String SETTINGS_TAB_DIR = "settings.tabdir";

    /** General's drop-down of the workspace to open at start. */
    static final String SETTINGS_START_WORKSPACE = "settings.startworkspace";

    /** The Open dialog's first tile, Open folder…. */
    static final String OPEN_FOLDER = "open.folder";

    /** The Open dialog's last tile, New workspace. */
    static final String OPEN_NEW = "open.new";

    /** The Open dialog's workspace tiles, by position: {@code open.workspace.0} is the first. */
    static final String OPEN_WORKSPACE = "open.workspace.";

    private Landmarks() {
    }
}
