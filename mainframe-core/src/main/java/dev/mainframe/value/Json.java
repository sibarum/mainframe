package dev.mainframe.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.MfError;
import dev.mainframe.Span;

/**
 * A small JSON reader, so output from other tools can join a pipeline as real
 * typed values instead of text to be re-parsed by eye.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) { this.text = text; }

    public static Value parse(String text, Span span) {
        Json json = new Json(text);
        json.skipSpace();
        Value value = json.value(span);
        json.skipSpace();
        if (json.pos < text.length()) {
            throw error(span, "there is extra text after the JSON value", json.pos);
        }
        return value;
    }

    private Value value(Span span) {
        if (pos >= text.length()) throw error(span, "the JSON ended sooner than expected", pos);
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(span);
            case '[' -> array(span);
            case '"' -> new Value.Str(string(span));
            case 't' -> literal("true", new Value.Bool(true), span);
            case 'f' -> literal("false", new Value.Bool(false), span);
            case 'n' -> literal("null", Value.Nothing.INSTANCE, span);
            default -> number(span);
        };
    }

    private Value object(Span span) {
        pos++;
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') { pos++; return new Value.Rec(fields); }
        while (true) {
            skipSpace();
            if (peek() != '"') throw error(span, "a JSON field name must be quoted", pos);
            String key = string(span);
            skipSpace();
            if (peek() != ':') throw error(span, "expected a : after the field name", pos);
            pos++;
            skipSpace();
            fields.put(key, value(span));
            skipSpace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return new Value.Rec(fields); }
            throw error(span, "expected , or } in the JSON object", pos);
        }
    }

    private Value array(Span span) {
        pos++;
        List<Value> items = new ArrayList<>();
        skipSpace();
        if (peek() == ']') { pos++; return new Value.ListVal(List.of()); }
        while (true) {
            skipSpace();
            items.add(value(span));
            skipSpace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return new Value.ListVal(List.copyOf(items)); }
            throw error(span, "expected , or ] in the JSON list", pos);
        }
    }

    private String string(Span span) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error(span, "this JSON text is missing its closing quote", pos);
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= text.length()) throw error(span, "a backslash at the very end of the JSON", pos);
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error(span, "a short \\u escape in the JSON", pos);
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Value number(Span span) {
        int start = pos;
        if (peek() == '-' || peek() == '+') pos++;
        boolean fractional = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isDigit(c)) { pos++; continue; }
            if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { fractional = true; pos++; continue; }
            break;
        }
        String slice = text.substring(start, pos);
        if (slice.isEmpty() || slice.equals("-")) throw error(span, "expected a JSON value here", start);
        try {
            return fractional
                    ? new Value.Float(Double.parseDouble(slice))
                    : new Value.Int(Long.parseLong(slice));
        } catch (NumberFormatException e) {
            throw error(span, "\"" + slice + "\" is not a number JSON allows", start);
        }
    }

    private Value literal(String word, Value value, Span span) {
        if (!text.startsWith(word, pos)) throw error(span, "expected a JSON value here", pos);
        pos += word.length();
        return value;
    }

    private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private static MfError error(Span span, String message, int at) {
        return MfError.of("E401", message)
                .at(span)
                .hint("the problem is around character " + (at + 1) + " of the JSON")
                .build();
    }
}
