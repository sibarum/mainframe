package dev.mainframe.api;

import java.nio.file.Path;
import java.util.List;

/**
 * One call of a hosted command: the checked arguments, the piped data, and the
 * bits of the shell a command legitimately needs.
 *
 * <p>Everything here has already been validated against the command's
 * {@link CommandSpec}. If an argument was declared required, it is present; if it
 * was declared a path, it is a path. A callback does not need to defend itself.
 */
public interface Invocation {

    /** The name the command was invoked by. */
    String commandName();

    // ---- positional arguments ----------------------------------------------------------

    /** How many positional arguments were given. */
    int count();

    boolean has(int index);

    /** The argument as text. */
    String text(int index);

    String text(int index, String fallback);

    long number(int index, long fallback);

    /** The argument as an absolute path, resolved against the shell's directory. */
    Path path(int index);

    Path path(int index, Path fallback);

    /** Every argument from {@code from} onwards, for a repeatable parameter. */
    List<String> texts(int from);

    List<Path> paths(int from);

    /** The argument with its type intact. */
    Data argument(int index);

    // ---- flags -------------------------------------------------------------------------

    /** True when a switch was given, e.g. {@code --force}. */
    boolean flag(String name);

    boolean hasFlag(String name);

    String flagText(String name, String fallback);

    long flagNumber(String name, long fallback);

    /** Every value of a flag that was repeated, e.g. {@code --skip=a --skip=b}. */
    List<String> flagList(String name);

    Data flagValue(String name);

    // ---- the pipeline ------------------------------------------------------------------

    /** What arrived through the pipe; {@link Data#nothing()} when the command began the line. */
    Data input();

    // ---- context -----------------------------------------------------------------------

    /** The directory the shell is in right now. */
    Path directory();

    /** A variable from the shell's live environment, or null. */
    String env(String name);

    /** True when the user asked for a dry run; a plain command should then change nothing. */
    boolean dryRun();

    // ---- talking to the user ------------------------------------------------------------

    /** Prints a line, for progress a person should see. */
    void print(String message);

    /** Prints a quieter line, for asides. */
    void note(String message);

    /** Prints a warning to the error stream. */
    void warn(String message);

    // ---- giving up ---------------------------------------------------------------------

    /**
     * Builds the exception to throw when a command cannot do what was asked.
     * MainFrame renders it exactly like its own errors, so give at least one hint
     * saying what to do next.
     *
     * <pre>{@code throw invocation.fail("no database is connected",
     *         "connect one with: db-connect <url>"); }</pre>
     */
    RuntimeException fail(String message, String... hints);
}
