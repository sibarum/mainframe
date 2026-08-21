package dev.mainframe.lang;

public enum TokenType {
    IDENT, BAREWORD, STRING, INT, FLOAT, SIZE, DATETIME, DURATION, TAG, VAR, FLAG_LONG, FLAG_SHORT,
    LET, IF, ELSE, FOR, IN, TRUE, FALSE, NOTHING, NOW, AND, OR, NOT,
    PIPE, EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ, MATCH, NOT_MATCH,
    PLUS, MINUS, STAR, SLASH, PERCENT, ASSIGN, CARET, DOT,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, COLON, SEMI,
    NEWLINE, EOF;

    public boolean isKeyword() {
        return switch (this) {
            case LET, IF, ELSE, FOR, IN, TRUE, FALSE, NOTHING, NOW, AND, OR, NOT -> true;
            default -> false;
        };
    }
}
