package dev.mainframe.api;

/**
 * A command implemented by the program hosting MainFrame.
 *
 * <p>The callback runs in the shell's own thread, receives the piped data and the
 * checked arguments, and returns the value that continues down the pipeline. No
 * process is started and nothing is serialised on the way in or out.
 *
 * <p>Anything that changes files should be a {@link PlannedCommand} instead, so
 * that --dry-run and confirmation work the same way they do for built-ins.
 */
@FunctionalInterface
public interface Command {

    /**
     * @return what the next stage of the pipeline receives; {@link Data#nothing()}
     *         if there is nothing to pass on
     */
    Data run(Invocation invocation) throws Exception;
}
