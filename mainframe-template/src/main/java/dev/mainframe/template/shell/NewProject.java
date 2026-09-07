package dev.mainframe.template.shell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.mainframe.MfError;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Plan;
import dev.mainframe.eval.Signature;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.form.FormScreen;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.panel.Editor;
import dev.mainframe.template.Answers;
import dev.mainframe.template.Blueprint;
import dev.mainframe.template.Catalogue;
import dev.mainframe.template.Checks;
import dev.mainframe.template.Scaffold;
import dev.mainframe.template.Template;
import dev.mainframe.template.TemplateError;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;

/**
 * {@code new} -- start a project from a template.
 *
 * <p>A planning command, which is the whole of its safety story and is not a
 * detail of the implementation. Everything is worked out before anything is
 * written: the answers are checked, the destination is checked, every file's
 * bytes are rendered, and the list of files is compared against what is on disk.
 * Only then does the first byte land. So {@code --dry-run} is honest for free,
 * the confirmation prompt says exactly which files, and a template that would
 * fail on its last file fails before its first -- which for this command is the
 * difference between no project and half a project.
 *
 * <h2>The one rule with no flag</h2>
 *
 * <p>A file that is already on disk is never written over. {@code --into} allows
 * the folder to exist and to have things in it, because putting a project into a
 * folder somebody already made is an ordinary thing to want; it does not allow
 * one single existing file to be replaced. There is no flag that does, and that
 * is deliberate: everything else this command can get wrong is undone by deleting
 * the folder, and that one is not.
 *
 * <h2>Three ways to answer</h2>
 *
 * <pre>{@code
 * new vexel-desktop                                    # the form, on screen or down the terminal
 * new vexel-desktop --in=~/src --set={artifactId: "plotter"} --no-ask
 * echo {artifactId: "plotter", where: ~/src} | new vexel-desktop --no-ask
 * }</pre>
 *
 * <p>The same rules hold all three. That is the point of a form being data: the
 * check that stops a typo at the keyboard is the check that stops it in a record
 * a script built, so a project generated unattended is the same project.
 */
final class NewProject implements Builtin.Planning {

    private static final String CATEGORY = "projects";

    /** How many collisions or problems are spelled out before it starts counting. */
    private static final int SHOWN = 8;

    private final Signature signature = Signature.named("new", CATEGORY)
            .summary("start a project from a template")
            .optional("template", ValueType.STRING, "which template; you are asked if you name none")
            .valueFlag("in", '\0', ValueType.PATH,
                    "the folder to create the project in, instead of being asked")
            .valueFlag("set", '\0', ValueType.RECORD,
                    "answers, so they are not asked for: --set={artifactId: \"plotter\"}")
            .switchFlag("into", '\0',
                    "allow a folder that already exists; existing files are still never replaced")
            .switchFlag("no-ask", '\0', "do not put up a form -- use what was given, and refuse if short")
            .input(ValueType.ANY)
            .output(ValueType.TABLE)
            .effect(Signature.Effect.WRITES)
            .example("new vexel-desktop")
            .example("new vexel-desktop --in=~/Documents/GitHub")
            .example("new vexel-desktop --in=~/src --set={artifactId: \"plotter\"} --no-ask")
            .example("new vexel-desktop --dry-run")
            .example("form-recall my-defaults | new vexel-desktop")
            .build();

    @Override
    public Signature signature() { return signature; }

    @Override
    public Plan plan(Args args) {
        Catalogue catalogue = Catalogues.forSession(args.session());
        Template template = pick(args, catalogue);

        Value.Rec given = given(args, template);
        Value.Rec record = args.flag("no-ask") ? given : ask(args, template, given);
        if (record == null) {
            // A cancel is a result, not a failure -- and an empty plan is how this
            // command says so, because the shell already prints "nothing to new".
            return new Plan("new").note("cancelled -- nothing was created");
        }

        Answers answers = Answers.of(Slots.answers(template, record)).filled(template);
        refuseProblems(args, template, answers);

        Path where = where(args, answers, template);
        Path target = folder(args, template, answers, where);
        String destination = Checks.destinationProblem(target, args.flag("into"), args.session().stateDir());
        if (destination != null) {
            throw args.fail("E1502", destination)
                    .hint(args.flag("into") ? null : "to add a project to a folder that already has "
                            + "things in it, use --into")
                    .hint("nothing that is already there is ever written over, either way")
                    .build();
        }

        Blueprint blueprint = blueprint(args, template, answers, catalogue);
        refuseMalformed(args, template, blueprint);
        List<String> hit = Checks.collisions(target, blueprint);
        if (!hit.isEmpty()) throw wouldReplace(args, target, hit);

        return written(args, template, answers, target, blueprint);
    }

    // ---- which template ------------------------------------------------------------------

    private Template pick(Args args, Catalogue catalogue) {
        if (args.has(0)) return read(args, catalogue, args.str(0));
        List<String> ids = catalogue.ids();
        if (ids.size() == 1) {
            args.session().out().note("the only template is " + ids.getFirst());
            return read(args, catalogue, ids.getFirst());
        }
        throw args.failUsage("E1500", "new needs to know which template")
                .hint(ids.isEmpty() ? "none are installed" : "there is: " + String.join(", ", ids))
                .hint("see what each one is with: templates")
                .build();
    }

    private Template read(Args args, Catalogue catalogue, String id) {
        try {
            return catalogue.get(id);
        } catch (TemplateError e) {
            throw translate(args, "E1500", e);
        }
    }

    // ---- what was given, before anybody is asked -------------------------------------------

    /**
     * The answers that arrived without being asked for.
     *
     * <p>Three sources, each beating the one before it: the template's own presets,
     * then a record that came down the pipe, then {@code --set}, with {@code --in}
     * last because it names one slot and could not have meant anything else.
     *
     * <p>Presets being in that list is what makes an unattended run practical, and
     * is worth saying out loud: the version numbers, the window size and the
     * summary are all answers the template already has an opinion about, and a
     * script should not have to repeat them to get the same project a person would
     * get by pressing Enter. They are not hidden -- {@code templates vexel-desktop}
     * prints every one of them.
     */
    private Value.Rec given(Args args, Template template) {
        Map<String, Value> fields = new LinkedHashMap<>(Slots.presets(template).fields());
        Value input = args.input();
        if (input instanceof Value.Rec record) {
            fields.putAll(record.fields());
        } else if (!(input instanceof Value.Nothing)) {
            throw args.fail("E1501", "new starts from one record of answers, and this is "
                            + ValueType.of(input).withArticle())
                    .hint("pipe in a record: echo {artifactId: \"plotter\"} | new vexel-desktop")
                    .hint("or put them on the line: --set={artifactId: \"plotter\"}")
                    .build();
        }
        if (args.hasFlag("set")) {
            if (!(args.flagValue("set") instanceof Value.Rec set)) {
                throw args.failUsage("E1501", "--set takes a record of answers")
                        .hint("e.g. --set={artifactId: \"plotter\", width: 900}")
                        .hint("see what it will take with: templates " + template.id())
                        .build();
            }
            fields.putAll(set.fields());
        }
        if (args.hasFlag("in")) {
            // A convenience for the one slot every template has, and the one a
            // person is most likely to already know off by heart.
            fields.put(whereSlot(template), new Value.PathVal(args.session().resolve(
                    args.flagValue("in") instanceof Value.PathVal path
                            ? path.path().toString()
                            : args.flagStr("in", "."))));
        }
        return new Value.Rec(new LinkedHashMap<>(fields));
    }

    /** The first folder slot, which is the one {@code --in} means. */
    private String whereSlot(Template template) {
        for (Template.Slot slot : template.slots()) {
            if (slot.kind() == Template.Kind.FOLDER) return slot.name();
        }
        return "where";
    }

    // ---- asking --------------------------------------------------------------------------

    /**
     * The form, put up the way MainFrame puts up any other.
     *
     * <p>Deliberately the same two calls the {@code form} command makes rather
     * than anything of its own: with a display to borrow the whole thing goes up
     * at once and the person moves about it, and without one it is printed
     * downwards. Same fields, same rules, same answers -- only the asking differs,
     * and a template has no business being the one place that is not true.
     */
    private Value.Rec ask(Args args, Template template, Value.Rec starting) {
        if (!args.session().somebodyToAsk()) {
            throw args.fail("E1503", "new has nobody to ask")
                    .hint("it puts up a form, and there is no terminal or screen here")
                    .hint("give the answers instead: --in=~/src "
                            + "--set={artifactId: \"plotter\"} --no-ask")
                    .hint("see what it would ask for with: templates " + template.id())
                    .build();
        }
        Form form = Form.read(Slots.fields(template), args.span());
        Editor editor = args.session().editor();
        String title = template.title();
        return editor != null
                ? FormPanel.show(form, starting, title, editor, args.session().cwd())
                : FormScreen.show(form, starting, title, true, args.session());
    }

    // ---- refusing before anything happens ---------------------------------------------------

    /**
     * Everything wrong with the answers, in one error.
     *
     * <p>The form has already refused what it can as it was typed. What is left is
     * the answers that arrived without a form -- and the checks a pattern cannot
     * make, like a package segment that happens to be a Java keyword.
     */
    private void refuseProblems(Args args, Template template, Answers answers) {
        List<String> problems = new ArrayList<>(Checks.problems(template, answers));
        List<String> missing = answers.missing(template);
        for (String name : missing) {
            Template.Slot slot = template.slot(name);
            String wanted = slot == null ? name : slot.label();
            if (!problems.contains(wanted + " is required")) problems.add(wanted + " is required");
        }
        if (problems.isEmpty()) return;
        MfError.Builder error = problems.size() == 1
                ? args.fail("E1504", problems.getFirst())
                : args.fail("E1504", problems.size() + " of the answers will not do");
        if (problems.size() > 1) {
            for (int i = 0; i < Math.min(problems.size(), SHOWN); i++) error.hint(problems.get(i));
            if (problems.size() > SHOWN) error.hint("and " + (problems.size() - SHOWN) + " more");
        }
        if (!missing.isEmpty()) {
            error.hint("give them with --set, e.g. --set={" + missing.getFirst() + ": ...}");
        }
        throw error.build();
    }

    /**
     * A generated pom that will not parse, caught while it is still bytes.
     *
     * <p>A refusal rather than a caution, and one of the few things checked about
     * the <em>output</em> rather than the input. A project whose pom is malformed
     * is not a project with a problem in it -- Maven will not read it at all, so
     * nothing about it can be built, run or diagnosed. It is also the failure most
     * likely to be the template's fault rather than the person's, which is exactly
     * why it should not be discovered by them.
     */
    private void refuseMalformed(Args args, Template template, Blueprint blueprint) {
        List<String> problems = Checks.malformedXml(blueprint);
        if (problems.isEmpty()) return;
        MfError.Builder error = args.fail("E1507",
                "the " + template.id() + " template would write XML that will not parse");
        for (int i = 0; i < Math.min(problems.size(), SHOWN); i++) error.hint(problems.get(i));
        error.hint("nothing was written");
        error.hint("this is the template's fault rather than yours unless an answer put "
                + "a < or an & somewhere");
        throw error.build();
    }

    private Path where(Args args, Answers answers, Template template) {
        String name = whereSlot(template);
        String value = answers.get(name);
        if (value == null || value.isBlank()) {
            throw args.fail("E1504", "nowhere to put it")
                    .hint("say where with --in, e.g. --in ~/Documents/GitHub")
                    .build();
        }
        return args.session().resolve(value);
    }

    private Path folder(Args args, Template template, Answers answers, Path where) {
        try {
            return Scaffold.folder(template, answers, where);
        } catch (TemplateError e) {
            throw translate(args, "E1504", e);
        }
    }

    private Blueprint blueprint(Args args, Template template, Answers answers, Catalogue catalogue) {
        try {
            return Scaffold.of(template, answers, catalogue);
        } catch (TemplateError e) {
            throw translate(args, "E1505", e);
        }
    }

    private MfError wouldReplace(Args args, Path target, List<String> hit) {
        MfError.Builder error = args.fail("E1506", hit.size() == 1
                ? "there is already a " + hit.getFirst() + " in " + display(args, target)
                : hit.size() + " of these files are already in " + display(args, target));
        for (int i = 0; i < Math.min(hit.size(), SHOWN); i++) error.hint(hit.get(i));
        if (hit.size() > SHOWN) error.hint("and " + (hit.size() - SHOWN) + " more");
        error.hint("nothing that is already there is written over -- there is no flag for it");
        error.hint("move them, or point this somewhere else");
        return error.build();
    }

    // ---- the plan ----------------------------------------------------------------------------

    /**
     * One step per file, and one to make the folder.
     *
     * <p>A step apiece rather than one step that writes everything, because
     * {@code --dry-run} and the confirmation prompt both print the steps, and
     * "create 16 files" is not something anybody can check. The rollback that a
     * per-file plan would otherwise cost is in {@link Blueprint.Writing}: the step
     * that fails takes back what the earlier ones made, so a template that dies
     * halfway leaves nothing rather than something that looks like a project.
     */
    private Plan written(Args args, Template template, Answers answers, Path target,
                         Blueprint blueprint) {
        Blueprint.Writing writing = new Blueprint.Writing(target);
        Plan plan = new Plan("create");
        String folder = display(args, target);
        plan.step("make " + folder, guarded(writing, writing::begin));
        for (Blueprint.Entry entry : blueprint.entries()) {
            plan.step("write " + folder + "/" + entry.path(), guarded(writing, () -> writing.write(entry)));
        }
        for (String note : blueprint.notes()) plan.note(note);

        List<String> missing = Checks.missingArtifacts(template, answers, Checks.localRepository());
        if (!missing.isEmpty()) {
            // A caution rather than a refusal: see Checks.missingArtifacts. Said
            // before it runs, so it is on screen with the plan and not after the
            // fact, and said as the command that fixes it.
            plan.note("this will not build yet -- " + missing.size()
                    + (missing.size() == 1 ? " library it needs is" : " libraries it needs are")
                    + " not in your local Maven repository:");
            for (int i = 0; i < Math.min(missing.size(), SHOWN); i++) plan.note("  " + missing.get(i));
            if (missing.size() > SHOWN) plan.note("  and " + (missing.size() - SHOWN) + " more");
            plan.note("  run mvn install in each of those projects first");
        }
        // What to do now comes from the template, because only the template knows.
        // A Maven project ends with mvn compile exec:exec and a folder of notes ends
        // with nothing at all; a shell that guessed would be confidently wrong for
        // every template but the one it was written against.
        if (template.next() != null) plan.note("then: " + answers.resolve(template.next()));
        return plan;
    }

    /** A step that takes the whole write back with it when it fails. */
    private Plan.Action guarded(Blueprint.Writing writing, Plan.Action action) {
        return () -> {
            try {
                action.run();
            } catch (java.io.IOException | RuntimeException e) {
                writing.undo();
                throw e;
            }
        };
    }

    private String display(Args args, Path path) {
        return SafeFs.describe(args.session().cwd(), path);
    }

    /** A template's own refusal, said in MainFrame's voice with its hints kept. */
    static MfError translate(Args args, String code, TemplateError e) {
        MfError.Builder error = args.fail(code, e.getMessage());
        for (String hint : e.hints()) error.hint(hint);
        return error.build();
    }
}
