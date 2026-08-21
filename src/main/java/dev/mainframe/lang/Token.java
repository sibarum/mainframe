package dev.mainframe.lang;

import dev.mainframe.Span;

/** @param text the source text of the token, with quotes and sigils stripped. */
public record Token(TokenType type, String text, Span span) {
    @Override
    public String toString() { return type + "(" + text + ")"; }
}
