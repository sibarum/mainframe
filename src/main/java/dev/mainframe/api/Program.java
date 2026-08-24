package dev.mainframe.api;

/**
 * A program the host program provides, run inside the JVM instead of spawned.
 *
 * <p>It is invoked with a caret, like anything found on the PATH -- {@code ^jdk 25}
 * -- and it keeps a binary's contract: arguments in, text out, an exit code at the
 * end. Return 0 when it worked; anything else stops the pipeline the way a failing
 * external program does, with whatever went to standard error as the first hint.
 *
 * <pre>{@code
 * .program(ProgramSpec.named("jdk")
 *                 .summary("switch the JDK this session uses")
 *                 .usage("jdk <version>")
 *                 .build(),
 *         call -> {
 *             String version = call.argument(0, "");
 *             Path home = toolchains.get(version);
 *             if (home == null) {
 *                 call.writeError("no such JDK: " + version);
 *                 return 1;
 *             }
 *             call.session().env("JAVA_HOME", home.toString());
 *             call.writeLine("JAVA_HOME is now " + home);
 *             return 0;
 *         })
 * }</pre>
 *
 * <p>Use this when something has to look like a tool -- a name people type, flags
 * it parses itself, text it prints. Use {@link Command} when it should be part of
 * the language instead: typed data through the pipe, arguments checked before it
 * runs, {@code --dry-run} and confirmation on anything destructive. A caret says
 * to the reader that MainFrame is not in charge of what happens next, and that is
 * as true of a hosted program as of a spawned one -- the guardrails do not apply.
 */
@FunctionalInterface
public interface Program {

    /**
     * @return the exit code: 0 when it worked, anything else to stop the pipeline
     */
    int run(ProgramCall call) throws Exception;
}
