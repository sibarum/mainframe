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

    // ---- changing the session ----------------------------------------------------------

    /**
     * Sets a variable for the rest of the session and every program it starts.
     *
     * <p>A command that does this should declare {@link Effect#SESSION}, the way
     * {@code env-set} and {@code path-add} do: nothing on disk changes, so there
     * is nothing to dry-run, but the user is told it outlives the command.
     */
    void env(String name, String value);

    /** Unsets a variable for the rest of the session. False when it was not set. */
    boolean envRemove(String name);

    /** The PATH, in the order it is searched. */
    List<Path> path();

    /** Adds a directory to the end of the PATH. False when it was already there. */
    boolean pathAdd(Path directory);

    /** Adds a directory to the front of the PATH. False when it was already there. */
    boolean pathAddFirst(Path directory);

    /** Takes a directory off the PATH. False when it was not on it. */
    boolean pathRemove(Path directory);

    /** Moves the shell to another directory, as {@code cd} would. */
    void directory(Path directory);

    // ---- talking to the user ------------------------------------------------------------

    /** Prints a line, for progress a person should see. */
    void print(String message);

    /** Prints a quieter line, for asides. */
    void note(String message);

    /** Prints a warning to the error stream. */
    void warn(String message);

    // ---- asking the user ----------------------------------------------------------------

    /** Asks the user to fill in a form. See {@link #form(Data, String, Data)}. */
    Data form(Data fields, String title);

    /**
     * Asks the user to fill in a form, and hands back the answers -- or
     * {@link Data#nothing()} when they cancelled it.
     *
     * <p>The fields are the same data the {@code form} command takes: a table of
     * field records, or a list of names. Every rule they declare -- required, a
     * length, a pattern, a list to choose from -- is enforced before the answers
     * come back, so a callback receives a record it does not have to re-check.
     *
     * <pre>{@code Data answers = invocation.form(spec, "New contact");
     * if (answers.isNothing()) return Data.nothing();       // they cancelled
     * contacts.add(answers.field("email").text()); }</pre>
     *
     * <p>A command that asks should expect to be run where there is nobody to
     * answer -- a script, a pipe, a CI job -- in which case this fails the way
     * {@code form} itself would, rather than inventing a record of blanks.
     *
     * @param starting values to offer as the answers, e.g. the record being
     *                 edited; anything in it the form does not ask about is
     *                 carried through to the result
     */
    Data form(Data fields, String title, Data starting);

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
