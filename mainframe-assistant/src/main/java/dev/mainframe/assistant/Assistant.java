package dev.mainframe.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.mainframe.eval.Signature;

/**
 * The loop: a question, some commands, an answer.
 *
 * <p>What makes this small is that almost nothing here is about safety. The
 * model's answer becomes a line of MainFrame ({@link CommandLine}), and that line
 * goes through the same reader and the same interpreter a typed one does -- so
 * argument checking, {@code --dry-run}, planning and the confirmation on anything
 * destructive all apply because they were already there. A second way in would
 * have needed all of it again, and would have drifted from the first the week
 * after it was written.
 *
 * <p>The two things this does decide are worth saying plainly. It decides whether
 * a guess is worth putting in front of a person at all ({@link Gate}), and it
 * decides what never leaves the machine however badly it was typed
 * ({@link Interceptor}). Everything else it delegates.
 */
public final class Assistant {

    /** How many times round before giving up, so a model that loops does not loop forever. */
    private static final int MOST_TURNS = 12;

    /** Somewhere to run a line of MainFrame and get back what it produced. */
    public interface Runner {

        /** Every command that could be called, which is the roster the model is shown. */
        List<Signature> roster();

        /** Runs one line the way a typed one would run. */
        String run(String line) throws Exception;
    }

    /** How the person is consulted, and how they are kept up with. */
    public interface Voice {

        /** Shows the line the assistant settled on, before it runs. */
        void echo(String line);

        /** Asks whether to go ahead. */
        boolean ok(String line);

        /** Says something to the person. */
        void say(String text);
    }

    private final Model model;
    private final Runner runner;
    private final Voice voice;

    public Assistant(Model model, Runner runner, Voice voice) {
        this.model = model;
        this.runner = runner;
        this.voice = voice;
    }

    /**
     * Answers one question, running whatever it needs to along the way.
     *
     * @return false when the question was held back rather than asked
     */
    public boolean ask(String question) {
        if (Interceptor.holds(question)) {
            voice.say(Interceptor.reply());
            return false;
        }

        List<Signature> roster = runner.roster();
        Model.Chat chat = model.start(system(), ToolSchema.of(roster));
        Model.Answer answer = chat.say(question);

        for (int turn = 0; turn < MOST_TURNS && answer.wantsToRun(); turn++) {
            List<Model.Outcome> outcomes = new ArrayList<>();
            for (Model.Call call : answer.calls()) outcomes.add(carryOut(call, roster));
            answer = chat.report(outcomes);
        }

        if (answer.wantsToRun()) {
            // Stopping and saying so, rather than stopping quietly. A loop that
            // gives up without a word looks exactly like one that finished.
            voice.say("Stopped after " + MOST_TURNS + " rounds without settling on an answer.");
            return false;
        }
        if (!answer.text().isBlank()) voice.say(answer.text());
        return true;
    }

    /**
     * One call: name it, write it out, ask if it needs asking, run it.
     *
     * <p>Every failure here goes back to the model as an outcome rather than
     * ending the turn. A model that named a command wrongly can name it rightly
     * next time, and a person who declined one thing has not declined the rest.
     */
    private Model.Outcome carryOut(Model.Call call, List<Signature> roster) {
        Signature signature = find(call.name(), roster);
        if (signature == null) {
            return Model.Outcome.failed(call.id(), "there is no command called " + call.name());
        }

        String line;
        try {
            line = CommandLine.render(signature, call.arguments());
        } catch (IllegalArgumentException e) {
            return Model.Outcome.failed(call.id(), e.getMessage());
        }

        // Shown before it runs, always -- including the ones that do not need
        // asking. A shell that quietly does the right thing when you typed the
        // wrong thing leaves you fluent in nothing.
        voice.echo(line);

        if (Gate.on(signature) == Gate.ASK && !voice.ok(line)) {
            return Model.Outcome.failed(call.id(), "the person declined to run " + line);
        }

        try {
            String output = runner.run(line);
            return Model.Outcome.of(call.id(), output == null ? "" : output);
        } catch (Exception e) {
            // MainFrame's own errors are written to be read, so they are worth
            // handing back as they are: the model gets the same explanation the
            // person would have got.
            String why = e.getMessage();
            return Model.Outcome.failed(call.id(), why == null ? e.toString() : why);
        }
    }

    private static Signature find(String name, List<Signature> roster) {
        for (Signature signature : roster) if (signature.name().equals(name)) return signature;
        return null;
    }

    /**
     * What the model is told about its job.
     *
     * <p>Short on purpose. Most of what it needs to know is in the tool
     * definitions, which are generated from the same declarations that produce
     * {@code help} -- so anything said twice is something that can end up
     * disagreeing with itself.
     */
    static String system() {
        return """
                You are inside MainFrame, a shell. Answer the person by running its \
                commands, which you have as tools.

                Prefer one command that answers the question to several that circle it. \
                If nothing in the roster can do what was asked, say so plainly rather \
                than running something close to it -- a near miss that changes data is \
                worse than an admission.

                Commands that change or destroy anything are shown to the person and run \
                only if they agree, so do not ask for permission in your reply; ask for \
                the command, and the person will be asked.""";
    }
}
