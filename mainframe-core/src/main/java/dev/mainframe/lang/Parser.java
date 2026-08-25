package dev.mainframe.lang;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.MfError;
import dev.mainframe.Span;
import dev.mainframe.value.Times;
import dev.mainframe.value.Value;

/**
 * Builds an {@link Ast} from tokens.
 *
 * <p>The parser is deliberately boring: there is exactly one way to write each
 * construct, and anything it cannot read becomes an error with a suggestion
 * rather than a guess.
 */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) { this.tokens = tokens; }

    public static Ast.Program parse(String source) {
        return new Parser(Lexer.tokenize(source)).program(TokenType.EOF);
    }

    // ---- program and statements -----------------------------------------------------

    private Ast.Program program(TokenType end) {
        Span start = peek().span();
        List<Ast.Stmt> statements = new ArrayList<>();
        skipSeparators();
        while (!check(end) && !check(TokenType.EOF)) {
            statements.add(statement());
            if (!check(end) && !check(TokenType.EOF)) expectSeparator();
            skipSeparators();
        }
        return new Ast.Program(List.copyOf(statements), start);
    }

    private Ast.Stmt statement() {
        if (check(TokenType.LET)) return letStatement();
        if (check(TokenType.IF)) return ifStatement();
        if (check(TokenType.FOR)) return forStatement();
        if (check(TokenType.IDENT) && peek(1).type() == TokenType.ASSIGN) {
            throw MfError.of("E110", "variables are introduced with let")
                    .at(peek().span())
                    .hint("write it like: let " + peek().text() + " = ...")
                    .build();
        }
        return new Ast.Run(pipeline());
    }

    private Ast.Stmt letStatement() {
        Span start = advance().span();
        Token name = expect(TokenType.IDENT, "a name for the variable",
                "write it like: let count = 3");
        expect(TokenType.ASSIGN, "an = sign", "write it like: let count = 3");
        Ast.Pipeline value = pipeline();
        return new Ast.Let(name.text(), value, start.through(value.span()));
    }

    private Ast.Stmt ifStatement() {
        Span start = advance().span();
        Ast.Expr condition = expression();
        Ast.Program then = block();
        Ast.Program orElse = null;
        if (check(TokenType.ELSE)) {
            advance();
            if (check(TokenType.IF)) {
                Ast.Stmt nested = ifStatement();
                orElse = new Ast.Program(List.of(nested), nested.span());
            } else {
                orElse = block();
            }
        }
        return new Ast.If(condition, then, orElse, start);
    }

    private Ast.Stmt forStatement() {
        Span start = advance().span();
        Token name = expect(TokenType.IDENT, "a name for each item",
                "write it like: for file in $files { ... }");
        expect(TokenType.IN, "the word in", "write it like: for file in $files { ... }");
        Ast.Expr iterable = expression();
        Ast.Program body = block();
        return new Ast.For(name.text(), iterable, body, start);
    }

    private Ast.Program block() {
        expect(TokenType.LBRACE, "a { to open the block", "blocks look like: { ls | length }");
        Ast.Program body = program(TokenType.RBRACE);
        expect(TokenType.RBRACE, "a } to close the block", "every { needs a matching }");
        return body;
    }

    // ---- pipelines ------------------------------------------------------------------

    private Ast.Pipeline pipeline() {
        List<Ast.Stage> stages = new ArrayList<>();
        Span start = peek().span();
        stages.add(stage());
        while (check(TokenType.PIPE)) {
            advance();
            skipNewlines();  // a pipeline may breathe across lines after the pipe
            stages.add(stage());
        }
        return new Ast.Pipeline(List.copyOf(stages), start.through(stages.getLast().span()));
    }

    private Ast.Stage stage() {
        if (check(TokenType.PIPE)) {
            throw MfError.of("E111", "a pipeline cannot start with a |")
                    .at(peek().span())
                    .hint("in the shell, end the previous line with | to carry it on")
                    .hint("in a script you can start a line with | -- but the first stage still needs a command")
                    .build();
        }
        if (check(TokenType.CARET)) {
            Span caret = advance().span();
            Token name = peek();
            if (name.type() != TokenType.IDENT && name.type() != TokenType.BAREWORD
                    && name.type() != TokenType.STRING) {
                throw MfError.of("E101", "a ^ needs the name of a program after it")
                        .at(caret).hint("for example: ^git status").build();
            }
            advance();
            return new Ast.External(name.text(), args(), caret.through(name.span()));
        }
        if (startsCommand()) {
            Token name = advance();
            return new Ast.Command(name.text(), args(), name.span());
        }
        return new Ast.ExprStage(expression());
    }

    /** An identifier starts a command unless the next token makes it part of an expression. */
    private boolean startsCommand() {
        if (!check(TokenType.IDENT)) return false;
        return switch (peek(1).type()) {
            case EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ, MATCH, NOT_MATCH,
                 PLUS, MINUS, SLASH, PERCENT, AND, OR, ASSIGN, COLON -> false;
            default -> true;
        };
    }

    private List<Ast.Arg> args() {
        List<Ast.Arg> args = new ArrayList<>();
        while (true) {
            if (check(TokenType.FLAG_LONG) || check(TokenType.FLAG_SHORT)) {
                Token flag = advance();
                Ast.Expr value = null;
                if (check(TokenType.ASSIGN)) {
                    advance();
                    value = expression();
                }
                args.add(new Ast.FlagArg(flag.text(), flag.type() == TokenType.FLAG_SHORT, value, flag.span()));
                continue;
            }
            if (!startsExpression()) return List.copyOf(args);
            args.add(new Ast.Positional(expression()));
        }
    }

    // ---- expressions ----------------------------------------------------------------

    private Ast.Expr expression() { return or(); }

    private Ast.Expr or() {
        Ast.Expr left = and();
        while (check(TokenType.OR)) {
            Token op = advance();
            Ast.Expr right = and();
            left = new Ast.Binary("or", left, right, op.span());
        }
        return left;
    }

    private Ast.Expr and() {
        Ast.Expr left = comparison();
        while (check(TokenType.AND)) {
            Token op = advance();
            Ast.Expr right = comparison();
            left = new Ast.Binary("and", left, right, op.span());
        }
        return left;
    }

    private Ast.Expr comparison() {
        Ast.Expr left = additive();
        if (isComparison(peek().type())) {
            Token op = advance();
            Ast.Expr right = additive();
            if (isComparison(peek().type())) {
                throw MfError.of("E102", "two comparisons in a row are ambiguous")
                        .at(peek().span())
                        .hint("write it as: a < b and b < c")
                        .build();
            }
            return new Ast.Binary(op.text(), left, right, op.span());
        }
        return left;
    }

    private static boolean isComparison(TokenType t) {
        return switch (t) {
            case EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ, MATCH, NOT_MATCH -> true;
            default -> false;
        };
    }

    private Ast.Expr additive() {
        Ast.Expr left = multiplicative();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            Ast.Expr right = multiplicative();
            left = new Ast.Binary(op.text(), left, right, op.span());
        }
        return left;
    }

    private Ast.Expr multiplicative() {
        Ast.Expr left = unary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            Ast.Expr right = unary();
            left = new Ast.Binary(op.text(), left, right, op.span());
        }
        return left;
    }

    private Ast.Expr unary() {
        if (check(TokenType.NOT)) {
            Token op = advance();
            return new Ast.Unary("not", unary(), op.span());
        }
        if (check(TokenType.MINUS)) {
            Token op = advance();
            return new Ast.Unary("-", unary(), op.span());
        }
        return postfix();
    }

    private Ast.Expr postfix() {
        Ast.Expr target = primary();
        while (true) {
            if (check(TokenType.DOT)) {
                Span dot = advance().span();
                Token name = peek();
                if (name.type() != TokenType.IDENT && name.type() != TokenType.INT) {
                    throw MfError.of("E103", "a . needs the name of a field after it")
                            .at(dot).hint("for example: $row.name").build();
                }
                advance();
                target = new Ast.Field(target, name.text(), dot.through(name.span()));
                continue;
            }
            if (check(TokenType.LBRACKET)) {
                Span open = advance().span();
                Ast.Expr index = expression();
                expect(TokenType.RBRACKET, "a ] to close the index", "for example: $rows[0]");
                target = new Ast.At(target, index, open);
                continue;
            }
            return target;
        }
    }

    private Ast.Expr primary() {
        Token t = peek();
        switch (t.type()) {
            case INT -> { advance(); return new Ast.Lit(new Value.Int(Long.parseLong(t.text())), t.span()); }
            case FLOAT -> { advance(); return new Ast.Lit(new Value.Float(Double.parseDouble(t.text())), t.span()); }
            case SIZE -> { advance(); return new Ast.Lit(size(t), t.span()); }
            case DURATION -> { advance(); return new Ast.Lit(duration(t), t.span()); }
            case DATETIME -> { advance(); return new Ast.Lit(datetime(t), t.span()); }
            case TAG -> { return tagged(); }
            case STRING -> { advance(); return new Ast.Lit(new Value.Str(t.text()), t.span()); }
            case TRUE -> { advance(); return new Ast.Lit(new Value.Bool(true), t.span()); }
            case FALSE -> { advance(); return new Ast.Lit(new Value.Bool(false), t.span()); }
            case NOTHING -> { advance(); return new Ast.Lit(Value.Nothing.INSTANCE, t.span()); }
            case NOW -> { advance(); return new Ast.Now(t.span()); }
            case VAR -> { advance(); return new Ast.Var(t.text(), t.span()); }
            case IDENT, BAREWORD -> { advance(); return new Ast.Word(t.text(), t.span()); }
            case STAR -> { advance(); return new Ast.Word("*", t.span()); }
            case LPAREN -> {
                advance();
                skipNewlines();
                Ast.Pipeline inner = pipeline();
                skipNewlines();
                expect(TokenType.RPAREN, "a ) to close the group", "every ( needs a matching )");
                return new Ast.Sub(inner, t.span());
            }
            case LBRACKET -> { return listLiteral(); }
            case LBRACE -> { return recordOrBlock(); }
            default -> throw MfError.of("E104", "I expected a value here, but found " + describe(t))
                    .at(t.span())
                    .hint("values look like: 42, 1.5mb, \"some text\", ./a/path, [1 2 3] or {name: \"x\"}")
                    .build();
        }
    }

    private Value size(Token t) {
        int split = unitStart(t.text());
        double n = Double.parseDouble(t.text().substring(0, split));
        Long unit = Lexer.sizeUnit(t.text().substring(split));
        return new Value.Size((long) (n * unit));
    }

    private Value duration(Token t) {
        int split = unitStart(t.text());
        double n = Double.parseDouble(t.text().substring(0, split));
        Long unit = Times.durationUnit(t.text().substring(split));
        return new Value.Duration((long) (n * unit));
    }

    private static int unitStart(String text) {
        int split = 0;
        while (split < text.length() && !Character.isLetter(text.charAt(split))) split++;
        return split;
    }

    private Value datetime(Token t) {
        try {
            return new Value.Time(Times.parse(t.text()));
        } catch (IllegalArgumentException e) {
            throw MfError.of("E112", e.getMessage())
                    .at(t.span())
                    .hint("times look like 2026-08-21, 2026-08-21T14:30, or 2026-08-21T14:30:00.000-04:00")
                    .hint("without an offset it means your local time")
                    .build();
        }
    }

    /**
     * A type-tagged text literal: {@code path"./src"}, {@code mime"text/plain"}.
     * These exist so that every kind of value has a written form, which is what
     * lets a table be saved to a file and read back as itself.
     */
    private Ast.Expr tagged() {
        Token tag = advance();
        if (!check(TokenType.STRING)) {
            throw MfError.of("E113", tag.text() + " must be followed directly by quoted text")
                    .at(tag.span())
                    .hint("for example: " + tag.text() + "\"some value\"")
                    .build();
        }
        Token text = advance();
        Span span = tag.span().through(text.span());
        Value value = switch (tag.text()) {
            case "path" -> new Value.PathVal(java.nio.file.Path.of(text.text()));
            case "mime" -> mime(text, span);
            case "time" -> datetime(new Token(TokenType.DATETIME, text.text(), span));
            case "size" -> new Value.Size(Long.parseLong(text.text()));
            case "duration" -> new Value.Duration(Long.parseLong(text.text()));
            default -> throw MfError.of("E114", "there is no \"" + tag.text() + "\" kind of value")
                    .at(tag.span())
                    .hint("the tagged forms are path, mime, time, size and duration")
                    .hint("if you meant a command followed by text, put a space between them")
                    .build();
        };
        return new Ast.Lit(value, span);
    }

    private Value mime(Token text, Span span) {
        int slash = text.text().indexOf('/');
        if (slash <= 0 || slash == text.text().length() - 1) {
            throw MfError.of("E115", "\"" + text.text() + "\" is not a media type")
                    .at(span)
                    .hint("media types look like text/plain or image/png")
                    .build();
        }
        return new Value.Mime(text.text().substring(0, slash), text.text().substring(slash + 1), "written");
    }

    private Ast.Expr listLiteral() {
        Span open = advance().span();
        List<Ast.Expr> items = new ArrayList<>();
        skipNewlines();
        while (!check(TokenType.RBRACKET)) {
            if (check(TokenType.EOF)) {
                throw MfError.of("E105", "this list is missing its ]").at(open)
                        .hint("lists look like: [1 2 3] or [\"a\", \"b\"]").build();
            }
            items.add(expression());
            if (check(TokenType.COMMA)) advance();
            skipNewlines();
        }
        advance();
        return new Ast.ListLit(List.copyOf(items), open);
    }

    private Ast.Expr recordOrBlock() {
        Span open = peek().span();
        if (looksLikeRecord()) {
            advance();
            List<Ast.Entry> entries = new ArrayList<>();
            skipNewlines();
            while (!check(TokenType.RBRACE)) {
                if (check(TokenType.EOF)) {
                    throw MfError.of("E106", "this record is missing its }").at(open)
                            .hint("records look like: {name: \"x\", size: 3}").build();
                }
                Token key = peek();
                if (key.type() != TokenType.IDENT && key.type() != TokenType.STRING) {
                    throw MfError.of("E107", "a record field needs a name, but I found " + describe(key))
                            .at(key.span()).hint("records look like: {name: \"x\"}").build();
                }
                advance();
                expect(TokenType.COLON, "a : after the field name", "records look like: {name: \"x\"}");
                entries.add(new Ast.Entry(key.text(), expression()));
                if (check(TokenType.COMMA)) advance();
                skipNewlines();
            }
            advance();
            return new Ast.RecordLit(List.copyOf(entries), open);
        }
        return new Ast.BlockLit(block(), open);
    }

    private boolean looksLikeRecord() {
        if (!check(TokenType.LBRACE)) return false;
        if (peek(1).type() == TokenType.RBRACE) return true;
        boolean namedKey = peek(1).type() == TokenType.IDENT || peek(1).type() == TokenType.STRING;
        return namedKey && peek(2).type() == TokenType.COLON;
    }

    // ---- token helpers ---------------------------------------------------------------

    private boolean startsExpression() {
        return switch (peek().type()) {
            case INT, FLOAT, SIZE, DURATION, DATETIME, TAG, STRING, TRUE, FALSE, NOTHING, NOW, VAR,
                 IDENT, BAREWORD, LPAREN, LBRACKET, LBRACE, STAR, MINUS, NOT -> true;
            default -> false;
        };
    }

    private void skipNewlines() {
        while (check(TokenType.NEWLINE)) advance();
    }

    private void skipSeparators() {
        while (check(TokenType.NEWLINE) || check(TokenType.SEMI)) advance();
    }

    private void expectSeparator() {
        if (check(TokenType.NEWLINE) || check(TokenType.SEMI) || check(TokenType.EOF)) return;
        throw MfError.of("E108", "I did not expect " + describe(peek()) + " here")
                .at(peek().span())
                .hint("put each command on its own line, or separate them with ;")
                .build();
    }

    private Token peek() { return peek(0); }

    private Token peek(int ahead) {
        int i = Math.min(pos + ahead, tokens.size() - 1);
        return tokens.get(i);
    }

    private boolean check(TokenType type) { return peek().type() == type; }

    private Token advance() {
        Token t = peek();
        if (pos < tokens.size() - 1) pos++;
        return t;
    }

    private Token expect(TokenType type, String what, String hint) {
        if (check(type)) return advance();
        throw MfError.of("E109", "I expected " + what + ", but found " + describe(peek()))
                .at(peek().span()).hint(hint).build();
    }

    private static String describe(Token t) {
        return switch (t.type()) {
            case EOF -> "the end of the line";
            case NEWLINE -> "the end of the line";
            case STRING -> "the text \"" + t.text() + "\"";
            case FLAG_LONG -> "the flag --" + t.text();
            case FLAG_SHORT -> "the flag -" + t.text();
            case VAR -> "the variable $" + t.text();
            default -> "\"" + t.text() + "\"";
        };
    }
}
