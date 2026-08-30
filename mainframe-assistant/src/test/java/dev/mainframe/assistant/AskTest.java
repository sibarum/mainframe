package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.Session;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;

/**
 * {@code ask} inside a real shell, with a real registry and a real interpreter --
 * everything except the model.
 *
 * <p>The one thing worth proving end to end is that a model's answer becomes a
 * line MainFrame actually runs, through the same parser and the same guardrails a
 * typed line goes through. A stand-in interpreter would have proved nothing about
 * that, so there is not one.
 */
class AskTest {

    @TempDir
    Path here;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** A model that asks for exactly what the test wants it to ask for. */
    private static final class Scripted implements Model, Model.Chat {

        final Deque<Answer> answers = new ArrayDeque<>();
        final List<List<Outcome>> reported = new java.util.ArrayList<>();
        List<Map<String, Object>> tools;

        @Override
        public Chat start(String system, List<Map<String, Object>> tools) {
            this.tools = tools;
            return this;
        }

        @Override
        public Answer say(String text) {
            return answers.isEmpty() ? Answer.said("") : answers.removeFirst();
        }

        @Override
        public Answer report(List<Outcome> outcomes) {
            reported.add(outcomes);
            return answers.isEmpty() ? Answer.said("that is all of them") : answers.removeFirst();
        }
    }

    private static Model.Answer wants(String command, Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) arguments.put((String) pairs[i], pairs[i + 1]);
        return new Model.Answer("", List.of(new Model.Call("a", command, arguments)));
    }

    private String printed() { return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }

    /** A shell with the assistant in it, wired the way {@code Assistants.install} wires one. */
    private Interpreter shell(Model model) {
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        Registry registry = Registry.standard();
        Assistants.install(registry, model);
        Session session = new Session(new Renderer(stream, stream, false),
                new IndexStore(here.resolve(".indexes")),
                new BufferedReader(new StringReader("")), here);
        return new Interpreter(session, registry);
    }

    private void run(Interpreter interpreter, String line) {
        interpreter.run(Parser.parse(line));
    }

    @Test
    void aQuestionBecomesACommandThatActuallyRuns() throws Exception {
        Files.writeString(here.resolve("notes.txt"), "hello");
        Scripted model = new Scripted();
        model.answers.add(wants("ls"));
        model.answers.add(Model.Answer.said("There is one file, notes.txt."));

        Interpreter interpreter = shell(model);
        run(interpreter, "ask \"what files are here\"");

        String printed = printed();
        // The line it settled on, shown before it ran -- this is the part that
        // teaches somebody what they could have typed instead.
        assertTrue(printed.contains("> ls"), printed);
        assertTrue(printed.contains("There is one file"), printed);
        // And it really ran: ls printed the directory, which nothing here faked.
        assertTrue(printed.contains("notes.txt"), printed);
        // The real ls came back through the real interpreter, so the model was
        // told what a person would have seen.
        assertTrue(model.reported.getFirst().getFirst().output().contains("notes.txt"),
                model.reported.toString());
    }

    @Test
    void theModelIsNeverOfferedTheAssistantsOwnCommands() {
        Scripted model = new Scripted();
        run(shell(model), "ask \"hello\"");

        List<String> offered = model.tools.stream().map(t -> String.valueOf(t.get("name"))).toList();
        assertTrue(offered.contains("ls"), "the ordinary roster is there");
        // A model that can ask itself can spend an afternoon doing it, and nothing
        // that touches a stored credential belongs on a list of things it may run.
        assertFalse(offered.contains("ask"), offered.toString());
        assertFalse(offered.contains("key"), offered.toString());
    }

    @Test
    void withoutAKeyItSaysSoRatherThanFailingObscurely() {
        // No model supplied, no key stored: the one thing somebody meets first if
        // they run this before setting anything up.
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        Registry registry = Registry.standard();
        registry.add(new Ask());
        Session session = new Session(new Renderer(stream, stream, false),
                new IndexStore(here.resolve(".indexes")),
                new BufferedReader(new StringReader("")), here);
        Interpreter interpreter = new Interpreter(session, registry);

        // Only meaningful when the machine running the tests has no key of its own.
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("ANTHROPIC_API_KEY") == null);
        System.setProperty("mainframe.home", here.resolve("state").toString());
        try {
            Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> run(interpreter, "ask \"anything\""));
            assertTrue(String.valueOf(thrown.getMessage()).contains("no key has been set"),
                    String.valueOf(thrown.getMessage()));
        } finally {
            System.clearProperty("mainframe.home");
        }
    }

    @Test
    void aSecretQuestionIsAnsweredWithoutAskingAnybody() {
        // No model at all, and no key: if this reached the model it would fail
        // looking for one. It comes back with the refusal instead.
        System.setProperty("mainframe.home", here.resolve("state").toString());
        try {
            Scripted model = new Scripted();
            run(shell(model), "ask \"set my api key to sk-abc123\"");
            assertTrue(printed().contains("not sent"), printed());
        } finally {
            System.clearProperty("mainframe.home");
        }
    }
}
