package dev.mainframe;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a driver
 * waits for the application to be ready, and it needs no hook when readiness is declared somewhere the read-model
 * can already see.
 */
final class Landmarks {

    /** The tab bar and its pages, the terminals. */
    static final String TABS = "tabs";

    /** The terminal in front. Moves with the front tab, so a script types into whichever is shown. */
    static final String TERMINAL = "terminal";

    /** The + at the tab bar's end: a new tab. */
    static final String NEW_TAB = "new-tab";

    /** The pill that says every key goes to the shell, there only while it does; a click gives the keys back. */
    static final String PASSTHROUGH = "passthrough";

    /** The field a tab's new name is typed into, there only while one is. */
    static final String RENAME = "rename";

    private Landmarks() {
    }
}
