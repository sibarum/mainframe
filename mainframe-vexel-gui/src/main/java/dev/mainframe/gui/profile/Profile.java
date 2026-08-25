package dev.mainframe.gui.profile;

import dev.mainframe.Environment;
import dev.mainframe.form.Form;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;

/**
 * A named set of environment variables and directories to find binaries in — a toolchain, said once and applied
 * whole.
 *
 * <h2>Why it is a form, and why the form is data</h2>
 * A profile is exactly the shape MainFrame's {@link Form} was built for: a couple of scalars and two lists of
 * repeated details. So the editor for one is not a screen anybody had to draw. {@link #definition()} states the
 * fields, and the same statement drives both halves of the job — {@code FormScreen} asks a person for the answers,
 * and {@code Field.problem} judges a record that never went near a keyboard. A variable name that a child process
 * could not receive is refused in both places by the same code, which is the only way the two cannot drift.
 *
 * <h2>Merge, do not replace</h2>
 * {@link #applyTo} sets this profile's variables over whatever the environment already holds and puts its
 * directories at the <em>front</em> of the PATH. Replacing the environment outright would be the more literal
 * reading of "a set of environment variables", and it would break the shell: MainFrame seeds itself from the
 * process, so a replacement would take away PATHEXT, SYSTEMROOT and COMSPEC and leave a session that cannot start
 * a program at all. A profile says what is different about a toolchain, not what an operating system is.
 *
 * @param name  how the profile is listed and referred to
 * @param env   variables to set, in the order they were entered
 * @param paths directories to put on the front of the PATH, first one winning
 */
public record Profile(String name, SequencedMap<String, String> env, List<String> paths) {

    public Profile {
        env = new LinkedHashMap<>(env);
        paths = List.copyOf(paths);
    }

    /** An empty profile under {@code name} — what "new profile" starts from. */
    public static Profile empty(String name) {
        return new Profile(name, new LinkedHashMap<>(), List.of());
    }

    /** How the profile reads in a listing: what it carries, without making anyone open it. */
    public String summary() {
        return env.size() + (env.size() == 1 ? " var" : " vars")
                + ", " + paths.size() + (paths.size() == 1 ? " path" : " paths");
    }

    // ---- the form ----------------------------------------------------------------------

    /**
     * The fields a profile is filled in with.
     *
     * <p>Both lists are {@code TABLE} fields with a sub-form, which is what makes them repeating groups rather
     * than one answer: the screen asks for entry after entry until told to stop, and {@code !clear} at a group
     * empties the whole list. Neither is required, because a profile that only sets a PATH and a profile that only
     * sets variables are both sensible things to want.
     */
    public static Form definition() {
        Form variable = new Form(List.of(
                field("name", "Variable", ValueType.STRING, true,
                        "the variable name, e.g. JAVA_HOME -- no spaces and no = sign"),
                field("value", "Value", ValueType.STRING, false,
                        "what it is set to; leave blank to set it empty")));
        Form directory = new Form(List.of(
                field("path", "Directory", ValueType.PATH, true,
                        "a directory holding binaries, put on the front of the PATH")));
        return new Form(List.of(
                field("name", "Profile name", ValueType.STRING, true,
                        "how this profile is listed and referred to"),
                group("env", "Environment variables", variable,
                        "variables this profile sets, over whatever the shell already has"),
                group("paths", "Binary directories", directory,
                        "directories to search for programs, ahead of the inherited PATH")));
    }

    private static Form.Field field(String name, String label, ValueType type, boolean required, String help) {
        return new Form.Field(name, label, type, required, null, null, null, null, null, help, null, null);
    }

    private static Form.Field group(String name, String label, Form entries, String help) {
        return new Form.Field(name, label, ValueType.TABLE, false, null, null, null, null, null, help, null,
                entries);
    }

    /**
     * This profile as the record the form starts from, so editing an existing one opens with what it holds rather
     * than blank. The shape is the form's own, field for field.
     */
    public Value.Rec toRecord() {
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        fields.put("name", new Value.Str(name));
        List<Value> variables = new ArrayList<>();
        env.forEach((key, value) -> {
            SequencedMap<String, Value> row = new LinkedHashMap<>();
            row.put("name", new Value.Str(key));
            row.put("value", new Value.Str(value));
            variables.add(new Value.Rec(row));
        });
        fields.put("env", new Value.ListVal(variables));
        List<Value> directories = new ArrayList<>();
        for (String path : paths) {
            SequencedMap<String, Value> row = new LinkedHashMap<>();
            row.put("path", new Value.Str(path));
            directories.add(new Value.Rec(row));
        }
        fields.put("paths", new Value.ListVal(directories));
        return new Value.Rec(fields);
    }

    /**
     * Read a profile back out of the answers.
     *
     * <p>Forgiving on the way in, because the record may not have come from the screen at all: a group the person
     * skipped arrives as nothing rather than as an empty table, and a row missing its value column is a variable
     * set to empty. What is <em>not</em> forgiven is a variable name a child process could not receive — that is
     * checked here as well as by the form, because this is also the door a piped-in record comes through.
     */
    public static Profile fromRecord(Value.Rec answers, String fallbackName) {
        String name = text(answers.get("name"), fallbackName).trim();
        SequencedMap<String, String> env = new LinkedHashMap<>();
        for (Value.Rec row : rows(answers.get("env"))) {
            String key = text(row.get("name"), "").trim();
            if (key.isEmpty()) {
                continue;
            }
            String problem = Environment.problemWithName(key);
            if (problem != null) {
                throw new IllegalArgumentException(problem + " (" + key + ")");
            }
            env.put(key, text(row.get("value"), ""));
        }
        List<String> paths = new ArrayList<>();
        for (Value.Rec row : rows(answers.get("paths"))) {
            String path = text(row.get("path"), "").trim();
            if (!path.isEmpty() && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return new Profile(name, env, paths);
    }

    private static List<Value.Rec> rows(Value value) {
        if (value == null || value instanceof Value.Nothing) {
            return List.of();
        }
        if (value instanceof Value.Rec single) {
            return List.of(single);
        }
        return Values.isTable(value) ? Values.rows(value) : List.of();
    }

    private static String text(Value value, String fallback) {
        return value == null || value instanceof Value.Nothing ? fallback : Values.display(value);
    }

    // ---- applying ----------------------------------------------------------------------

    /**
     * Set this profile's variables and put its directories on the front of the PATH.
     *
     * <p>A directory already on the PATH is not added twice — compared as a real path, so {@code ./bin} and the
     * absolute form of the same place count as one. Order is preserved: the first directory listed ends up first,
     * which is what makes a profile able to shadow a system-wide toolchain rather than sit behind it.
     *
     * @return what happened, one line per thing, for the shell to print
     */
    public List<String> applyTo(Environment environment) {
        List<String> report = new ArrayList<>();
        env.forEach((key, value) -> {
            environment.set(key, value);
            report.add("set " + key);
        });
        List<String> entries = new ArrayList<>(environment.pathEntries());
        int at = 0;
        for (String raw : paths) {
            Path directory = Path.of(raw).toAbsolutePath().normalize();
            if (environment.onPath(directory) || entries.contains(directory.toString())) {
                report.add("already on the path: " + directory);
                continue;
            }
            entries.add(at++, directory.toString());
            report.add(Files.isDirectory(directory)
                    ? "path + " + directory
                    : "path + " + directory + "  (no such directory yet)");
        }
        environment.pathEntries(entries);
        return report;
    }

    /** A name usable as a settings key and readable in a listing, or a problem with it. */
    public static String problemWithName(String name) {
        if (name == null || name.isBlank()) {
            return "a profile needs a name";
        }
        String trimmed = name.trim();
        if (!trimmed.equals(trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", ""))) {
            return "a profile name takes lower-case letters, digits, dot, dash and underscore";
        }
        return null;
    }
}
