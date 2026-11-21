package dev.mainframe;

import dev.mainframe.core.InsertPath;

/**
 * Text MainFrame types into a tab's shell on the person's behalf. {@code --insert-path}'s, once its prompt is drawn: a
 * space and the quoted name. The action bar's Send (docs/action-bar.md): the field's lines, then Enter. Each wrapped as
 * a paste when the shell asked for bracketed paste, so a line editor takes it as text rather than as keys.
 */
final class InsertText {

    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";

    private InsertText() {
    }

    /** Whether {@code text} holds anything but text: never typed, whoever vouched for it (0.1's rule). */
    static boolean unsafe(String text) {
        return InsertPath.hasControl(text);
    }

    static String typed(String text, boolean bracketed) {
        String typed = " " + text;
        return bracketed ? PASTE_START + typed + PASTE_END : typed;
    }

    /**
     * What Send types for the field's {@code text}: its lines, each ended as the Enter key ends one (CR), and the last
     * one run. With bracketed paste the lines go as one paste and only the final CR is a key, so a line editor such as
     * PSReadLine takes a many-line block as one edit. A tab becomes a space and every other control character is
     * dropped, so the field can never send an escape sequence or a key the person did not see in it. Blank text is
     * Enter alone.
     */
    static String sent(String text, boolean bracketed) {
        StringBuilder b = new StringBuilder(text.length());
        String lines = text.replace("\r\n", "\n").replace('\r', '\n');
        lines.codePoints().forEach(cp -> {
            if (cp == '\n') {
                b.append('\r');
            } else if (cp == '\t') {
                b.append(' ');
            } else if (!(cp < 0x20 || (cp >= 0x7f && cp <= 0x9f))) {
                b.appendCodePoint(cp);
            }
        });
        if (b.isEmpty()) {
            return "\r";
        }
        return bracketed ? PASTE_START + b + PASTE_END + "\r" : b + "\r";
    }
}
