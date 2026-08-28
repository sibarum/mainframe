package dev.mainframe.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Splits the raw tail of an external stage into the arguments a process receives.
 *
 * <p>This is deliberately not MainFrame's grammar. Everything a build tool writes
 * -- {@code exec:exec}, {@code -Pdist,native}, {@code -Dexpression=project.version}
 * -- is one word here, because the only thing that separates words is unquoted
 * whitespace. Inventing a dialect in between is what made those lines unwritable
 * in the first place.
 *
 * <p>What stays special is the least that still makes the line useful:
 * <ul>
 *   <li>Quotes group. A quote in the middle of a word joins rather than starting a
 *       new one, so {@code -Dmsg="hello world"} is a single argument.
 *   <li>{@code $name} expands, except inside single quotes. That is the one way to
 *       get a MainFrame value onto a command line, and losing it would mean no
 *       script could build an argument.
 * </ul>
 *
 * <p>Backslash is <em>not</em> an escape. On Windows it is a path separator far
 * more often than it is anything else, and {@code C:\Users\me} has to survive
 * being typed.
 */
public final class Argv {

    private Argv() {}

    /**
     * @param variables resolves {@code $name}; returning null leaves the text as
     *                  written, so a stray {@code $} in an argument is not an error
     */
    public static List<String> split(String raw, Function<String, String> variables) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean started = false;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (started) { out.add(word.toString()); word.setLength(0); started = false; }
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                started = true;
                i++;
                while (i < raw.length() && raw.charAt(i) != c) {
                    if (c == '"' && raw.charAt(i) == '$') {
                        i = expand(raw, i, word, variables);
                    } else {
                        word.append(raw.charAt(i));
                        i++;
                    }
                }
                // An unterminated quote takes the rest of the line, which is what
                // every shell does and is friendlier than refusing the command.
                if (i < raw.length()) i++;
                continue;
            }
            if (c == '$') {
                started = true;
                i = expand(raw, i, word, variables);
                continue;
            }
            started = true;
            word.append(c);
            i++;
        }
        if (started) out.add(word.toString());
        return out;
    }

    /** Appends what {@code $name} stands for, and returns the position after it. */
    private static int expand(String raw, int at, StringBuilder word,
                              Function<String, String> variables) {
        int j = at + 1;
        while (j < raw.length() && isNamePart(raw.charAt(j))) j++;
        String name = raw.substring(at + 1, j);
        String value = name.isEmpty() ? null : variables.apply(name);
        if (value == null) {
            // Not a variable we know: the text was meant literally. $HOME on a
            // command line is a real thing to want to pass through.
            word.append(raw, at, j);
        } else {
            word.append(value);
        }
        return j;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
