package dev.mainframe.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

/**
 * {@link Model}, spoken to Anthropic.
 *
 * <p>The only file in this module that knows which company makes the model, and
 * the only one that needs the SDK on the classpath. Everything the assistant
 * actually decides lives next door in plain maps and interfaces, so this can be
 * swapped for a hand-written HTTP client -- which is what MainFrame's
 * no-runtime-dependencies rule would prefer -- without any of the loop moving.
 *
 * <p>What is here is turning records into requests: the roster as tools, the
 * outcomes as tool results, and the reply back into an {@link Answer}.
 */
public final class Claude implements Model {

    /** The current Opus. Named rather than derived, so an upgrade is a visible edit. */
    private static final String MODEL = "claude-opus-5";

    /** Room to answer without being cut off mid-sentence, and short of the timeout. */
    private static final long MOST_TOKENS = 16_000L;

    private final AnthropicClient client;

    public Claude(AnthropicClient client) { this.client = client; }

    /** Reads the key from the environment the way every other Anthropic tool does. */
    public static Claude fromEnvironment() {
        return new Claude(AnthropicOkHttpClient.fromEnv());
    }

    /** With the key MainFrame keeps -- see {@link Secrets}. */
    public static Claude withKey(String key) {
        return new Claude(AnthropicOkHttpClient.builder().apiKey(key).build());
    }

    @Override
    public Chat start(String system, List<Map<String, Object>> tools) {
        return new Conversation(system, tools);
    }

    /**
     * One conversation, holding its own history.
     *
     * <p>The assistant's reply goes back into the history unchanged, blocks and
     * all. Keeping only the text would drop the thinking and the tool calls, and
     * the next request would be answering a conversation that never happened.
     */
    private final class Conversation implements Chat {

        private final String system;
        private final List<Tool> tools;
        private final List<MessageParam> messages = new ArrayList<>();

        Conversation(String system, List<Map<String, Object>> tools) {
            this.system = system;
            this.tools = new ArrayList<>();
            for (Map<String, Object> tool : tools) this.tools.add(asTool(tool));
        }

        @Override
        public Answer say(String text) {
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(text).build());
            return send();
        }

        @Override
        public Answer report(List<Outcome> outcomes) {
            List<ContentBlockParam> results = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(outcome.id())
                        .content(outcome.output())
                        .isError(outcome.failed())
                        .build()));
            }
            // All of them in one message. Splitting them teaches the model to stop
            // asking for more than one thing at a time.
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build());
            return send();
        }

        private Answer send() {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MOST_TOKENS)
                    .system(system)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .messages(messages);
            for (Tool tool : tools) params.addTool(tool);

            Message reply = client.messages().create(params.build());
            messages.add(reply.toParam());
            return read(reply);
        }
    }

    /** The reply, as something the loop can act on. */
    private static Answer read(Message reply) {
        StringBuilder text = new StringBuilder();
        List<Call> calls = new ArrayList<>();
        for (ContentBlock block : reply.content()) {
            block.text().ifPresent(said -> {
                if (!text.isEmpty()) text.append("\n\n");
                text.append(said.text());
            });
            block.toolUse().ifPresent(use -> calls.add(new Call(use.id(), use.name(), arguments(use))));
        }
        return new Answer(text.toString(), List.copyOf(calls));
    }

    /**
     * A tool call's arguments as a plain map.
     *
     * <p>Through the SDK's own JSON rather than by reading the serialized string:
     * escaping in tool inputs is the model's business and not something to match
     * on.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(ToolUseBlock use) {
        Object raw = use._input().asObject().orElse(null);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            map.forEach((key, value) -> arguments.put(String.valueOf(key), unwrap(value)));
            return arguments;
        }
        return Map.of();
    }

    private static Object unwrap(Object value) {
        return value instanceof JsonValue json ? json.asObject().orElse(value) : value;
    }

    /** One rendered tool definition, as the SDK wants it. */
    @SuppressWarnings("unchecked")
    private static Tool asTool(Map<String, Object> rendered) {
        Map<String, Object> schema = (Map<String, Object>) rendered.get("input_schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        Tool.InputSchema.Properties.Builder shape = Tool.InputSchema.Properties.builder();
        properties.forEach((name, property) -> shape.putAdditionalProperty(name, JsonValue.from(property)));

        return Tool.builder()
                .name(String.valueOf(rendered.get("name")))
                .description(String.valueOf(rendered.get("description")))
                .inputSchema(Tool.InputSchema.builder()
                        .properties(shape.build())
                        .required((List<String>) schema.get("required"))
                        .build())
                .build();
    }
}
