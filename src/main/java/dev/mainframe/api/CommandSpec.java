package dev.mainframe.api;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.eval.Signature;

/**
 * What a hosted command takes, gives back and touches.
 *
 * <p>Every command in MainFrame is described this way, built-in or hosted, which
 * is what makes them all behave alike: the same argument checking before
 * anything runs, the same generated {@code --help}, the same {@code --dry-run}
 * and confirmation on anything that changes data, the same "did you mean" on a
 * typo. A host declares the shape once and gets all of it.
 *
 * <pre>{@code
 * CommandSpec spec = CommandSpec.named("deploy")
 *         .category("my app")
 *         .summary("push the current build to an environment")
 *         .argument("environment", DataType.TEXT, "where to deploy: staging or live")
 *         .switchFlag("skip-tests", '\0', "deploy without running the test suite")
 *         .input(DataType.TABLE)
 *         .output(DataType.RECORD)
 *         .effect(Effect.DESTRUCTIVE)
 *         .example("deploy staging")
 *         .build();
 * }</pre>
 */
public final class CommandSpec {

    private final Signature signature;

    private CommandSpec(Signature signature) { this.signature = signature; }

    public static Builder named(String name) { return new Builder(name); }

    public String name() { return signature.name(); }

    public String category() { return signature.category(); }

    /** The one-line usage MainFrame will show, e.g. {@code deploy <environment>}. */
    public String usage() { return signature.usage(); }

    Signature signature() { return signature; }

    Effect effect() {
        return switch (signature.effect()) {
            case PURE -> Effect.PURE;
            case READS -> Effect.READS;
            case SESSION -> Effect.SESSION;
            case WRITES -> Effect.WRITES;
            case DESTRUCTIVE -> Effect.DESTRUCTIVE;
        };
    }

    /** Fails early on anything the shell could not accept later. */
    static void checkName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a command needs a name");
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            throw new IllegalArgumentException(
                    "the command name \"" + name + "\" must start with a letter, so that MainFrame can read it");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '_' || c == '-';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "the command name \"" + name + "\" cannot contain '" + c
                                + "' -- names may hold letters, digits, dashes and underscores");
            }
            // A dash must join two words: sort-by reads as one name, sort- does not.
            if (c == '-' && (i == name.length() - 1 || !Character.isLetter(name.charAt(i + 1)))) {
                throw new IllegalArgumentException(
                        "in the command name \"" + name + "\", every dash must be followed by a letter");
            }
        }
    }

    public static final class Builder {

        private record ParamSpec(String name, DataType type, boolean required, boolean rest,
                                 String description) {}

        /** @param type null for a switch that takes no value. */
        private record FlagSpec(String name, char shortName, DataType type, String description) {}

        private final String name;
        private final List<ParamSpec> params = new ArrayList<>();
        private final List<FlagSpec> flags = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private DataType input;
        private DataType output = DataType.ANY;
        private boolean sawRest;
        private Effect effect = Effect.PURE;
        private String category = "hosted commands";
        private String summary = "";

        private Builder(String name) {
            checkName(name);
            this.name = name;
        }

        /** The group this command is listed under in {@code help}. */
        public Builder category(String category) {
            this.category = category == null || category.isBlank() ? "hosted commands" : category;
            return this;
        }

        /** One line, lower case, saying what it does. Shown in {@code help}. */
        public Builder summary(String summary) {
            this.summary = summary == null ? "" : summary;
            return this;
        }

        /** A required positional argument. Declare these before the optional ones. */
        public Builder argument(String name, DataType type, String description) {
            checkParameter(name, description, true);
            params.add(new ParamSpec(name, type, true, false, description));
            return this;
        }

        /** An argument that may be left out. */
        public Builder optional(String name, DataType type, String description) {
            checkParameter(name, description, false);
            params.add(new ParamSpec(name, type, false, false, description));
            return this;
        }

        /** A final argument that soaks up everything else. Only one, and it goes last. */
        public Builder repeatable(String name, DataType type, String description) {
            checkParameter(name, description, false);
            params.add(new ParamSpec(name, type, false, true, description));
            sawRest = true;
            return this;
        }

        /** A flag that is either given or not, e.g. {@code --force}. */
        public Builder switchFlag(String name, char shortName, String description) {
            checkFlag(name, description);
            flags.add(new FlagSpec(name, shortName, null, description));
            return this;
        }

        /** A flag that carries a value, written {@code --name=value}. */
        public Builder valueFlag(String name, char shortName, DataType type, String description) {
            checkFlag(name, description);
            flags.add(new FlagSpec(name, shortName, type, description));
            return this;
        }

        /**
         * What this command accepts from the pipe. Leave it unset to ignore the
         * pipe entirely; set it and MainFrame checks the shape before calling you.
         */
        public Builder input(DataType type) {
            this.input = type;
            return this;
        }

        /** What the command hands to the next stage. Documentation for the reader. */
        public Builder output(DataType type) {
            this.output = type == null ? DataType.ANY : type;
            return this;
        }

        public Builder effect(Effect effect) {
            this.effect = effect == null ? Effect.PURE : effect;
            return this;
        }

        /** A line someone could type. Worth adding: it is the part of help people read. */
        public Builder example(String example) {
            if (example != null && !example.isBlank()) examples.add(example);
            return this;
        }

        public CommandSpec build() {
            if (summary.isBlank()) {
                throw new IllegalArgumentException(
                        "the command \"" + name + "\" needs a summary, or help would have nothing to say about it");
            }
            Signature.Builder signature = Signature.named(name, category)
                    .summary(summary)
                    .effect(effect.internal())
                    .output(output.internal());
            if (input != null) signature.input(input.internal());
            for (ParamSpec param : params) {
                if (param.rest()) {
                    signature.rest(param.name(), param.type().internal(), param.description());
                } else if (param.required()) {
                    signature.required(param.name(), param.type().internal(), param.description());
                } else {
                    signature.optional(param.name(), param.type().internal(), param.description());
                }
            }
            for (FlagSpec flag : flags) {
                if (flag.type() == null) {
                    signature.switchFlag(flag.name(), flag.shortName(), flag.description());
                } else {
                    signature.valueFlag(flag.name(), flag.shortName(), flag.type().internal(),
                            flag.description());
                }
            }
            for (String example : examples) signature.example(example);
            return new CommandSpec(signature.build());
        }

        private void checkParameter(String parameterName, String description, boolean isRequired) {
            if (parameterName == null || parameterName.isBlank()) {
                throw new IllegalArgumentException("every argument of \"" + name + "\" needs a name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("the argument \"" + parameterName + "\" of \"" + name
                        + "\" needs a description, or help would have nothing to say about it");
            }
            if (sawRest) {
                throw new IllegalArgumentException("in \"" + name
                        + "\", the repeatable argument has to be the last one");
            }
            if (isRequired && !params.isEmpty() && !params.getLast().required()) {
                throw new IllegalArgumentException("in \"" + name + "\", the required argument \""
                        + parameterName + "\" has to come before the optional ones");
            }
            for (ParamSpec existing : params) {
                if (existing.name().equals(parameterName)) {
                    throw new IllegalArgumentException("\"" + name + "\" already has an argument called \""
                            + parameterName + "\"");
                }
            }
        }

        private void checkFlag(String flagName, String description) {
            if (flagName == null || flagName.isBlank()) {
                throw new IllegalArgumentException("every flag of \"" + name + "\" needs a name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("the flag --" + flagName + " of \"" + name
                        + "\" needs a description, or help would have nothing to say about it");
            }
            if (flagName.equals("help") || flagName.equals("dry-run") || flagName.equals("yes")) {
                throw new IllegalArgumentException("--" + flagName
                        + " is provided by MainFrame, so \"" + name + "\" must not declare it");
            }
            for (FlagSpec existing : flags) {
                if (existing.name().equals(flagName)) {
                    throw new IllegalArgumentException("\"" + name + "\" already has a --" + flagName + " flag");
                }
            }
        }
    }
}
