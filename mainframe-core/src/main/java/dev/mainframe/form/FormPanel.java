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

    /**
     * The widest a form is laid out, however much room the editor says it has.
     *
     * <p>A cap rather than a fraction, because what a form is mostly deciding with its width is how long an entry
     * field is, and past a certain length a field is harder to read rather than easier. An editor with more room
     * than this is told so and may do as it likes with the rest — the protocol says as much.
     */
    private static final int MAX_WIDTH = 120;

    /** Prefixes for the things a click can mean. Prefixed so a field can be called anything at all. */
    private static final String ADD = "add:";
    private static final String DROP = "drop:";
    private static final String SUBMIT = "do:submit";
    private static final String CANCEL = "do:cancel";

    private final Form form;
    private final Editor editor;
    private final Path from;
    private final String title;

    private int nextId = 1;

    private FormPanel(Form form, String title, Editor editor, Path from) {
        this.form = form;
        this.editor = editor;
        this.from = from;
        this.title = title == null || title.isBlank() ? "form" : title;
    }

    /**
     * How wide to lay this screen out, asked afresh every time one is painted.
     *
     * <p>Asked afresh rather than settled once, because an editor's room is not a constant: a window gets
     * dragged. An editor that reports a {@code resize} gets the next screen laid out for what it has now, and one
     * that does not is asked the same question and gives the same answer, so nothing has to know which kind it is
     * talking to.
     */
    private int width() {
        return Math.max(MIN_WIDTH, Math.min(editor.hello().cols(), MAX_WIDTH));
    }

    /**
     * How much room a line of prose under a field has.
     *
     * <p>Four columns spare on the right: three to clear the frame that a list of details draws around it, and one
     * more so the text does not sit against it. Prose that touches a border reads as prose that has overrun.
     *
     * <p>Prose is the only thing here whose length MainFrame does not choose — a label is as long as
     * the field is called and an entry is as wide as it was told, but a hint is however long somebody wrote it —
     * so it is the only thing that has to be fitted rather than placed.
     */
    private int room() {
        return Math.max(12, width() - ENTRY_COLUMN - 4);
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

            // A submit is a submit whether it arrived as a key or as somebody pressing the button that says so.
            // Keeping the two apart until here is what lets the buttons be ordinary parts: MainFrame names them,
            // the editor reports which was pressed, and neither has to know they are special.
            boolean submitted = event.is(Event.SUBMIT);
            if (event.is(Event.CLICK)) {
                String clicked = event.on() == null ? "" : event.on();
                if (clicked.equals(CANCEL)) return null;
                else if (clicked.equals(SUBMIT)) submitted = true;
                else if (clicked.startsWith(ADD)) { focus = add(clicked.substring(ADD.length()), answers); continue; }
                else if (clicked.startsWith(DROP)) { focus = drop(clicked.substring(DROP.length()), answers); continue; }
                else continue;                      // a name nothing here knows; nothing to do about it
            }
            if (!submitted) continue;               // a change, a resize, something new

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

        int width = width();
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
            for (String line : wrapped("carried through untouched: "
                    + String.join(", ", carried.keySet()), width - LEFT - 1)) {
                screen.text(row++, LEFT, line, "hint");
            }
        }
        screen.text(row, LEFT, "=".repeat(width - LEFT), "frame");
        screen.key("F12", Event.SUBMIT, "Submit");
        screen.key("F3", Event.CANCEL, "Cancel");
        // Buttons where the key line used to be, and the key line only where there can be no buttons. A form whose
        // only way out is a function key is a form that cannot be finished on a keyboard that has none, which is
        // most of them -- so the keys stay listed, for whoever has them, and stop being the way. Never both at
        // once: they want the same cells, and an editor that places characters where it was told would render the
        // two through each other.
        if (editor.hello().can("action")) {
            screen.action(row + 1, LEFT, SUBMIT, "Submit", "F12");
            screen.action(row + 1, LEFT + 10, CANCEL, "Cancel", "F3");
        } else {
            screen.text(row + 1, LEFT, "F12 Submit    F3 Cancel", "status");
        }
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
        if (field.required()) screen.text(row, width() - 8, "required", "hint");

        // The deepest row written rather than the row this counted to, because a
        // part is allowed to take more room than it was placed at: a choice an
        // editor cannot show renders itself down to the options and an entry
        // underneath them, which is two rows out of one. Counting locally worked
        // for as long as every part was one row tall, and stopped silently the
        // first time one was not -- the next field landed on this one.
        row = Math.max(row, screen.rows());
        row = note(screen, field.rules(), "hint", row);
        row = note(screen, problems.get(name), "error", row);
        return Math.max(row, screen.rows());
    }

    /**
     * A line of prose under a field, on as many rows as it takes.
     *
     * <p>Wrapped rather than written and hoped for. A hint is the one thing on the screen whose length nobody
     * chose — {@code help} is however long it was written — and a screen is cells, so a line too long for the
     * room does not run on: it runs <em>through</em> whatever is to the right of it, which on a list of details is
     * the frame around the list.
     */
    private int note(Screen screen, String text, String style, int row) {
        if (text == null || text.isEmpty()) return row;
        for (String line : wrapped(text, room())) {
            screen.text(++row, ENTRY_COLUMN, line, style);
        }
        return row;
    }

    /**
     * {@code text} broken into lines of at most {@code room} characters, at spaces where there is one.
     *
     * <p>A word longer than the room is broken rather than allowed to overhang, because a path or a URL is
     * exactly the sort of thing that turns up in a hint and exactly the sort of thing that has no spaces in it.
     */
    private static List<String> wrapped(String text, int room) {
        List<String> lines = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > room) {
            int at = rest.lastIndexOf(' ', room);
            if (at <= 0) at = room;
            lines.add(rest.substring(0, at).stripTrailing());
            rest = rest.substring(at).stripLeading();
        }
        if (!rest.isEmpty() || lines.isEmpty()) lines.add(rest);
        return lines;
    }

    /**
     * A list of details: what is in it, a button to take each one out, and a
     * button to add another.
     */
    private int entries(Screen screen, Field field, Map<String, Value> answers,
                        Map<String, String> problems, int row) {
        String name = field.name();
        int width = width();
        List<Value.Rec> rows = Values.rows(answers.getOrDefault(name, Value.Nothing.INSTANCE));
        int top = row;
        boolean boxed = editor.hello().can("box");

        // Boxed, the heading is the caption on the frame's top edge; unboxed it
        // is a label like any other. Never both, or they land on the same cells.
        if (!boxed) screen.text(row, LEFT, field.heading(), "label");
        if (field.required()) screen.text(row, width - 8, "required", "hint");
        row = note(screen, field.rules(), "hint", row);

        for (int i = 0; i < rows.size(); i++) {
            row++;
            boolean removable = editor.hello().can("action");
            // Elided rather than wrapped: this is a row of a table, and a table whose rows are two lines high is
            // harder to read than one that says there is more.
            screen.text(row, ENTRY_COLUMN, elided("[" + (i + 1) + "] " + line(rows.get(i)),
                    width - ENTRY_COLUMN - (removable ? 11 : 3)), "plain");
            if (removable) {
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
        row = note(screen, problems.get(name), "error", row);

        if (boxed) screen.box(top, LEFT - 1, row - top + 2, width - LEFT - 1, field.heading());
        // The frame's bottom edge is one row below the last thing inside it, so the
        // same rule as in one(): what was written, not what was counted.
        return Math.max(row, screen.rows());
    }

    private static String line(Value.Rec entry) {
        List<String> cells = new ArrayList<>();
        entry.fields().forEach((name, value) -> cells.add(Values.display(value)));
        return String.join(", ", cells);
    }

    private static String shown(Value value) {
        return value == null || value instanceof Value.Nothing ? "" : Values.display(value);
    }

    /** {@code text}, cut to {@code room} with an ellipsis if it will not fit. */
    private static String elided(String text, int room) {
        int most = Math.max(4, room);
        return text.length() <= most ? text : text.substring(0, most - 3) + "...";
    }

    private int entryWidth() { return Math.max(12, width() - ENTRY_COLUMN - 10); }

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
