package dev.mainframe.template.shell;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Signature;
import dev.mainframe.template.Answers;
import dev.mainframe.template.Catalogue;
import dev.mainframe.template.Template;
import dev.mainframe.template.TemplateError;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;

/**
 * {@code templates} -- what there is to start from, and what one will ask for.
 *
 * <p>Rows rather than printed text, so it is a table like everything else:
 * {@code templates | where id == "vexel-desktop"} works, and so does piping the
 * field list of a template into something that reads it. A command that printed
 * a nicely formatted listing would be the one place in MainFrame where the answer
 * could not be used.
 *
 * <pre>{@code
 * templates                  # every template: id, title, summary, where it came from
 * templates vexel-desktop    # that one's slots: what it asks for, and what will do
 * }</pre>
 */
final class TemplateList implements Builtin {

    private static final String CATEGORY = "projects";

    private final Signature signature = Signature.named("templates", CATEGORY)
            .summary("the project templates you can start from, or what one of them asks for")
            .optional("template", ValueType.STRING,
                    "which one to look inside; all of them, listed, if you name none")
            .output(ValueType.TABLE)
            .effect(Signature.Effect.READS)
            .example("templates")
            .example("templates vexel-desktop")
            .example("templates vexel-desktop | where required == true | select name help")
            .build();

    @Override
    public Signature signature() { return signature; }

    @Override
    public Value run(Args args) {
        Catalogue catalogue = Catalogues.forSession(args.session());
        return args.has(0) ? slots(args, catalogue, args.str(0)) : listing(args, catalogue);
    }

    /** Every template, with the one that will not read named rather than hidden. */
    private Value listing(Args args, Catalogue catalogue) {
        List<Value> rows = new ArrayList<>();
        for (String id : catalogue.ids()) {
            Value.Rec row = Value.Rec.of("id", new Value.Str(id));
            try {
                Template template = catalogue.get(id);
                rows.add(row.with("title", new Value.Str(template.title()))
                        .with("summary", new Value.Str(template.summary()))
                        .with("files", new Value.Int(template.items().size()))
                        .with("from", new Value.Str(catalogue.origin(id))));
            } catch (TemplateError e) {
                // One template nobody can read should not hide the rest of the list.
                rows.add(row.with("title", Value.Nothing.INSTANCE)
                        .with("summary", Value.Nothing.INSTANCE)
                        .with("files", Value.Nothing.INSTANCE)
                        .with("from", new Value.Str(String.valueOf(catalogue.origin(id))))
                        .with("problem", new Value.Str(String.valueOf(e.getMessage()))));
            }
        }
        if (rows.isEmpty()) {
            args.session().out().note("no templates -- put one in " + Catalogues.directory());
        } else {
            args.session().out().note("start one with: new " + catalogue.ids().getFirst());
        }
        return new Value.ListVal(List.copyOf(rows));
    }

    /**
     * One template's slots, as rows.
     *
     * <p>The same list the form is built from, which is the point: this is not a
     * description of what the form will ask, it is what the form will ask. A
     * script writing {@code --set} lines reads it, and cannot be looking at
     * something that has drifted.
     */
    private Value slots(Args args, Catalogue catalogue, String id) {
        Template template;
        try {
            template = catalogue.get(id);
        } catch (TemplateError e) {
            throw NewProject.translate(args, "E1500", e);
        }
        Answers presets = Answers.presets(template);
        List<Value> rows = new ArrayList<>(template.slots().size());
        for (Template.Slot slot : template.slots()) {
            String preset = presets.get(slot.name());
            rows.add(Value.Rec.of(
                    "name", new Value.Str(slot.name()),
                    "label", new Value.Str(slot.label()),
                    "holds", new Value.Str(slot.kind().written()),
                    "required", new Value.Bool(slot.required()),
                    "default", preset == null || preset.isBlank()
                            ? Value.Nothing.INSTANCE : new Value.Str(preset),
                    "help", slot.help() == null ? Value.Nothing.INSTANCE : new Value.Str(slot.help())));
        }
        args.session().out().note(template.title() + " -- " + template.summary());
        args.session().out().note("fill it in with: new " + id);
        return new Value.ListVal(List.copyOf(rows));
    }
}
