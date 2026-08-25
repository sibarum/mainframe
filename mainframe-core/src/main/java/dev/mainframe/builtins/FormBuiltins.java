package dev.mainframe.builtins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import dev.mainframe.MfError;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.form.FormScreen;
import dev.mainframe.form.FormStore;
import dev.mainframe.panel.Editor;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * Asking a person for a record, checking one that arrived without being asked,
 * and keeping one so the next person does not start from nothing.
 *
 * <p>{@code form} and {@code form-check} share one definition of what a good
 * answer is, which is the whole point of writing a form as data: the rules that
 * stop a typo at the keyboard are the rules that stop it in a file.
 *
 * <p>The three that save, recall and forget share the other half of that idea. An
 * answer sheet is a record, a record has one written form, so keeping one is
 * writing that form under a name -- and starting from it again is reading it back.
 * Nothing is stored that could not have been typed.
 */
public final class FormBuiltins {

    private static final String CATEGORY = "data entry";

    /** How many problems form-check spells out before it starts counting. */
    private static final int PROBLEMS_SHOWN = 8;

    private FormBuiltins() {}

    public static void register(Registry registry) {
        registry.add(form());
        registry.add(formCheck());
        registry.add(formSave());
        registry.add(formRecall());
        registry.add(formForget());
    }

    private static Builtin form() {
        Signature signature = Signature.named("form", CATEGORY)
                .summary("ask for a record, field by field, and hand back the answers")
                .required("fields", ValueType.LIST,
                        "the fields to ask for: a table of field records, or just their names")
                .valueFlag("title", '\0', ValueType.STRING, "the heading across the top of the form")
                .valueFlag("prefill", '\0', ValueType.STRING,
                        "start from the form data saved under this name, if there is any")
                .switchFlag("no-review", '\0',
                        "hand the answers back without showing them for approval")
                .input(ValueType.ANY)
                .output(ValueType.RECORD)
                .effect(Effect.READS)
                .example("form [\"name\", \"email\"]")
                .example("form $contact --title=\"New contact\" | save contact.json")
                .example("open contact.json | form $contact | save contact.json --force")
                .example("form $contact --prefill=defaults | form-save defaults")
                .build();
        return Cmd.of(signature, args -> {
            Form form = Form.read(args.value(0), args.span());
            Value.Rec starting = merged(prefill(args), starting(args));
            if (!args.session().interactive()) {
                throw args.fail("E1209", "form has nobody to ask")
                        .hint("it reads the answers from the terminal, and there is not one here")
                        .hint("build the record instead, e.g. echo {name: \"Ada\", email: \"ada@x.io\"}")
                        .hint("to hold data to these same rules without asking, use form-check")
                        .build();
            }
            // With a display to borrow, the whole form goes up at once and the
            // person moves about it; without one it is printed downwards. Same
            // fields, same rules, same answers -- only the asking differs.
            Editor editor = args.session().editor();
            String title = args.flagStr("title", "form");
            Value.Rec answers = editor != null
                    ? FormPanel.show(form, starting, title, editor, args.session().cwd())
                    : FormScreen.show(form, starting, title, !args.flag("no-review"), args.session());
            if (answers == null) {
                // A cancel is a result, not a failure -- but it is never silent,
                // because the next command in the line is about to get nothing.
                args.session().out().note("cancelled -- nothing was entered");
                return Value.Nothing.INSTANCE;
            }
            return answers;
        });
    }

    /** The record being edited, when one was piped in. */
    private static Value.Rec starting(Args args) {
        Value input = args.input();
        if (input instanceof Value.Nothing) return null;
        if (input instanceof Value.Rec record) return record;
        throw args.fail("E1208", "form fills in one record, and this is a " + args.describeInput())
                .hint("pipe in a record to edit it: open contact.json | form $contact")
                .hint("or start the line with form to ask for a new one")
                .hint("for a table, ask once per row: open rows.json | each { form $contact }")
                .build();
    }

    /**
     * What was saved under {@code --prefill}, or null.
     *
     * <p>A name with nothing saved under it yet is said out loud and carried on
     * from, because the first time round there is nothing to start from and that
     * is not a mistake. A name that could never have been saved, or saved data
     * that cannot be read, is a mistake and stops.
     */
    private static Value.Rec prefill(Args args) {
        if (!args.hasFlag("prefill")) return null;
        String name = args.flagStr("prefill", "");
        checkName(args, name);
        FormStore store = args.session().forms();
        if (!store.exists(name)) {
            String note = "nothing is saved as " + name + " yet, so there is nothing to start from";
            String closest = Suggest.closest(name, store.names());
            args.session().out().note(closest == null ? note : note + " -- did you mean " + closest + "?");
            return null;
        }
        Value saved = load(args, name);
        if (saved instanceof Value.Rec record) return record;
        throw args.fail("E1213", "what is saved as " + name + " is "
                        + ValueType.of(saved).withArticle() + ", not a record")
                .hint("a form starts from a record: one value per field")
                .hint("look at it with: form-recall " + name)
                .build();
    }

    /** Two sets of starting values, the second winning field by field. */
    private static Value.Rec merged(Value.Rec under, Value.Rec over) {
        if (under == null) return over;
        if (over == null) return under;
        SequencedMap<String, Value> fields = new LinkedHashMap<>(under.fields());
        fields.putAll(over.fields());
        return new Value.Rec(fields);
    }

    // ---- keeping the answers ----------------------------------------------------------

    private static Builtin formSave() {
        Signature signature = Signature.named("form-save", CATEGORY)
                .summary("keep a record of answers under a name, to start from next time")
                .required("name", ValueType.STRING, "what to call it")
                .input(ValueType.RECORD)
                .output(ValueType.TABLE)
                .effect(Effect.WRITES)
                .example("form $contact | form-save last-contact")
                .example("form $contact --prefill=defaults | select tier office | form-save defaults")
                .build();
        return Cmd.planning(signature, args -> {
            String name = args.str(0);
            checkName(args, name);
            Value.Rec record = (Value.Rec) args.input();
            FormStore store = args.session().forms();
            boolean replacing = store.exists(name);
            // Written out while planning, so a value that cannot be written down
            // fails before the file that is there has been touched.
            String source = Values.source(record);
            int count = record.fields().size();
            Plan plan = new Plan("save")
                    .step((replacing ? "replace" : "save") + " the answers saved as " + name
                                    + " (" + count + (count == 1 ? " field" : " fields") + ")",
                            () -> store.write(name, source))
                    .note("start from it next time with: form <fields> --prefill=" + name);
            if (replacing) plan.note("what was saved as " + name + " before is replaced, not kept");
            return plan;
        });
    }

    private static Builtin formRecall() {
        Signature signature = Signature.named("form-recall", CATEGORY)
                .summary("hand back form data you saved, or list what you have saved")
                .optional("name", ValueType.STRING, "which one; all of them, listed, if you name none")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("form-recall")
                .example("form-recall defaults")
                .example("form-recall last-contact | form $contact")
                .build();
        return Cmd.of(signature, args -> {
            FormStore store = args.session().forms();
            if (args.has(0)) {
                String name = args.str(0);
                checkName(args, name);
                if (!store.exists(name)) throw unknown(args, name, store);
                return load(args, name);
            }
            List<Value> rows = new ArrayList<>();
            for (String name : store.names()) {
                Value.Rec row = Value.Rec.of(
                        "name", new Value.Str(name),
                        "saved", new Value.Time(store.savedAt(name)),
                        "on-disk", new Value.Size(store.fileSize(name)));
                try {
                    Value saved = Formats.read(Formats.Format.SOURCE, store.read(name), args);
                    rows.add(row.with("fields", new Value.Str(saved instanceof Value.Rec record
                            ? String.join(", ", record.fields().keySet())
                            : ValueType.of(saved).display())));
                } catch (IOException | MfError e) {
                    // One file nobody can read should not hide the rest of the list.
                    rows.add(row.with("fields", Value.Nothing.INSTANCE)
                            .with("problem", new Value.Str(String.valueOf(e.getMessage()))));
                }
            }
            if (rows.isEmpty()) {
                args.session().out().note("nothing saved yet -- try: form [\"name\"] | form-save defaults");
            }
            return new Value.ListVal(List.copyOf(rows));
        });
    }

    private static Builtin formForget() {
        Signature signature = Signature.named("form-forget", CATEGORY)
                .summary("stop keeping form data you saved")
                .required("name", ValueType.STRING, "which one to forget")
                .output(ValueType.TABLE)
                .effect(Effect.DESTRUCTIVE)
                .example("form-forget defaults")
                .build();
        return Cmd.planning(signature, args -> {
            String name = args.str(0);
            checkName(args, name);
            FormStore store = args.session().forms();
            if (!store.exists(name)) throw unknown(args, name, store);
            return new Plan("forget")
                    .step("forget the answers saved as " + name, () -> store.drop(name))
                    .note("it was in " + store.file(name));
        });
    }

    // ---- shared by the three ----------------------------------------------------------

    private static void checkName(Args args, String name) {
        String problem = FormStore.problemWithName(name);
        if (problem == null) return;
        throw args.fail("E1211", problem)
                .hint("a name is one word, e.g. defaults or last-contact")
                .hint("to keep it at a path of your own choosing, use save and open instead")
                .build();
    }

    private static MfError unknown(Args args, String name, FormStore store) {
        var error = args.fail("E1212", "nothing is saved as " + name);
        String closest = Suggest.closest(name, store.names());
        if (closest != null) error.hint("did you mean " + closest + "?");
        error.hint(store.names().isEmpty()
                ? "save some first: form <fields> | form-save " + name
                : "you have: " + String.join(", ", store.names()));
        return error.build();
    }

    private static Value load(Args args, String name) {
        FormStore store = args.session().forms();
        try {
            return Formats.read(Formats.Format.SOURCE, store.read(name), args);
        } catch (IOException e) {
            throw args.fail("E1213", "could not read what is saved as " + name + ": " + e.getMessage())
                    .hint("the file is " + store.file(name))
                    .hint("forget it and save again: form-forget " + name)
                    .build();
        }
    }

    private static Builtin formCheck() {
        Signature signature = Signature.named("form-check", CATEGORY)
                .summary("hold a record, or a table of them, to a form's rules")
                .required("fields", ValueType.LIST, "the same fields you would hand to form")
                .input(ValueType.LIST)
                .output(ValueType.LIST)
                .effect(Effect.PURE)
                .example("open contacts.csv | form-check $contact")
                .example("open theirs.json | cast {joined: time} | form-check $contact | save ours.csv")
                .build();
        return Cmd.of(signature, args -> {
            Form form = Form.read(args.value(0), args.span());
            Value input = args.input();
            boolean single = input instanceof Value.Rec;
            List<Value> items = args.items();

            List<String> problems = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof Value.Rec row)) {
                    throw args.fail("E1208", "form-check checks records, and item " + (i + 1)
                                    + " is " + ValueType.of(items.get(i)).withArticle())
                            .hint("pipe in a record, or a table of them")
                            .hint("see what you have with: ... | describe")
                            .build();
                }
                String where = single ? "" : "row " + (i + 1) + ": ";
                for (String problem : form.problems(row)) problems.add(where + problem);
            }
            if (!problems.isEmpty()) throw refused(args, form, problems);
            return input;
        });
    }

    /**
     * One error carrying every problem, not the first one found. Somebody fixing
     * a file wants the whole list, not a round trip per mistake.
     */
    private static MfError refused(Args args, Form form, List<String> problems) {
        MfError.Builder error = problems.size() == 1
                ? args.fail("E1210", problems.getFirst())
                : args.fail("E1210", problems.size() + " answers do not fit these fields");
        if (problems.size() == 1) {
            error.hint("the fields you gave are: " + String.join(", ", form.names()));
        } else {
            for (int i = 0; i < Math.min(problems.size(), PROBLEMS_SHOWN); i++) {
                error.hint(problems.get(i));
            }
            if (problems.size() > PROBLEMS_SHOWN) {
                error.hint("and " + (problems.size() - PROBLEMS_SHOWN) + " more like that");
            }
        }
        return error.build();
    }
}
