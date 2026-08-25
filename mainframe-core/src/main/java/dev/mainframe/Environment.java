package dev.mainframe;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;

/**
 * The environment MainFrame hands to the programs it starts.
 *
 * <p>A running Java process cannot change its own environment, so MainFrame keeps
 * its own copy: seeded from the process at startup, editable between commands,
 * and handed to every external program in full. That is what makes {@code env-set}
 * and {@code path-add} take effect immediately rather than at the next restart.
 *
 * <p>On Windows, variable names are matched without regard to case -- {@code Path}
 * and {@code PATH} are the same variable -- while the spelling the system used is
 * kept for display.
 */
public final class Environment {

    /** Variables that other things depend on; removing one needs {@code --force}. */
    private static final Set<String> PROTECTED = Set.of(
            "PATH", "PATHEXT", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR",
            "TEMP", "TMP", "COMSPEC", "SHELL", "SYSTEMDRIVE");

    private final boolean caseInsensitive;
    /** Display name to value, in insertion order. */
    private final SequencedMap<String, String> values = new LinkedHashMap<>();
    /** Lookup key to the display name actually in use. */
    private final Map<String, String> keys = new LinkedHashMap<>();

    public Environment(Map<String, String> initial, boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
        initial.forEach(this::set);
    }

    /** A copy of the environment this process was started with. */
    public static Environment fromProcess() {
        return new Environment(System.getenv(), onWindows());
    }

    public static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** The character between PATH entries: {@code ;} on Windows, {@code :} elsewhere. */
    public static String separator() {
        return File.pathSeparator;
    }

    private String lookup(String name) {
        return caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
    }

    public boolean has(String name) { return keys.containsKey(lookup(name)); }

    /** The value, or null when the variable is not set. */
    public String get(String name) {
        String key = keys.get(lookup(name));
        return key == null ? null : values.get(key);
    }

    public String getOrDefault(String name, String fallback) {
        String value = get(name);
        return value == null ? fallback : value;
    }

    /** Sets a variable, keeping the spelling it already had if it existed. */
    public void set(String name, String value) {
        String existing = keys.get(lookup(name));
        String display = existing == null ? name : existing;
        keys.put(lookup(name), display);
        values.put(display, value);
    }

    /** Removes a variable and returns what it held, or null if it was not set. */
    public String remove(String name) {
        String display = keys.remove(lookup(name));
        return display == null ? null : values.remove(display);
    }

    /** Names in the order they were added, which puts inherited ones first. */
    public SequencedSet<String> names() { return new LinkedHashSet<>(values.keySet()); }

    public List<String> sortedNames() {
        List<String> sorted = new ArrayList<>(values.keySet());
        sorted.sort(String::compareToIgnoreCase);
        return sorted;
    }

    /** The whole environment, for handing to a child process. */
    public Map<String, String> all() { return Map.copyOf(values); }

    public int size() { return values.size(); }

    public static boolean isProtected(String name) {
        return PROTECTED.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * A name is only usable if a child process could receive it: no {@code =},
     * no NUL, not empty.
     */
    public static String problemWithName(String name) {
        if (name.isBlank()) return "a variable name cannot be empty";
        if (name.indexOf('=') >= 0) return "a variable name cannot contain an = sign";
        if (name.indexOf('\0') >= 0) return "a variable name cannot contain a NUL character";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) return "a variable name cannot contain spaces";
        }
        return null;
    }

    // ---- PATH ------------------------------------------------------------------------

    /** The spelling of the PATH variable in this environment, e.g. {@code Path}. */
    public String pathName() {
        String display = keys.get(lookup("PATH"));
        return display == null ? "PATH" : display;
    }

    /** The PATH, split into entries, with empty ones dropped. */
    public List<String> pathEntries() {
        String path = get("PATH");
        if (path == null || path.isBlank()) return List.of();
        List<String> entries = new ArrayList<>();
        for (String entry : path.split(java.util.regex.Pattern.quote(separator()), -1)) {
            if (!entry.isBlank()) entries.add(entry.trim());
        }
        return entries;
    }

    /** Replaces the PATH with these entries. */
    public void pathEntries(List<String> entries) {
        set(pathName(), String.join(separator(), entries));
    }

    /** True when {@code candidate} is already on the PATH, compared as a real path. */
    public boolean onPath(Path candidate) {
        for (String entry : pathEntries()) {
            if (samePath(entry, candidate)) return true;
        }
        return false;
    }

    public static boolean samePath(String entry, Path candidate) {
        try {
            Path normalized = Path.of(entry).toAbsolutePath().normalize();
            return caseInsensitivePaths()
                    ? normalized.toString().equalsIgnoreCase(candidate.toString())
                    : normalized.equals(candidate);
        } catch (RuntimeException e) {
            // An entry that is not even a valid path cannot match one that is.
            return false;
        }
    }

    private static boolean caseInsensitivePaths() { return onWindows(); }

    /**
     * Finds an executable on this PATH the way the operating system would,
     * including the Windows habit of trying PATHEXT suffixes.
     */
    public Path findProgram(String name) {
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        if (onWindows()) {
            String pathext = getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            for (String extension : pathext.split(";")) {
                if (!extension.isBlank()) candidates.add(name + extension.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String entry : pathEntries()) {
            for (String candidate : candidates) {
                try {
                    Path full = Path.of(entry).resolve(candidate);
                    if (java.nio.file.Files.isRegularFile(full)) return full;
                } catch (RuntimeException e) {
                    // Skip a malformed PATH entry rather than failing the lookup.
                }
            }
        }
        return null;
    }
}
