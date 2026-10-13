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
 * can already see. {@link #STATUS_FILE} works that way: its name is the front tab's title, dot and all, so
 * {@code await status.file notes.md} waits for a file to open and {@code await status.file •} for it to go
 * unsaved.
 */
final class Landmarks {

    /** The tab bar and its pages. */
    static final String TABS = "tabs";

    /** What the editor area shows when no file is open. */
    static final String EMPTY = "empty";

    /** The navigator's path bar: type a folder to show it, a file to open it, a new name to create it. */
    static final String PATH = "path";

    /** The navigator's tree. */
    static final String TREE = "navigator";

    /** The status line, and its three slots. */
    static final String STATUS = "status";
    static final String STATUS_FILE = "status.file";
    static final String STATUS_MESSAGE = "status.message";
    static final String STATUS_POSITION = "status.position";

    private Landmarks() {
    }
}
