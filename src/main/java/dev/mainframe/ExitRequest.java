package dev.mainframe;

/** Thrown by the exit builtin; caught by the shell loop. */
public final class ExitRequest extends RuntimeException {
    private final int code;

    public ExitRequest(int code) {
        super("exit " + code);
        this.code = code;
    }

    public int code() { return code; }
}
