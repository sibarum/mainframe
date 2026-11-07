package dev.mainframe;

import dev.mainframe.core.InsertPath;

/**
 * What {@code --insert-path} sends a tab's shell, once its prompt is drawn: a space and the quoted name, wrapped as a
 * paste when the shell asked for bracketed paste, so a line editor takes it as text rather than as keys.
 */
final class InsertText {

    private InsertText() {
    }

    /** Whether {@code text} holds anything but text: never typed, whoever vouched for it (0.1's rule). */
    static boolean unsafe(String text) {
        return InsertPath.hasControl(text);
    }

    static String typed(String text, boolean bracketed) {
        String typed = " " + text;
        return bracketed ? "\u001b[200~" + typed + "\u001b[201~" : typed;
    }
}
