package dev.mainframe.assistant;

import java.util.List;

import dev.mainframe.MfError;
import dev.mainframe.Span;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Signature;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.form.FormScreen;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;

/**
 * {@code key set}, {@code key status}, {@code key forget} -- the one credential
 * MainFrame keeps.
 *
 * <p>A planning command, because setting a key writes a file and MainFrame's rule
 * is that anything which writes says what it will write first. The plan is what
 * makes {@code --dry-run} mean something here, and what puts the warning in front
 * of the decision rather than after it.
 *
 * <p>The key itself is asked for through a form with one secret field, so it is
 * masked as it is typed, never echoed, and never sent back down to the editor.
 * If the editor cannot mask, the form refuses rather than asking anyway -- see
 * {@code FormPanel}. That refusal is the feature.
 */
public final class Keys implements Builtin.Planning {

    static final String NAME = "key";

    private static final String SET = "set";
    private static final String STATUS = "status";
    private static final String FORGET = "forget";

    private static final Signature SIGNATURE = Signature.named(NAME, "assistant")
            .summary("set, check or forget the key the assistant uses")
            .required("action", ValueType.STRING, "set, status or forget")
            .effect(Signature.Effect.WRITES)
            .example("key set")
            .example("key status")
            .example("key forget")
            .build();

    private final Secrets secrets;

    public Keys(Secrets secrets) { this.secrets = secrets; }

    public Keys() { this(Secrets.inState()); }

    @Override
    public Signature signature() { return SIGNATURE; }

    @Override
    public Plan plan(Args args) {
        String action = args.str(0);
        return switch (action) {
            case SET -> setting(args);
            case STATUS -> status();
            case FORGET -> forgetting();
            default -> throw MfError.of("E1301", "there is no key command called \"" + action + "\"")
                    .at(args.span())
                    .hint("the actions are set, status and forget")
                    .build();
        };
    }

    /**
     * Asking is part of planning here, which is unusual and deliberate.
     *
     * <p>Planning is supposed to change nothing, and this changes nothing: it puts
     * a form up and holds what came back. Doing it here rather than in the step is
     * what lets the plan say what it is about to write, and what lets
     * {@code --dry-run} rehearse the whole thing without leaving a key behind.
     */
    private Plan setting(Args args) {
        Value.Rec answers = ask(args);
        if (answers == null) return new Plan("set").note("nothing was entered, so nothing was stored");

        String key = text(answers);
        if (key.isBlank()) {
            return new Plan("set").note("nothing was entered, so nothing was stored");
        }

        Plan plan = new Plan("set");
        plan.note(secrets.risk());
        // The description never carries the key. A plan is printed, and a printed
        // plan is the same thing as an echo.
        plan.step("store the key in " + secrets.file(), () -> secrets.write(key));
        return plan;
    }

    private Plan status() {
        Plan plan = new Plan("check");
        // Whether, never what. A status that showed the key would undo everything
        // the secret field was for.
        plan.note(secrets.isSet()
                ? "a key is set, in " + secrets.file()
                : "no key is set -- run `key set` to enter one");
        return plan;
    }

    private Plan forgetting() {
        if (!secrets.isSet()) return new Plan("forget").note("there is no key to forget");
        Plan plan = new Plan("forget");
        plan.step("delete the key in " + secrets.file(), secrets::clear);
        return plan;
    }

    /** One field, secret, asked however this session can ask. */
    private static Value.Rec ask(Args args) {
        Form form = Form.read(new Value.ListVal(List.of(Value.Rec.of()
                .with("name", new Value.Str("key"))
                .with("label", new Value.Str("api key"))
                .with("type", new Value.Str("string"))
                .with("required", new Value.Bool(true))
                .with("secret", new Value.Bool(true))
                .with("help", new Value.Str("from console.anthropic.com")))), Span.NONE);

        return args.session().editor() != null
                ? FormPanel.show(form, null, "Assistant key", args.session().editor(),
                        args.session().cwd())
                : FormScreen.show(form, null, "Assistant key", true, args.session());
    }

    private static String text(Value.Rec answers) {
        Value value = answers.get("key");
        return value instanceof Value.Str string ? string.value() : "";
    }

}
