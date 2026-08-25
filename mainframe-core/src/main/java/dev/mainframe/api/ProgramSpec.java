package dev.mainframe.api;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.Programs;

/**
 * What a hosted {@link Program} is called, and what to say about it.
 *
 * <p>There is no signature here, and that is the point: a program parses its own
 * line, so MainFrame has nothing to check for it and nothing to generate. What it
 * does have to be able to do is answer a person who asks what this thing is --
 * which is what {@code which} and {@code programs} do with the summary, the usage
 * line and the examples.
 *
 * <pre>{@code
 * ProgramSpec spec = ProgramSpec.named("jdk")
 *         .summary("switch the JDK this session uses")
 *         .usage("jdk <version>")
 *         .example("^jdk 25")
 *         .example("^jdk --list")
 *         .build();
 * }</pre>
 */
public final class ProgramSpec {

    private final String name;
    private final String summary;
    private final String usage;
    private final List<String> examples;

    private ProgramSpec(String name, String summary, String usage, List<String> examples) {
        this.name = name;
        this.summary = summary;
        this.usage = usage;
        this.examples = examples;
    }

    public static Builder named(String name) { return new Builder(name); }

    public String name() { return name; }

    public String summary() { return summary; }

    /** The one-line usage, e.g. {@code jdk <version>}. */
    public String usage() { return usage; }

    public List<String> examples() { return examples; }

    /** How it is written on a line, which is the name after a caret. */
    public String invocation() {
        return Programs.isPlainWord(name) ? "^" + name : "^\"" + name + "\"";
    }

    public static final class Builder {

        private final String name;
        private final List<String> examples = new ArrayList<>();
        private String summary = "";
        private String usage;

        private Builder(String name) {
            String problem = Programs.problemWithName(name);
            if (problem != null) throw new IllegalArgumentException(problem);
            this.name = name;
        }

        /** One line, lower case, saying what it does. Shown by {@code programs}. */
        public Builder summary(String summary) {
            this.summary = summary == null ? "" : summary;
            return this;
        }

        /**
         * How it is used, in one line, e.g. {@code jdk <version>}. Defaults to the
         * name on its own -- worth setting, since MainFrame cannot work this out
         * from a program the way it can from a command.
         */
        public Builder usage(String usage) {
            this.usage = usage == null || usage.isBlank() ? null : usage.strip();
            return this;
        }

        /** A line someone could type. It is the part of help people actually read. */
        public Builder example(String example) {
            if (example != null && !example.isBlank()) examples.add(example.strip());
            return this;
        }

        public ProgramSpec build() {
            if (summary.isBlank()) {
                throw new IllegalArgumentException("the program \"" + name
                        + "\" needs a summary, or which and programs would have nothing to say about it");
            }
            return new ProgramSpec(name, summary, usage == null ? name : usage, List.copyOf(examples));
        }
    }
}
