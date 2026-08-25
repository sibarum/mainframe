package dev.mainframe.api;

import java.util.List;

/**
 * A problem MainFrame can explain: a parse error, a failed check, or a command
 * that refused to run. Carries the same code, hints and position the shell would
 * have shown a person.
 */
public final class ShellError extends RuntimeException {

    private final String code;
    private final List<String> hints;
    private final int line;
    private final int column;
    private final int length;

    ShellError(String code, String message, List<String> hints, int line, int column, int length) {
        super(message);
        this.code = code;
        this.hints = List.copyOf(hints);
        this.line = line;
        this.column = column;
        this.length = length;
    }

    /** The stable error code, e.g. {@code E305}. */
    public String code() { return code; }

    /** What to do about it; there is always at least one. */
    public List<String> hints() { return hints; }

    /** Line in the source that caused it, or 0 when it came from no particular line. */
    public int line() { return line; }

    /** Column in that line, or 0. */
    public int column() { return column; }

    /** How many characters the problem covers, for drawing your own marker. */
    public int length() { return length; }

    /** The message and its hints, as a person would be shown them. */
    public String explain() {
        StringBuilder sb = new StringBuilder("error[").append(code).append("] ").append(getMessage());
        if (line > 0) sb.append(" (line ").append(line).append(':').append(column).append(')');
        for (String hint : hints) sb.append("\nhelp ").append(hint);
        return sb.toString();
    }
}
