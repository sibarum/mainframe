package dev.mainframe.gui.profile;

import dev.vexelray.gui.core.app.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Every profile the user has, and which one they prefer by default — kept with the rest of their settings, because
 * a toolchain is a fact about the machine and the person, not about a project.
 *
 * <h2>Global on purpose</h2>
 * A profile holds absolute directories: where a JDK is unpacked, where cargo put its binaries. Those are true of
 * this machine and of no other, so they live in the user's own settings and never in a project.
 * A project says only which profile it <em>wants</em>, by name ({@code ProjectScope}), which is a statement
 * that survives being committed and cloned. A checkout naming a profile this machine has never heard of is told
 * so and carries on with the default.
 *
 * <h2>The shape in the file</h2>
 * <pre>
 * profiles              = rust-nightly, jdk-21          (the order they are listed in)
 * profile.default       = jdk-21                        (the user's own default, if any)
 * profile.jdk-21.vars   = JAVA_HOME, MAVEN_OPTS         (names, in entry order)
 * profile.jdk-21.var.JAVA_HOME = /opt/jdk-21
 * profile.jdk-21.paths  = /opt/jdk-21/bin
 * </pre>
 * Two lists and a value per variable rather than one packed blob, so a person can read the file, and so a
 * variable holding the list separator cannot corrupt its neighbours. Names are kept in their own list because
 * properties have no order and the order a profile was entered in is the order its PATH entries apply in.
 */
public final class ProfileStore {

    private static final String NAMES = "profiles";
    private static final String DEFAULT = "profile.default";

    private final Settings settings;

    public ProfileStore(Settings settings) {
        this.settings = settings;
    }

    /** Every profile, in the order they were created. */
    public List<Profile> all() {
        List<Profile> profiles = new ArrayList<>();
        for (String name : names()) {
            read(name).ifPresent(profiles::add);
        }
        return profiles;
    }

    /** The names, in order. */
    public List<String> names() {
        return settings.getList(NAMES);
    }

    public boolean has(String name) {
        return name != null && !name.isBlank() && names().contains(name.trim());
    }

    /** The profile under {@code name}, or empty if there is none. */
    public Optional<Profile> read(String name) {
        if (!has(name)) {
            return Optional.empty();
        }
        String key = name.trim();
        SequencedMap<String, String> env = new LinkedHashMap<>();
        for (String variable : settings.getList(prefix(key) + ".vars")) {
            env.put(variable, settings.getString(prefix(key) + ".var." + variable, ""));
        }
        return Optional.of(new Profile(key, env, settings.getList(prefix(key) + ".paths")));
    }

    /**
     * Write {@code profile}, adding it to the order if it is new and replacing it in place if it is not.
     *
     * <p>Its previous variables are dropped first: a save is the whole profile, so a variable removed in the form
     * has to disappear from the file rather than linger because nothing overwrote it.
     */
    public void write(Profile profile) {
        String key = profile.name();
        forget(key, false);
        List<String> names = new ArrayList<>(names());
        if (!names.contains(key)) {
            names.add(key);
        }
        settings.putList(NAMES, names);
        settings.putList(prefix(key) + ".vars", new ArrayList<>(profile.env().keySet()));
        profile.env().forEach((variable, value) ->
                settings.putString(prefix(key) + ".var." + variable, value));
        settings.putList(prefix(key) + ".paths", profile.paths());
        settings.save();
    }

    /** Rename in place, keeping the profile's position in the order. */
    public void rename(String from, String to) {
        Optional<Profile> existing = read(from);
        if (existing.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(names());
        int at = names.indexOf(from.trim());
        forget(from, true);
        names.remove(from.trim());
        names.add(at < 0 ? names.size() : at, to.trim());
        Profile moved = new Profile(to.trim(), existing.get().env(), existing.get().paths());
        settings.putList(NAMES, names);
        settings.putList(prefix(to.trim()) + ".vars", new ArrayList<>(moved.env().keySet()));
        moved.env().forEach((variable, value) ->
                settings.putString(prefix(to.trim()) + ".var." + variable, value));
        settings.putList(prefix(to.trim()) + ".paths", moved.paths());
        if (from.trim().equals(defaultName())) {
            settings.putString(DEFAULT, to.trim());
        }
        settings.save();
    }

    /** Drop a profile, and the default preference if it was the one preferred. */
    public void drop(String name) {
        forget(name, true);
        if (name != null && name.trim().equals(defaultName())) {
            settings.remove(DEFAULT);
        }
        settings.save();
    }

    /** The user's own default profile name, or {@code ""} if they have not set one. */
    public String defaultName() {
        return settings.getString(DEFAULT, "");
    }

    /** Set (or, with a blank name, clear) the user's own default. */
    public void defaultName(String name) {
        if (name == null || name.isBlank()) {
            settings.remove(DEFAULT);
        } else {
            settings.putString(DEFAULT, name.trim());
        }
        settings.save();
    }

    /** Remove a profile's keys. {@code alsoName} additionally takes it out of the order. */
    private void forget(String name, boolean alsoName) {
        String key = name.trim();
        for (String variable : settings.getList(prefix(key) + ".vars")) {
            settings.remove(prefix(key) + ".var." + variable);
        }
        settings.remove(prefix(key) + ".vars");
        settings.remove(prefix(key) + ".paths");
        if (alsoName) {
            List<String> names = new ArrayList<>(names());
            names.remove(key);
            settings.putList(NAMES, names);
        }
    }

    private static String prefix(String name) {
        return "profile." + name;
    }
}
