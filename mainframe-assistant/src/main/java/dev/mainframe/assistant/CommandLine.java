package dev.mainframe.assistant;

import java.util.List;
import java.util.Map;

import dev.mainframe.eval.Signature;

/**
 * A tool call, written back out as the line a person would have typed.
 *
 * <p>This is the only form in which a model's answer reaches MainFrame. Nothing
 * calls a builtin directly on the model's behalf, because everything that makes
 * a command safe -- argument checking, {@code --dry-run}, the confirmation on
 * anything destructive -- lives on the path from a typed line to a running
 * command. A second entrance would need all of it again, and would drift.
 *
 * <p>It is also what the person is shown. A shell that quietly does the right
 * thing when you typed the wrong thing teaches you nothing and leaves you fluent
 * in nothing; showing the line it settled on forgives the input and explains it
 * in the same breath. That the shown line and the run line are the same string
 * is the property worth keeping -- an explanation that can differ from what
 * happened is worse than no explanation.
 */
public final class CommandLine {

    private CommandLine() {}

    /**
     * @param arguments what the model sent, keyed the way {@link ToolSchema}
     *                  named them: positionals by name, flags with their dashes
     * @throws IllegalArgumentException if the call names something the command
     *                                  does not declare
     */
    public static String render(Signature signature, Map<String, Object> arguments) {
        checkNothingInvented(signature, arguments);
        StringBuilder line = new StringBuilder(signature.name());

        for (Signature.Param param : signature.params()) {
            Object value = arguments.get(param.name());
            if (value == null) continue;
            if (param.rest() && value instanceof List<?> items) {
                for (Object item : items) line.append(' ').append(literal(item));
            } else {
                line.append(' ').append(literal(value));
            }
        }

        for (Signature.Flag flag : signature.flags()) {
            Object value = arguments.get("--" + flag.name());
            if (value == null) continue;
            if (flag.isSwitch()) {
                // A switch sent as false is the same as not sending it, and
                // "--skip-tests=false" is not a thing anyone can type.
                if (value instanceof Boolean on && on) line.append(" --").append(flag.name());
            } else {
                line.append(" --").append(flag.name()).append('=').append(literal(value));
            }
        }

        return line.toString();
    }

    /**
     * A model that invents an argument is told so before a line is built, not
     * after: a line assembled from a call that was partly ignored is a line
     * nobody asked for, and it would run without complaint.
     */
    private static void checkNothingInvented(Signature signature, Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            boolean known = key.startsWith("--")
                    ? signature.flag(key.substring(2)) != null
                    : signature.params().stream().anyMatch(p -> p.name().equals(key));
            if (!known) {
                throw new IllegalArgumentException(
                        signature.name() + " has no argument called \"" + key + "\"");
            }
        }
    }

    /**
     * One value, written so the lexer reads back what was meant.
     *
     * <p>Numbers and booleans go bare. Everything else is quoted, even when it
     * would have survived unquoted: a bare word can be a path glob, a size, a
     * duration or a command name depending on how it looks, and a rule that
     * quotes only sometimes is a rule that will one day guess wrong about a value
     * that came from outside.
     */
    static String literal(Object value) {
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return quote(String.valueOf(value));
    }

    /** Double quotes and JSON's escape set, which is what the lexer reads. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
