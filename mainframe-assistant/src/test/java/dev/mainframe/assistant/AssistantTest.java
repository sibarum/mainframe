package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.mainframe.eval.Signature;
import dev.mainframe.value.ValueType;

/** The loop, driven against a model that says exactly what the test needs it to. */
class AssistantTest {

    // ---- the roster ----------------------------------------------------------------------

    private static Signature list() {
        return Signature.named("list", "files")
                .summary("show what is in a folder")
                .optional("folder", ValueType.PATH, "where to look")
                .effect(Signature.Effect.READS)
                .build();
    }

    private static Signature remove() {
        return Signature.named("remove", "files")
                .summary("delete files")
                .required("path", ValueType.PATH, "what to delete")
                .effect(Signature.Effect.DESTRUCTIVE)
                .build();
    }

    // ---- the stand-ins -------------------------------------------------------------------

    /** A model with its answers written down in advance. */
    private static class Scripted implements Model, Model.Chat {

        final Deque<Answer> answers = new ArrayDeque<>();
        final List<List<Outcome>> reported = new ArrayList<>();
        List<Map<String, Object>> tools;
        String said;

        @Override
        public Chat start(String system, List<Map<String, Object>> tools) {
            this.tools = tools;
            return this;
        }

        @Override
        public Answer say(String text) {
            said = text;
            return answers.isEmpty() ? Answer.said("") : answers.removeFirst();
        }

        @Override
        public Answer report(List<Outcome> outcomes) {
            reported.add(outcomes);
            return answers.isEmpty() ? Answer.said("done") : answers.removeFirst();
        }
    }

    private static final class Fake implements Assistant.Runner, Assistant.Voice {

        final List<String> ran = new ArrayList<>();
        final List<String> echoed = new ArrayList<>();
        final List<String> saidToPerson = new ArrayList<>();
        boolean agrees = true;
        RuntimeException blowUp;

        @Override public List<Signature> roster() { return List.of(list(), remove()); }

        @Override
        public String run(String line) {
            ran.add(line);
            if (blowUp != null) throw blowUp;
            return "ok: " + line;
        }

        @Override public void echo(String line) { echoed.add(line); }

        @Override public boolean ok(String line) { return agrees; }

        @Override public void say(String text) { saidToPerson.add(text); }
    }

    private static Model.Call call(String id, String name, Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) arguments.put((String) pairs[i], pairs[i + 1]);
        return new Model.Call(id, name, arguments);
    }

    private static Model.Answer wants(Model.Call... calls) {
        return new Model.Answer("", List.of(calls));
    }

    // ---- what it does --------------------------------------------------------------------

    @Test
    void runsWhatTheModelAskedForAndAnswers() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list", "folder", "src")));
        model.answers.add(Model.Answer.said("Three files."));
        Fake shell = new Fake();

        assertTrue(new Assistant(model, shell, shell).ask("what is in src"));
        assertEquals(List.of("list \"src\""), shell.ran);
        assertEquals(List.of("Three files."), shell.saidToPerson);
    }

    @Test
    void theRosterItIsShownIsTheRosterThatExists() {
        Scripted model = new Scripted();
        new Assistant(model, new Fake(), new Fake()).ask("hello");
        assertEquals(List.of("list", "remove"),
                model.tools.stream().map(t -> String.valueOf(t.get("name"))).toList());
    }

    @Test
    void everyCommandIsShownBeforeItRuns() {
        // Including the ones nobody has to agree to. Echoing is not a confirmation
        // step -- it is the part that teaches the person what they could have typed.
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list", "folder", "src")));
        Fake shell = new Fake();
        new Assistant(model, shell, shell).ask("what is in src");
        assertEquals(List.of("list \"src\""), shell.echoed);
    }

    @Test
    void readingRunsWithoutAsking() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list")));
        Fake shell = new Fake();
        shell.agrees = false;                       // would refuse if it were ever asked
        new Assistant(model, shell, shell).ask("what is here");
        assertEquals(List.of("list"), shell.ran);
    }

    @Test
    void destroyingIsNotRunWhenThePersonSaysNo() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "remove", "path", "notes.txt")));
        Fake shell = new Fake();
        shell.agrees = false;

        new Assistant(model, shell, shell).ask("get rid of notes.txt");
        assertEquals(List.of(), shell.ran, "a declined command must not run");
        // And the model is told, so it can say something sensible rather than
        // reporting a success that did not happen.
        assertTrue(model.reported.getFirst().getFirst().failed());
        assertTrue(model.reported.getFirst().getFirst().output().contains("declined"));
    }

    @Test
    void aCommandThatDoesNotExistComesBackAsAFailureNotACrash() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "teleport", "to", "mars")));
        Fake shell = new Fake();

        assertTrue(new Assistant(model, shell, shell).ask("teleport me"));
        assertEquals(List.of(), shell.ran);
        assertTrue(model.reported.getFirst().getFirst().output().contains("no command called teleport"));
    }

    @Test
    void anInventedArgumentIsRefusedBeforeAnythingRuns() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list", "recursive", true)));
        Fake shell = new Fake();

        new Assistant(model, shell, shell).ask("list everything");
        assertEquals(List.of(), shell.ran, "a line was built from a call that was partly ignored");
        assertTrue(model.reported.getFirst().getFirst().failed());
    }

    @Test
    void aCommandThatFailsIsReportedRatherThanThrown() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list", "folder", "nowhere")));
        Fake shell = new Fake();
        shell.blowUp = new RuntimeException("nowhere: no such folder");

        assertTrue(new Assistant(model, shell, shell).ask("look in nowhere"));
        // The model gets the same explanation the person would have got, because
        // MainFrame's errors are written to be read.
        assertEquals("nowhere: no such folder", model.reported.getFirst().getFirst().output());
    }

    @Test
    void severalCallsInOneTurnAllComeBackTogether() {
        Scripted model = new Scripted();
        model.answers.add(wants(call("a", "list", "folder", "src"), call("b", "list", "folder", "docs")));
        Fake shell = new Fake();

        new Assistant(model, shell, shell).ask("compare src and docs");
        assertEquals(List.of("list \"src\"", "list \"docs\""), shell.ran);
        assertEquals(2, model.reported.getFirst().size());
        assertEquals(List.of("a", "b"), model.reported.getFirst().stream().map(Model.Outcome::id).toList());
    }

    @Test
    void aQuestionCarryingASecretNeverReachesTheModel() {
        Scripted model = new Scripted();
        Fake shell = new Fake();

        assertFalse(new Assistant(model, shell, shell).ask("set my api key to sk-abc123"));
        assertEquals(null, model.said, "the line was forwarded");
        assertEquals(List.of(), shell.ran);
        assertTrue(shell.saidToPerson.getFirst().contains("not sent"));
    }

    @Test
    void aModelThatKeepsGoingIsStoppedAndSaidSo() {
        Scripted model = new Scripted() {
            @Override public Answer report(List<Outcome> outcomes) {
                return wants(call("x", "list"));    // never settles
            }
        };
        model.answers.add(wants(call("a", "list")));
        Fake shell = new Fake();

        assertFalse(new Assistant(model, shell, shell).ask("go forever"));
        // Stopping quietly looks exactly like finishing, so it says which it was.
        assertTrue(shell.saidToPerson.getFirst().contains("Stopped"), shell.saidToPerson.toString());
    }
}
