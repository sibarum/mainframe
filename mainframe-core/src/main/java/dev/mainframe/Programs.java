package dev.mainframe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.SequencedSet;

/**
 * The programs the surrounding application provides in-process, searched before
 * the PATH.
 *
 * <p>Names match the way the operating system matches program names: without
 * regard to case on Windows, exactly elsewhere. The spelling the host registered
 * is the one shown.
 */
public final class Programs {

    private final boolean caseInsensitive;
    private final SequencedMap<String, HostedProgram> installed = new LinkedHashMap<>();

    public Programs() { this(Environment.onWindows()); }

    public Programs(boolean caseInsensitive) { this.caseInsensitive = caseInsensitive; }

    private String key(String name) {
        return caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
    }

    /** Installs a program, taking the place of one already under that name. */
    public void install(HostedProgram program) {
        installed.put(key(program.name()), program);
    }

    /** Uninstalls one, and says whether there was anything to uninstall. */
    public boolean remove(String name) { return installed.remove(key(name)) != null; }

    /** The program with this name, or null when the host provides no such thing. */
    public HostedProgram get(String name) { return installed.get(key(name)); }

    public boolean has(String name) { return installed.containsKey(key(name)); }

    /** The names as the host spelled them, in the order they were installed. */
    public SequencedSet<String> names() {
        SequencedSet<String> names = new LinkedHashSet<>();
        for (HostedProgram program : installed.values()) names.add(program.name());
        return names;
    }

    public List<HostedProgram> all() { return List.copyOf(installed.values()); }

    public int size() { return installed.size(); }

    public boolean isEmpty() { return installed.isEmpty(); }

    /**
     * A name is only usable if the line can carry it: a caret is followed by one
     * word, and a word cannot hold a space, a path separator or an = sign.
     */
    public static String problemWithName(String name) {
        if (name == null || name.isBlank()) return "a program needs a name";
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return "a program name cannot contain a path separator -- "
                    + "a name with a slash in it means a file on disk, not an installed program";
        }
        if (name.indexOf('=') >= 0) return "a program name cannot contain an = sign";
        if (name.indexOf('\0') >= 0) return "a program name cannot contain a NUL character";
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) return "a program name cannot contain spaces";
        }
        return null;
    }

    /**
     * True when the name can be written straight after a caret. Anything else has
     * to be quoted -- {@code ^"7z"} -- because the lexer would read it as
     * something other than one word.
     */
    public static boolean isPlainWord(String name) {
        if (name.isEmpty()) return false;
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') return false;
        }
        return true;
    }
}
