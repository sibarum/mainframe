package dev.mainframe.eval;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.value.ValueType;

/**
 * The declared shape of a builtin: what it takes, what it gives back and what it
 * touches.
 *
 * <p>This is the single source of truth for three things MainFrame promises:
 * arguments are checked before anything runs, {@code help} is always accurate,
 * and completion knows what can come next. A builtin cannot ship without one.
 */
public record Signature(
        String name,
        String category,
        String summary,
        List<Param> params,
        List<Flag> flags,
        ValueType input,
        ValueType output,
        Effect effect,
        List<String> examples) {

    /** What a builtin does to the world. Drives the guardrails in {@link Interpreter}. */
    public enum Effect {
        /** Touches nothing outside the pipeline. */
        PURE,
        /** Reads from disk or the environment. */
        READS,
        /** Changes this session -- the directory, a variable, the PATH -- and nothing on disk. */
        SESSION,
        /** Creates or updates things, but never loses data. */
        WRITES,
        /** Can lose data. Never runs without confirmation. */
        DESTRUCTIVE
    }

    /**
     * @param rest  when true this parameter soaks up every remaining argument
     * @param lazy  when true the argument arrives unevaluated, so it can be
     *              evaluated once per row (this is how {@code where} works)
     */
    public record Param(String name, ValueType type, boolean required, boolean rest, boolean lazy,
                        String description) {}

    /** @param shortName {@code '\0'} when the flag has no single-letter form. */
    public record Flag(String name, char shortName, ValueType type, String description) {
        public boolean isSwitch() { return type == ValueType.BOOL; }
    }

    public Param param(int index) {
        if (index < params.size()) return params.get(index);
        if (!params.isEmpty() && params.getLast().rest()) return params.getLast();
        return null;
    }

    public Flag flag(String name) {
        for (Flag f : flags) if (f.name().equals(name)) return f;
        return null;
    }

    public Flag shortFlag(String name) {
        if (name.length() != 1) return null;
        for (Flag f : flags) if (f.shortName() == name.charAt(0)) return f;
        return null;
    }

    public int requiredCount() {
        int n = 0;
        for (Param p : params) if (p.required()) n++;
        return n;
    }

    /** The one-line form shown by {@code help} and in argument errors. */
    public String usage() {
        StringBuilder sb = new StringBuilder(name);
        for (Param p : params) {
            sb.append(' ');
            String body = p.name() + (p.rest() ? "..." : "");
            sb.append(p.required() ? "<" + body + ">" : "[" + body + "]");
        }
        for (Flag f : flags) sb.append(" [--").append(f.name()).append(f.isSwitch() ? "" : " " + f.type().display()).append(']');
        return sb.toString();
    }

    public static Builder named(String name, String category) { return new Builder(name, category); }

    public static final class Builder {
        private final String name;
        private final String category;
        private String summary = "";
        private final List<Param> params = new ArrayList<>();
        private final List<Flag> flags = new ArrayList<>();
        private ValueType input = ValueType.NOTHING;
        private ValueType output = ValueType.ANY;
        private Effect effect = Effect.PURE;
        private final List<String> examples = new ArrayList<>();

        private Builder(String name, String category) { this.name = name; this.category = category; }

        public Builder summary(String s) { this.summary = s; return this; }

        public Builder required(String name, ValueType type, String description) {
            params.add(new Param(name, type, true, false, false, description));
            return this;
        }

        public Builder optional(String name, ValueType type, String description) {
            params.add(new Param(name, type, false, false, false, description));
            return this;
        }

        public Builder rest(String name, ValueType type, String description) {
            params.add(new Param(name, type, false, true, false, description));
            return this;
        }

        /** A parameter evaluated once per row, e.g. the condition of {@code where}. */
        public Builder condition(String name, String description) {
            params.add(new Param(name, ValueType.EXPR, true, false, true, description));
            return this;
        }

        public Builder conditions(String name, String description) {
            params.add(new Param(name, ValueType.EXPR, true, true, true, description));
            return this;
        }

        public Builder switchFlag(String name, char shortName, String description) {
            flags.add(new Flag(name, shortName, ValueType.BOOL, description));
            return this;
        }

        public Builder valueFlag(String name, char shortName, ValueType type, String description) {
            flags.add(new Flag(name, shortName, type, description));
            return this;
        }

        public Builder input(ValueType t) { this.input = t; return this; }
        public Builder output(ValueType t) { this.output = t; return this; }
        public Builder effect(Effect e) { this.effect = e; return this; }
        public Builder example(String e) { examples.add(e); return this; }

        public Signature build() {
            // Every command that changes something gets the same two guardrail
            // flags, so a user never has to wonder whether this one supports them.
            if (effect == Effect.WRITES || effect == Effect.DESTRUCTIVE) {
                flags.add(new Flag("dry-run", '\0', ValueType.BOOL, "show what would happen, change nothing"));
            }
            if (effect == Effect.DESTRUCTIVE) {
                flags.add(new Flag("yes", '\0', ValueType.BOOL, "skip the confirmation prompt"));
            }
            return new Signature(name, category, summary, List.copyOf(params), List.copyOf(flags),
                    input, output, effect, List.copyOf(examples));
        }
    }
}
