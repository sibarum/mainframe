package dev.mainframe.template;

import java.util.ArrayList;
import java.util.List;

/**
 * A template: what to ask for, and what to write once it has been answered.
 *
 * <p>Data, not code. A template is read out of a manifest and is inert until
 * something hands it a set of answers -- which is what lets the same definition
 * drive the form a person fills in, the check a script runs over a record it
 * built itself, and the listing {@code templates} prints. There is no second
 * description of a template anywhere.
 *
 * <p>The engine knows nothing about any particular template, and that is the
 * point rather than a nicety: the day a second one is added, the only new thing
 * in the world should be a folder of files and a manifest beside them.
 */
/**
 * @param next what to tell somebody to do once it is written, or null. Comes from
 *             the template because only the template knows: a Maven project ends
 *             with {@code mvn compile exec:exec} and a folder of notes ends with
 *             nothing at all, and a shell that guessed would be confidently wrong
 *             for every template but the one it was written against.
 */
public record Template(
        String id,
        String title,
        String summary,
        String folder,
        String next,
        List<Slot> slots,
        List<Fill> fills,
        List<Item> items,
        List<Need> needs) {

    /** What a slot's answer will be read as. Deliberately fewer than a form has. */
    public enum Kind {
        /** Text. */
        TEXT("string"),
        /** A whole number. */
        NUMBER("int"),
        /** A yes-or-no. An item may be conditional on one of these and nothing else. */
        FLAG("bool"),
        /** A folder that is already there, offered with a chooser. */
        FOLDER("path");

        private final String written;

        Kind(String written) { this.written = written; }

        /** How the shell's form vocabulary spells this. */
        public String written() { return written; }

        static Kind of(String word) {
            for (Kind kind : values()) if (kind.name().toLowerCase().equals(word)) return kind;
            return null;
        }

        static List<String> names() {
            List<String> names = new ArrayList<>();
            for (Kind kind : values()) names.add(kind.name().toLowerCase());
            return List.copyOf(names);
        }
    }

    /**
     * One thing to ask for.
     *
     * @param match   a regular expression the answer has to match whole, or null
     * @param preset  what the field starts filled in with; may name other slots
     * @param rules   the extra checks by name -- see {@link Checks}
     */
    public record Slot(
            String name,
            String label,
            Kind kind,
            boolean required,
            String preset,
            String match,
            String help,
            Long least,
            Long most,
            List<String> choices,
            List<String> rules) {}

    /**
     * A value worked out rather than asked for.
     *
     * <p>Two reasons a template needs these. One is a placeholder nobody should be
     * asked about -- {@code packagePath} is {@code package} with the dots turned
     * into slashes, and asking twice is asking to be told two different things.
     * The other is a slot somebody may leave blank: {@code when BLANK} fills it in
     * from what they did type, so "leave it empty for the obvious answer" is a real
     * offer rather than a comment in a README.
     *
     * @param when   {@link When#ALWAYS}, or {@link When#BLANK} to fill in a slot
     *               only when its answer came back empty
     * @param how    the transform applied to {@code from} once it is substituted
     * @param from   the text, which may name slots and earlier fills
     */
    public record Fill(String name, When when, Transform how, String from) {

        public enum When { ALWAYS, BLANK }
    }

    /** One file the template writes. */
    public record Item(String path, String source, String when, boolean unless) {

        /** True when this file is written only if a flag says so. */
        public boolean conditional() { return when != null; }
    }

    /**
     * A Maven artifact the generated project will not build without.
     *
     * <p>Declared so the answer to "will this actually run" is looked up rather
     * than hoped for. See {@link Checks#missingArtifacts}.
     */
    public record Need(String group, String artifact, String version) {

        public String coordinate() { return group + ":" + artifact + ":" + version; }

        /** Where this would be in a local Maven repository. */
        public String repoPath() {
            return group.replace('.', '/') + "/" + artifact + "/" + version;
        }

        /** The jar's name inside that folder. */
        public String jarName() { return artifact + "-" + version + ".jar"; }
    }

    public Slot slot(String name) {
        for (Slot slot : slots) if (slot.name().equals(name)) return slot;
        return null;
    }

    /** Every placeholder a caller is expected to supply or have filled in. */
    public List<String> placeholders() {
        List<String> names = new ArrayList<>(slots.size() + fills.size());
        for (Slot slot : slots) names.add(slot.name());
        for (Fill fill : fills) if (!names.contains(fill.name())) names.add(fill.name());
        return List.copyOf(names);
    }
}
