package dev.mainframe.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.Span;
import dev.mainframe.lang.Ast;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/** Runs a parsed program. */
public final class Interpreter {

    private final Session session;
    private final Registry registry;
    private int depth;

    /** Set while evaluating a row expression, so bare words mean columns. */
    private Value.Rec currentRow;

    public Interpreter(Session session, Registry registry) {
        this.session = session;
        this.registry = registry;
    }

    public Session session() { return session; }
    public Registry registry() { return registry; }

    // ---- statements ------------------------------------------------------------------

    /** Runs a whole program and returns the value of its last pipeline. */
    public Value run(Ast.Program program) {
        return run(program, session.globals());
    }

    public Value run(Ast.Program program, Scope scope) {
        Value last = Value.Nothing.INSTANCE;
        for (Ast.Stmt statement : program.statements()) last = exec(statement, scope);
        return last;
    }

    private Value exec(Ast.Stmt statement, Scope scope) {
        return switch (statement) {
            case Ast.Let let -> {
                scope.define(let.name(), pipeline(let.value(), scope));
                yield Value.Nothing.INSTANCE;
            }
            case Ast.If node -> {
                if (Values.truthy(eval(node.condition(), scope))) {
                    yield runNested(node.then(), scope.child());
                } else if (node.orElse() != null) {
                    yield runNested(node.orElse(), scope.child());
                } else {
                    yield Value.Nothing.INSTANCE;
                }
            }
            case Ast.For loop -> {
                Value iterable = eval(loop.iterable(), scope);
                List<Value> items = iterable instanceof Value.ListVal l ? l.items() : List.of(iterable);
                Scope body = scope.child();
                for (Value item : items) {
                    body.define(loop.variable(), item);
                    runNested(loop.body(), body);
                }
                yield Value.Nothing.INSTANCE;
            }
            case Ast.Run run -> {
                Value value = pipeline(run.pipeline(), scope);
                // Top-level results are shown; results inside a block are not.
                if (depth == 0) session.out().print(value);
                yield value;
            }
        };
    }

    /** Runs a program without printing anything, for commands that read source. */
    public Value evalQuiet(Ast.Program program, Scope scope) {
        return runNested(program, scope);
    }

    private Value runNested(Ast.Program program, Scope scope) {
        depth++;
        try {
            return run(program, scope);
        } finally {
            depth--;
        }
    }

    // ---- pipelines -------------------------------------------------------------------

    public Value pipeline(Ast.Pipeline pipeline, Scope scope) {
        Value carried = Value.Nothing.INSTANCE;
        List<Ast.Stage> stages = pipeline.stages();
        for (int i = 0; i < stages.size(); i++) {
            carried = stage(stages.get(i), carried, scope, i == stages.size() - 1);
        }
        return carried;
    }

    private Value stage(Ast.Stage stage, Value input, Scope scope, boolean last) {
        return switch (stage) {
            case Ast.ExprStage e -> eval(e.expr(), scope);
            case Ast.External x -> external(x, input, scope, last);
            case Ast.Command c -> command(c, input, scope);
        };
    }

    // ---- builtin invocation -----------------------------------------------------------

    private Value command(Ast.Command call, Value input, Scope scope) {
        Builtin builtin = registry.get(call.name());
        if (builtin == null) throw unknownCommand(call);

        if (wantsHelp(call)) {
            dev.mainframe.ui.Help.command(session.out(), builtin);
            return Value.Nothing.INSTANCE;
        }

        Args args = bind(builtin.signature(), call, input, scope);
        if (builtin instanceof Builtin.Planning planning) return applyPlanned(planning, args);
        return builtin.run(args);
    }

    private boolean wantsHelp(Ast.Command call) {
        for (Ast.Arg arg : call.args()) {
            if (arg instanceof Ast.FlagArg f && !f.shortForm() && f.name().equals("help")) return true;
        }
        return false;
    }

    private MfError unknownCommand(Ast.Command call) {
        MfError.Builder error = MfError.of("E301", "there is no command called " + call.name())
                .at(call.span());
        String closest = Suggest.closest(call.name(), registry.names());
        if (closest != null) error.hint("did you mean " + closest + "?");
        error.hint("run help to see every command, or ^" + call.name() + " to run the program of that name");
        return error.build();
    }

    /**
     * The guardrails. Anything that changes the world states its plan first, and
     * then either shows it, asks about it, or carries it out -- never silently.
     */
    private Value applyPlanned(Builtin.Planning builtin, Args args) {
        Signature signature = builtin.signature();
        Plan plan = builtin.plan(args);

        if (plan.isEmpty()) {
            session.out().note("nothing to " + signature.name() + " -- no changes made");
            for (String note : plan.notes()) session.out().note("  " + note);
            return Value.Nothing.INSTANCE;
        }

        if (session.dryRun() || args.flag("dry-run")) {
            session.out().plan(plan);
            return Value.Nothing.INSTANCE;
        }

        if (signature.effect() == Signature.Effect.DESTRUCTIVE
                && !args.flag("yes") && !session.assumeYes()) {
            if (!session.interactive()) {
                throw MfError.of("E302", signature.name() + " can lose data, so it will not run unattended")
                        .at(args.span())
                        .hint("add --yes once you are sure, or --dry-run to see the "
                                + (plan.size() == 1 ? "one thing" : plan.size() + " things") + " it would do")
                        .build();
            }
            session.out().info(session.out().yellow("about to ") + plan.summary() + ":");
            for (Plan.Step step : plan.steps()) session.out().note("  " + step.description());
            if (!session.confirm("go ahead?")) {
                session.out().note("cancelled -- nothing was changed");
                return Value.Nothing.INSTANCE;
            }
        }

        List<Value.Rec> done = new ArrayList<>(plan.size());
        for (Plan.Step step : plan.steps()) {
            try {
                step.action().run();
            } catch (IOException e) {
                throw MfError.of("E303", signature.name() + " stopped part way: " + e.getMessage())
                        .at(args.span())
                        .hint(done.size() + " of " + plan.size() + " step(s) had already finished")
                        .hint("the step that failed was: " + step.description())
                        .build();
            }
            done.add(Value.Rec.of("did", new Value.Str(step.description())));
        }
        for (String note : plan.notes()) session.out().note(note);
        return new Value.ListVal(List.copyOf(done));
    }

    // ---- argument binding --------------------------------------------------------------

    /** Checks an invocation against its signature. Nothing has run at this point. */
    private Args bind(Signature signature, Ast.Command call, Value input, Scope scope) {
        List<Value> positional = new ArrayList<>();
        List<Ast.Expr> unevaluated = new ArrayList<>();
        Map<String, Value> flags = new LinkedHashMap<>();
        int index = 0;

        for (Ast.Arg arg : call.args()) {
            if (arg instanceof Ast.FlagArg flag) {
                bindFlag(signature, flag, flags, scope);
                continue;
            }
            Ast.Positional given = (Ast.Positional) arg;
            Signature.Param param = signature.param(index);
            if (param == null) {
                throw MfError.of("E304", signature.name() + " takes "
                                + (signature.params().isEmpty() ? "no arguments"
                                : signature.params().size() + " argument(s), but got " + (index + 1)))
                        .at(given.span())
                        .hint("usage: " + signature.usage())
                        .hint("if this is part of one name, put quotes around it")
                        .build();
            }
            if (param.lazy()) {
                positional.add(null);
                unevaluated.add(given.value());
            } else {
                Value value = eval(given.value(), scope);
                if (!param.type().accepts(value)) {
                    throw MfError.of("E305", "the " + param.name() + " of " + signature.name()
                                    + " should be a " + param.type().display()
                                    + ", but this is a " + ValueType.of(value).display())
                            .at(given.span())
                            .hint("usage: " + signature.usage())
                            .build();
                }
                positional.add(value);
                unevaluated.add(null);
            }
            index++;
        }

        if (index < signature.requiredCount()) {
            Signature.Param missing = signature.params().get(index);
            throw MfError.of("E306", signature.name() + " needs " + missing.name()
                            + " (" + missing.description() + ")")
                    .at(call.span())
                    .hint("usage: " + signature.usage())
                    .build();
        }

        checkInput(signature, input, call.span());
        return new Args(signature, session, this, scope, input, positional, unevaluated, flags, call.span());
    }

    private void checkInput(Signature signature, Value input, Span span) {
        if (signature.input() == ValueType.NOTHING) return;  // the command ignores the pipe
        if (signature.input().accepts(input)) return;
        MfError.Builder error = MfError.of("E307", signature.name() + " expects a "
                        + signature.input().display() + " from the pipe, but got "
                        + (input instanceof Value.Nothing ? "nothing" : "a " + ValueType.of(input).display()))
                .at(span);
        if (input instanceof Value.Nothing) {
            error.hint("put something in front of it, e.g. ls | " + signature.name());
        } else {
            error.hint("check what the previous command produces with: ... | describe");
        }
        throw error.build();
    }

    private void bindFlag(Signature signature, Ast.FlagArg given, Map<String, Value> flags, Scope scope) {
        if (given.shortForm() && given.name().length() > 1) {
            // -ab is shorthand for -a -b, but only for switches.
            for (char c : given.name().toCharArray()) {
                Signature.Flag flag = signature.shortFlag(String.valueOf(c));
                if (flag == null || !flag.isSwitch()) throw unknownFlag(signature, given);
                flags.put(flag.name(), new Value.Bool(true));
            }
            return;
        }
        Signature.Flag flag = given.shortForm()
                ? signature.shortFlag(given.name())
                : signature.flag(given.name());
        if (flag == null) throw unknownFlag(signature, given);

        if (flag.isSwitch()) {
            if (given.value() != null) {
                throw MfError.of("E308", "--" + flag.name() + " is a switch, so it takes no value")
                        .at(given.span())
                        .hint("just write --" + flag.name())
                        .build();
            }
            flags.put(flag.name(), new Value.Bool(true));
            return;
        }
        if (given.value() == null) {
            throw MfError.of("E309", "--" + flag.name() + " needs a value")
                    .at(given.span())
                    .hint("write it like --" + flag.name() + "=" + example(flag.type()))
                    .build();
        }
        Value value = eval(given.value(), scope);
        if (!flag.type().accepts(value)) {
            throw MfError.of("E310", "--" + flag.name() + " should be a " + flag.type().display()
                            + ", but this is a " + ValueType.of(value).display())
                    .at(given.span())
                    .build();
        }
        // Repeating a value flag collects, so --skip=.git --skip=target both count.
        Value existing = flags.get(flag.name());
        if (existing instanceof Value.ListVal list) {
            List<Value> items = new ArrayList<>(list.items());
            items.add(value);
            flags.put(flag.name(), new Value.ListVal(List.copyOf(items)));
        } else if (existing != null) {
            flags.put(flag.name(), new Value.ListVal(List.of(existing, value)));
        } else {
            flags.put(flag.name(), value);
        }
    }

    private static String example(ValueType type) {
        return switch (type) {
            case INT, NUMBER -> "10";
            case SIZE -> "10mb";
            case PATH -> "./somewhere";
            case MIME -> "text/plain";
            default -> "value";
        };
    }

    private MfError unknownFlag(Signature signature, Ast.FlagArg given) {
        List<String> known = new ArrayList<>();
        for (Signature.Flag f : signature.flags()) known.add(f.name());
        MfError.Builder error = MfError.of("E311",
                        signature.name() + " has no " + (given.shortForm() ? "-" : "--") + given.name() + " flag")
                .at(given.span());
        String closest = Suggest.closest(given.name(), known);
        if (closest != null) error.hint("did you mean --" + closest + "?");
        error.hint(known.isEmpty()
                ? signature.name() + " takes no flags"
                : "it accepts: --" + String.join(", --", known));
        error.hint("run " + signature.name() + " --help to see them explained");
        return error.build();
    }

    // ---- expressions -------------------------------------------------------------------

    public Value eval(Ast.Expr expr, Scope scope) {
        return switch (expr) {
            case Ast.Lit lit -> lit.value();
            case Ast.Var var -> variable(var, scope);
            case Ast.Now _ -> new Value.Time(System.currentTimeMillis());
            case Ast.Word word -> word(word, scope);
            case Ast.ListLit list -> {
                List<Value> items = new ArrayList<>(list.items().size());
                for (Ast.Expr item : list.items()) items.add(eval(item, scope));
                yield new Value.ListVal(List.copyOf(items));
            }
            case Ast.RecordLit record -> {
                var fields = new LinkedHashMap<String, Value>();
                for (Ast.Entry entry : record.entries()) fields.put(entry.key(), eval(entry.value(), scope));
                yield new Value.Rec(fields);
            }
            case Ast.BlockLit block -> new Value.Block(block.body());
            case Ast.Sub sub -> pipeline(sub.pipeline(), scope);
            case Ast.Field field -> field(eval(field.target(), scope), field.name(), field.span());
            case Ast.At at -> index(eval(at.target(), scope), eval(at.index(), scope), at.span());
            case Ast.Unary unary -> unary(unary, scope);
            case Ast.Binary binary -> binary(binary, scope);
        };
    }

    /** Evaluates an expression with {@code row}'s columns in scope. */
    public Value evalInRow(Ast.Expr expr, Value.Rec row, Scope scope) {
        Value.Rec previous = currentRow;
        currentRow = row;
        try {
            return eval(expr, scope);
        } finally {
            currentRow = previous;
        }
    }

    /** Runs a block with {@code $it} bound to one item. */
    public Value runBlock(Value.Block block, Value item, Scope scope) {
        Scope inner = scope.child();
        inner.define("it", item);
        if (item instanceof Value.Rec rec) {
            Value.Rec previous = currentRow;
            currentRow = rec;
            try {
                return runNested(block.body(), inner);
            } finally {
                currentRow = previous;
            }
        }
        return runNested(block.body(), inner);
    }

    private Value variable(Ast.Var var, Scope scope) {
        Value value = scope.get(var.name());
        if (value != null) return value;
        // $env is built fresh each time, so it always shows the current state.
        if (var.name().equals("env")) return environmentRecord();
        MfError.Builder error = MfError.of("E312", "there is no variable called " + var.name())
                .at(var.span());
        String closest = Suggest.closest(var.name(), scope.names());
        if (closest != null) error.hint("did you mean $" + closest + "?");
        error.hint("make one with: let " + var.name() + " = ...");
        if (session.env().has(var.name())) {
            error.hint("the environment variable of that name is $env." + var.name());
        }
        return error.raise();
    }

    /** The live environment as a record, so $env.HOME reads the current value. */
    private Value.Rec environmentRecord() {
        var fields = new LinkedHashMap<String, Value>();
        for (String name : session.env().sortedNames()) {
            fields.put(name, new Value.Str(session.env().get(name)));
        }
        return new Value.Rec(fields);
    }

    /**
     * A bare word is a column name while a row expression is running, and plain
     * text everywhere else. Unknown columns are an error rather than a silent
     * fallback to text, because that is the mistake that costs an afternoon.
     */
    private Value word(Ast.Word word, Scope scope) {
        if (currentRow == null) return new Value.Str(word.text());
        Value direct = currentRow.get(word.text());
        if (direct != null) return direct;
        if (word.text().indexOf('.') > 0) {
            Value walked = currentRow;
            for (String part : word.text().split("\\.")) {
                if (!(walked instanceof Value.Rec rec) || rec.get(part) == null) { walked = null; break; }
                walked = rec.get(part);
            }
            if (walked != null) return walked;
        }
        MfError.Builder error = MfError.of("E313", "these rows have no column called " + word.text())
                .at(word.span());
        String closest = Suggest.closest(word.text(), currentRow.fields().keySet());
        if (closest != null) error.hint("did you mean " + closest + "?");
        error.hint("the columns here are: " + String.join(", ", currentRow.fields().keySet()));
        error.hint("if you meant the text \"" + word.text() + "\", put quotes around it");
        return error.raise();
    }

    private Value field(Value target, String name, Span span) {
        if (target instanceof Value.Rec rec) {
            Value value = rec.get(name);
            if (value != null) return value;
            MfError.Builder error = MfError.of("E314", "this record has no field called " + name).at(span);
            String closest = Suggest.closest(name, rec.fields().keySet());
            if (closest != null) error.hint("did you mean ." + closest + "?");
            error.hint("it has: " + String.join(", ", rec.fields().keySet()));
            return error.raise();
        }
        if (target instanceof Value.Mime mime) {
            return switch (name) {
                case "type" -> new Value.Str(mime.type());
                case "subtype" -> new Value.Str(mime.subtype());
                case "full" -> new Value.Str(mime.full());
                case "detected-by", "detectedBy" -> new Value.Str(mime.detectedBy());
                default -> throw MfError.of("E315", "a media type has no ." + name).at(span)
                        .hint("it has .type, .subtype, .full and .detected-by").build();
            };
        }
        if (target instanceof Value.ListVal list) {
            // Reaching for a field on a table means "that column, from every row".
            List<Value> column = new ArrayList<>(list.items().size());
            for (Value item : list.items()) column.add(field(item, name, span));
            return new Value.ListVal(List.copyOf(column));
        }
        throw MfError.of("E316", "a " + ValueType.of(target).display() + " has no fields to look inside")
                .at(span)
                .hint("only records, tables and media types have fields")
                .build();
    }

    private Value index(Value target, Value key, Span span) {
        if (target instanceof Value.Rec rec) return field(rec, Values.asString(key, span), span);
        if (target instanceof Value.ListVal list) {
            long i = Values.asLong(key, span);
            long at = i < 0 ? list.items().size() + i : i;
            if (at < 0 || at >= list.items().size()) {
                throw MfError.of("E317", "there is no item " + i + " -- the list holds "
                                + list.items().size())
                        .at(span)
                        .hint("items are numbered from 0, and -1 means the last one")
                        .build();
            }
            return list.items().get((int) at);
        }
        if (target instanceof Value.Str str) {
            long i = Values.asLong(key, span);
            if (i < 0 || i >= str.value().length()) {
                throw MfError.of("E317", "there is no character " + i + " in that text").at(span).build();
            }
            return new Value.Str(String.valueOf(str.value().charAt((int) i)));
        }
        throw MfError.of("E318", "a " + ValueType.of(target).display() + " cannot be indexed")
                .at(span).hint("lists, records and text can").build();
    }

    private Value unary(Ast.Unary unary, Scope scope) {
        Value operand = eval(unary.operand(), scope);
        if (unary.op().equals("not")) return new Value.Bool(!Values.truthy(operand));
        if (operand instanceof Value.Size size) return new Value.Size(-size.bytes());
        if (operand instanceof Value.Float f) return new Value.Float(-f.value());
        return new Value.Int(-Values.asLong(operand, unary.span()));
    }

    private Value binary(Ast.Binary binary, Scope scope) {
        String op = binary.op();
        if (op.equals("and")) {
            return new Value.Bool(Values.truthy(eval(binary.left(), scope))
                    && Values.truthy(eval(binary.right(), scope)));
        }
        if (op.equals("or")) {
            return new Value.Bool(Values.truthy(eval(binary.left(), scope))
                    || Values.truthy(eval(binary.right(), scope)));
        }
        Value left = eval(binary.left(), scope);
        Value right = eval(binary.right(), scope);
        Span span = binary.span();
        return switch (op) {
            case "==" -> new Value.Bool(Values.equal(left, right));
            case "!=" -> new Value.Bool(!Values.equal(left, right));
            case "<" -> new Value.Bool(Values.compare(left, right, span) < 0);
            case "<=" -> new Value.Bool(Values.compare(left, right, span) <= 0);
            case ">" -> new Value.Bool(Values.compare(left, right, span) > 0);
            case ">=" -> new Value.Bool(Values.compare(left, right, span) >= 0);
            case "=~" -> new Value.Bool(Values.matches(Values.asString(left, span), Values.asString(right, span)));
            case "!~" -> new Value.Bool(!Values.matches(Values.asString(left, span), Values.asString(right, span)));
            case "+", "-", "*", "/", "%" -> {
                if (left instanceof Value.Time || right instanceof Value.Time
                        || left instanceof Value.Duration || right instanceof Value.Duration) {
                    yield moments(op, left, right, span);
                }
                yield op.equals("+") ? add(left, right, span) : arithmetic(op, left, right, span);
            }
            default -> throw new IllegalStateException("unhandled operator " + op);
        };
    }

    /**
     * Arithmetic on moments and spans, where the units have to make sense: the
     * gap between two moments is a span, a moment plus a span is another moment,
     * and a moment plus a moment is nothing at all.
     */
    private Value moments(String op, Value left, Value right, Span span) {
        if (left instanceof Value.Time a && right instanceof Value.Time b) {
            if (op.equals("-")) return new Value.Duration(a.epochMillis() - b.epochMillis());
            throw badMoment(op, left, right, span, "two moments can only be subtracted, giving the span between them");
        }
        if (left instanceof Value.Time a && right instanceof Value.Duration b) {
            if (op.equals("+")) return new Value.Time(a.epochMillis() + b.millis());
            if (op.equals("-")) return new Value.Time(a.epochMillis() - b.millis());
            throw badMoment(op, left, right, span, "a span can be added to or subtracted from a moment");
        }
        if (left instanceof Value.Duration a && right instanceof Value.Time b) {
            if (op.equals("+")) return new Value.Time(a.millis() + b.epochMillis());
            throw badMoment(op, left, right, span, "write it the other way round: a moment plus a span");
        }
        if (left instanceof Value.Duration a && right instanceof Value.Duration b) {
            return switch (op) {
                case "+" -> new Value.Duration(a.millis() + b.millis());
                case "-" -> new Value.Duration(a.millis() - b.millis());
                case "%" -> new Value.Duration(a.millis() % b.millis());
                // How many of one span fit in the other: a plain number, not a span.
                case "/" -> b.millis() == 0
                        ? throwDivideByZero(span)
                        : new Value.Float((double) a.millis() / b.millis());
                default -> throw badMoment(op, left, right, span, "two spans cannot be multiplied together");
            };
        }
        if (left instanceof Value.Duration a && Values.isNumeric(right)) {
            double factor = Values.asDouble(right, span);
            return switch (op) {
                case "*" -> new Value.Duration((long) (a.millis() * factor));
                case "/" -> factor == 0
                        ? throwDivideByZero(span)
                        : new Value.Duration((long) (a.millis() / factor));
                default -> throw badMoment(op, left, right, span,
                        "a span can be multiplied or divided by a number");
            };
        }
        if (Values.isNumeric(left) && right instanceof Value.Duration b && op.equals("*")) {
            return new Value.Duration((long) (Values.asDouble(left, span) * b.millis()));
        }
        throw badMoment(op, left, right, span, left instanceof Value.Time || right instanceof Value.Time
                ? "spans of time are written with a unit: 7d, 2h, 90m"
                : "check the units on both sides");
    }

    private Value throwDivideByZero(Span span) {
        throw MfError.of("E321", "cannot divide by zero").at(span)
                .hint("check the right-hand side before dividing").build();
    }

    private MfError badMoment(String op, Value left, Value right, Span span, String hint) {
        return MfError.of("E326", "cannot work out a " + ValueType.of(left).display() + " " + op + " a "
                        + ValueType.of(right).display())
                .at(span)
                .hint(hint)
                .build();
    }

    private Value add(Value left, Value right, Span span) {
        if (left instanceof Value.Str a && right instanceof Value.Str b) {
            return new Value.Str(a.value() + b.value());
        }
        if (left instanceof Value.ListVal a && right instanceof Value.ListVal b) {
            List<Value> items = new ArrayList<>(a.items());
            items.addAll(b.items());
            return new Value.ListVal(List.copyOf(items));
        }
        if (left instanceof Value.Rec a && right instanceof Value.Rec b) {
            var merged = new LinkedHashMap<>(a.fields());
            merged.putAll(b.fields());
            return new Value.Rec(merged);
        }
        if (Values.isNumeric(left) && Values.isNumeric(right)) return arithmetic("+", left, right, span);
        throw MfError.of("E319", "cannot add a " + ValueType.of(left).display()
                        + " to a " + ValueType.of(right).display())
                .at(span)
                .hint("+ joins two numbers, two pieces of text, two lists or two records")
                .build();
    }

    private Value arithmetic(String op, Value left, Value right, Span span) {
        if (!Values.isNumeric(left) || !Values.isNumeric(right)) {
            throw MfError.of("E320", op + " needs numbers, but got a " + ValueType.of(left).display()
                            + " and a " + ValueType.of(right).display())
                    .at(span).build();
        }
        boolean sized = left instanceof Value.Size || right instanceof Value.Size;
        boolean exact = !(left instanceof Value.Float) && !(right instanceof Value.Float);
        double a = Values.asDouble(left, span);
        double b = Values.asDouble(right, span);
        if ((op.equals("/") || op.equals("%")) && b == 0) {
            throw MfError.of("E321", "cannot divide by zero").at(span)
                    .hint("check the right-hand side before dividing").build();
        }
        double result = switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            default -> throw new IllegalStateException(op);
        };
        if (exact && result == Math.rint(result)) {
            // Dividing sizes gives a plain ratio; adding them keeps the unit.
            boolean keepUnit = sized && !op.equals("/");
            return keepUnit ? new Value.Size((long) result) : new Value.Int((long) result);
        }
        return new Value.Float(result);
    }

    // ---- external programs --------------------------------------------------------------

    /**
     * Runs an external program, written with a leading caret so it is always
     * obvious that MainFrame is not in charge of what happens next.
     */
    private Value external(Ast.External call, Value input, Scope scope, boolean last) {
        List<String> command = new ArrayList<>();
        command.add(resolveProgram(call));
        for (Ast.Arg arg : call.args()) {
            if (arg instanceof Ast.FlagArg flag) {
                String dashes = flag.shortForm() ? "-" : "--";
                if (flag.value() == null) {
                    command.add(dashes + flag.name());
                } else {
                    command.add(dashes + flag.name() + "=" + Values.asString(eval(flag.value(), scope), flag.span()));
                }
            } else {
                command.add(Values.asString(eval(((Ast.Positional) arg).value(), scope), arg.span()));
            }
        }

        boolean handOver = last && input instanceof Value.Nothing && session.interactive();
        ProcessBuilder builder = new ProcessBuilder(command).directory(session.cwd().toFile());
        // The child gets the environment as it stands right now, not the one this
        // process was started with, so env-set and path-add take effect at once.
        builder.environment().clear();
        builder.environment().putAll(session.env().all());
        try {
            if (handOver) {
                builder.inheritIO();
                Process process = builder.start();
                int code = process.waitFor();
                if (code != 0) session.out().warn(call.name() + " exited with code " + code);
                return Value.Nothing.INSTANCE;
            }
            builder.redirectErrorStream(false);
            Process process = builder.start();
            if (!(input instanceof Value.Nothing)) {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(textOf(input).getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                MfError.Builder error = MfError.of("E322", call.name() + " failed with exit code " + code)
                        .at(call.span());
                if (!stderr.isBlank()) error.hint(stderr.strip().lines().findFirst().orElse(""));
                error.hint("MainFrame does not guess what a failing program meant, so the pipeline stops here");
                throw error.build();
            }
            if (!stderr.isBlank()) session.out().warn(stderr.strip());
            return new Value.Str(stdout.stripTrailing());
        } catch (IOException e) {
            throw MfError.of("E323", "could not start " + call.name() + ": " + e.getMessage())
                    .at(call.span())
                    .hint("check the name and that it is on your PATH")
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw MfError.of("E324", call.name() + " was interrupted").at(call.span()).build();
        }
    }

    /**
     * Finds the program to run using MainFrame's own PATH.
     *
     * <p>The operating system would search the PATH this process was started
     * with, which would quietly ignore anything {@code path-add} did. Resolving
     * it here is what makes an edited PATH real, and it turns "not found" into an
     * error that says where MainFrame looked.
     */
    private String resolveProgram(Ast.External call) {
        String name = call.name();
        boolean spelledOut = name.contains("/") || name.contains("\\")
                || (name.length() > 1 && name.charAt(1) == ':');
        if (spelledOut) return session.resolve(name).toString();
        java.nio.file.Path found = session.env().findProgram(name);
        if (found != null) return found.toString();
        throw MfError.of("E325", "there is no program called " + name + " on your PATH")
                .at(call.span())
                .hint("check the spelling, or run path to see the " + session.env().pathEntries().size()
                        + " place(s) MainFrame looked")
                .hint("add somewhere to look with: path-add <directory>")
                .hint("if it is a MainFrame command, drop the ^")
                .build();
    }

    /** The text a value becomes when it is handed to an external program. */
    private String textOf(Value value) {
        if (value instanceof Value.ListVal list) {
            StringBuilder sb = new StringBuilder();
            for (Value item : list.items()) sb.append(Values.display(item)).append('\n');
            return sb.toString();
        }
        return Values.display(value);
    }

    /** Reads and runs a script file. */
    public Value runFile(java.nio.file.Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String previous = session.source();
        session.source(text);
        try {
            return run(dev.mainframe.lang.Parser.parse(text));
        } finally {
            session.source(previous);
        }
    }
}
