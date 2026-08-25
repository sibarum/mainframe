package dev.mainframe.gui.profile;

import dev.mainframe.Session;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.gui.app.ProjectScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.Consumer;

/**
 * Profiles, as commands.
 *
 * <h2>Why the menu does not do this itself</h2>
 * Every one of these is a real MainFrame command with real {@code help} text, and the settings menu works by
 * <b>submitting one of them</b> rather than by calling Java behind the shell's back. Three things fall out of
 * that and none of them would have if the menu owned the logic: what the menu did is in the scrollback, so you can
 * see it and read it back; it is in the history, so Up repeats it; and anything the menu can do can be scripted,
 * piped and put in a file, because it was never anything but a command. The menu is a way of discovering the
 * command surface, not a second implementation of it.
 *
 * <h2>The definition is the editor</h2>
 * {@code profile-new} and {@code profile-edit} hand {@link Profile#definition()} to
 * {@link ConsoleContext#form}, which prints the questions into the scrollback and reads the answers off the
 * command line. So there is no profile editor anywhere in this codebase: there is a form definition, and
 * MainFrame's data entry does the rest -- including {@code !back}, {@code !cancel}, {@code !clear}, the review
 * step, and refusing an answer that would not survive being handed to a child process.
 *
 * <p>That is the shape any other app should reach for when it needs a screen of its own. Profiles are the
 * console's worked example of it.
 */
final class ProfileCommands {

    private static final String CATEGORY = "profiles";

    private final ProfileStore store;
    /** The console these run in: where a form is shown, and who knows what the project is right now. */
    private final ConsoleContext console;
    /** Told the name of the profile just applied to the live environment, so the header can say so. */
    private final Consumer<String> onApplied;

    ProfileCommands(ProfileStore store, ConsoleContext console, Consumer<String> onApplied) {
        this.store = store;
        this.console = console;
        this.onApplied = onApplied;
    }

    /** Add every profile command to {@code registry}. */
    void register(Registry registry) {
        registry.add(list());
        registry.add(create());
        registry.add(edit());
        registry.add(drop());
        registry.add(use());
        registry.add(preferred());
    }

    // ---- profile ---------------------------------------------------------------------

    /** {@code profile} -- what there is, as a table, so the rest of the language works on it. */
    private Builtin list() {
        Signature signature = Signature.named("profile", CATEGORY)
                .summary("list the environment profiles you have")
                .input(ValueType.NOTHING)
                .output(ValueType.TABLE)
                .effect(Signature.Effect.READS)
                .example("profile")
                .example("profile | where paths > 0")
                .example("profile | select name vars paths")
                .build();
        return command(signature, args -> {
            String mine = store.defaultName();
            String here = console.project().profile();
            List<Value> rows = new ArrayList<>();
            for (Profile profile : store.all()) {
                SequencedMap<String, Value> row = new LinkedHashMap<>();
                row.put("name", new Value.Str(profile.name()));
                row.put("vars", new Value.Int(profile.env().size()));
                row.put("paths", new Value.Int(profile.paths().size()));
                row.put("default", new Value.Str(marks(profile.name(), mine, here)));
                rows.add(new Value.Rec(row));
            }
            if (rows.isEmpty()) {
                args.session().out().note("no profiles yet -- profile-new makes one");
            }
            return new Value.ListVal(rows);
        });
    }

    /** How a profile is marked in a listing: whose default it is, if anyone's. */
    private static String marks(String name, String mine, String here) {
        List<String> marks = new ArrayList<>();
        if (name.equals(here)) {
            marks.add("project");
        }
        if (name.equals(mine)) {
            marks.add("mine");
        }
        return String.join(" + ", marks);
    }

    // ---- profile-new / profile-edit ---------------------------------------------------

    private Builtin create() {
        Signature signature = Signature.named("profile-new", CATEGORY)
                .summary("fill in a new environment profile")
                .optional("name", ValueType.STRING, "a name to start the form with")
                .input(ValueType.ANY)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.WRITES)
                .example("profile-new")
                .example("profile-new \"jdk-21\"")
                .build();
        return command(signature, args -> {
            String name = args.str(0, "");
            Value.Rec starting = piped(args, Profile.empty(name).toRecord());
            return fill(args, starting, name.isBlank() ? "New profile" : "New profile: " + name, null);
        });
    }

    private Builtin edit() {
        Signature signature = Signature.named("profile-edit", CATEGORY)
                .summary("change an environment profile you already have")
                .required("name", ValueType.STRING, "which profile to open")
                .input(ValueType.ANY)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.WRITES)
                .example("profile-edit \"jdk-21\"")
                .build();
        return command(signature, args -> {
            String name = args.str(0);
            Profile existing = store.read(name).orElseThrow(() -> unknown(args, name));
            Value.Rec starting = piped(args, existing.toRecord());
            return fill(args, starting, "Profile: " + name, existing.name());
        });
    }

    /**
     * Show the form and save what comes back.
     *
     * <p>{@code was} is the name the profile had before, or null for a new one. Renaming through the form is
     * therefore an ordinary edit rather than a separate command: the answers name the profile, and if that is a
     * different name than the one opened, the old one moves rather than a second one appearing beside it.
     */
    private Value fill(Args args, Value.Rec starting, String title, String was) {
        Session session = args.session();
        Value.Rec answers = console.form(Profile.definition(), starting, title);
        if (answers == null) {
            session.out().note("cancelled -- nothing was saved");
            return Value.Nothing.INSTANCE;
        }
        Profile profile;
        try {
            profile = Profile.fromRecord(answers, was == null ? "" : was);
        } catch (IllegalArgumentException e) {
            throw args.fail("E911", e.getMessage())
                    .hint("a variable name has to be something a child process could receive")
                    .build();
        }
        String problem = Profile.problemWithName(profile.name());
        if (problem != null) {
            throw args.fail("E912", problem)
                    .hint("e.g. jdk-21, rust-nightly, ci-like")
                    .build();
        }
        if (was != null && !was.equals(profile.name())) {
            store.rename(was, profile.name());
        }
        if (was == null && store.has(profile.name())) {
            throw args.fail("E913", "there is already a profile called " + profile.name())
                    .hint("profile-edit " + Values.quoted(profile.name()) + " changes that one")
                    .build();
        }
        store.write(profile);
        session.out().note("saved " + profile.name() + " -- " + profile.summary());
        session.out().note("profile-use " + Values.quoted(profile.name()) + " applies it to this session");
        return Value.Nothing.INSTANCE;
    }

    /** A record piped in stands in for what the form starts from, so a profile can be built without the screen. */
    private static Value.Rec piped(Args args, Value.Rec fallback) {
        return args.input() instanceof Value.Rec record ? record : fallback;
    }

    // ---- profile-drop ----------------------------------------------------------------

    private Builtin drop() {
        Signature signature = Signature.named("profile-drop", CATEGORY)
                .summary("delete an environment profile")
                .required("name", ValueType.STRING, "which profile to delete")
                .switchFlag("yes", 'y', "delete it without asking")
                .input(ValueType.NOTHING)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.DESTRUCTIVE)
                .example("profile-drop \"old-jdk\" --yes")
                .build();
        return command(signature, args -> {
            String name = args.str(0);
            Profile existing = store.read(name).orElseThrow(() -> unknown(args, name));
            if (!args.flag("yes") && !args.session().confirm("Delete the profile " + name + "?")) {
                throw args.fail("E914", "not deleting " + name)
                        .hint("profile-drop " + Values.quoted(name) + " --yes once you are sure")
                        .build();
            }
            store.drop(existing.name());
            args.session().out().note("deleted " + existing.name());
            return Value.Nothing.INSTANCE;
        });
    }

    // ---- profile-use -----------------------------------------------------------------

    private Builtin use() {
        Signature signature = Signature.named("profile-use", CATEGORY)
                .summary("apply a profile to this session's environment")
                .required("name", ValueType.STRING, "which profile to apply")
                .input(ValueType.NOTHING)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.SESSION)
                .example("profile-use \"jdk-21\"")
                .example("profile-use \"jdk-21\"; ^java -version")
                .build();
        return command(signature, args -> {
            String name = args.str(0);
            Profile profile = store.read(name).orElseThrow(() -> unknown(args, name));
            List<String> report = profile.applyTo(args.session().env());
            if (report.isEmpty()) {
                args.session().out().note(profile.name() + " sets nothing");
            } else {
                report.forEach(args.session().out()::note);
            }
            onApplied.accept(profile.name());
            return Value.Nothing.INSTANCE;
        });
    }

    // ---- profile-default -------------------------------------------------------------

    private Builtin preferred() {
        Signature signature = Signature.named("profile-default", CATEGORY)
                .summary("say which profile should be the default")
                .optional("name", ValueType.STRING, "which profile; omit to show what is set")
                .switchFlag("project", 'p', "set it for this project only, in the project's own file")
                .switchFlag("clear", 'c', "remove the preference instead of setting one")
                .input(ValueType.NOTHING)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.WRITES)
                .example("profile-default")
                .example("profile-default \"jdk-21\"")
                .example("profile-default \"rust-nightly\" --project")
                .example("profile-default --project --clear")
                .build();
        return command(signature, args -> {
            ProjectScope here = console.project();
            boolean scoped = args.flag("project");
            if (scoped && !here.present()) {
                throw args.fail("E915", "there is no project open, so there is nowhere to write that")
                        .hint("open a project in the application first, and this can be written there")
                        .hint("without --project this sets your own default instead")
                        .build();
            }
            if (args.flag("clear")) {
                if (scoped) {
                    here.profile("");
                    args.session().out().note("cleared the project default in " + here.file());
                } else {
                    store.defaultName("");
                    args.session().out().note("cleared your default profile");
                }
                return Value.Nothing.INSTANCE;
            }
            if (!args.has(0)) {
                report(args.session(), here);
                return Value.Nothing.INSTANCE;
            }
            String name = args.str(0);
            Profile profile = store.read(name).orElseThrow(() -> unknown(args, name));
            if (scoped) {
                here.profile(profile.name());
                args.session().out().note("this project now prefers " + profile.name());
                args.session().out().note("written to " + here.file());
            } else {
                store.defaultName(profile.name());
                args.session().out().note("your default profile is now " + profile.name());
            }
            return Value.Nothing.INSTANCE;
        });
    }

    /** What is set, when nobody asked to change it. */
    private void report(Session session, ProjectScope here) {
        String mine = store.defaultName();
        session.out().note(mine.isEmpty() ? "you have no default profile" : "your default is " + mine);
        if (!here.present()) {
            session.out().note("no project is open, so there is no project default");
            return;
        }
        String there = here.profile();
        if (there.isEmpty()) {
            session.out().note(here.name() + " does not name a profile");
        } else if (store.has(there)) {
            session.out().note(here.name() + " prefers " + there);
        } else {
            session.out().warn(here.name() + " prefers " + there + ", which this machine does not have");
        }
    }

    // ---- plumbing --------------------------------------------------------------------

    private static dev.mainframe.MfError unknown(Args args, String name) {
        return args.fail("E910", "there is no profile called " + name)
                .hint("profile lists the ones you have")
                .hint("profile-new " + Values.quoted(name) + " makes it")
                .build();
    }

    /** One command, from a signature and what it does -- the shape the window's own commands already use. */
    private static Builtin command(Signature signature, java.util.function.Function<Args, Value> body) {
        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                return body.apply(args);
            }
        };
    }
}
