package dev.mainframe.form;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.mainframe.MfError;
import dev.mainframe.Span;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.ui.Suggest;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/**
 * A form: the fields to ask a person for, and the rules an answer has to meet.
 *
 * <p>A form is written as data, not as code -- a table of field records, which is
 * to say a value MainFrame can already save, open and pass down a pipe. The same
 * definition therefore drives the screen a person fills in and the check a script
 * runs over a file, so the two cannot drift apart.
 *
 * <p>Nothing here reads or prints anything. {@link FormScreen} does the asking;
 * this is the part that says what a good answer is, and it is the part that has
 * to hold when there is nobody at the keyboard.
 */
public record Form(List<Field> fields) {

    private static final Set<String> YES = Set.of("y", "yes", "true", "1");
    private static final Set<String> NO = Set.of("n", "no", "false", "0");

    /** What reading typed text as a field's type came to. */
    public record Reading(Value value, String problem, String hint) {

        static Reading of(Value value) { return new Reading(value, null, null); }

        static Reading no(String problem, String hint) { return new Reading(null, problem, hint); }

        public boolean ok() { return problem == null; }
    }

    /** The types a field can hold. Deliberately fewer than the language has. */
    private static final List<String> TYPES = List.of(
            "string", "int", "float", "number", "bool", "size", "time", "duration",
            "path", "mime", "table");

    /** The keys a field record may use, in the order they are worth reading in. */
    private static final List<String> KEYS = List.of(
            "name", "label", "type", "required", "min", "max", "match", "choose", "pick", "help",
            "default", "secret", "fields");

    /**
     * How a path field is answered: by choosing something that is already there,
     * or by naming somewhere something is about to go.
     *
     * <p>All three hold a path, so this is not a type -- it is the question. "Which
     * file?", "which folder?" and "where shall I put it?" are three different
     * things to ask, and they cannot be told apart from the answer, because every
     * one of them comes back as a path. So a field that wants a chooser rather
     * than somewhere to type has to say which chooser, and this is the word it
     * says it with.
     *
     * <p>It sits beside {@code choose} rather than beside {@code type} for the
     * reason {@code choose} does: both turn an entry into a way of picking, and
     * neither changes what the answer is. A form is still a table of records, and
     * a {@code pick} field still holds a path.
     *
     * <p>What it does <em>not</em> do is insist the thing is already there. A
     * folder a profile is about to put on the PATH may not have been unpacked yet,
     * and a file being saved to had better not exist. See {@link Field#pickProblem}.
     */
    public enum Pick {
        /** A file that is already there. */
        FILE("file", "a file to choose", "Choose a file", "Choose"),
        /** A folder that is already there. */
        FOLDER("folder", "a folder to choose", "Choose a folder", "Use this folder"),
        /** A file to write, which need not exist yet. */
        SAVE("save", "a file to save as", "Save as", "Save");

        private final String written;
        private final String shape;
        private final String heading;
        private final String verb;

        Pick(String written, String shape, String heading, String verb) {
            this.written = written;
            this.shape = shape;
            this.heading = heading;
            this.verb = verb;
        }

        /** How it is written in a field record, and how it goes on the wire. */
        public String written() { return written; }

        /** What the field takes, for the line of prose under the label. */
        public String shape() { return shape; }

        /** The heading across the top of a chooser offering it. */
        public String heading() { return heading; }

        /** What the button that settles it says. */
        public String verb() { return verb; }

        /** True when the answer names somewhere to write rather than something to open. */
        public boolean isNew() { return this == SAVE; }

        /** True when the answer is a folder rather than something inside one. */
        public boolean isFolder() { return this == FOLDER; }

        /** The one written like this, or null -- which is a misspelling, not a crash. */
        public static Pick of(String written) {
            for (Pick pick : values()) {
                if (pick.written.equals(written)) return pick;
            }
            return null;
        }

        /** The words, for a listing and for a "did you mean". */
        public static List<String> names() {
            List<String> names = new ArrayList<>(values().length);
            for (Pick pick : values()) names.add(pick.written);
            return List.copyOf(names);
        }
    }

    /**
     * One field of a form.
     *
     * @param type    the type its answer will have; {@code TABLE} for a group of
     *                repeated entries, which is what {@code entries} describes
     * @param min     the smallest acceptable answer: a character count for text, a
     *                value for a number or a moment, an entry count for a group
     * @param pick    for a path, what sort of chooser to offer instead of somewhere
     *                to type, or null to ask for it as text
     * @param entries the sub-form each entry of a group is filled in with, or null
     *                when this field holds a single value
     * @param secret  a password or a key: shown as dots, never sent back down to
     *                the editor, and sent up only once. See {@link #isSecret}.
     */
    public record Field(
            String name,
            String label,
            ValueType type,
            boolean required,
            Value min,
            Value max,
            Pattern match,
            String matchSource,
            List<String> choices,
            Pick pick,
            String help,
            Value preset,
            Form entries,
            boolean secret) {

        /** True when this field repeats: a list of details rather than one value. */
        public boolean isGroup() { return entries != null; }

        /** True when this field is answered by browsing rather than by typing. */
        public boolean isPicked() { return pick != null; }

        /**
         * True when this field holds something that must not travel like the rest.
         *
         * <p>Every other entry on a screen obeys one rule: an event carries the
         * whole screen, every field, every time, because a diff that can disagree
         * with itself is not worth the bytes. That rule is right, and for a
         * password it is the problem. It would put the key in the change event, the
         * click event and the resize event -- not once, but for as long as the
         * screen is open -- and it would send the stored value back <em>down</em>
         * every time the screen was drawn.
         *
         * <p>So a secret field is the documented exception, and the exception is
         * its own rule rather than a special case hidden in the panel: <b>a secret
         * travels once, upward, on submit.</b> Never on a change, never on a click,
         * never on a resize, and never downward at all. What the editor shows for
         * one is dots, and what it is offered to show is nothing -- the screen says
         * whether a key is set, and never what it is.
         *
         * <p>The cost is that the whole-state rule now has an exception, and the
         * value of the whole-state rule was that it had none. That is the trade,
         * taken deliberately: the alternative is a key on the wire a hundred times
         * for one that was typed.
         */
        public boolean isSecret() { return secret; }

        /** Text, or something that can always be read as text. */
        public boolean isTextual() {
            return type == ValueType.STRING || type == ValueType.PATH || type == ValueType.MIME;
        }

        /** How the label looks on screen: a field name on a terminal shouts. */
        public String heading() { return label.toUpperCase(Locale.ROOT); }

        /** The fewest entries a group will settle for. */
        public long leastEntries() {
            long declared = min == null ? 0 : Values.asLong(min, Span.NONE);
            return required ? Math.max(1, declared) : declared;
        }

        /**
         * What is wrong with {@code answer}, or null when nothing is.
         *
         * <p>The one place an answer is judged. The screen calls it after every
         * line someone types, and {@code form-check} calls it on data that never
         * went near a terminal.
         */
        public String problem(Value answer) {
            Value given = answer == null ? Value.Nothing.INSTANCE : answer;
            if (isGroup()) return groupProblem(given);
            if (isBlank(given)) return required ? label + " is required" : null;
            if (!type.accepts(given)) {
                return label + " should be " + type.withArticle() + ", not "
                        + ValueType.of(given).withArticle()
                        + " -- give the column its type first, e.g. cast {" + name + ": "
                        + type.display() + "}";
            }
            return valueProblem(given);
        }

        private String valueProblem(Value answer) {
            String text = Values.display(answer);
            if (choices != null) {
                for (String choice : choices) if (choice.equalsIgnoreCase(text)) return null;
                return label + " has to be one of: " + String.join(", ", choices);
            }
            if (isTextual()) {
                int length = text.length();
                if (min != null && length < count(min)) {
                    return label + " needs at least " + plural(count(min), "character", "characters")
                            + ", and that is " + plural(length, "character", "characters");
                }
                if (max != null && length > count(max)) {
                    return label + " takes at most " + plural(count(max), "character", "characters")
                            + ", and that is " + plural(length, "character", "characters");
                }
            } else {
                if (min != null && Values.compare(answer, min, Span.NONE) < 0) {
                    return label + " has to be at least " + Values.display(min);
                }
                if (max != null && Values.compare(answer, max, Span.NONE) > 0) {
                    return label + " has to be at most " + Values.display(max);
                }
            }
            if (match != null && !match.matcher(text).matches()) {
                return label + " does not match " + matchSource;
            }
            if (pick != null) return pickProblem(text);
            return null;
        }

        /**
         * What is wrong with the thing a picked path names -- when there is
         * something there to look at.
         *
         * <p>Being there is not the rule, and deliberately: a folder a profile is
         * about to add to the PATH may not have been unpacked yet, and a file
         * being saved to had better not exist. A chooser only ever hands back
         * something real, so the case this catches is the other one -- a record
         * that arrived down a pipe or out of a file, naming a folder where a file
         * was wanted or the other way about. That is a mistake worth catching
         * away from the keyboard, and it is the only one that can be caught
         * without guessing at what somebody meant.
         */
        private String pickProblem(String text) {
            Path path;
            try {
                path = Path.of(text);
            } catch (InvalidPathException e) {
                return label + " is not a path this machine could have: " + text;
            }
            if (!Files.exists(path)) return null;
            boolean folder = Files.isDirectory(path);
            if (pick.isFolder() && !folder) {
                return label + " takes a folder, and " + text + " is a file";
            }
            if (!pick.isFolder() && folder) {
                return label + " takes a file, and " + text + " is a folder";
            }
            return null;
        }

        private String groupProblem(Value answer) {
            if (!isBlank(answer) && !ValueType.TABLE.accepts(answer)) {
                return label + " should be a list of entries, not "
                        + ValueType.of(answer).withArticle();
            }
            List<Value.Rec> rows = Values.rows(answer);
            long least = leastEntries();
            if (rows.size() < least) {
                return label + " needs at least " + plural(least, "entry", "entries")
                        + ", and there " + (rows.size() == 1 ? "is " : "are ")
                        + plural(rows.size(), "entry", "entries");
            }
            if (max != null && rows.size() > count(max)) {
                return label + " takes at most " + plural(count(max), "entry", "entries")
                        + ", and there are " + plural(rows.size(), "entry", "entries");
            }
            for (int i = 0; i < rows.size(); i++) {
                for (Field sub : entries.fields()) {
                    String problem = sub.problem(rows.get(i).get(sub.name()));
                    if (problem != null) return label + " entry " + (i + 1) + ": " + problem;
                }
            }
            return null;
        }

        /**
         * Text somebody typed, read as this field's type -- or why it is not one.
         *
         * <p>The counterpart to {@link #problem}: that one says whether a value is
         * acceptable, this one says whether text is a value at all. Both are here
         * rather than in whatever is doing the asking, because a form filled in on
         * a terminal and a form filled in on a screen somebody else is painting
         * have to agree about what was meant.
         */
        public Reading read(String typed, Path from) {
            if (choices != null) {
                int pick = whole(typed);
                if (pick >= 1 && pick <= choices.size()) {
                    return Reading.of(new Value.Str(choices.get(pick - 1)));
                }
                for (String choice : choices) {
                    if (choice.equalsIgnoreCase(typed)) return Reading.of(new Value.Str(choice));
                }
                return Reading.no(label + " has to be one of: " + String.join(", ", choices),
                        "type the choice, or the number beside it");
            }
            switch (type) {
                // Read here rather than through the language's reader, so the word
                // "nothing" typed into a text field is the text somebody typed.
                case STRING -> { return Reading.of(new Value.Str(typed)); }
                case PATH -> {
                    try {
                        return Reading.of(new Value.PathVal(from == null
                                ? Path.of(typed)
                                : SafeFs.resolve(from, typed)));
                    } catch (InvalidPathException e) {
                        // What counts as a path is the machine's business, and on
                        // some of them a colon or a quotation mark is not one. Said
                        // rather than thrown: this is somebody at a keyboard.
                        return Reading.no(label + " is not a path this machine could have",
                                "no " + illegal(typed) + " in a name here");
                    }
                }
                case BOOL -> {
                    String lower = typed.toLowerCase(Locale.ROOT);
                    if (YES.contains(lower)) return Reading.of(new Value.Bool(true));
                    if (NO.contains(lower)) return Reading.of(new Value.Bool(false));
                    return Reading.no(label + " is a yes or no question", "answer y or n");
                }
                default -> { }
            }
            try {
                return Reading.of(Values.parseAs(type, typed, Span.NONE));
            } catch (MfError e) {
                // The language already explains every one of these well; a form has
                // no business explaining them a second, different way.
                return Reading.no(e.getMessage(), e.hints().isEmpty() ? null : e.hints().getFirst());
            }
        }

        /**
         * What this field will take, in one breath: its own help, the shape of
         * the type, and the bounds. Shown under the label on a terminal and under
         * the entry on a panel, which is why it lives here rather than in either.
         */
        public String rules() {
            List<String> parts = new ArrayList<>();
            if (help != null && !help.isBlank()) parts.add(help);
            String shape = pick != null ? pick.shape() : shape(type);
            if (shape != null) parts.add(shape);
            if (isGroup()) {
                long least = leastEntries();
                if (least > 0) parts.add("at least " + least + (least == 1 ? " entry" : " entries"));
                if (max != null) parts.add("at most " + Values.display(max) + " entries");
            } else if (isTextual()) {
                if (min != null) parts.add("at least " + Values.display(min) + " characters");
                if (max != null) parts.add("at most " + Values.display(max) + " characters");
            } else {
                if (min != null) parts.add("at least " + Values.display(min));
                if (max != null) parts.add("at most " + Values.display(max));
            }
            if (matchSource != null) parts.add("matching " + matchSource);
            return String.join(", ", parts);
        }

        /**
         * How to write one of these, for the types where that is not obvious. No
         * commas in them: they are joined with commas.
         */
        private static String shape(ValueType type) {
            return switch (type) {
                case INT -> "a whole number";
                case FLOAT, NUMBER -> "a number";
                case BOOL -> "y or n";
                case SIZE -> "a size like 4mb";
                case TIME -> "a moment like 2026-08-21 or 2026-08-21T14:30";
                case DURATION -> "a span like 7d or 90m";
                case PATH -> "a path from where the shell is";
                case MIME -> "a media type like text/plain";
                default -> null;
            };
        }

        /** The first character of {@code typed} the filesystem would not take, quoted. */
        private static String illegal(String typed) {
            for (int i = 0; i < typed.length(); i++) {
                char c = typed.charAt(i);
                if ("<>:\"|?*".indexOf(c) >= 0) return "\"" + c + "\"";
            }
            return "that character";
        }

        private static int whole(String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static boolean isBlank(Value value) {
            return value instanceof Value.Nothing
                    || (value instanceof Value.Str text && text.value().isBlank());
        }

        private static long count(Value value) { return Values.asLong(value, Span.NONE); }

        private static String plural(long n, String one, String many) {
            return n + " " + (n == 1 ? one : many);
        }
    }

    // ---- reading a form out of a value ------------------------------------------------

    /**
     * Reads a form definition, and refuses the whole thing if any part of it is
     * wrong.
     *
     * <p>This runs before a single question is asked, which is the same promise
     * every other command makes: a form that would fail on its last field fails
     * before its first.
     *
     * @param spec a table of field records, or a list of names, or one of either
     */
    public static Form read(Value spec, Span span) {
        return read(spec, span, true);
    }

    private static Form read(Value spec, Span span, boolean groupsAllowed) {
        List<Value> items = spec instanceof Value.ListVal list ? list.items() : List.of(spec);
        if (items.isEmpty()) {
            throw MfError.of("E1201", "a form needs at least one field").at(span)
                    .hint("name them and they are text fields: form [\"name\", \"email\"]")
                    .hint("or describe one: form [{name: \"email\", required: true}]")
                    .build();
        }
        List<Field> fields = new ArrayList<>(items.size());
        SequencedSet<String> seen = new LinkedHashSet<>();
        for (Value item : items) {
            Field field = field(item, span, groupsAllowed);
            if (!seen.add(field.name())) {
                throw MfError.of("E1205", "two fields are called " + field.name()).at(span)
                        .hint("each field becomes a column of the answer, so each needs its own name")
                        .build();
            }
            fields.add(field);
        }
        return new Form(List.copyOf(fields));
    }

    /** The field names, which are the columns of the answer. */
    public List<String> names() {
        List<String> names = new ArrayList<>(fields.size());
        for (Field field : fields) names.add(field.name());
        return List.copyOf(names);
    }

    public Field field(String name) {
        for (Field field : fields) if (field.name().equals(name)) return field;
        return null;
    }

    /**
     * Every problem with a record of answers, in field order.
     *
     * <p>All of them, not the first: someone fixing a file wants the whole list,
     * not one round trip per mistake.
     */
    public List<String> problems(Value.Rec answers) {
        List<String> problems = new ArrayList<>();
        for (Field field : fields) {
            String problem = field.problem(answers.get(field.name()));
            if (problem != null) problems.add(problem);
        }
        return List.copyOf(problems);
    }

    private static Field field(Value item, Span span, boolean groupsAllowed) {
        if (item instanceof Value.Str name) return plain(name.value(), span);
        if (!(item instanceof Value.Rec rec)) {
            throw MfError.of("E1202", "a field is either a name or a record, not "
                            + ValueType.of(item).withArticle()).at(span)
                    .hint("a name on its own asks for text: \"email\"")
                    .hint("a record can say more: {name: \"email\", required: true}")
                    .build();
        }
        for (String key : rec.fields().keySet()) {
            if (KEYS.contains(key)) continue;
            MfError.Builder error = MfError.of("E1203", "a field has no \"" + key + "\"").at(span);
            String closest = Suggest.closest(key, KEYS);
            if (closest != null) error.hint("did you mean " + closest + "?");
            error.hint("a field can say: " + String.join(", ", KEYS));
            throw error.build();
        }

        String name = text(rec, "name");
        if (name == null || name.isBlank()) {
            throw MfError.of("E1202", "every field needs a name").at(span)
                    .hint("the name becomes a column of the answer: {name: \"email\"}")
                    .hint("if a name is all you have to say, write just the name: \"email\"")
                    .build();
        }
        String label = text(rec, "label");
        if (label == null || label.isBlank()) label = name.replace('-', ' ').replace('_', ' ');

        // A key holding nothing is a key that is not there. That matters because a
        // form read back out of a file has every column on every row, and the ones
        // it did not use are nothing.
        Value nested = present(rec, "fields");
        String declared = text(rec, "type");
        ValueType type = type(declared, nested != null, name, span);
        Form entries = null;
        if (type == ValueType.TABLE) {
            if (nested == null) {
                throw MfError.of("E1207", name + " is a table of entries, but does not say "
                                + "what one entry holds").at(span)
                        .hint("add the fields of an entry: "
                                + "{name: \"" + name + "\", fields: [\"kind\", \"value\"]}")
                        .build();
            }
            if (!groupsAllowed) {
                throw MfError.of("E1207", name + " is a group of entries inside another group").at(span)
                        .hint("one level of entries is as deep as a form goes, because a person "
                                + "fills one in a line at a time")
                        .hint("ask for the inner list as its own field instead")
                        .build();
            }
            entries = read(nested, span, false);
        }

        boolean required = truthy(rec, "required");
        Value min = present(rec, "min");
        Value max = present(rec, "max");
        Pattern match = null;
        String matchSource = text(rec, "match");
        List<String> choices = choices(rec, name, type, span);
        Pick pick = pick(rec, name, type, span);
        String help = text(rec, "help");
        Value preset = present(rec, "default");

        if (matchSource != null) {
            if (type != ValueType.STRING && type != ValueType.PATH && type != ValueType.MIME) {
                throw MfError.of("E1206", name + " is " + type.withArticle()
                                + ", so a pattern cannot be matched against it").at(span)
                        .hint("match works on string, path and mime fields")
                        .hint("a code with leading zeros or spacing rules is text, not a number "
                                + "-- say type: \"string\" and keep the pattern")
                        .build();
            }
            try {
                match = Pattern.compile(matchSource);
            } catch (PatternSyntaxException e) {
                throw MfError.of("E1206", "the pattern on " + name + " is not a valid "
                                + "regular expression: " + matchSource).at(span)
                        .hint("the whole answer has to match it, so no anchors are needed")
                        .hint(firstLine(e.getMessage()))
                        .build();
            }
        }
        checkBound(min, "min", name, type, span);
        checkBound(max, "max", name, type, span);
        if (min != null && max != null && Values.compare(min, max, span) > 0) {
            throw MfError.of("E1206", "min on " + name + " is larger than its max, "
                            + "so nothing could satisfy it").at(span)
                    .hint("min is " + Values.display(min) + " and max is " + Values.display(max))
                    .build();
        }

        boolean secret = truthy(rec, "secret");
        if (secret) checkSecret(name, type, preset, choices, pick, entries, span);

        Field field = new Field(name, label, type, required, min, max, match, matchSource,
                choices, pick, help, preset, entries, secret);
        if (preset != null) {
            String problem = field.problem(preset);
            if (problem != null) {
                throw MfError.of("E1206", "the default for " + name
                                + " breaks its own rules: " + problem).at(span)
                        .hint("a default is offered as an answer, so it has to be one")
                        .build();
            }
        }
        return field;
    }

    private static Field plain(String name, Span span) {
        if (name.isBlank()) {
            throw MfError.of("E1202", "a field cannot be named nothing at all").at(span)
                    .hint("a name becomes a column of the answer, e.g. \"email\"")
                    .build();
        }
        return new Field(name, name.replace('-', ' ').replace('_', ' '), ValueType.STRING,
                false, null, null, null, null, null, null, null, null, null, false);
    }

    /**
     * What a secret field may not also be.
     *
     * <p>Each of these would send the secret somewhere it must not go, so they are
     * refused when the form is read rather than quietly ignored when it is drawn.
     * A form that says {@code secret: true} and hands the value out anyway is
     * worse than one that never claimed to.
     */
    private static void checkSecret(String name, ValueType type, Value preset,
                                    List<String> choices, Pick pick, Form entries, Span span) {
        if (entries != null) {
            throw MfError.of("E1206", name + " cannot be a secret and a group at once").at(span)
                    .hint("a group is a table of answers, and a secret is one answer that is not kept")
                    .build();
        }
        if (type != ValueType.STRING) {
            throw MfError.of("E1206", name + " is " + type.withArticle()
                            + ", and a secret is text").at(span)
                    .hint("say type: \"string\", or drop secret: true")
                    .build();
        }
        if (preset != null) {
            throw MfError.of("E1206", "a secret cannot have a default").at(span)
                    .hint("a default is sent down to the editor to be shown, which is the one "
                            + "thing a secret never does")
                    .build();
        }
        if (choices != null) {
            throw MfError.of("E1206", "a secret cannot be chosen from a list").at(span)
                    .hint("the list would have to carry every possible answer, including the real one")
                    .build();
        }
        if (pick != null) {
            throw MfError.of("E1206", "a secret cannot be picked with a chooser").at(span)
                    .hint("a chooser answers with a path, which is not a secret -- ask for the "
                            + "path as an ordinary field")
                    .build();
        }
    }

    private static ValueType type(String declared, boolean hasEntries, String name, Span span) {
        if (declared == null || declared.isBlank()) {
            return hasEntries ? ValueType.TABLE : ValueType.STRING;
        }
        ValueType type = switch (declared) {
            case "string" -> ValueType.STRING;
            case "int" -> ValueType.INT;
            case "float" -> ValueType.FLOAT;
            case "number" -> ValueType.NUMBER;
            case "bool" -> ValueType.BOOL;
            case "size" -> ValueType.SIZE;
            case "time" -> ValueType.TIME;
            case "duration" -> ValueType.DURATION;
            case "path" -> ValueType.PATH;
            case "mime" -> ValueType.MIME;
            case "table" -> ValueType.TABLE;
            default -> null;
        };
        if (type == null) {
            MfError.Builder error = MfError.of("E1204",
                    "\"" + declared + "\" is not a type a field can hold").at(span);
            String closest = Suggest.closest(declared, TYPES);
            if (closest != null) error.hint("did you mean " + closest + "?");
            error.hint("a field holds one of: " + String.join(", ", TYPES));
            throw error.build();
        }
        if (hasEntries && type != ValueType.TABLE) {
            throw MfError.of("E1207", name + " is " + type.withArticle()
                            + ", so it has no entries to describe").at(span)
                    .hint("a repeated list of details is type: \"table\", "
                            + "and fields says what one entry holds")
                    .hint("or drop fields and keep it one " + type.display())
                    .build();
        }
        return type;
    }

    private static List<String> choices(Value.Rec rec, String name, ValueType type, Span span) {
        Value given = present(rec, "choose");
        if (given == null) return null;
        if (type != ValueType.STRING) {
            throw MfError.of("E1206", name + " is " + type.withArticle()
                            + ", so it cannot offer a list to choose from").at(span)
                    .hint("choose works on string fields, which is what a menu picks")
                    .build();
        }
        List<Value> items = given instanceof Value.ListVal list ? list.items() : List.of(given);
        if (items.isEmpty()) {
            throw MfError.of("E1206", "the list to choose from on " + name + " is empty").at(span)
                    .hint("give it something to pick: choose: [\"phone\", \"email\"]")
                    .build();
        }
        List<String> choices = new ArrayList<>(items.size());
        for (Value item : items) {
            if (item instanceof Value.Rec || item instanceof Value.ListVal) {
                throw MfError.of("E1206", "the list to choose from on " + name
                                + " holds " + ValueType.of(item).withArticle()).at(span)
                        .hint("a menu offers one line each, so every choice is a single value")
                        .build();
            }
            choices.add(Values.display(item));
        }
        return List.copyOf(choices);
    }

    /**
     * What sort of chooser a path field asks for, or null when it wants typing
     * into.
     *
     * <p>Refused on anything but a path, because a chooser hands back a path and
     * there is nothing else it could hand back. Saying so is better than
     * accepting the word and then never offering the chooser.
     */
    private static Pick pick(Value.Rec rec, String name, ValueType type, Span span) {
        String written = text(rec, "pick");
        if (written == null) return null;
        if (type != ValueType.PATH) {
            throw MfError.of("E1206", name + " is " + type.withArticle()
                            + ", so there is nothing to browse for").at(span)
                    .hint("pick works on path fields, because a chooser hands back a path")
                    .hint("say type: \"path\" and keep the pick")
                    .build();
        }
        Pick pick = Pick.of(written);
        if (pick == null) {
            MfError.Builder error = MfError.of("E1204",
                    "\"" + written + "\" is not something a field can pick").at(span);
            String closest = Suggest.closest(written, Pick.names());
            if (closest != null) error.hint("did you mean " + closest + "?");
            error.hint("pick is one of: " + String.join(", ", Pick.names()));
            error.hint("file and folder choose one that is there; save names one that is not");
            throw error.build();
        }
        return pick;
    }

    /**
     * A bound has to be comparable with what the field holds: a count of
     * characters or entries where that is what a bound means, and otherwise a
     * value of the field's own type.
     */
    private static void checkBound(Value bound, String key, String name, ValueType type, Span span) {
        if (bound == null || bound instanceof Value.Nothing) return;
        boolean counts = type == ValueType.TABLE || type == ValueType.STRING
                || type == ValueType.PATH || type == ValueType.MIME;
        if (counts) {
            if (bound instanceof Value.Int) return;
            throw MfError.of("E1206", key + " on " + name + " counts "
                            + (type == ValueType.TABLE ? "entries" : "characters")
                            + ", so it is a whole number, not "
                            + ValueType.of(bound).withArticle()).at(span)
                    .hint("write it plainly: " + key + ": 2")
                    .build();
        }
        if (type.accepts(bound)) return;
        throw MfError.of("E1206", key + " on " + name + " should be " + type.withArticle()
                        + ", not " + ValueType.of(bound).withArticle()).at(span)
                .hint("a bound is compared against the answer, so it has the same type")
                .hint("e.g. " + key + ": " + example(type))
                .build();
    }

    private static String example(ValueType type) {
        return switch (type) {
            case INT, NUMBER, FLOAT -> "18";
            case SIZE -> "4mb";
            case TIME -> "2026-01-01";
            case DURATION -> "7d";
            default -> "a value of that type";
        };
    }

    /** A key that is there and holds something, or null. */
    private static Value present(Value.Rec rec, String key) {
        Value value = rec.get(key);
        return value == null || value instanceof Value.Nothing ? null : value;
    }

    private static String text(Value.Rec rec, String key) {
        Value value = present(rec, key);
        return value == null ? null : Values.display(value);
    }

    private static boolean truthy(Value.Rec rec, String key) {
        Value value = rec.get(key);
        return value != null && Values.truthy(value);
    }

    private static String firstLine(String message) {
        if (message == null) return "check the pattern";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
