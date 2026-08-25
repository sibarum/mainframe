package dev.mainframe;

import java.util.List;

import dev.mainframe.value.Value;

/**
 * A program the surrounding application provides itself, run inside this JVM
 * instead of being started as a process.
 *
 * <p>It is invoked with a caret, exactly like a program found on the PATH --
 * {@code ^jdk 25} -- because that is what it is as far as the line is concerned:
 * arguments in, text out, an exit code at the end, and no promises from MainFrame
 * about what happens in between. What it is not is a process: nothing is spawned,
 * nothing is serialised, and it can see the piped value with its types intact.
 *
 * <p>Hosted programs are searched before the PATH, so one of them takes the place
 * of a real program with the same name. {@code which} says which one would run,
 * and {@code programs} lists them, so the shadowing is visible rather than
 * mysterious.
 *
 * <p>Hosts implement this through {@code dev.mainframe.api.Program}; this is the
 * seam the interpreter calls, kept out of the public API so the two can change
 * independently.
 */
public interface HostedProgram {

    /** The name typed after the caret. */
    String name();

    /** One line saying what it does, for {@code which} and {@code programs}. */
    String summary();

    /** The one-line usage, e.g. {@code jdk <version>}. */
    String usage();

    /** Lines someone could type, for {@code programs --verbose}. */
    List<String> examples();

    /**
     * Runs the program.
     *
     * @return the exit code: 0 when it worked, anything else stops the pipeline
     *         the way a failing external program does
     */
    int run(Call call) throws Exception;

    /** One run of a hosted program: what it was given, and where its output goes. */
    interface Call {

        /** The arguments after the name, as a process would have received them. */
        List<String> arguments();

        /** What came down the pipe, still typed; {@code Nothing} when nothing did. */
        Value input();

        /** The text a spawned program would have read on its standard input. */
        String inputText();

        /** The session, for the directory, the environment and the guardrail flags. */
        Session session();

        /** Writes to standard output, which becomes the value of this stage. */
        void write(String text);

        /** Writes to standard error, which MainFrame reports as a warning. */
        void writeError(String text);
    }
}
