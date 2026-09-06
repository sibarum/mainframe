// A stand-in for api.anthropic.com on 127.0.0.1, so the SDK's whole serialize -> transport -> deserialize
// path can be driven with no key and no external traffic. Run: java FakeApi.java [port]
//
// Two turns, because the assistant's loop has two: the first reply asks for a command to be run (tool_use),
// the second answers in words. That is what makes the trace cover ToolUseBlock and ToolResultBlockParam
// rather than only the text path.
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeApi {

    static final AtomicInteger TURN = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            String request = new String(body, StandardCharsets.UTF_8);
            int turn = TURN.incrementAndGet();
            System.out.println("REQ " + turn + " " + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " bytes=" + body.length);
            System.out.println("REQ-HAS-MODEL=" + request.contains("\"model\"")
                    + " REQ-HAS-TOOLS=" + request.contains("\"tools\"")
                    + " REQ-HAS-TOOL-RESULT=" + request.contains("tool_result"));
            System.out.flush();

            String reply = turn == 1
                    ? """
                    {"id":"msg_fake_1","type":"message","role":"assistant","model":"claude-opus-5",\
                    "content":[{"type":"text","text":"let me look"},\
                    {"type":"tool_use","id":"toolu_fake_1","name":"ls","input":{"path":"."}}],\
                    "stop_reason":"tool_use","stop_sequence":null,\
                    "usage":{"input_tokens":7,"output_tokens":3}}"""
                    : """
                    {"id":"msg_fake_2","type":"message","role":"assistant","model":"claude-opus-5",\
                    "content":[{"type":"text","text":"polo"}],\
                    "stop_reason":"end_turn","stop_sequence":null,\
                    "usage":{"input_tokens":9,"output_tokens":2}}""";
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("FAKE-API-READY " + port);
        System.out.flush();
    }
}
