package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.Session;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.fs.Store;
import dev.mainframe.lang.Parser;
import dev.mainframe.panel.Editor;
import dev.mainframe.panel.Event;
import dev.mainframe.panel.Screen;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/** Setting the one key MainFrame keeps, and what it refuses to say about it. */
class KeysTest {

    @TempDir
    Path here;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /**
     * An editor that can mask, and remembers what it was sent.
     *
     * <p>It answers the first screen with a submit carrying the key, which is
     * exactly what the protocol says a masking editor does: once, upward, on
     * submit.
     */
    private static final class Masking implements Editor {

        final List<Value.Rec> sent = new ArrayList<>();
        final String answer;
        boolean claimsSecret = true;

        Masking(String answer) { this.answer = answer; }

        @Override
        public Hello hello() {
            LinkedHashSet<String> can = new LinkedHashSet<>(List.of("text", "entry", "action"));
            if (claimsSecret) can.add(Screen.SECRET);
            return new Hello("masking", 1, 24, 80, can);
        }

        @Override
        public Event show(Screen screen) {
            Value.Rec message = screen.message();
            sent.add(message);
            SequencedMap<String, Value> fields = new LinkedHashMap<>();
            fields.put("key", new Value.Str(answer));
            return Event.read(Value.Rec.of("event", Value.Rec.of(
                    "screen", new Value.Int(1),
                    "did", new Value.Str(Event.SUBMIT),
                    "fields", new Value.Rec(fields))));
        }

        @Override public void print(String text, String style) { }
    }

    private String printed() { return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }

    private Secrets secrets() { return new Secrets(Store.secret(here.resolve("keys"), "")); }

    private Interpreter shell(Secrets secrets, Editor editor) {
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        Registry registry = Registry.standard();
        registry.add(new Keys(secrets));
        Session session = new Session(new Renderer(stream, stream, false),
                new IndexStore(here.resolve(".indexes")),
                new BufferedReader(new StringReader("")), here);
        session.editor(editor);
        session.assumeYes(true);            // the confirmation is not what is under test
        Interpreter interpreter = new Interpreter(session, registry);
        return interpreter;
    }

    private void run(Interpreter interpreter, String line) {
        interpreter.run(Parser.parse(line));
    }

    @Test
    void aKeyTypedIntoAScreenIsStored() throws IOException {
        Secrets secrets = secrets();
        Masking editor = new Masking("sk-ant-example");

        run(shell(secrets, editor), "key set");

        assertEquals("sk-ant-example", secrets.read());
    }

    @Test
    void theScreenItIsTypedIntoCarriesNoValue() {
        Masking editor = new Masking("sk-ant-example");
        run(shell(secrets(), editor), "key set");

        // The whole downward half of the rule, checked on the wire form rather
        // than on the objects: MainFrame sends a secret entry with no value key.
        String message = Values.display(editor.sent.getFirst());
        assertTrue(message.contains("secret"), message);
        assertFalse(message.contains("value"), "a secret entry must carry no value: " + message);
    }

    @Test
    void nothingThatIsPrintedEverCarriesTheKey() throws IOException {
        Secrets secrets = secrets();
        run(shell(secrets, new Masking("sk-ant-hunter2")), "key set");

        // The plan is printed before it runs, and a printed plan is an echo. The
        // warning, the path and the verb are all fair game; the key is not.
        String printed = printed();
        assertFalse(printed.contains("sk-ant-hunter2"), printed);
        assertTrue(printed.contains("plain text"), "the risk is stated where the decision is: " + printed);
    }

    @Test
    void statusSaysWhetherNotWhat() throws IOException {
        Secrets secrets = secrets();
        secrets.write("sk-ant-hunter2");

        run(shell(secrets, new Masking("")), "key status");

        String printed = printed();
        assertTrue(printed.contains("a key is set"), printed);
        assertFalse(printed.contains("sk-ant-hunter2"), printed);
    }

    @Test
    void forgettingRemovesIt() throws IOException {
        Secrets secrets = secrets();
        secrets.write("sk-ant-example");

        run(shell(secrets, new Masking("")), "key forget");

        assertFalse(secrets.isSet());
    }

    @Test
    void anEditorThatCannotMaskIsNeverSentTheField() {
        Secrets secrets = secrets();
        Masking editor = new Masking("sk-ant-example");
        editor.claimsSecret = false;

        // Refused rather than downgraded to an ordinary entry. This is the one
        // capability where degrading gracefully would mean leaking.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> run(shell(secrets, editor), "key set"));
        assertTrue(editor.sent.isEmpty(), "a screen was built before the refusal");
        assertFalse(secrets.isSet());
    }

    @Test
    void aDryRunRehearsesAndStoresNothing() throws IOException {
        Secrets secrets = secrets();

        run(shell(secrets, new Masking("sk-ant-example")), "key set --dry-run");

        assertFalse(secrets.isSet(), "--dry-run left a key behind");
        assertTrue(printed().contains("store the key"), printed());
    }
}
