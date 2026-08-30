package dev.mainframe.assistant;

import java.util.List;
import java.util.Map;

/**
 * Something that can be asked, and that can ask for commands to be run.
 *
 * <p>An interface rather than a class because the loop in {@link Assistant} is
 * the part worth being sure about, and being sure about it should not cost an
 * API call. Everything the loop decides -- what may run unattended, what has to
 * be asked about, what happens when a call names a command that does not exist
 * -- is exercised against a scripted model in the tests. What is left for the
 * real one is turning records into JSON.
 *
 * <p>A conversation remembers what has been said, so this is a {@link Chat}
 * rather than a function. The alternative is handing the whole history back and
 * forth, which makes the caller responsible for a shape only the model cares
 * about.
 */
public interface Model {

    /** A command the model wants run, as it sent it. */
    record Call(String id, String name, Map<String, Object> arguments) {}

    /** What running one produced, on its way back. */
    record Outcome(String id, String output, boolean failed) {

        public static Outcome of(String id, String output) { return new Outcome(id, output, false); }

        public static Outcome failed(String id, String why) { return new Outcome(id, why, true); }
    }

    /**
     * One turn back from the model: something to say, commands to run, or both.
     *
     * @param text  what to show the person, which may be empty when the model
     *              only wants to run something
     * @param calls what it wants run, in the order it asked
     */
    record Answer(String text, List<Call> calls) {

        public static Answer said(String text) { return new Answer(text, List.of()); }

        public boolean wantsToRun() { return !calls.isEmpty(); }
    }

    /** A conversation that remembers what has been said in it. */
    interface Chat {

        /** Asks a question, or tells it something. */
        Answer say(String text);

        /** Hands back what the commands it asked for produced. */
        Answer report(List<Outcome> outcomes);
    }

    /**
     * @param system what the model is told about its job, once
     * @param tools  the roster, as {@link ToolSchema} renders it
     */
    Chat start(String system, List<Map<String, Object>> tools);
}
