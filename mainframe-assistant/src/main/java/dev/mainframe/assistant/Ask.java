package dev.mainframe.assistant;

import java.io.IOException;

import dev.mainframe.MfError;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Signature;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;

/**
 * {@code ask "what files are here"} -- say what you want in words.
 *
 * <p>A command for now, because it is the smallest thing that can be built and
 * the easiest to be sure about. Where this is going is the parse-failure path: a
 * line MainFrame could not read comes here instead of becoming an error, so the
 * cost of asking a model lands only on input that was going to fail anyway. The
 * engine is the same either way, which is why it was worth writing this one
 * first.
 */
public final class Ask implements Builtin {

    static final String NAME = "ask";

    /**
     * Declared as {@code READS}, which is worth defending.
     *
     * <p>What {@code ask} does by itself is send a question and read an answer. It
     * can end up deleting a file, but not by being {@code ask} -- by running
     * {@code remove}, which is declared destructive, plans what it will do, and
     * asks before it does it. Declaring {@code ask} destructive would put a
     * confirmation in front of "what files are here", and a prompt that fires on
     * everything is a prompt people learn to clear without reading. The guardrails
     * belong on the command that actually does the thing, which is where they
     * already are.
     */
    private static final Signature SIGNATURE = Signature.named(NAME, "assistant")
            .summary("say what you want in words, and let MainFrame work out the commands")
            .required("question", ValueType.STRING, "what you want, in plain language")
            .effect(Signature.Effect.READS)
            .example("ask \"what files are here\"")
            .example("ask \"which of these is biggest\"")
            .build();

    private final Model model;

    /** With a model of your own -- a test's, or one that is not Anthropic's. */
    public Ask(Model model) { this.model = model; }

    /** With whatever key MainFrame has been given, looked up when it is needed. */
    public Ask() { this(null); }

    @Override
    public Signature signature() { return SIGNATURE; }

    @Override
    public Value run(Args args) {
        String question = args.str(0);
        ShellVoice shell = new ShellVoice(args);
        new Assistant(model == null ? claude(args) : model, shell, shell).ask(question);
        // Nothing down the pipe. What the assistant had to say has been said, and
        // handing back its prose as a value would invite it into a pipeline, where
        // it is not data and never was.
        return Value.Nothing.INSTANCE;
    }

    /**
     * The model, or an explanation of why there is not one.
     *
     * <p>Looked up per call rather than held, so setting a key takes effect on the
     * next line instead of the next session.
     */
    private static Model claude(Args args) {
        String key;
        try {
            key = Secrets.inState().read();
        } catch (IOException e) {
            throw MfError.of("E1300", "the stored key could not be read: " + e.getMessage())
                    .at(args.span())
                    .hint("set it again with `key set`")
                    .build();
        }
        if (key == null) {
            // The environment second, so a machine that already has one configured
            // the usual way works without being told twice.
            if (System.getenv("ANTHROPIC_API_KEY") != null) return Claude.fromEnvironment();
            throw MfError.of("E1300", "no key has been set, so there is nothing to ask")
                    .at(args.span())
                    .hint("run `key set` to enter one")
                    .hint("or set ANTHROPIC_API_KEY in the environment")
                    .build();
        }
        return Claude.withKey(key);
    }
}
