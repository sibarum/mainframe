package dev.mainframe.form;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;

import dev.mainframe.form.Form.Field;
import dev.mainframe.panel.Editor;
import dev.mainframe.panel.Event;
import dev.mainframe.panel.Screen;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * The same form, on a screen somebody else is painting.
 *
 * <p>This is the second way of asking, alongside {@link FormScreen}, and it shares
 * everything that matters with the first: {@link Form} still says what a good
 * answer is, {@link Field#read} still says what typed text means. All that differs
 * is that the questions are all on view at once and the person can move between
 * them, because the editor is handling the arrow keys MainFrame cannot.
 *
 * <p>The division of labour is the one the protocol describes. The editor lets
 * somebody type, tab and click about; it speaks up only on submit, on a click, or
 * on a field it was asked to report. MainFrame decides everything: what is
 * acceptable, what the error says, what happens next. Nothing here trusts the
 * editor with a rule.
 *
 * <p>One thing a panel can do that a printed form cannot: an entry in a list of
 * details can be taken out again, because there is somewhere to put the button.
 */
public final class FormPanel {

    private static final int LEFT = 3;
    private static final int ENTRY_COLUMN = 21;
    private static final int MIN_WIDTH = 56;
    private static final int MAX_WIDTH = 96;

    /** Prefixes for the two things a click can mean. */
    private static final String ADD = "add:";
    private static final String DROP = "drop:";

    private final Form form;
    private final Editor editor;
    private final Path from;
    private final String title;
    private final int width;

    private int nextId = 1;

    private FormPanel(Form form, String title, Editor editor, Path from) {
        this.form = form;
        this.editor = editor;
        this.from = from;
        this.title = title == null || title.isBlank() ? "form" : title;
        this.width = Math.max(MIN_WIDTH, Math.min(editor.hello().cols(), MAX_WIDTH));
    }

    /**
     * Shows the form on the editor's screen and collects the answers.
     *
     * @return the answers as a record, or null when the person gave up
     */
    public static Value.Rec show(Form form, Value.Rec starting, String title, Editor editor,
                                 Path from) {
        return new FormPanel(form, title, editor, from).run(starting);
    }

    private Value.Rec run(Value.Rec starting) {
        SequencedMap<String, Value> carried = new LinkedHashMap<>();
        if (starting != null) {
            starting.fields().forEach((name, value) -> {
                if (form.field(name) == null) carried.put(name, value);
            });
        }
        SequencedMap<String, Value> answers = offered(starting);
        SequencedMap<String, String> problems = new LinkedHashMap<>();
        // What was typed but could not be read. Shown back as typed, so nobody has
        // to wonder where their text went while they are being told it is wrong.
        SequencedMap<String, String> asTyped = new LinkedHashMap<>();
        String focus = form.fields().isEmpty() ? null : form.fields().getFirst().name();

        while (true) {
            Event event = editor.show(paint(answers, problems, asTyped, focus, carried));
            if (event.is(Event.CANCEL)) return null;

            problems = absorb(event, answers, asTyped);
            if (event.focus() != null) focus = event.focus();

            if (event.is(Event.CLICK)) {
                String clicked = event.on() == null ? "" : event.on();
                if (clicked.startsWith(ADD)) focus = add(clicked.substring(ADD.length()), answers);
                else if (clicked.startsWith(DROP)) focus = drop(clicked.substring(DROP.length()), answers);
                continue;
            }
            if (!event.is(Event.SUBMIT)) continue;   // a change, a resize, something new

            for (Field field : form.fields()) {
                if (problems.containsKey(field.name())) continue;
                String problem = field.problem(answers.get(field.name()));
                if (problem != null) problems.put(field.name(), problem);
            }
            if (problems.isEmpty()) return record(answers, carried);
            // The first problem down the screen, not the first one noticed --
            // those are different orders, and only one of them is where the eye
            // already is.
            for (Field field : form.fields()) {
                if (problems.containsKey(field.name())) { focus = field.name(); break; }
            }
        }
    }

    // ---- what the editor sent back -------------------------------------------------------

    /**
     * Takes the values off the event, reading each as the type its field declared.
     *
     * <p>The editor sends text, always: it was never told what a size or a moment
     * is, and it is not going to start guessing now.
     */
    private SequencedMap<String, String> absorb(Event event, SequencedMap<String, Value> answers,
                                                SequencedMap<String, String> asTyped) {
        SequencedMap<String, String> problems = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            // A list of details is MainFrame's to keep -- the editor only ever
            // shows it and reports the buttons beside it.
            if (field.isGroup()) continue;
            String typed = event.field(field.name());
            if (typed == null) continue;             // the editor said nothing about this one
            asTyped.remove(field.name());
            if (typed.isBlank()) {
                answers.put(field.name(), Value.Nothing.INSTANCE);
                continue;
            }
            Form.Reading reading = field.read(typed, from);
            if (reading.ok()) {
                answers.put(field.name(), reading.value());
            } else {
                asTyped.put(field.name(), typed);
                problems.put(field.name(), reading.problem());
            }
        }
        return problems;
    }

    /** Asks for one entry of a list of details, on a screen of its own. */
    private String add(String name, SequencedMap<String, Value> answers) {
        Field group = form.field(name);
        if (group == null || !group.isGroup()) return name;
        Value.Rec entry = new FormPanel(group.entries(), group.label(), editor, from).run(null);
        if (entry == null) return name;              // they backed out of the entry, not the form
        List<Value> entries = new ArrayList<>(Values.rows(answers.getOrDefault(name,
                Value.Nothing.INSTANCE)));
        entries.add(entry);
        answers.put(name, new Value.ListVal(List.copyOf(entries)));
        return name;
    }

    /** Takes one entry back out, which is the thing a printed form cannot offer. */
    private String drop(String what, SequencedMap<String, Value> answers) {
        int colon = what.lastIndexOf(':');
        if (colon <= 0) return null;
        String name = what.substring(0, colon);
        List<Value> entries = new ArrayList<>(Values.rows(answers.getOrDefault(name,
                Value.Nothing.INSTANCE)));
        try {
            int at = Integer.parseInt(what.substring(colon + 1)) - 1;
            if (at >= 0 && at < entries.size()) entries.remove(at);
        } catch (NumberFormatException e) {
            return name;                             // an editor inventing a name; nothing to do
        }
        answers.put(name, new Value.ListVal(List.copyOf(entries)));
        return name;
    }

    // ---- painting it ---------------------------------------------------------------------

    private Screen paint(SequencedMap<String, Value> answers, Map<String, String> problems,
                         Map<String, String> asTyped, String focus, Map<String, Value> carried) {
        Screen screen = new Screen(nextId++).title(title.toUpperCase(Locale.ROOT));
        if (focus != null) screen.focus(focus);

        screen.text(1, LEFT, title.toUpperCase(Locale.ROOT), "title");
        screen.text(2, LEFT, "=".repeat(width - LEFT), "frame");
        int row = 4;
        for (Field field : form.fields()) {
            row = field.isGroup()
                    ? entries(screen, field, answers, problems, row)
                    : one(screen, field, answers, problems, asTyped, row);
            row++;
        }
        if (!carried.isEmpty()) {
            screen.text(row++, LEFT, "carried through untouched: "
                    + String.join(", ", carried.keySet()), "hint");
        }
        screen.text(row, LEFT, "=".repeat(width - LEFT), "frame");
        screen.text(row + 1, LEFT, "F12 Submit    F3 Cancel", "status");
        screen.key("F12", Event.SUBMIT, "Submit");
        screen.key("F3", Event.CANCEL, "Cancel");
        return screen;
    }

    private int one(Screen screen, Field field, Map<String, Value> answers,
                    Map<String, String> problems, Map<String, String> asTyped, int row) {
        String name = field.name();
        screen.text(row, LEFT, field.heading(), "label");

        String value = asTyped.containsKey(name) ? asTyped.get(name) : shown(answers.get(name));
        if (field.choices() != null) {
            screen.choice(row, ENTRY_COLUMN, name, field.choices(), value,
                    editor.hello().can("choice"));
        } else {
            screen.entry(row, ENTRY_COLUMN, name, entryWidth(), value, field.type().display());
        }
        if (field.required()) screen.text(row, width - 8, "required", "hint");

        String rules = field.rules();
        if (!rules.isEmpty()) screen.text(++row, ENTRY_COLUMN, rules, "hint");
        String problem = problems.get(name);
        if (problem != null) screen.text(++row, ENTRY_COLUMN, problem, "error");
        return row;
    }

    /**
     * A list of details: what is in it, a button to take each one out, and a
     * button to add another.
     */
    private int entries(Screen screen, Field field, Map<String, Value> answers,
                        Map<String, String> problems, int row) {
        String name = field.name();
        List<Value.Rec> rows = Values.rows(answers.getOrDefault(name, Value.Nothing.INSTANCE));
        int top = row;
        boolean boxed = editor.hello().can("box");

        // Boxed, the heading is the caption on the frame's top edge; unboxed it
        // is a label like any other. Never both, or they land on the same cells.
        if (!boxed) screen.text(row, LEFT, field.heading(), "label");
        if (field.required()) screen.text(row, width - 8, "required", "hint");
        String rules = field.rules();
        if (!rules.isEmpty()) screen.text(++row, ENTRY_COLUMN, rules, "hint");

        for (int i = 0; i < rows.size(); i++) {
            row++;
            screen.text(row, ENTRY_COLUMN, "[" + (i + 1) + "] " + line(rows.get(i)), "plain");
            if (editor.hello().can("action")) {
                // The thing a printed form cannot offer: somewhere to click to
                // take one back out again.
                screen.action(row, width - 9, DROP + name + ":" + (i + 1), "remove", null);
            }
        }
        if (rows.isEmpty()) screen.text(++row, ENTRY_COLUMN, "(none yet)", "hint");

        row++;
        if (editor.hello().can("action")) {
            screen.action(row, ENTRY_COLUMN, ADD + name, "+ Add another", null);
        } else {
            // Without somewhere to click there is nothing to click, so the list is
            // shown and left alone rather than half-offered.
            screen.text(row, ENTRY_COLUMN, "(this editor cannot add entries)", "hint");
        }
        String problem = problems.get(name);
        if (problem != null) screen.text(++row, ENTRY_COLUMN, problem, "error");

        if (boxed) screen.box(top, LEFT - 1, row - top + 2, width - LEFT - 1, field.heading());
        return row;
    }

    private static String line(Value.Rec entry) {
        List<String> cells = new ArrayList<>();
        entry.fields().forEach((name, value) -> cells.add(Values.display(value)));
        return String.join(", ", cells);
    }

    private static String shown(Value value) {
        return value == null || value instanceof Value.Nothing ? "" : Values.display(value);
    }

    private int entryWidth() { return Math.max(12, width - ENTRY_COLUMN - 10); }

    // ---- the same two helpers the printed form uses ---------------------------------------

    private SequencedMap<String, Value> offered(Value.Rec starting) {
        SequencedMap<String, Value> answers = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            Value seed = starting == null ? null : starting.get(field.name());
            if (seed != null && !(seed instanceof Value.Nothing) && !field.type().accepts(seed)) {
                editor.print(field.name() + " arrived as " + ValueType.of(seed).withArticle()
                        + " where this form wants " + field.type().withArticle()
                        + ", so it is being asked for afresh", "error");
                seed = null;
            }
            if (seed == null || seed instanceof Value.Nothing) seed = field.preset();
            if (seed != null && !(seed instanceof Value.Nothing)) answers.put(field.name(), seed);
            // A list nobody has added to is an empty list, not an absent one --
            // the same answer the printed form gives, so the two agree.
            else if (field.isGroup()) answers.put(field.name(), new Value.ListVal(List.of()));
        }
        return answers;
    }

    private Value.Rec record(Map<String, Value> answers, Map<String, Value> carried) {
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            Value answer = answers.get(field.name());
            fields.put(field.name(), answer == null ? Value.Nothing.INSTANCE : answer);
        }
        carried.forEach(fields::putIfAbsent);
        return new Value.Rec(fields);
    }
}
