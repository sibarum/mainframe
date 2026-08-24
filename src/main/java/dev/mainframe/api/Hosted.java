package dev.mainframe.api;

import java.nio.file.Path;
import java.util.List;

import dev.mainframe.HostedProgram;
import dev.mainframe.MfError;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Signature;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.form.FormScreen;
import dev.mainframe.value.Value;

/**
 * Turns a host's callback into a command the interpreter can run.
 *
 * <p>This is the whole bridge, and it is deliberately thin: a hosted command is a
 * {@link Builtin} like any other, so it goes through the same argument checking,
 * the same guardrails, the same help. Nothing here gives a hosted command a
 * shortcut a built-in does not have.
 */
final class Hosted {

    private Hosted() {}

    static Builtin command(CommandSpec spec, Command command) {
        if (spec.effect().needsAPlan()) {
            throw new IllegalArgumentException("\"" + spec.name() + "\" is declared as "
                    + spec.effect() + ", so it has to be registered as a PlannedCommand -- "
                    + "otherwise --dry-run would have nothing to show and could not be honoured");
        }
        Signature signature = spec.signature();
        return new Builtin() {
            @Override public Signature signature() { return signature; }

            @Override
            public Value run(Args args) {
                View view = new View(args);
                try {
                    Data result = command.run(view);
                    return result == null ? Value.Nothing.INSTANCE : result.unwrap();
                } catch (MfError e) {
                    throw e;
                } catch (Exception e) {
                    throw failed(signature.name(), args, e);
                }
            }
        };
    }

    static Builtin planned(CommandSpec spec, PlannedCommand command) {
        Signature signature = spec.signature();
        return new Builtin.Planning() {
            @Override public Signature signature() { return signature; }

            @Override
            public Plan plan(Args args) {
                Plan plan = new Plan(signature.name());
                View view = new View(args);
                try {
                    command.plan(view, new Steps(plan, signature.name(), args));
                } catch (MfError e) {
                    throw e;
                } catch (Exception e) {
                    throw failed(signature.name(), args, e);
                }
                return plan;
            }
        };
    }

    /**
     * Turns a host's program into one the interpreter can run behind a caret.
     *
     * <p>Deliberately thinner than the command bridge: there is no signature to
     * check against, because the program parses its own line, and no plan to ask
     * about, because a caret already says the guardrails do not apply. What is
     * left is a name, an exit code, and two streams.
     */
    static HostedProgram program(ProgramSpec spec, Program program) {
        return new HostedProgram() {
            @Override public String name() { return spec.name(); }
            @Override public String summary() { return spec.summary(); }
            @Override public String usage() { return spec.usage(); }
            @Override public List<String> examples() { return spec.examples(); }

            @Override
            public int run(Call call) throws Exception {
                return program.run(new ProgramView(spec.name(), call));
            }
        };
    }

    /** The host's view of one program run, backed by the interpreter's call. */
    private record ProgramView(String name, HostedProgram.Call call) implements ProgramCall {

        @Override public List<String> arguments() { return call.arguments(); }
        @Override public int count() { return call.arguments().size(); }
        @Override public boolean has(int index) { return index >= 0 && index < count(); }

        @Override
        public String argument(int index, String fallback) {
            return has(index) ? call.arguments().get(index) : fallback;
        }

        @Override public String input() { return call.inputText(); }
        @Override public boolean hasInput() { return !(call.input() instanceof Value.Nothing); }
        @Override public Data inputData() { return Data.wrap(call.input()); }

        @Override public void write(String text) { call.write(text); }
        @Override public void writeLine(String text) { call.write((text == null ? "" : text) + "\n"); }
        @Override public void writeError(String text) { call.writeError(text); }

        @Override public Path directory() { return call.session().cwd(); }
        @Override public void directory(Path dir) { Sessions.directory(call.session(), dir); }
        @Override public String env(String name) { return call.session().env().get(name); }
        @Override public void env(String name, String value) { Sessions.env(call.session(), name, value); }
        @Override public boolean envRemove(String name) { return Sessions.envRemove(call.session(), name); }
        @Override public List<Path> path() { return Sessions.path(call.session()); }
        @Override public boolean pathAdd(Path dir) { return Sessions.pathAdd(call.session(), dir, false); }
        @Override public boolean pathAddFirst(Path dir) { return Sessions.pathAdd(call.session(), dir, true); }
        @Override public boolean pathRemove(Path dir) { return Sessions.pathRemove(call.session(), dir); }

        @Override public boolean dryRun() { return call.session().dryRun(); }
        @Override public boolean interactive() { return call.session().interactive(); }
    }

    /**
     * A failure the host did not describe. It still gets a code and a hint, and
     * it says where it came from, so nobody hunts for a bug in MainFrame.
     */
    private static MfError failed(String name, Args args, Exception cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return args.fail("E1001", name + " could not finish: " + message)
                .hint("this came from the program hosting MainFrame, not from the shell")
                .hint("run " + name + " --help to check what it expects")
                .build();
    }

    /** Collects a host's steps into a MainFrame plan. */
    private record Steps(Plan plan, String name, Args args) implements PlannedCommand.Steps {

        @Override
        public PlannedCommand.Steps step(String description, PlannedCommand.Action action) {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("every step of \"" + name
                        + "\" needs a description, because a person may be asked to approve it");
            }
            plan.step(description, () -> {
                try {
                    action.run();
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
                }
            });
            return this;
        }

        @Override
        public PlannedCommand.Steps note(String note) {
            plan.note(note);
            return this;
        }
    }

    /** The host's view of one invocation, backed by the interpreter's checked arguments. */
    private record View(Args args) implements Invocation {

        @Override public String commandName() { return args.signature().name(); }

        @Override public int count() { return args.count(); }
        @Override public boolean has(int index) { return args.has(index); }
        @Override public String text(int index) { return args.str(index); }
        @Override public String text(int index, String fallback) { return args.str(index, fallback); }
        @Override public long number(int index, long fallback) { return args.lng(index, fallback); }
        @Override public Path path(int index) { return args.path(index); }
        @Override public Path path(int index, Path fallback) { return args.path(index, fallback); }
        @Override public List<String> texts(int from) { return args.strings(from); }
        @Override public List<Path> paths(int from) { return args.paths(from); }
        @Override public Data argument(int index) { return Data.wrap(args.value(index)); }

        @Override public boolean flag(String name) { return args.flag(name); }
        @Override public boolean hasFlag(String name) { return args.hasFlag(name); }
        @Override public String flagText(String name, String fallback) { return args.flagStr(name, fallback); }
        @Override public long flagNumber(String name, long fallback) { return args.flagLong(name, fallback); }
        @Override public List<String> flagList(String name) { return args.flagList(name); }
        @Override public Data flagValue(String name) { return Data.wrap(args.flagValue(name)); }

        @Override public Data input() { return Data.wrap(args.input()); }

        @Override public Path directory() { return args.session().cwd(); }
        @Override public String env(String name) { return args.session().env().get(name); }
        @Override public boolean dryRun() { return args.dryRun(); }

        @Override public void env(String name, String value) { Sessions.env(args.session(), name, value); }
        @Override public boolean envRemove(String name) { return Sessions.envRemove(args.session(), name); }
        @Override public List<Path> path() { return Sessions.path(args.session()); }
        @Override public boolean pathAdd(Path dir) { return Sessions.pathAdd(args.session(), dir, false); }
        @Override public boolean pathAddFirst(Path dir) { return Sessions.pathAdd(args.session(), dir, true); }
        @Override public boolean pathRemove(Path dir) { return Sessions.pathRemove(args.session(), dir); }
        @Override public void directory(Path dir) { Sessions.directory(args.session(), dir); }

        @Override public void print(String message) { args.session().out().info(message); }
        @Override public void note(String message) { args.session().out().note(message); }
        @Override public void warn(String message) { args.session().out().warn(message); }

        @Override
        public Data form(Data fields, String title) { return form(fields, title, null); }

        @Override
        public Data form(Data fields, String title, Data starting) {
            if (fields == null) throw new IllegalArgumentException("a form needs fields to ask for");
            Value.Rec offered = starting != null && starting.unwrap() instanceof Value.Rec record
                    ? record
                    : null;
            // Read before asked, and refused before printed: a form nobody can fill
            // in should not spray a blank one down a log first.
            Form form = Form.read(fields.unwrap(), args.span());
            MainFrame.requireSomebodyToAsk(args.session(), commandName());
            Value.Rec answers = args.session().editor() != null
                    ? FormPanel.show(form, offered, title, args.session().editor(), args.session().cwd())
                    : FormScreen.show(form, offered, title, true, args.session());
            return answers == null ? Data.nothing() : Data.wrap(answers);
        }

        @Override
        public RuntimeException fail(String message, String... hints) {
            MfError.Builder error = args.fail("E1002", message);
            for (String hint : hints) error.hint(hint);
            if (hints.length == 0) error.hint("run " + commandName() + " --help to see what it expects");
            return error.build();
        }
    }
}
