package dev.mainframe.api;

import java.nio.file.Path;
import java.util.List;

import dev.mainframe.MfError;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Signature;
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

        @Override public void print(String message) { args.session().out().info(message); }
        @Override public void note(String message) { args.session().out().note(message); }
        @Override public void warn(String message) { args.session().out().warn(message); }

        @Override
        public RuntimeException fail(String message, String... hints) {
            MfError.Builder error = args.fail("E1002", message);
            for (String hint : hints) error.hint(hint);
            if (hints.length == 0) error.hint("run " + commandName() + " --help to see what it expects");
            return error.build();
        }
    }
}
