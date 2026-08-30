package dev.mainframe.form;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import dev.mainframe.Session;
import dev.mainframe.Span;
import dev.mainframe.form.Form.Field;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * A form on a terminal: a framed sheet, one field at a time, and a way out of
 * every single prompt.
 *
 * <p>MainFrame has no cursor addressing and no line editing, so a screen here is
 * a screen in the older sense -- printed, filled in downwards, and shown back for
 * approval before it counts. That turns out to suit the medium: every step is in
 * the scrollback, a pipeline can be pasted, and a script that pipes answers in
 * behaves exactly like a person typing them.
 *
 * <p>Four words are read as instructions rather than as data: {@code !back} goes
 * to the field before, {@code !cancel} abandons the form, {@code !clear} empties a
 * field -- or, at a list of details, the whole list -- and {@code !browse}, at a
 * field that asks for a file, goes looking for one. A line starting with a
 * backslash is the rest of that line taken literally, which is how one of those
 * four is entered as an answer. The end of the input -- Ctrl-D, or a script running
 * out of lines -- is a cancel, because a form that carried on with blanks would be
 * inventing data.
 */
public final class FormScreen {

    /** Wide enough to read, narrow enough to look like a form rather than a wall. */
    private static final int MAX_WIDTH = 72;
    private static final int MIN_WIDTH = 44;

    private static final Set<String> YES = Set.of("y", "yes", "true", "1");
    private static final Set<String> NO = Set.of("n", "no", "false", "0");

    /** What a prompt decided: carry on, step back, or abandon the form. */
    private enum Step { DONE, BACK, CANCEL }

    private enum Answer { YES, NO, BACK, CANCEL, CLEAR }

    private final Session session;
    private final Renderer out;
    private final Form form;
    private final String title;
    private final boolean review;
    private final int width;

    private FormScreen(Form form, String title, boolean review, Session session) {
        this.form = form;
        this.title = title == null || title.isBlank() ? "form" : title;
        this.review = review;
        this.session = session;
        this.out = session.out();
        this.width = Math.max(MIN_WIDTH, Math.min(out.width(), MAX_WIDTH));
    }

    /**
     * Shows the form and collects the answers.
     *
     * @param starting values to offer as the answers, e.g. the record being
     *                 edited; anything in it the form does not ask about is
     *                 carried through untouched rather than quietly dropped
     * @return the answers as a record, or null when the person gave up
     */
    public static Value.Rec show(Form form, Value.Rec starting, String title, boolean review,
                                 Session session) {
        return new FormScreen(form, title, review, session).run(starting);
    }

    private Value.Rec run(Value.Rec starting) {
        SequencedMap<String, Value> carried = new LinkedHashMap<>();
        if (starting != null) {
            starting.fields().forEach((name, value) -> {
                if (form.field(name) == null) carried.put(name, value);
            });
        }
        banner(carried.keySet());
        SequencedMap<String, Value> answers = offered(starting);

        while (true) {
            int at = 0;
            while (at < form.fields().size()) {
                Step step = ask(form.fields().get(at), answers, 1);
                if (step == Step.CANCEL) return null;
                if (step == Step.BACK) at = Math.max(0, at - 1);
                else at++;
            }
            if (!review) break;
            Step step = submit(answers, carried);
            if (step == Step.CANCEL) return null;
            if (step == Step.DONE) break;
        }
        return record(form, answers, carried);
    }

    /**
     * The answer each field starts out holding: what was piped in, or the
     * default it declared.
     *
     * <p>A value of the wrong type is not offered, because blank would then
     * accept it and the form would have produced a record it would refuse. It is
     * said out loud rather than dropped quietly.
     */
    private SequencedMap<String, Value> offered(Value.Rec starting) {
        SequencedMap<String, Value> answers = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            Value seed = starting == null ? null : starting.get(field.name());
            if (!blank(seed) && !field.type().accepts(seed)) {
                out.warn(field.name() + " arrived as " + ValueType.of(seed).withArticle()
                        + " where this form wants " + field.type().withArticle()
                        + ", so it is being asked for afresh");
                seed = null;
            }
            if (blank(seed)) seed = field.preset();
            if (!blank(seed)) answers.put(field.name(), seed);
        }
        return answers;
    }

    /** The answers as the record they were always going to be. */
    private static Value.Rec record(Form form, Map<String, Value> answers, Map<String, Value> carried) {
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            Value answer = answers.get(field.name());
            fields.put(field.name(), answer == null ? Value.Nothing.INSTANCE : answer);
        }
        carried.forEach(fields::putIfAbsent);
        return new Value.Rec(fields);
    }

    // ---- the frame ---------------------------------------------------------------------

    private void banner(Collection<String> carried) {
        int count = form.fields().size();
        out.info("");
        out.info(out.dim(rule('=')));
        out.info(" " + out.bold(title.toUpperCase(Locale.ROOT)));
        out.info(out.dim(rule('-')));
        out.info(out.dim(" " + count + (count == 1 ? " field" : " fields")
                + ", asked one at a time"));
        out.info(out.dim(" ") + out.cyan("!back") + out.dim(" the field before, ")
                + out.cyan("!cancel") + out.dim(" the whole form"));
        if (!carried.isEmpty()) {
            out.info(out.dim(" carried through untouched: " + String.join(", ", carried)));
        }
        out.info(out.dim(rule('=')));
    }

    private Step submit(SequencedMap<String, Value> answers, SequencedMap<String, Value> carried) {
        out.info("");
        out.info(out.dim(rule('=')));
        out.info(" " + out.bold(title.toUpperCase(Locale.ROOT)) + out.dim("  what you entered"));
        out.info(out.dim(rule('-')));
        sheet(answers, carried);
        out.info(out.dim(rule('=')));
        Answer answer = askYesNo(1, "submit?", true, false);
        if (answer == Answer.CANCEL) return Step.CANCEL;
        if (answer == Answer.YES) return Step.DONE;
        // No is not a cancel: it is "let me go round again", with everything
        // already entered offered back as the answer to each field.
        return Step.BACK;
    }

    private void sheet(SequencedMap<String, Value> answers, SequencedMap<String, Value> carried) {
        int widest = 0;
        for (Field field : form.fields()) widest = Math.max(widest, field.name().length());
        for (String name : carried.keySet()) widest = Math.max(widest, name.length());
        final int keys = widest;

        for (Field field : form.fields()) {
            Value answer = answers.getOrDefault(field.name(), Value.Nothing.INSTANCE);
            if (!field.isGroup()) {
                out.info(" " + out.dim(padded(field.name(), keys)) + "  "
                        + (blank(answer) ? out.dim("(not given)") : Values.display(answer)));
                continue;
            }
            List<Value.Rec> rows = Values.rows(answer);
            if (rows.isEmpty()) {
                out.info(" " + out.dim(padded(field.name(), keys)) + "  " + out.dim("(no entries)"));
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                out.info(" " + out.dim(padded(i == 0 ? field.name() : "", keys)) + "  "
                        + out.dim("[" + (i + 1) + "] ") + Values.display(rows.get(i)));
            }
        }
        carried.forEach((name, value) -> out.info(" " + out.dim(padded(name, keys)) + "  "
                + Values.display(value) + out.dim("  (carried through)")));
    }

    // ---- one field ---------------------------------------------------------------------

    private Step ask(Field field, SequencedMap<String, Value> answers, int indent) {
        return field.isGroup() ? askEntries(field, answers, indent) : askValue(field, answers, indent);
    }

    private Step askValue(Field field, SequencedMap<String, Value> answers, int indent) {
        if (field.isSecret() && System.console() == null) {
            // The printed form is the fallback for a terminal with no panel, and
            // here it has run out of fallback. Typing would be echoed, and echoing
            // is the thing a secret field exists to prevent -- so it says so
            // rather than asking anyway. The same rule the panel protocol applies
            // to an editor that cannot mask: no capability, no question.
            complain(indent, field.label() + " is a secret, and this terminal cannot hide typing");
            hint(indent, "run MainFrame attached to a terminal, or set it from a screen");
            return Step.CANCEL;
        }
        Value current = answers.get(field.name());
        out.info("");
        out.info(leader(indent, field.heading(), field.required() ? "required" : "optional"));
        String rules = field.rules();
        if (!rules.isEmpty()) out.info(out.dim(spaces(indent + 2) + rules));
        if (field.choices() != null) {
            List<String> choices = field.choices();
            for (int i = 0; i < choices.size(); i++) {
                out.info(out.dim(spaces(indent + 2) + (i + 1) + ") ") + choices.get(i));
            }
        }
        if (field.isPicked()) {
            out.info(out.dim(spaces(indent + 2) + "!browse looks for one, or type the path"));
        }
        if (!blank(current)) {
            out.info(out.dim(spaces(indent + 2) + "blank keeps " + Values.display(current)
                    + (field.required() ? "" : ", !clear empties it")));
        }

        while (true) {
            String line = field.isSecret() ? readSecret(indent) : read(indent);
            if (line == null) return Step.CANCEL;
            String typed = line.trim();
            if (typed.equals("!cancel")) return Step.CANCEL;
            if (typed.equals("!back")) return Step.BACK;
            if (typed.equals("!browse") && field.isPicked()) {
                // A chooser hands back a path or nothing. Nothing is not an answer
                // -- it is somebody deciding to type it after all -- so the field
                // asks again with whatever it already held still offered.
                Path chosen = Picker.ask(field.pick(),
                        pathOf(answers.get(field.name())), session);
                if (chosen == null) continue;
                Value value = new Value.PathVal(chosen);
                String problem = field.problem(value);
                if (problem != null) {
                    complain(indent, problem);
                    continue;
                }
                answers.put(field.name(), value);
                out.info(out.dim(spaces(indent + 2) + "chose ") + chosen);
                return Step.DONE;
            }
            if (typed.equals("!clear")) {
                if (field.required()) {
                    complain(indent, field.label() + " is required, so it cannot be emptied");
                    continue;
                }
                answers.put(field.name(), Value.Nothing.INSTANCE);
                return Step.DONE;
            }
            // A backslash makes the rest of the line data, whatever it spells.
            if (typed.startsWith("\\")) typed = typed.substring(1);

            if (typed.isEmpty()) {
                if (!blank(current)) return Step.DONE;
                if (!field.required()) {
                    answers.put(field.name(), Value.Nothing.INSTANCE);
                    return Step.DONE;
                }
                complain(indent, field.label() + " is required");
                continue;
            }
            Value value = parse(field, typed, indent);
            if (value == null) continue;
            String problem = field.problem(value);
            if (problem != null) {
                complain(indent, problem);
                continue;
            }
            answers.put(field.name(), value);
            return Step.DONE;
        }
    }

    /**
     * A list of details: the same few questions, once per entry, until the person
     * says that is all of them.
     */
    private Step askEntries(Field field, SequencedMap<String, Value> answers, int indent) {
        out.info("");
        out.info(leader(indent, field.heading(), field.required() ? "required" : "optional"));
        String rules = field.rules();
        if (!rules.isEmpty()) out.info(out.dim(spaces(indent + 2) + rules));

        List<Value> entries = new ArrayList<>(
                Values.rows(answers.getOrDefault(field.name(), Value.Nothing.INSTANCE)));
        for (int i = 0; i < entries.size(); i++) {
            out.info(out.dim(spaces(indent + 2) + "[" + (i + 1) + "] ")
                    + Values.display(entries.get(i)));
        }
        long most = field.max() == null ? Long.MAX_VALUE : Values.asLong(field.max(), Span.NONE);

        while (true) {
            boolean owed = entries.size() < field.leastEntries();
            if (!owed) {
                if (entries.size() >= most) {
                    out.info(out.dim(spaces(indent + 2) + "that is as many entries as this takes"));
                    break;
                }
                Answer more = askYesNo(indent + 2,
                        entries.isEmpty() ? "add an entry?" : "another entry?", false,
                        !entries.isEmpty());
                if (more == Answer.CANCEL) return Step.CANCEL;
                if (more == Answer.BACK) return Step.BACK;
                if (more == Answer.CLEAR) {
                    // No way to pick one entry out of the middle, so the way back
                    // from a list gone wrong is to start it again.
                    entries.clear();
                    out.info(out.dim(spaces(indent + 2) + "the entries were cleared"));
                    continue;
                }
                if (more == Answer.NO) break;
            }

            out.info(out.dim(spaces(indent + 2) + "[" + (entries.size() + 1) + "]"));
            SequencedMap<String, Value> entry = new LinkedHashMap<>();
            for (Field sub : field.entries().fields()) {
                if (!blank(sub.preset())) entry.put(sub.name(), sub.preset());
            }
            int at = 0;
            boolean dropped = false;
            while (at < field.entries().fields().size()) {
                Step step = ask(field.entries().fields().get(at), entry, indent + 4);
                if (step == Step.CANCEL) return Step.CANCEL;
                if (step == Step.BACK) {
                    if (at == 0) { dropped = true; break; }
                    at--;
                } else {
                    at++;
                }
            }
            if (dropped) {
                // Stepping back out of the first question of an entry drops that
                // entry. When the form is insisting on one, there is no "another
                // entry?" to go back to, so the way out is the field before this.
                if (owed) {
                    answers.put(field.name(), new Value.ListVal(List.copyOf(entries)));
                    return Step.BACK;
                }
                out.info(out.dim(spaces(indent + 2) + "that entry was dropped"));
                continue;
            }
            entries.add(record(field.entries(), entry, Map.of()));
        }
        answers.put(field.name(), new Value.ListVal(List.copyOf(entries)));
        return Step.DONE;
    }

    /**
     * Turns what someone typed into a typed value, or says why it is not one and
     * hands back null so the field asks again. The reading itself belongs to the
     * field, so a form on a terminal and a form on a panel agree about what was
     * meant; all that is left here is saying so out loud.
     */
    private Value parse(Field field, String typed, int indent) {
        Form.Reading reading = field.read(typed, session.cwd());
        if (reading.ok()) return reading.value();
        complain(indent, reading.problem());
        if (reading.hint() != null) hint(indent, reading.hint());
        return null;
    }

    /** @param clearable whether {@code !clear} means anything at this prompt */
    private Answer askYesNo(int indent, String question, boolean byDefault, boolean clearable) {
        while (true) {
            out.out().print(spaces(indent) + question + " "
                    + out.dim(byDefault ? "[Y/n]" : "[y/N]") + " ");
            out.out().flush();
            String line = session.readLine();
            if (line == null) return Answer.CANCEL;
            String typed = line.trim().toLowerCase(Locale.ROOT);
            if (typed.equals("!cancel")) return Answer.CANCEL;
            if (typed.equals("!back")) return Answer.BACK;
            if (clearable && typed.equals("!clear")) return Answer.CLEAR;
            if (typed.isEmpty()) return byDefault ? Answer.YES : Answer.NO;
            if (YES.contains(typed)) return Answer.YES;
            if (NO.contains(typed)) return Answer.NO;
            complain(indent, "answer y or n");
        }
    }

    /**
     * The same prompt, with nothing echoed.
     *
     * <p>Straight to the terminal rather than through {@code session.readLine}: the
     * shell's reader is a buffered stream that may be a pipe or a script, and the
     * only thing that can suppress an echo is the console itself. Whether one is
     * there was settled before the field was asked -- see {@link #askValue}.
     *
     * <p>The characters are wiped from the array afterwards. It is a small
     * gesture, since the {@code String} it was turned into stays until it is
     * collected, and it costs one line.
     */
    private String readSecret(int indent) {
        java.io.Console console = System.console();
        if (console == null) return null;
        out.out().print(spaces(indent) + out.cyan("> "));
        out.out().flush();
        char[] typed = console.readPassword();
        if (typed == null) return null;
        try {
            // The person typed and saw nothing, so the newline they expected has
            // to come from here or the next line lands on top of the prompt.
            out.info("");
            return new String(typed);
        } finally {
            java.util.Arrays.fill(typed, '\0');
        }
    }

    private String read(int indent) {
        out.out().print(spaces(indent) + out.cyan("> "));
        out.out().flush();
        return session.readLine();
    }

    private void complain(int indent, String message) {
        out.info(out.red(spaces(indent) + "!! ") + message);
    }

    private void hint(int indent, String message) {
        out.info(out.dim(spaces(indent) + "   " + message));
    }

    // ---- the look of it ----------------------------------------------------------------

    /** A label, dots to carry the eye across, and what the field expects. */
    private String leader(int indent, String heading, String note) {
        String left = spaces(indent) + heading + " ";
        String right = note.isEmpty() ? "" : " " + note;
        int dots = width - left.length() - right.length();
        return out.bold(left) + out.dim(".".repeat(Math.max(3, dots)) + right);
    }

    private String rule(char c) { return String.valueOf(c).repeat(width); }

    private static boolean blank(Value value) {
        return value == null || value instanceof Value.Nothing;
    }

    /**
     * The answer as a path, for a chooser to open at, or null when there is not
     * one to open at yet.
     *
     * <p>Read rather than cast, because a path field accepts text as well: a
     * record piped in from a file holds the path as the string it was written as.
     */
    private static Path pathOf(Value value) {
        if (blank(value)) return null;
        try {
            return Path.of(Values.display(value));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String spaces(int n) { return " ".repeat(Math.max(0, n)); }

    private static String padded(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
