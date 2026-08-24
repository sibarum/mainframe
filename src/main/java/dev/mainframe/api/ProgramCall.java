package dev.mainframe.api;

import java.nio.file.Path;
import java.util.List;

/**
 * One run of a hosted {@link Program}: what it was given, where its output goes,
 * and what it may change about the session.
 *
 * <p>The arguments arrive as a process would have received them -- plain strings,
 * flags spelled out as {@code --name=value}, nothing pre-parsed -- because a
 * program that looks like a binary should be able to parse its own line. What it
 * gets on top of that is what only in-process code can have: the piped value with
 * its types intact, and a session it can actually change. A spawned program gets a
 * copy of the environment and its edits die with it; {@code ^jdk 25} can leave
 * {@code JAVA_HOME} set for everything that follows.
 */
public interface ProgramCall {

    /** The name it was invoked by, without the caret. */
    String name();

    /** The arguments after the name, in order. */
    List<String> arguments();

    /** How many arguments there were. */
    int count();

    /** True when there is an argument at this position. */
    boolean has(int index);

    /** One argument, or {@code fallback} when there is none there. */
    String argument(int index, String fallback);

    /** The text a spawned program would have read on standard input; "" when none. */
    String input();

    /** True when something was piped in. */
    boolean hasInput();

    /**
     * What was piped in, still typed -- rows are rows, sizes are sizes. Nothing
     * was serialised on the way in, so a hosted program can read the value
     * instead of re-parsing the text of it.
     */
    Data inputData();

    // ---- output ------------------------------------------------------------------------

    /** Writes to standard output, which becomes the value of this pipeline stage. */
    void write(String text);

    /** Writes a line to standard output. */
    void writeLine(String text);

    /** Writes to standard error, which MainFrame reports as a warning. */
    void writeError(String text);

    // ---- the session -------------------------------------------------------------------

    /** The directory the shell is in, which is where a spawned program would start. */
    Path directory();

    /** Changes the directory the shell is in, for everything that follows. */
    void directory(Path directory);

    /** A variable from the shell's environment, or null. */
    String env(String name);

    /** Sets a variable for the rest of the session and everything it starts. */
    void env(String name, String value);

    /** Unsets a variable. False when it was not set. */
    boolean envRemove(String name);

    /** The PATH, in the order it is searched. */
    List<Path> path();

    /** Adds a directory to the end of the PATH. False when it was already there. */
    boolean pathAdd(Path directory);

    /** Adds a directory to the front of the PATH. False when it was already there. */
    boolean pathAddFirst(Path directory);

    /** Takes a directory off the PATH. False when it was not on it. */
    boolean pathRemove(Path directory);

    /**
     * True when the session was told to change nothing. MainFrame cannot enforce
     * that on a program, spawned or hosted -- a well-behaved one checks this and
     * prints what it would have done instead of doing it.
     */
    boolean dryRun();

    /** True when there is a person at the other end who could be asked something. */
    boolean interactive();
}
