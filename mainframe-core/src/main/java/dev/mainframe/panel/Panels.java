package dev.mainframe.panel;

import java.util.List;

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

    private static final int LEFT = 3;

    private Panels() {}

    /** The question on its own, for a caller with nothing to add to it. */
    public static boolean confirm(Editor editor, String question) {
        return confirm(editor, question, List.of());
    }

    /**
     * Asks a yes/no question on a screen, for a session whose terminal is a
     * protocol rather than a person.
     *
     * <p>{@code detail} is what is about to happen, a line each. A screen replaces
     * whatever was on the glass, so a confirmation that only asked would be asking
     * about something the person can no longer see -- which is the difference
     * between a screen and a printed prompt, and the reason this takes them.
     *
     * <p>The editor going away is a no, which is the same answer a confirmation
     * gives when the input runs out anywhere else in MainFrame: nobody said yes,
     * so nothing that could lose data happens.
     */
    public static boolean confirm(Editor editor, String question, List<String> detail) {
        // Until it is answered, rather than once -- and laid out afresh each time round, so a window that was
        // dragged wider is asked again at the size it is now. Anything that is not an answer (a resize, a change,
        // something a later protocol adds) puts the question back up, because the alternative is what this used to
        // do: read the first event of any kind as a verdict, and take a window being dragged for a no.
        while (true) {
            Event answer = editor.show(screen(editor, question, detail));
            if (answer.is(Event.CLICK)) return "yes".equals(answer.on());
            if (answer.is(Event.SUBMIT)) return true;
            if (answer.is(Event.CANCEL)) return false;
        }
    }

    private static Screen screen(Editor editor, String question, List<String> detail) {
        int width = Math.max(32, Math.min(editor.hello().cols(), 96));
        Screen screen = new Screen(0).title("CONFIRM");
        // Said out loud, because an editor puts the caret somewhere whether or not it was told to, and on a screen
        // whose buttons can be pressed by the keyboard the difference between the two is one keystroke. The safe
        // answer is where it starts: nobody has said yes yet, so nothing that could lose data is one Enter away.
        screen.focus("no");
        int row = 1;
        for (String line : wrapped(question, width - LEFT - 1)) {
            screen.text(row++, LEFT, line, "title");
        }
        row++;
        for (String line : detail) {
            for (String part : wrapped(line, width - LEFT - 1)) {
                screen.text(row++, LEFT, part, "plain");
            }
        }
        row++;
        screen.key("F12", Event.SUBMIT, "Yes");
        screen.key("F3", Event.CANCEL, "No");
        // The buttons or the key line, never both. They want the same cells, and an
        // editor that places characters where it was told -- which is every editor
        // not drawing widgets over the top of them -- would render the two through
        // each other. FormPanel makes the same call about a box's caption.
        if (editor.hello().can("action")) {
            screen.action(row, LEFT, "yes", "Yes", "F12");
            screen.action(row, LEFT + 10, "no", "No", "F3");
            screen.text(row, LEFT + 20, "F12 / F3", "status");
        } else {
            screen.text(row, LEFT, "F12 Yes    F3 No", "status");
        }
        return screen;
    }

    /**
     * {@code text} broken into lines of at most {@code room} characters, at spaces where there is one.
     *
     * <p>A screen is cells, so a line too long for the room does not run on -- it runs through whatever is to the
     * right of it. The plan being confirmed is a list of paths, which is exactly the sort of thing that is too
     * long, so it is exactly the sort of thing that has to be fitted rather than placed.
     */
    private static List<String> wrapped(String text, int room) {
        List<String> lines = new java.util.ArrayList<>();
        String rest = text == null ? "" : text.trim();
        int most = Math.max(8, room);
        while (rest.length() > most) {
            int at = rest.lastIndexOf(' ', most);
            if (at <= 0) at = most;
            lines.add(rest.substring(0, at).stripTrailing());
            rest = rest.substring(at).stripLeading();
        }
        if (!rest.isEmpty() || lines.isEmpty()) lines.add(rest);
        return lines;
    }
}
