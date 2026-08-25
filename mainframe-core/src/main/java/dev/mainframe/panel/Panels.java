package dev.mainframe.panel;

/**
 * The small screens MainFrame needs that are not anybody's form: a question with
 * two answers, and a message with one.
 *
 * <p>They are here rather than built where they are needed so that a panel
 * session never falls back to reading a line. In panel mode standard input is the
 * protocol, and anything that read from it directly would be eating the editor's
 * messages.
 */
public final class Panels {

    private Panels() {}

    /**
     * Asks a yes/no question on a screen, for a session whose terminal is a
     * protocol rather than a person.
     *
     * <p>The editor going away is a no, which is the same answer a confirmation
     * gives when the input runs out anywhere else in MainFrame: nobody said yes,
     * so nothing that could lose data happens.
     */
    public static boolean confirm(Editor editor, String question) {
        Screen screen = new Screen(0)
                .title("CONFIRM")
                .text(1, 3, question, "title")
                .text(3, 3, "F12 Yes    F3 No", "status")
                .key("F12", Event.SUBMIT, "Yes")
                .key("F3", Event.CANCEL, "No");
        if (editor.hello().can("action")) {
            screen.action(3, 3, "yes", "Yes", "F12").action(3, 12, "no", "No", "F3");
        }
        Event answer = editor.show(screen);
        if (answer.is(Event.CLICK)) return "yes".equals(answer.on());
        return answer.is(Event.SUBMIT);
    }
}
