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

    /** The notice over a terminal: why {@code --insert-path} typed nothing, say. */
    static final String NOTICE = "notice";

    /** The settings button in the main window's title bar. */
    static final String SETTINGS = "settings";

    /** The settings window's General tab. */
    static final String SETTINGS_GENERAL = "settings.general";

    private Landmarks() {
    }
}
