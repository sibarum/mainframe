package ${packageName};

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote,
 * and the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a
 * driver waits for the application to be ready, and it needs no application-specific hook when readiness is
 * declared somewhere the read-model can already see it. {@link #COUNT} works that way: its name is the number,
 * so {@code await count 3} is a real wait rather than a sleep.
 */
final class Landmarks {

    /** The card everything sits on. Its name is the panel. */
    static final String CARD = "card";

    /** The figure. <b>Its name is the number</b>, so a driver can wait on a value rather than on a clock. */
    static final String COUNT = "count";

    /** The line under the figure. */
    static final String NOTE = "note";

    /** The button that counts. */
    static final String COUNT_BUTTON = "button.count";

    /** The button that starts again. */
    static final String RESET_BUTTON = "button.reset";

    private Landmarks() {
    }
}
