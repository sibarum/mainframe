package dev.mainframe.lang;

import java.util.List;

import dev.mainframe.Span;
import dev.mainframe.value.Value;

/** The parsed shape of a MainFrame program. */
public sealed interface Ast {

    Span span();

    record Program(List<Stmt> statements, Span span) implements Ast {}

    // ---- statements ----------------------------------------------------------------

    sealed interface Stmt extends Ast {}

    record Let(String name, Pipeline value, Span span) implements Stmt {}

    /** {@code orElse} is null when there is no else branch. */
    record If(Expr condition, Program then, Program orElse, Span span) implements Stmt {}

    record For(String variable, Expr iterable, Program body, Span span) implements Stmt {}

    /** A bare pipeline, whose value is printed at the top level. */
    record Run(Pipeline pipeline) implements Stmt {
        @Override public Span span() { return pipeline.span(); }
    }

    // ---- pipelines -----------------------------------------------------------------

    record Pipeline(List<Stage> stages, Span span) implements Ast {}

    sealed interface Stage extends Ast {}

    /** A builtin invocation: {@code where size > 1mb}. */
    record Command(String name, List<Arg> args, Span span) implements Stage {}

    /** An external program invocation, written with a leading caret: {@code ^git status}. */
    record External(String name, List<Arg> args, Span span) implements Stage {}

    /** A stage that is just a value, e.g. the {@code [1 2 3]} in {@code [1 2 3] | length}. */
    record ExprStage(Expr expr) implements Stage {
        @Override public Span span() { return expr.span(); }
    }

    // ---- arguments -----------------------------------------------------------------

    sealed interface Arg extends Ast {}

    record Positional(Expr value) implements Arg {
        @Override public Span span() { return value.span(); }
    }

    /** {@code value} is null for a switch such as {@code --dry-run}. */
    record FlagArg(String name, boolean shortForm, Expr value, Span span) implements Arg {}

    // ---- expressions ---------------------------------------------------------------

    sealed interface Expr extends Ast {}

    record Lit(Value value, Span span) implements Expr {}

    /**
     * An unquoted word. In a normal argument it is text; inside a row expression
     * such as {@code where name =~ "x"} it names a column.
     */
    record Word(String text, Span span) implements Expr {}

    record Var(String name, Span span) implements Expr {}

    record Field(Expr target, String name, Span span) implements Expr {}

    record At(Expr target, Expr index, Span span) implements Expr {}

    record Unary(String op, Expr operand, Span span) implements Expr {}

    record Binary(String op, Expr left, Expr right, Span span) implements Expr {}

    record ListLit(List<Expr> items, Span span) implements Expr {}

    record RecordLit(List<Entry> entries, Span span) implements Expr {}

    record Entry(String key, Expr value) {}

    /** A {@code { ... }} body handed to builtins like {@code each}. */
    record BlockLit(Program body, Span span) implements Expr {}

    /** A parenthesised pipeline used as a value. */
    record Sub(Pipeline pipeline, Span span) implements Expr {}
}
