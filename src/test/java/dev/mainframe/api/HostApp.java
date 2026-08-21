package dev.mainframe.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A worked example of a program hosting MainFrame: a tiny task list, exposed as
 * shell commands, with no process ever spawned.
 *
 * <p>Run it with the project built:
 *
 * <pre>
 * java -cp target/classes:target/test-classes dev.mainframe.api.HostApp
 * java -cp target/classes:target/test-classes dev.mainframe.api.HostApp -c "tasks | where done == false"
 * </pre>
 */
public final class HostApp {

    /** The host's own state. The shell never sees this; it only sees the callbacks. */
    private record Task(int id, String what, boolean done) {}

    private final List<Task> tasks = new ArrayList<>();
    private int nextId = 1;

    private HostApp() {
        add("write the release notes");
        add("book the venue");
        add("chase the invoice");
    }

    private Task add(String what) {
        Task task = new Task(nextId++, what, false);
        tasks.add(task);
        return task;
    }

    public static void main(String[] arguments) {
        HostApp app = new HostApp();
        MainFrame shell = app.shell();
        if (arguments.length >= 2 && arguments[0].equals("-c")) {
            System.exit(shell.execute(arguments[1]));
        }
        System.out.println("A task list with a shell in it. Try: tasks | where done == false");
        System.exit(shell.repl());
    }

    private MainFrame shell() {
        return MainFrame.builder()
                .command(listSpec(), this::listTasks)
                .command(addSpec(), this::addTask)
                .command(doneSpec(), this::planFinish)
                // This shell is for tasks, so the filesystem commands that could
                // surprise someone are simply not in it.
                .without("rm", "mv", "cp", "save", "mkdir")
                .build();
    }

    // ---- tasks: a plain reading command -------------------------------------------------

    private static CommandSpec listSpec() {
        return CommandSpec.named("tasks")
                .category("tasks")
                .summary("list the tasks this program is keeping")
                .optional("containing", DataType.TEXT, "only tasks whose text contains this")
                .output(DataType.TABLE)
                .effect(Effect.READS)
                .example("tasks")
                .example("tasks invoice")
                .example("tasks | where done == false | length")
                .build();
    }

    private Data listTasks(Invocation invocation) {
        String filter = invocation.text(0, "").toLowerCase();
        List<Data> rows = new ArrayList<>();
        for (Task task : tasks) {
            if (!task.what().toLowerCase().contains(filter)) continue;
            rows.add(Data.row()
                    .put("id", task.id())
                    .put("what", task.what())
                    .put("done", task.done())
                    .build());
        }
        return Data.list(rows);
    }

    // ---- task-add: a command that changes the host's state -------------------------------

    private static CommandSpec addSpec() {
        return CommandSpec.named("task-add")
                .category("tasks")
                .summary("add a task")
                .argument("what", DataType.TEXT, "what needs doing")
                .output(DataType.RECORD)
                .effect(Effect.SESSION)
                .example("task-add \"send the contract\"")
                .build();
    }

    private Data addTask(Invocation invocation) {
        String what = invocation.text(0).strip();
        if (what.isEmpty()) {
            throw invocation.fail("a task needs some text",
                    "for example: task-add \"send the contract\"");
        }
        Task task = add(what);
        return Data.row().put("id", task.id()).put("what", task.what()).put("done", false).build();
    }

    // ---- task-done: destructive, so it plans first ----------------------------------------

    private static CommandSpec doneSpec() {
        return CommandSpec.named("task-done")
                .category("tasks")
                .summary("mark tasks finished, which cannot be undone")
                .repeatable("ids", DataType.INTEGER, "which tasks; taken from the pipe if you name none")
                .input(DataType.ANY)
                .output(DataType.TABLE)
                .effect(Effect.DESTRUCTIVE)
                .example("task-done 2")
                .example("tasks invoice | task-done --dry-run")
                .build();
    }

    /**
     * Because this is declared destructive, MainFrame shows the plan for
     * --dry-run, asks before running it, and refuses to run unattended without
     * --yes. None of that is written here.
     */
    private void planFinish(Invocation invocation, PlannedCommand.Steps steps) {
        List<Integer> ids = new ArrayList<>();
        for (String text : invocation.texts(0)) ids.add(Integer.parseInt(text));
        if (ids.isEmpty()) {
            // Nothing named, so take the id column of whatever was piped in.
            for (Data row : invocation.input().rows()) ids.add((int) row.field("id").number());
        }
        if (ids.isEmpty()) {
            throw invocation.fail("no tasks to finish",
                    "name some ids, or pipe rows in: tasks invoice | task-done");
        }
        for (int id : ids) {
            Task task = find(id);
            if (task == null) {
                throw invocation.fail("there is no task " + id,
                        "run tasks to see what there is -- nothing has been changed");
            }
            if (task.done()) {
                steps.note("task " + id + " was already finished -- left alone");
                continue;
            }
            steps.step("finish task " + id + ": " + task.what(),
                    () -> tasks.set(tasks.indexOf(task), new Task(task.id(), task.what(), true)));
        }
    }

    private Task find(int id) {
        for (Task task : tasks) if (task.id() == id) return task;
        return null;
    }
}
