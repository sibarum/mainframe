// Not part of the build. What dev.mainframe/mainframe-dist-assistant/reachability-metadata.json was traced
// from, and the way to regenerate it -- see "reachability-metadata.json" in the README.
//
// Drives dev.mainframe.assistant.Claude against FakeApi: the SDK's real request/response path -- Jackson
// serialisation, OkHttp transport, Jackson deserialisation -- with a dummy key and a localhost base URL.
// The whole point is to run this same class as a native image and see whether it still works.
//
//   mvn -Pdist -pl mainframe-dist dependency:build-classpath -Dmdep.outputFile=cp.txt -DincludeScope=runtime
//   java src/main/trace/FakeApi.java 8787 &                       # the stand-in, in its own JVM
//   javac -cp "@cp.txt" -d out src/main/trace/Probe.java
//   java -agentlib:native-image-agent=config-output-dir=agent-out -cp "<cp.txt>;out" Probe
//
// Then drop the `Probe` and `sun.launcher.LauncherHelper` entries the launcher contributes, and put the rest
// in place. To check that it actually closed the gap, native-image the same probe -- it takes about 40s --
// with and without -H:ConfigurationFileDirectories=agent-out and run both: without it, the failure is
// `JsonValue$Deserializer has no default (no arg) constructor`. Restart FakeApi between runs; it answers by
// turn number, so a second run in the same process starts at the wrong reply.
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.mainframe.assistant.Claude;
import dev.mainframe.assistant.Model;

import java.util.List;
import java.util.Map;

public class Probe {

    public static void main(String[] args) {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8787";
        try {
            Claude claude = new Claude(AnthropicOkHttpClient.builder()
                    .apiKey("not-a-real-key")
                    .baseUrl(base)
                    .build());
            // A roster with one tool, so the request carries the tool schema the real `ask` would send.
            List<Map<String, Object>> tools = List.of(Map.of(
                    "name", "ls",
                    "description", "list the files here",
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", Map.of("path", Map.of("type", "string", "description", "where")),
                            "required", List.of("path"))));
            Model.Chat chat = claude.start("you are a shell", tools);
            Model.Answer first = chat.say("marco");
            System.out.println("PROBE-TURN1-TEXT=" + first.text());
            System.out.println("PROBE-TURN1-CALLS=" + first.calls().size());
            for (Model.Call call : first.calls()) {
                System.out.println("PROBE-CALL " + call.name() + " " + call.arguments());
            }
            // The other half of the loop: hand back what the command produced, which is the tool_result path.
            List<Model.Outcome> outcomes = first.calls().stream()
                    .map(call -> Model.Outcome.of(call.id(), "a.txt\nb.txt"))
                    .toList();
            Model.Answer second = chat.report(outcomes);
            System.out.println("PROBE-TURN2-TEXT=" + second.text());
            System.out.println("PROBE-OK");
        } catch (Throwable t) {
            System.out.println("PROBE-FAILED " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
