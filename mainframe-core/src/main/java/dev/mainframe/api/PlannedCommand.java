package dev.mainframe.api;

/**
 * A hosted command that says what it will do before doing it.
 *
 * <p>Work out the whole job in {@link #plan}, adding one step per thing that will
 * change. MainFrame then shows the steps for --dry-run, asks for confirmation
 * when the command is destructive, and otherwise runs them in order -- so a
 * hosted command gets the same safety net as rm or save without writing any of
 * it.
 *
 * <p>Planning must not change anything itself. If the job cannot be done, throw
 * {@link Invocation#fail} from the plan and nothing will have happened.
 */
@FunctionalInterface
public interface PlannedCommand {

    void plan(Invocation invocation, Steps steps) throws Exception;

    /** Collects the steps a command intends to carry out. */
    interface Steps {

        /**
         * @param description what this step will do, phrased for someone reading a
         *                    confirmation prompt: "delete 4 rows from customers"
         */
        Steps step(String description, Action action);

        /** A remark to show alongside the plan, e.g. what was skipped. */
        Steps note(String note);
    }

    /** One unit of work, run only after the whole plan is accepted. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }
}
