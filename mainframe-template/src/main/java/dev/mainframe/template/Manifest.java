package dev.mainframe.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reading a template's manifest.
 *
 * <p>A line-based format of its own, which wants a word of justification because
 * MainFrame already has a perfectly good one. The engine cannot use it: the reader
 * is in the shell, and a template engine that could only parse its own templates
 * inside MainFrame would be a template engine that is not reusable, which is the
 * whole reason this is a module. So it parses its own, the format is small enough
 * to hold in the head, and the duplication is the boundary being real rather than
 * a directory that looks tidy.
 *
 * <pre>
 * template  vexel-desktop
 * title     VexelRay desktop application
 * summary   a window on vexelray-gui, wired the way the reference is
 * folder    ${artifactId}
 *
 * slot artifactId
 *     label     project name
 *     kind      text
 *     required  yes
 *     preset    my-app
 *     match     [a-z][a-z0-9]*(-[a-z0-9]+)*
 *     help      the folder it goes in, and the Maven artifactId
 *
 * fill blank  packageName  package  ${groupId}.${artifactId}
 * fill always packagePath  path     ${packageName}
 *
 * file pom.xml
 * file src/main/java/${packagePath}/${className}.java  from App.java
 * file src/main/java/${packagePath}/Capture.java       when capture
 *
 * needs dev.vexelray.gui:vexelray-gui-widget:${guiVersion}
 * </pre>
 *
 * <p>A line beginning with {@code #} is a comment, a blank line is nothing, and a
 * line's first word is what it is. Indentation is for reading: a {@code label}
 * line belongs to the {@code slot} above it because it says {@code label}, not
 * because of the spaces in front of it.
 *
 * <p>The whole thing is refused if any part of it is wrong, and every refusal
 * carries the line number. That is the same promise the rest of MainFrame makes,
 * and here it earns its keep twice over: a manifest that half-loaded would produce
 * a project with a placeholder still sitting in it.
 */
public final class Manifest {

    /** The keys a slot may use, in the order they are worth reading in. */
    private static final List<String> SLOT_KEYS = List.of(
            "label", "kind", "required", "preset", "match", "help", "least", "most", "choose", "rule");

    /** The keys a manifest may use at the top level. */
    private static final List<String> TOP_KEYS = List.of(
            "template", "title", "summary", "folder", "next", "slot", "fill", "file", "needs");

    private Manifest() {}

    /**
     * Reads a manifest.
     *
     * @param text  the manifest's whole contents
     * @param where what to call it in an error -- a path, usually
     */
    public static Template read(String text, String where) {
        String id = null;
        String title = null;
        String summary = null;
        String folder = null;
        String next = null;
        List<Template.Slot> slots = new ArrayList<>();
        List<Template.Fill> fills = new ArrayList<>();
        List<Template.Item> items = new ArrayList<>();
        List<Template.Need> needs = new ArrayList<>();
        Draft draft = null;

        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            int line = i + 1;
            String raw = lines[i].strip();
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            String word = firstWord(raw);
            String rest = raw.substring(word.length()).strip();

            // A slot's keys are read while its slot is open, so one of them turning
            // up before any slot is a mistake worth naming rather than ignoring.
            if (SLOT_KEYS.contains(word)) {
                if (draft == null) {
                    throw at(where, line, word + " belongs to a slot, and no slot has been opened")
                            .hint("open one first, e.g. slot artifactId")
                            .build();
                }
                draft.set(word, rest, where, line);
                continue;
            }
            if (draft != null && !word.equals("slot")) {
                slots.add(draft.build(where));
                draft = null;
            }

            switch (word) {
                case "template" -> id = one(rest, where, line, "template", "an id, e.g. vexel-desktop");
                case "title" -> title = one(rest, where, line, "title", "a title people will read");
                case "summary" -> summary = one(rest, where, line, "summary", "one line about it");
                case "folder" -> folder = one(rest, where, line, "folder",
                        "what the new folder is called, e.g. folder ${artifactId}");
                case "next" -> next = one(rest, where, line, "next",
                        "what to do once it is written, e.g. next cd ${artifactId} && mvn test");
                case "slot" -> {
                    if (draft != null) slots.add(draft.build(where));
                    draft = new Draft(one(rest, where, line, "slot", "a name, e.g. slot artifactId"), line);
                }
                case "fill" -> fills.add(fill(rest, where, line));
                case "file" -> items.add(item(rest, where, line));
                case "needs" -> needs.add(need(rest, where, line));
                default -> throw at(where, line, "a manifest has no " + quoted(word) + " line")
                        .hint("it can say: " + String.join(", ", TOP_KEYS))
                        .hint("a slot can say: " + String.join(", ", SLOT_KEYS))
                        .build();
            }
        }
        if (draft != null) slots.add(draft.build(where));

        if (id == null || id.isBlank()) {
            throw TemplateError.of(where + " does not say which template it is")
                    .hint("its first line should be: template <id>")
                    .build();
        }
        if (items.isEmpty()) {
            throw TemplateError.of(id + " writes no files")
                    .hint("a template needs at least one, e.g. file pom.xml")
                    .build();
        }
        checkWhole(id, slots, fills, items);
        return new Template(id,
                title == null ? id : title,
                summary == null ? "" : summary,
                folder == null ? "${" + firstSlotName(slots) + "}" : folder,
                next,
                List.copyOf(slots), List.copyOf(fills), List.copyOf(items), List.copyOf(needs));
    }

    // ---- the lines that are not slots --------------------------------------------------

    /** {@code fill blank packageName package ${groupId}.${artifactId}} */
    private static Template.Fill fill(String rest, String where, int line) {
        String[] parts = rest.split("\\s+", 4);
        if (parts.length < 4) {
            throw at(where, line, "a fill says when, what, how, and from what")
                    .hint("e.g. fill always packagePath path ${packageName}")
                    .hint("when is always or blank; how is one of: " + String.join(", ", Transform.names()))
                    .build();
        }
        Template.Fill.When when = switch (parts[0]) {
            case "always" -> Template.Fill.When.ALWAYS;
            case "blank" -> Template.Fill.When.BLANK;
            default -> throw at(where, line, "a fill happens always, or when the answer is blank -- not "
                    + quoted(parts[0])).build();
        };
        Transform how = Transform.of(parts[2]);
        if (how == null) {
            throw at(where, line, "there is no " + quoted(parts[2]) + " transform")
                    .hint("the transforms are: " + String.join(", ", Transform.names()))
                    .build();
        }
        return new Template.Fill(parts[1], when, how, parts[3]);
    }

    /** {@code file src/main/java/${packagePath}/Capture.java from Capture.java when capture} */
    private static Template.Item item(String rest, String where, int line) {
        List<String> words = words(rest);
        if (words.isEmpty()) {
            throw at(where, line, "a file line needs a path").hint("e.g. file pom.xml").build();
        }
        String path = words.getFirst();
        String source = null;
        String when = null;
        boolean unless = false;
        for (int i = 1; i < words.size(); i++) {
            String key = words.get(i);
            if (i + 1 >= words.size()) {
                throw at(where, line, key + " on a file line needs a value after it").build();
            }
            String value = words.get(++i);
            switch (key) {
                case "from" -> source = value;
                case "when" -> { when = value; unless = false; }
                case "unless" -> { when = value; unless = true; }
                default -> throw at(where, line, "a file line has no " + quoted(key))
                        .hint("it can say: from, when, unless")
                        .build();
            }
        }
        return new Template.Item(path, source == null ? path : source, when, unless);
    }

    /** {@code needs dev.vexelray.gui:vexelray-gui-widget:${guiVersion}} */
    private static Template.Need need(String rest, String where, int line) {
        String[] parts = rest.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw at(where, line, "a needs line is group:artifact:version")
                    .hint("e.g. needs dev.vexelray:vexelray-surface:${vexelrayVersion}")
                    .build();
        }
        return new Template.Need(parts[0].strip(), parts[1].strip(), parts[2].strip());
    }

    // ---- a slot, gathered a line at a time ----------------------------------------------

    private static final class Draft {

        private final String name;
        private final int line;
        private String label;
        private Template.Kind kind = Template.Kind.TEXT;
        private boolean required = true;
        private String preset;
        private String match;
        private String help;
        private Long least;
        private Long most;
        private final List<String> choices = new ArrayList<>();
        private final List<String> rules = new ArrayList<>();

        Draft(String name, int line) {
            this.name = name;
            this.line = line;
        }

        void set(String key, String value, String where, int at) {
            switch (key) {
                case "label" -> label = value;
                case "help" -> help = value;
                case "preset" -> preset = value;
                case "match" -> {
                    try {
                        Pattern.compile(value);
                    } catch (PatternSyntaxException e) {
                        throw Manifest.at(where, at,
                                name + " has a match that is not a pattern: " + e.getDescription()).build();
                    }
                    match = value;
                }
                case "kind" -> {
                    kind = Template.Kind.of(value.toLowerCase(Locale.ROOT));
                    if (kind == null) {
                        throw Manifest.at(where, at, "there is no " + quoted(value) + " kind of slot")
                                .hint("the kinds are: " + String.join(", ", Template.Kind.names()))
                                .build();
                    }
                }
                case "required" -> required = value.equals("yes") || value.equals("true");
                case "least" -> least = number(value, where, at, name + " has a least that");
                case "most" -> most = number(value, where, at, name + " has a most that");
                case "choose" -> choices.addAll(words(value));
                case "rule" -> rules.addAll(words(value));
                default -> throw Manifest.at(where, at, "a slot has no " + quoted(key)).build();
            }
        }

        private static long number(String value, String where, int at, String what) {
            try {
                return Long.parseLong(value.strip());
            } catch (NumberFormatException e) {
                throw Manifest.at(where, at, what + " is not a number: " + value).build();
            }
        }

        Template.Slot build(String where) {
            if (name.isBlank()) throw Manifest.at(where, line, "a slot needs a name").build();
            for (String rule : rules) {
                if (!Checks.knows(rule)) {
                    throw Manifest.at(where, line, "there is no " + quoted(rule) + " rule")
                            .hint("the rules are: " + String.join(", ", Checks.ruleNames()))
                            .build();
                }
            }
            return new Template.Slot(name,
                    label == null || label.isBlank() ? name.replace('-', ' ') : label,
                    kind, required, preset, match, help, least, most,
                    choices.isEmpty() ? null : List.copyOf(choices),
                    List.copyOf(rules));
        }
    }

    // ---- refusing a manifest that would half-work ---------------------------------------

    /**
     * The checks that need the whole file rather than one line: that no name is
     * claimed twice, that a conditional file names a yes-or-no that exists, and
     * that nothing writes to the same place twice.
     */
    private static void checkWhole(String id, List<Template.Slot> slots, List<Template.Fill> fills,
                                   List<Template.Item> items) {
        Set<String> names = new LinkedHashSet<>();
        for (Template.Slot slot : slots) {
            if (!names.add(slot.name())) {
                throw TemplateError.of(id + " asks for " + slot.name() + " twice")
                        .hint("each slot is one placeholder, so each needs its own name")
                        .build();
            }
        }
        Set<String> flags = new LinkedHashSet<>();
        for (Template.Slot slot : slots) if (slot.kind() == Template.Kind.FLAG) flags.add(slot.name());
        for (Template.Fill fill : fills) {
            if (fill.when() == Template.Fill.When.BLANK && !names.contains(fill.name())) {
                throw TemplateError.of(id + " fills in " + fill.name()
                                + " when it is left blank, and there is no such slot to leave blank")
                        .hint("use \"fill always\" for a value nobody is asked about")
                        .build();
            }
            names.add(fill.name());
        }
        for (Template.Item item : items) {
            if (item.when() == null) continue;
            if (!flags.contains(item.when())) {
                throw TemplateError.of(id + " writes " + item.path() + " only when "
                                + quoted(item.when()) + " says so, and there is no such yes-or-no slot")
                        .hint(flags.isEmpty()
                                ? "add one: slot " + item.when() + ", then kind flag"
                                : "the yes-or-no slots are: " + String.join(", ", flags))
                        .build();
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Template.Item item : items) {
            if (!paths.add(item.path())) {
                throw TemplateError.of(id + " writes " + item.path() + " twice")
                        .hint("two lines that resolve to one path would mean the second silently won")
                        .build();
            }
        }
    }

    // ---- odds and ends -------------------------------------------------------------------

    /**
     * What a template that did not say gets called.
     *
     * <p>The first slot that is not a folder, rather than simply the first. Almost
     * every template opens by asking where the project goes, so "the first slot" is
     * usually the destination -- and a folder named after the folder it is being
     * created in is not a near miss, it is nonsense, refused later by
     * {@code Scaffold.folder} with a message about the wrong thing. Guessing the
     * first slot that could plausibly be a name is right far more often, and wrong
     * in a way somebody can see.
     */
    private static String firstSlotName(List<Template.Slot> slots) {
        for (Template.Slot slot : slots) {
            if (slot.kind() != Template.Kind.FOLDER) return slot.name();
        }
        return slots.isEmpty() ? "name" : slots.getFirst().name();
    }

    private static String firstWord(String line) {
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return line.substring(0, end);
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.strip().split("\\s+")) if (!word.isEmpty()) words.add(word);
        return words;
    }

    private static String one(String rest, String where, int line, String key, String wanted) {
        if (rest.isBlank()) throw at(where, line, key + " needs " + wanted).build();
        return rest;
    }

    private static String quoted(String text) { return "\"" + text + "\""; }

    private static TemplateError.Builder at(String where, int line, String message) {
        return TemplateError.of(where + " line " + line + ": " + message);
    }
}
