package dev.mainframe.builtins;

import java.util.function.Function;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Signature;
import dev.mainframe.value.Value;

/** Small adapters so a builtin is a signature plus a lambda. */
final class Cmd {

    private Cmd() {}

    /** A command that reads and returns. */
    static Builtin of(Signature signature, Function<Args, Value> body) {
        return new Builtin() {
            @Override public Signature signature() { return signature; }
            @Override public Value run(Args args) { return body.apply(args); }
        };
    }

    /** A command that changes things, and so says what it will do first. */
    static Builtin planning(Signature signature, Function<Args, Plan> planner) {
        return new Builtin.Planning() {
            @Override public Signature signature() { return signature; }
            @Override public Plan plan(Args args) { return planner.apply(args); }
        };
    }
}
