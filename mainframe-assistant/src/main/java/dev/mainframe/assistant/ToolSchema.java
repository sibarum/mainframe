package dev.mainframe.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.mainframe.eval.Signature;
import dev.mainframe.value.ValueType;

/**
 * A command, described so a language model can call it.
 *
 * <p>Nothing here is new information. {@link Signature} already says what a
 * command takes, what it gives back and what it touches -- that is what makes
 * argument checking, {@code help} and completion all agree with each other. This
 * turns the same declaration into the shape a model expects, so the model is
 * reading the command rather than a document written about the command. There is
 * no second place to keep up to date, which is the whole point: a description
 * that can drift from the thing it describes will.
 *
 * <p>The rendering is deliberately plain maps and lists rather than any vendor's
 * classes. Core has no runtime dependencies and this module keeps that until
 * something actually needs a wire; a map is also what a test can assert against
 * without spending a request.
 */
public final class ToolSchema {

    private ToolSchema() {}

    /**
     * One tool definition: {@code name}, {@code description} and
     * {@code input_schema}.
     */
    public static Map<String, Object> of(Signature signature) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", signature.name());
        tool.put("description", describe(signature));
        tool.put("input_schema", inputSchema(signature));
        return tool;
    }

    /** The whole roster, in the order it was given. */
    public static List<Map<String, Object>> of(List<Signature> signatures) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Signature signature : signatures) tools.add(of(signature));
        return tools;
    }

    /**
     * What the model reads to decide whether this is the command it wants.
     *
     * <p>The summary alone is written for someone scanning {@code help}, who has
     * the rest of the screen for context. A model choosing between forty commands
     * has only this string, so the usage line and the examples go in too -- the
     * examples especially, since they are the part that shows what an argument
     * actually looks like.
     */
    static String describe(Signature signature) {
        StringBuilder text = new StringBuilder(signature.summary());
        text.append("\n\nUsage: ").append(signature.usage());
        if (!signature.examples().isEmpty()) {
            text.append("\n\nExamples:");
            for (String example : signature.examples()) text.append("\n  ").append(example);
        }
        if (signature.input() != ValueType.NOTHING) {
            text.append("\n\nAccepts ").append(signature.input().withArticle()).append(" from the pipe.");
        }
        return text.toString();
    }

    private static Map<String, Object> inputSchema(Signature signature) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Signature.Param param : signature.params()) {
            // A rest parameter soaks up everything left, so it arrives as a list
            // however it was typed -- one item or nine.
            Map<String, Object> property = param.rest()
                    ? listOf(param.type(), param.description())
                    : property(param.type(), param.description());
            properties.put(param.name(), property);
            if (param.required()) required.add(param.name());
        }

        for (Signature.Flag flag : signature.flags()) {
            if (isGuardrail(flag)) continue;
            // Flags are named the way they are typed, dashes and all, so what the
            // model produces can be read back as the line a person would write.
            properties.put("--" + flag.name(), flag.isSwitch()
                    ? property(ValueType.BOOL, flag.description())
                    : property(flag.type(), flag.description()));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // Strict tool use needs this, and without it a model that invents an
        // argument gets no complaint until MainFrame refuses the call.
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * The two flags MainFrame adds to anything that writes or destroys, which the
     * model is never shown.
     *
     * <p>{@code --yes} skips the confirmation prompt. Offering it to a model is
     * offering it permission to stop asking -- the one argument in the roster
     * that decides whether a person is consulted at all, handed to the party the
     * person is being consulted about. {@code --dry-run} is harmless by itself
     * but is MainFrame's to set: whether a call is rehearsed or run follows from
     * how far the caller is trusted, and is not the caller's to choose.
     *
     * <p>Both are added by {@link Signature.Builder#build()} rather than declared
     * by anyone, so leaving them out here is not second-guessing a command
     * author.
     */
    private static boolean isGuardrail(Signature.Flag flag) {
        return flag.isSwitch() && (flag.name().equals("yes") || flag.name().equals("dry-run"));
    }

    private static Map<String, Object> listOf(ValueType type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", property(type, null));
        if (description != null) schema.put("description", description);
        return schema;
    }

    /**
     * One argument.
     *
     * <p>Several of MainFrame's types are written as text and read as something
     * else -- {@code 10mb} is a size, {@code 7d} a duration. The model is told the
     * JSON type it can actually produce, and the note says how MainFrame will read
     * it back. Claiming {@code size} is a number would be closer to the truth and
     * further from anything the model could send.
     */
    private static Map<String, Object> property(ValueType type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String note = null;
        switch (type) {
            case ANY, EXPR, BLOCK -> { /* no constraint worth stating */ }
            case NOTHING -> schema.put("type", "null");
            case BOOL -> schema.put("type", "boolean");
            case INT -> schema.put("type", "integer");
            case FLOAT, NUMBER -> schema.put("type", "number");
            case STRING -> schema.put("type", "string");
            case PATH -> {
                schema.put("type", "string");
                note = "a filesystem path, resolved against the current directory";
            }
            case MIME -> {
                schema.put("type", "string");
                note = "a media type, such as text/markdown";
            }
            case SIZE -> {
                schema.put("type", "string");
                note = "a byte count written like 10mb or 512kb";
            }
            case DURATION -> {
                schema.put("type", "string");
                note = "a span of time written like 7d or 90m";
            }
            case TIME -> {
                schema.put("type", "string");
                note = "a moment in time";
            }
            case LIST -> schema.put("type", "array");
            case RECORD -> schema.put("type", "object");
            case TABLE -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "object"));
                note = "rows and columns";
            }
        }
        String text = description == null || description.isBlank() ? note
                : note == null ? description
                : description + " (" + note + ")";
        if (text != null) schema.put("description", text);
        return schema;
    }
}
