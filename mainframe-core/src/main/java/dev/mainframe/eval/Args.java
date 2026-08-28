package dev.mainframe.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.Span;
import dev.mainframe.lang.Ast;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * The checked arguments of one command invocation.
 *
 * <p>By the time a builtin sees an {@code Args}, every count and type in its
 * signature already matched, so accessors here do not need to defend themselves.
 */
public final class Args {

    private final Signature signature;
    private final Session session;
    private final Interpreter interpreter;
    private final Scope scope;
    private final Value input;
    private final List<Value> positional;
    private final List<Ast.Expr> unevaluated;
    private final Map<String, Value> flags;
    private final Span span;

    Args(Signature signature, Session session, Interpreter interpreter, Scope scope, Value input,
         List<Value> positional, List<Ast.Expr> unevaluated, Map<String, Value> flags, Span span) {
        this.signature = signature;
        this.session = session;
        this.interpreter = interpreter;
        this.scope = scope;
        this.input = input;
        this.positional = positional;
        this.unevaluated = unevaluated;
        this.flags = flags;
        this.span = span;
    }

    // ---- context ---------------------------------------------------------------------

    public Signature signature() { return signature; }
    public Session session() { return session; }

    /** The commands this shell has, for anything that has to not collide with one. */
    public Registry registry() { return interpreter.registry(); }
    public Scope scope() { return scope; }
    public Span span() { return span; }
    public boolean dryRun() { return session.dryRun(); }

    /** What arrived through the pipe. */
    public Value input() { return input; }

    public boolean hasInput() { return !(input instanceof Value.Nothing); }

    /** The piped input as rows. The signature guarantees this is a table. */
    public List<Value.Rec> rows() { return Values.rows(input); }

    /** The piped input as a list, treating a single value as a list of one. */
    public List<Value> items() {
        if (input instanceof Value.ListVal l) return l.items();
        if (input instanceof Value.Nothing) return List.of();
        return List.of(input);
    }

    // ---- positional arguments ---------------------------------------------------------

    public int count() { return positional.size(); }

    public boolean has(int index) { return index < positional.size() && positional.get(index) != null; }

    public Value value(int index) { return has(index) ? positional.get(index) : Value.Nothing.INSTANCE; }

    public String str(int index) { return Values.asString(value(index), span); }

    public String str(int index, String fallback) { return has(index) ? str(index) : fallback; }

    public long lng(int index, long fallback) { return has(index) ? Values.asLong(value(index), span) : fallback; }

    /** A resolved, absolute path from argument {@code index}. */
    public Path path(int index) {
        Value v = value(index);
        if (v instanceof Value.PathVal p) return p.path();
        return session.resolve(Values.asString(v, span));
    }

    public Path path(int index, Path fallback) { return has(index) ? path(index) : fallback; }

    /** Every argument from {@code from} onwards as text, for rest parameters. */
    public List<String> strings(int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < positional.size(); i++) {
            if (positional.get(i) != null) out.add(Values.asString(positional.get(i), span));
        }
        return out;
    }

    /** Every argument from {@code from} onwards as a resolved path. */
    public List<Path> paths(int from) {
        List<Path> out = new ArrayList<>();
        for (int i = from; i < positional.size(); i++) if (positional.get(i) != null) out.add(path(i));
        return out;
    }

    // ---- flags -----------------------------------------------------------------------

    /** True when a switch flag was given. */
    public boolean flag(String name) {
        Value v = flags.get(name);
        return v != null && Values.truthy(v);
    }

    public boolean hasFlag(String name) { return flags.containsKey(name); }

    public Value flagValue(String name) {
        Value v = flags.get(name);
        return v == null ? Value.Nothing.INSTANCE : v;
    }

    public String flagStr(String name, String fallback) {
        Value v = flags.get(name);
        return v == null ? fallback : Values.asString(v, span);
    }

    public long flagLong(String name, long fallback) {
        Value v = flags.get(name);
        return v == null ? fallback : Values.asLong(v, span);
    }

    public List<String> flagList(String name) {
        Value v = flags.get(name);
        if (v == null) return List.of();
        if (v instanceof Value.ListVal l) {
            List<String> out = new ArrayList<>(l.items().size());
            for (Value item : l.items()) out.add(Values.asString(item, span));
            return out;
        }
        return List.of(Values.asString(v, span));
    }

    // ---- lazy arguments --------------------------------------------------------------

    /** The unevaluated argument at {@code index}, for row-by-row evaluation. */
    public Ast.Expr expr(int index) {
        Ast.Expr e = index < unevaluated.size() ? unevaluated.get(index) : null;
        if (e == null) {
            throw MfError.of("E204", signature().name() + " needs an expression here")
                    .at(span).hint("usage: " + signature().usage()).build();
        }
        return e;
    }

    public List<Ast.Expr> exprs(int from) {
        List<Ast.Expr> out = new ArrayList<>();
        for (int i = from; i < unevaluated.size(); i++) {
            if (unevaluated.get(i) != null) out.add(unevaluated.get(i));
        }
        return out;
    }

    /** Evaluates an expression with a row's fields in scope. */
    public Value evalInRow(Ast.Expr expr, Value.Rec row) {
        return interpreter.evalInRow(expr, row, scope);
    }

    /** Runs a block once per item, with {@code $it} bound to the item. */
    public Value runBlock(Value.Block block, Value item) {
        return interpreter.runBlock(block, item, scope);
    }

    /**
     * Runs parsed MainFrame source and hands back its value, printing nothing.
     * Used by the commands that read MainFrame's own written form.
     */
    public Value evalSource(dev.mainframe.lang.Ast.Program program) {
        return interpreter.evalQuiet(program, scope.child());
    }

    // ---- errors ----------------------------------------------------------------------

    /** Raises a well-formed error attributed to this command. */
    public MfError.Builder fail(String code, String message) {
        return MfError.of(code, message).at(span);
    }

    public MfError.Builder failUsage(String code, String message) {
        return MfError.of(code, message).at(span).hint("usage: " + signature.usage());
    }

    /** Type name for messages, e.g. when input is the wrong shape. */
    public String describeInput() { return ValueType.of(input).display(); }
}
