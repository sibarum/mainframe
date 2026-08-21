package dev.mainframe.api;

import dev.mainframe.value.ValueType;

/**
 * The kinds of value a hosted command can declare for its arguments, its piped
 * input and its result.
 *
 * <p>Declaring these is what buys a hosted command the same treatment as a
 * built-in one: arguments are checked before the callback runs, {@code help} is
 * generated from the declaration, and a wrong type produces the same explained
 * error a built-in would.
 */
public enum DataType {

    /** Anything at all. */
    ANY,
    /** No value. */
    NOTHING,
    /** true or false. */
    BOOL,
    /** A whole number. */
    INTEGER,
    /** Any number, whole or fractional. */
    NUMBER,
    /** Text. Paths and media types can be read as text too. */
    TEXT,
    /** A byte count, written like 10mb. */
    SIZE,
    /** A moment in time. */
    TIME,
    /** A filesystem path, resolved against the shell's directory. */
    PATH,
    /** A media type, such as text/markdown. */
    MIME,
    /** A list of values. */
    LIST,
    /** A set of named fields. */
    RECORD,
    /** A list of records: rows and columns. */
    TABLE;

    ValueType internal() {
        return switch (this) {
            case ANY -> ValueType.ANY;
            case NOTHING -> ValueType.NOTHING;
            case BOOL -> ValueType.BOOL;
            case INTEGER -> ValueType.INT;
            case NUMBER -> ValueType.NUMBER;
            case TEXT -> ValueType.STRING;
            case SIZE -> ValueType.SIZE;
            case TIME -> ValueType.TIME;
            case PATH -> ValueType.PATH;
            case MIME -> ValueType.MIME;
            case LIST -> ValueType.LIST;
            case RECORD -> ValueType.RECORD;
            case TABLE -> ValueType.TABLE;
        };
    }

    static DataType of(ValueType type) {
        return switch (type) {
            case NOTHING -> NOTHING;
            case BOOL -> BOOL;
            case INT -> INTEGER;
            case FLOAT, NUMBER -> NUMBER;
            case STRING -> TEXT;
            case SIZE -> SIZE;
            case TIME -> TIME;
            case PATH -> PATH;
            case MIME -> MIME;
            case LIST -> LIST;
            case RECORD -> RECORD;
            case TABLE -> TABLE;
            case ANY, BLOCK, EXPR -> ANY;
        };
    }
}
