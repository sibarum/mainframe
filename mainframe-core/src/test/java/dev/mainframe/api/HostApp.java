package dev.mainframe.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private record Task(int id, String what, String priority, Instant due, boolean done) {}

    private final List<Task> tasks = new ArrayList<>();
    private int nextId = 1;

    private HostApp() {
        add("write the release notes", "soon", null);
        add("book the venue", "now", null);
        add("chase the invoice", "later", null);
    }

    private Task add(String what, String priority, Instant due) {
        Task task = new Task(nextId++, what, priority, due, false);
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
        System.out.println("It brings a program of its own too. Try: programs");
        System.exit(shell.repl());
    }

    private MainFrame shell() {
        return MainFrame.builder()
                .command(listSpec(), this::listTasks)
                .command(addSpec(), this::addTask)
                .command(newSpec(), this::askForTask)
                .command(doneSpec(), this::planFinish)
                // Not a command but a program: ^workspace, run in this JVM.
                .program(workspaceSpec(), this::useWorkspace)
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
                    .put("priority", task.priority())
                    // A moment, not text, so "where due < (now + 7d)" is arithmetic.
                    .put("due", task.due() == null ? Data.nothing() : Data.time(task.due()))
                    .put("done", task.done())
                    .build());
        }
        return Data.list(rows);
    }

    // ---- task-new: the host asks, and MainFrame does the asking ---------------------------

    /**
     * The form, written as data. It is the same shape the {@code form} command
     * takes, so it could equally have been read out of a file -- and the rules on
     * it are enforced before the callback sees an answer.
     */
    private static Data taskForm() {
        // The list is the order the questions come in; the keys inside a field are
        // named, so their order is nobody's business.
        return Data.of(List.of(
                Map.of("name", "what", "required", true, "min", 4, "help", "what needs doing"),
                Map.of("name", "priority", "required", true, "default", "soon",
                        "choose", List.of("now", "soon", "later")),
                Map.of("name", "due", "type", "time", "help", "leave it blank if it can wait")));
    }

    private static CommandSpec newSpec() {
        return CommandSpec.named("task-new")
                .category("tasks")
                .summary("add a task, asking for the details")
                .output(DataType.RECORD)
                .effect(Effect.SESSION)
                .example("task-new")
                .build();
    }

    /**
     * Nothing here validates anything. The form's own rules did that, and a
     * cancelled form arrives as nothing, which is the one case worth handling.
     */
    private Data askForTask(Invocation invocation) {
        Data answers = invocation.form(taskForm(), "New task");
        if (answers.isNothing()) {
            invocation.note("nothing was added");
            return Data.nothing();
        }
        Data due = answers.field("due");
        Task task = add(answers.field("what").text(), answers.field("priority").text(),
                due.isNothing() ? null : due.time());
        return Data.row().put("id", task.id()).put("what", task.what())
                .put("priority", task.priority()).build();
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
        Task task = add(what, "soon", null);
        return Data.row().put("id", task.id()).put("what", task.what())
                .put("priority", task.priority()).put("done", false).build();
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
                    () -> tasks.set(tasks.indexOf(task),
                            new Task(task.id(), task.what(), task.priority(), task.due(), true)));
        }
    }

    // ---- workspace: a program of the host's own, rather than a command --------------------

    /**
     * Written {@code ^workspace}, like anything on the PATH, because that is what
     * it is: a tool that parses its own line, prints text and exits with a code.
     * What it can do that a spawned one could not is change the session it was run
     * from -- the variable and the PATH entry are still there for the next line.
     */
    private static ProgramSpec workspaceSpec() {
        return ProgramSpec.named("workspace")
                .summary("work in a directory: set TASKS_HOME and put its tools on the PATH")
                .usage("workspace <directory> [--quiet]")
                .example("^workspace ./release")
                .build();
    }

    private int useWorkspace(ProgramCall call) {
        if (call.count() == 0) {
            call.writeError("say which directory, e.g. ^workspace ./release");
            return 2;
        }
        // Relative to where the shell is, which is where a spawned program would
        // have started.
        Path directory = call.directory().resolve(call.argument(0, ".")).normalize();
        if (!Files.isDirectory(directory)) {
            call.writeError(directory + " is not a directory");
            return 1;
        }
        if (call.dryRun()) {
            call.writeLine("would work in " + directory);
            return 0;
        }
        call.env("TASKS_HOME", directory.toString());
        call.pathAddFirst(directory.resolve("bin"));
        call.directory(directory);
        if (!call.arguments().contains("--quiet")) {
            call.writeLine("TASKS_HOME is now " + directory);
            call.writeLine(directory.resolve("bin") + " comes first on the PATH");
        }
        return 0;
    }

    private Task find(int id) {
        for (Task task : tasks) if (task.id() == id) return task;
        return null;
    }
}
