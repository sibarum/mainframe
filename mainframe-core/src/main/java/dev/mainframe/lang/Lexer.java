package dev.mainframe.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.mainframe.MfError;
import dev.mainframe.Span;

/**
 * Turns source text into tokens.
 *
 * <p>Two rules keep MainFrame unambiguous, and both are enforced here rather
 * than guessed at later:
 * <ul>
 *   <li>Binary operators must have space around them. {@code a - b} subtracts;
 *       {@code a-b} is a single name (which is why {@code sort-by} works).
 *   <li>Nothing is ever split on whitespace after the fact. A quoted string or a
 *       bareword is exactly one argument, always.
 * </ul>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("let", TokenType.LET),
            Map.entry("if", TokenType.IF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("for", TokenType.FOR),
            Map.entry("in", TokenType.IN),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("nothing", TokenType.NOTHING),
            // JSON writes null. Reading it as anything but nothing would be a
            // silent mis-parse, which is the one thing this language will not do.
            Map.entry("null", TokenType.NOTHING),
            Map.entry("now", TokenType.NOW),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT));

    /** Units accepted as a suffix on a number literal, e.g. 10mb. */
    private static final Map<String, Long> SIZE_UNITS = Map.ofEntries(
            Map.entry("b", 1L),
            Map.entry("kb", 1024L),
            Map.entry("mb", 1024L * 1024),
            Map.entry("gb", 1024L * 1024 * 1024),
            Map.entry("tb", 1024L * 1024 * 1024 * 1024));

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;
    private final List<Token> out = new ArrayList<>();

    private Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src).run();
    }

    /** How many bytes a unit suffix multiplies by, or null if it is not a unit. */
    public static Long sizeUnit(String suffix) { return SIZE_UNITS.get(suffix.toLowerCase()); }

    private List<Token> run() {
        while (!eof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') { advance(); continue; }
            if (c == '#') { while (!eof() && peek() != '\n') advance(); continue; }
            if (c == '\n') { add(TokenType.NEWLINE, "\\n", mark(), 1); advance(); continue; }
            // A caret stage is not read as MainFrame at all: the program's name, and
            // then the rest of the line exactly as it was typed. This is the one place
            // the language stops interpreting, and it is what lets a program own its
            // own argument syntax.
            if (atCommandPosition() && c == '^') { external(); continue; }
            if (c == '"' || c == '\'') { string(c); continue; }
            if (Character.isDigit(c) && datetimeAhead(pos)) { datetime(); continue; }
            if (Character.isDigit(c)) { number(false); continue; }
            if (isIdentStart(c)) { word(); continue; }
            if (c == '$') { variable(); continue; }
            if (c == '.' && fieldAccessFollows()) { add(TokenType.DOT, ".", mark(), 1); advance(); continue; }
            // * and / lead a path or glob when something follows them immediately;
            // with space on both sides they are arithmetic. Same rule as for -.
            if ((c == '*' || c == '/') && spaceFollows()) { operator(); continue; }
            if (startsBareword(c)) { bareword(mark(), new StringBuilder()); continue; }
            operator();
        }
        add(TokenType.EOF, "", mark(), 0);
        return out;
    }

    // ---- pieces ------------------------------------------------------------------

    private void string(char quote) {
        Span start = mark();
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof() || peek() == '\n') {
                throw MfError.of("E001", "this text is missing its closing " + quote)
                        .at(start)
                        .hint("add a " + quote + " at the end of the text")
                        .build();
            }
            char c = peek();
            if (c == quote) { advance(); break; }
            if (c == '\\' && quote == '"') {
                advance();
                char e = eof() ? '\\' : peek();
                advance();
                // JSON's escape set, so that any JSON string is read the same way
                // here as it is by the JSON reader.
                if (e == 'u') {
                    if (pos + 4 > src.length()) {
                        throw MfError.of("E008", "a \\u escape needs four hex digits after it")
                                .at(start).hint("for example \\u0041 is the letter A").build();
                    }
                    String hex = src.substring(pos, pos + 4);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException bad) {
                        throw MfError.of("E008", "\"" + hex + "\" is not four hex digits")
                                .at(start).hint("a \\u escape looks like \\u0041").build();
                    }
                    for (int i = 0; i < 4; i++) advance();
                    continue;
                }
                sb.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case '0' -> '\0';
                    default -> e;
                });
                continue;
            }
            sb.append(c);
            advance();
        }
        add(TokenType.STRING, sb.toString(), start, 0);
    }

    private void number(boolean negative) {
        Span start = mark();
        StringBuilder sb = new StringBuilder(negative ? "-" : "");
        boolean isFloat = false;
        while (!eof() && (Character.isDigit(peek()) || peek() == '_')) {
            if (peek() != '_') sb.append(peek());
            advance();
        }
        if (!eof() && peek() == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            isFloat = true;
            sb.append('.');
            advance();
            while (!eof() && (Character.isDigit(peek()) || peek() == '_')) {
                if (peek() != '_') sb.append(peek());
                advance();
            }
        }
        // An exponent, because JSON numbers have them and JSON has to read here.
        // Checked before the unit suffix so that 1e5 is a number, not a bad unit.
        if (!eof() && (peek() == 'e' || peek() == 'E') && exponentFollows()) {
            isFloat = true;
            sb.append('e');
            advance();
            if (peek() == '+' || peek() == '-') { sb.append(peek()); advance(); }
            while (!eof() && Character.isDigit(peek())) { sb.append(peek()); advance(); }
            rejectGluedOperator(start);
            add(TokenType.FLOAT, sb.toString(), start, 0);
            return;
        }
        // A unit suffix, e.g. 10mb, makes this a size rather than a plain number.
        StringBuilder suffix = new StringBuilder();
        while (!eof() && Character.isLetter(peek())) { suffix.append(peek()); advance(); }
        if (suffix.isEmpty()) {
            rejectGluedOperator(start);
            add(isFloat ? TokenType.FLOAT : TokenType.INT, sb.toString(), start, 0);
            return;
        }
        String unit = suffix.toString().toLowerCase();
        if (sizeUnit(unit) != null) {
            rejectGluedOperator(start);
            add(TokenType.SIZE, sb + unit, start, 0);
            return;
        }
        if (dev.mainframe.value.Times.durationUnit(unit) != null) {
            rejectGluedOperator(start);
            add(TokenType.DURATION, sb + unit, start, 0);
            return;
        }
        throw MfError.of("E002", "\"" + suffix + "\" is not a unit I know")
                .at(start)
                .hint("sizes take b, kb, mb, gb and tb")
                .hint("spans of time take " + dev.mainframe.value.Times.durationUnits())
                .hint("or put a space in if you meant two things")
                .build();
    }

    /**
     * True when a written time starts here: four digits, a dash, two digits, a
     * dash, two digits. That shape is otherwise an error -- the spacing rule
     * rejects 2026-08-21 as glued arithmetic -- so reading it as a time takes
     * nothing away.
     */
    private boolean datetimeAhead(int at) {
        return digits(at, 4) && charAt(at + 4) == '-' && digits(at + 5, 2)
                && charAt(at + 7) == '-' && digits(at + 8, 2);
    }

    /** True when an e is the start of an exponent rather than the start of a unit. */
    private boolean exponentFollows() {
        char next = charAt(pos + 1);
        if (Character.isDigit(next)) return true;
        return (next == '+' || next == '-') && Character.isDigit(charAt(pos + 2));
    }

    private boolean digits(int at, int howMany) {
        for (int i = 0; i < howMany; i++) {
            if (!Character.isDigit(charAt(at + i))) return false;
        }
        return true;
    }

    private char charAt(int at) {
        return at < src.length() ? src.charAt(at) : '\0';
    }

    /**
     * A written time: a date, optionally a clock time, optionally an offset. The
     * exact form {@link dev.mainframe.value.Times#machine} writes, so a time can
     * be saved to a file and read back unchanged.
     */
    private void datetime() {
        Span start = mark();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) { sb.append(peek()); advance(); }   // yyyy-MM-dd
        if (!eof() && (peek() == 'T' || peek() == 't')) {
            sb.append('T');
            advance();
            while (!eof() && (Character.isDigit(peek()) || peek() == ':' || peek() == '.')) {
                sb.append(peek());
                advance();
            }
            if (!eof() && (peek() == 'Z' || peek() == 'z')) {
                sb.append('Z');
                advance();
            } else if (!eof() && (peek() == '+' || peek() == '-') && digits(pos + 1, 2)) {
                sb.append(peek());
                advance();
                while (!eof() && (Character.isDigit(peek()) || peek() == ':')) {
                    sb.append(peek());
                    advance();
                }
            }
        }
        add(TokenType.DATETIME, sb.toString(), start, 0);
    }

    /** Catches 5-3 and 5+3, which look like arithmetic but read as one token. */
    private void rejectGluedOperator(Span start) {
        if (eof()) return;
        char c = peek();
        if (c == '-' || c == '+' || c == '*' || c == '/' || c == '%') {
            throw MfError.of("E003", "put spaces around the " + c)
                    .at(start)
                    .hint("MainFrame needs \"a " + c + " b\", not \"a" + c + "b\", so that names like sort-by stay readable")
                    .build();
        }
    }

    private void word() {
        Span start = mark();
        StringBuilder sb = new StringBuilder();
        while (!eof() && isIdentPart(peek())) {
            if (peek() == '-' && !(pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1)))) break;
            sb.append(peek());
            advance();
        }
        // A name glued to a quote tags the text with a type: path"./src", mime"text/plain".
        // That gives the types with no literal shape of their own a written form,
        // so every value can be saved and read back as itself.
        if (!eof() && peek() == '"') {
            add(TokenType.TAG, sb.toString(), start, 0);
            return;
        }
        // src/main or notes.txt: an identifier glued to a path character is a bareword.
        if (!eof() && startsBareword(peek()) && !(peek() == '*' && sb.isEmpty())) {
            bareword(start, sb);
            return;
        }
        String text = sb.toString();
        TokenType kw = KEYWORDS.get(text);
        add(kw != null ? kw : TokenType.IDENT, text, start, 0);
    }

    private void variable() {
        Span start = mark();
        advance();
        StringBuilder sb = new StringBuilder();
        while (!eof() && isIdentPart(peek())) { sb.append(peek()); advance(); }
        if (sb.isEmpty()) {
            throw MfError.of("E004", "a $ needs a name after it")
                    .at(start)
                    .hint("write $name to use the variable called name")
                    .build();
        }
        add(TokenType.VAR, sb.toString(), start, 0);
    }

    /** Paths, globs and anything else that is one unquoted word. */
    private void bareword(Span start, StringBuilder sb) {
        while (!eof() && !isBarewordEnd(peek())) { sb.append(peek()); advance(); }
        add(TokenType.BAREWORD, sb.toString(), start, 0);
    }

    private void operator() {
        Span start = mark();
        char c = peek();
        advance();
        switch (c) {
            case '|' -> {
                // A pipe may begin a line: in a script, the newlines before it
                // are not a statement break.
                while (!out.isEmpty() && out.getLast().type() == TokenType.NEWLINE) {
                    out.removeLast();
                }
                add(TokenType.PIPE, "|", start, 0);
            }
            case '(' -> add(TokenType.LPAREN, "(", start, 0);
            case ')' -> add(TokenType.RPAREN, ")", start, 0);
            case '[' -> add(TokenType.LBRACKET, "[", start, 0);
            case ']' -> add(TokenType.RBRACKET, "]", start, 0);
            case '{' -> add(TokenType.LBRACE, "{", start, 0);
            case '}' -> add(TokenType.RBRACE, "}", start, 0);
            case ',' -> add(TokenType.COMMA, ",", start, 0);
            case ':' -> add(TokenType.COLON, ":", start, 0);
            case ';' -> add(TokenType.SEMI, ";", start, 0);
            case '^' -> add(TokenType.CARET, "^", start, 0);
            case '+' -> add(TokenType.PLUS, "+", start, 0);
            case '%' -> add(TokenType.PERCENT, "%", start, 0);
            case '*' -> add(TokenType.STAR, "*", start, 0);
            case '/' -> add(TokenType.SLASH, "/", start, 0);
            case '&' -> {
                if (match('&')) add(TokenType.AND, "&&", start, 0);
                else throw MfError.of("E005", "a single & does not mean anything here")
                        .at(start).hint("use && or the word and").build();
            }
            case '=' -> {
                if (match('=')) add(TokenType.EQ_EQ, "==", start, 0);
                else if (match('~')) add(TokenType.MATCH, "=~", start, 0);
                else add(TokenType.ASSIGN, "=", start, 0);
            }
            case '!' -> {
                if (match('=')) add(TokenType.BANG_EQ, "!=", start, 0);
                else if (match('~')) add(TokenType.NOT_MATCH, "!~", start, 0);
                else add(TokenType.NOT, "!", start, 0);
            }
            case '<' -> {
                if (match('=')) add(TokenType.LT_EQ, "<=", start, 0);
                else add(TokenType.LT, "<", start, 0);
            }
            case '>' -> {
                if (match('=')) add(TokenType.GT_EQ, ">=", start, 0);
                else add(TokenType.GT, ">", start, 0);
            }
            case '-' -> minusOrFlag(start);
            default -> throw MfError.of("E006", "I do not know what to do with " + c)
                    .at(start)
                    .hint("if it is part of a file name, put quotes around the whole name")
                    .build();
        }
    }

    /** {@code -} is a flag, a negative number or subtraction, decided by what follows. */
    private void minusOrFlag(Span start) {
        if (!eof() && peek() == '-') {
            advance();
            StringBuilder sb = new StringBuilder();
            while (!eof() && isIdentPart(peek())) { sb.append(peek()); advance(); }
            if (sb.isEmpty()) {
                throw MfError.of("E007", "-- needs a flag name after it")
                        .at(start).hint("for example --dry-run").build();
            }
            add(TokenType.FLAG_LONG, sb.toString(), start, 0);
            return;
        }
        if (!eof() && Character.isDigit(peek()) && startsValue()) { number(true); return; }
        if (!eof() && isIdentStart(peek()) && startsValue()) {
            StringBuilder sb = new StringBuilder();
            while (!eof() && isIdentPart(peek())) { sb.append(peek()); advance(); }
            add(TokenType.FLAG_SHORT, sb.toString(), start, 0);
            return;
        }
        add(TokenType.MINUS, "-", start, 0);
    }

    /**
     * A dot means "field of" only right after something that holds fields --
     * $row.name or (ls).0. Everywhere else a dot belongs to a file name.
     */
    private boolean fieldAccessFollows() {
        if (out.isEmpty() || pos + 1 >= src.length()) return false;
        char next = src.charAt(pos + 1);
        if (!isIdentStart(next) && !Character.isDigit(next)) return false;
        return switch (out.getLast().type()) {
            case VAR, RPAREN, RBRACKET -> true;
            default -> false;
        };
    }

    /** True when the character after this one ends the token, i.e. it stands alone. */
    private boolean spaceFollows() {
        if (pos + 1 >= src.length()) return true;
        char next = src.charAt(pos + 1);
        return next == ' ' || next == '\t' || next == '\r' || next == '\n';
    }

    /** True when the previous token cannot be the left side of a subtraction. */
    private boolean startsValue() {
        if (out.isEmpty()) return true;
        return switch (out.getLast().type()) {
            case INT, FLOAT, SIZE, DURATION, DATETIME, STRING, VAR, RPAREN, RBRACKET, RBRACE,
                 BAREWORD -> false;
            default -> true;
        };
    }

    // ---- character classes -------------------------------------------------------

    private static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_'; }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static boolean startsBareword(char c) {
        return c == '.' || c == '/' || c == '\\' || c == '~' || c == '*' || c == '?' || c == '@';
    }

    private static boolean isBarewordEnd(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '|' || c == ';'
                || c == ')' || c == ']' || c == '}' || c == ',' || c == '(' || c == '[';
    }

    // ---- caret stages ------------------------------------------------------------

    /**
     * Whether a stage could begin here.
     *
     * <p>Only at a stage boundary does a caret mean a program. A {@code ^} written
     * as an argument to something else is just a character.
     */
    private boolean atCommandPosition() {
        if (out.isEmpty()) return true;
        return switch (out.getLast().type()) {
            case NEWLINE, SEMI, PIPE, LBRACE -> true;
            default -> false;
        };
    }

    /** The program's name, and then everything else as one raw token. */
    private void external() {
        add(TokenType.CARET, "^", mark(), 0);
        advance();
        skipBlanks();
        // A ^ with no name after it is the parser's error to report, and it says
        // it better than the lexer could.
        if (eof()) return;
        char n = peek();
        if (n == '"' || n == '\'') string(n);
        else if (isIdentStart(n)) word();
        else if (startsBareword(n)) bareword(mark(), new StringBuilder());
        else return;
        rawTail();
    }

    /**
     * The rest of the stage, verbatim.
     *
     * <p>Only what separates stages ends it: a pipe, a semicolon, a newline, and a
     * closing brace when there is a block open to close. Quotes are stepped over
     * rather than read, so a pipe inside an argument stays in the argument.
     *
     * <p>The token is added even when it is empty, because its presence is what
     * tells the parser this stage was handed over rather than parsed.
     */
    private void rawTail() {
        skipBlanks();
        Span start = mark();
        int begin = pos;
        int depth = braceDepth();
        while (!eof()) {
            char c = peek();
            if (c == '\n' || c == ';' || c == '|') break;
            if (c == '}' && depth > 0) break;
            if (c == '"' || c == '\'') { skipQuoted(c); continue; }
            advance();
        }
        String raw = src.substring(begin, pos).stripTrailing();
        add(TokenType.RAW, raw, start, Math.max(1, raw.length()));
    }

    private void skipBlanks() {
        while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) advance();
    }

    private void skipQuoted(char quote) {
        advance();
        while (!eof() && peek() != quote && peek() != '\n') advance();
        if (!eof() && peek() == quote) advance();
    }

    private int braceDepth() {
        int depth = 0;
        for (Token t : out) {
            if (t.type() == TokenType.LBRACE) depth++;
            else if (t.type() == TokenType.RBRACE) depth--;
        }
        return depth;
    }

    // ---- cursor ------------------------------------------------------------------

    private boolean eof() { return pos >= src.length(); }
    private char peek() { return src.charAt(pos); }

    private boolean match(char c) {
        if (!eof() && peek() == c) { advance(); return true; }
        return false;
    }

    private void advance() {
        if (eof()) return;
        if (src.charAt(pos) == '\n') { line++; col = 1; } else { col++; }
        pos++;
    }

    private Span mark() { return new Span(line, col, 1); }

    private void add(TokenType type, String text, Span start, int lengthOverride) {
        int len = lengthOverride > 0 ? lengthOverride : Math.max(1, col - start.col());
        out.add(new Token(type, text, new Span(start.line(), start.col(), len)));
    }
}
