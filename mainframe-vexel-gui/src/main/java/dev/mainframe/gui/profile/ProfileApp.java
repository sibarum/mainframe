package dev.mainframe.gui.profile;

import dev.mainframe.eval.Registry;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.value.Values;
import dev.vexelray.gui.core.input.MenuSink;

import java.util.List;

/**
 * Environment profiles, as something plugged into a console.
 *
 * <h2>Why this is an app and not a feature of the window</h2>
 * Profiles are the most obviously useful thing to have in a shell that starts programs, and they are still not
 * the console's business. A console in a mail reader has no toolchains to switch between; one in an editor has
 * several. So this is a {@link ConsoleApp} like any other — the commands, the menu that finds them, and the
 * badge in the header are all contributed rather than built in, and dropping the {@code .app(new ProfileApp(..))}
 * line leaves a console with no idea that profiles exist.
 *
 * <p>It is also the reference for what an app looks like. Everything it does, a host's own app can do: it
 * registers commands, it draws a menu that <em>submits</em> those commands rather than duplicating them, it puts
 * a fact in the header, and it runs one line as the session opens. No private door was needed for any of it.
 *
 * <pre>{@code
 * ProfileApp profiles = new ProfileApp(new ProfileStore(settings));
 * ConsoleSpec.builder().app(profiles).badge(profiles::badge).build();
 * }</pre>
 */
public final class ProfileApp implements ConsoleApp {

    private final ProfileStore store;

    /** The profile last applied to this session's environment, for the header to show. Written on the job thread. */
    private volatile String applied = "";

    /**
     * The console this was plugged into, remembered from {@link #commands} — the one hook that is called once,
     * early, and is handed the context.
     *
     * <p>It exists so {@link #badge()} can be a plain supplier. A header line is bound before the console is
     * constructed ({@code ConsoleSpec} is what the constructor takes), so an app that needs the console to work
     * out what to show cannot be handed it at binding time; it has to pick it up on the way past.
     */
    private volatile ConsoleContext console;

    public ProfileApp(ProfileStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "profiles";
    }

    @Override
    public String summary() {
        return "named sets of environment variables and binary directories";
    }

    /** The store these commands read and write, so a host can list or preselect profiles itself. */
    public ProfileStore store() {
        return store;
    }

    @Override
    public void commands(Registry registry, ConsoleContext console) {
        this.console = console;
        new ProfileCommands(store, console, name -> applied = name).register(registry);
    }

    /**
     * Apply the default profile as the session opens, by running the command for it.
     *
     * <p>Visibly rather than quietly, because a shell whose PATH is not the PATH you would have guessed has to
     * say so somewhere, and the honest place is the first lines of the scrollback. A project naming a profile
     * this machine does not have is said out loud too, and then ignored.
     */
    @Override
    public void started(ConsoleContext console) {
        String here = console.project().profile();
        if (!here.isEmpty() && !store.has(here)) {
            console.post("this project asks for the profile " + here
                    + ", which this machine does not have -- carrying on without it");
        }
        String name = effectiveDefault(console);
        if (!name.isEmpty()) {
            console.run("profile-use " + quoted(name));
        }
    }

    /**
     * The profiles menu: what there is, and what can be done with them right now.
     *
     * <p>Every line runs a command, so nothing here is a second implementation of anything: the menu is a way of
     * finding {@code profile-new}, not an alternative to it. What does not apply is greyed rather than hidden, so
     * the menu teaches the same shape whatever the state.
     */
    @Override
    public void menu(MenuSink menu, ConsoleContext console) {
        String active = activeProfile(console);
        List<String> names = store.names();

        menu.item("New profile...", () -> console.run("profile-new"));
        menu.item("Edit " + (active.isEmpty() ? "profile" : active) + "...", !active.isEmpty(),
                () -> console.run("profile-edit " + quoted(active)));

        menu.separator();
        if (names.isEmpty()) {
            menu.item("No profiles yet", false, null);
        }
        // In use and preferred are different states, and the line has to say which -- a profile that is only the
        // default has not touched the PATH yet. Both the marker and the greying read the same fact, so they
        // cannot disagree: the only line that is greyed is the one there is nothing left to do to.
        String preferred = effectiveDefault(console);
        for (String name : names) {
            String mark = name.equals(applied) ? "  (in use)" : name.equals(preferred) ? "  (default)" : "";
            menu.item("Use " + name + mark, !name.equals(applied),
                    () -> console.run("profile-use " + quoted(name)));
        }

        menu.separator();
        var here = console.project();
        menu.item(here.present()
                        ? "Default for " + here.name() + ": " + (active.isEmpty() ? "-" : active)
                        : "Default for this project (none open)",
                here.present() && !active.isEmpty(),
                () -> console.run("profile-default " + quoted(active) + " --project"));
        menu.item("My default: " + (store.defaultName().isEmpty() ? "-" : store.defaultName()),
                !active.isEmpty(),
                () -> console.run("profile-default " + quoted(active)));

        menu.separator();
        menu.item("List profiles", () -> console.run("profile"));
    }

    /**
     * The profile the next command will run under, shown up in the header where a 5250 kept its library list.
     *
     * <p>A profile that is set but not yet applied is marked, because those are different states and the
     * difference is the one that catches people out: the PATH is not yet what the header would let you assume.
     *
     * <p>Hand this to {@code ConsoleSpec.badge} as a method reference; it is read once a frame and is cheap
     * enough for that.
     */
    public String badge() {
        if (!applied.isEmpty()) {
            return applied;
        }
        ConsoleContext here = console;
        if (here == null) {
            return "";
        }
        String pending = effectiveDefault(here);
        return pending.isEmpty() ? "" : pending + " (not applied)";
    }

    /** The profile last applied to this session, or {@code ""} if none has been. */
    public String appliedProfile() {
        return applied;
    }

    /**
     * Which profile this session is on: what was applied if anything has been, otherwise what would be.
     *
     * <p>The two are different questions and the menu needs both, but the one worth showing is this: the profile
     * whose variables the next command will see, or would see once it were applied.
     */
    private String activeProfile(ConsoleContext console) {
        return applied.isEmpty() ? effectiveDefault(console) : applied;
    }

    /** The default that applies here: the project's if it names one this machine has, otherwise the user's. */
    private String effectiveDefault(ConsoleContext console) {
        String here = console.project().profile();
        if (store.has(here)) {
            return here;
        }
        String mine = store.defaultName();
        return store.has(mine) ? mine : "";
    }

    /**
     * A profile name as a MainFrame argument.
     *
     * <p>Quoted, always. MainFrame's language reads {@code -} as an operator, so a bare {@code jdk-21} parses as
     * {@code jdk} minus {@code 21} and the command is handed two arguments instead of one. Hyphens are exactly
     * what people call toolchains, so the name goes in quotes rather than the hyphen being taken away from them.
     */
    private static String quoted(String name) {
        return Values.quoted(name);
    }
}
