package dev.mainframe.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Somewhere for a program to keep things between runs, without asking anybody.
 *
 * <p>MainFrame already kept forms and indexes this way, each with its own copy of
 * the same twenty lines: a directory, a suffix, list the names, read one, write
 * one atomically, say when it was saved. This is that shape, once, so a program
 * that wants to remember the window it was last using does not have to invent a
 * file layout to do it.
 *
 * <p>The important part is the word <em>owned</em>. Every program gets its own
 * directory, named after it, and nothing outside that directory is any of its
 * business. Two programs cannot collide, one cannot quietly read another's
 * settings by guessing a filename, and removing a program is removing a folder.
 * Persisting is then something a program may simply do -- no prompt, no
 * permission, no shared namespace to be careful in -- which is the point: asking
 * a person where to put a window size teaches them nothing and trains them to say
 * yes to everything.
 *
 * <h2>Which area</h2>
 *
 * <p>The three areas differ in who the contents belong to, which is the question
 * that decides what may be thrown away.
 *
 * <ul>
 *   <li>{@link Area#SETTINGS} -- what the person chose. Deleting it loses a
 *       decision they made, so nothing should delete it but them.
 *   <li>{@link Area#STATE} -- what the program worked out for itself: where the
 *       window was, what was open, a cache. Deleting it costs a moment of
 *       recomputation and nothing else, so it is always safe to clear.
 *   <li>{@link Area#SECRETS} -- what must not be readable by other accounts.
 *       Written owner-only; see {@link SafeFs#restrictToOwner}.
 * </ul>
 *
 * <p>Anything that would be embarrassing to lose goes in settings; anything that
 * would be embarrassing to keep goes in state. When it is genuinely both, it is
 * two entries.
 */
public final class Store {

    /** Whose the contents are, which decides what may throw them away. */
    public enum Area {
        /** What the person chose. */
        SETTINGS("settings", SafeFs.Visibility.NORMAL),
        /** What the program worked out for itself. Always safe to clear. */
        STATE("state", SafeFs.Visibility.NORMAL),
        /** What no other account should be able to read. */
        SECRETS("secrets", SafeFs.Visibility.OWNER_ONLY);

        private final String folder;
        private final SafeFs.Visibility visibility;

        Area(String folder, SafeFs.Visibility visibility) {
            this.folder = folder;
            this.visibility = visibility;
        }

        public String folder() { return folder; }
    }

    private final Path directory;
    private final String suffix;
    private final SafeFs.Visibility visibility;

    private Store(Path directory, String suffix, SafeFs.Visibility visibility) {
        this.directory = directory;
        this.suffix = suffix;
        this.visibility = visibility;
    }

    /**
     * The store a program owns: {@code ~/.mainframe/<area>/<owner>}.
     *
     * @param owner the program's name, which becomes a directory name and so has
     *              to be a plain word -- see {@link #problemWithName}
     */
    public static Store owned(Area area, String owner) {
        String problem = problemWithName(owner);
        if (problem != null) {
            throw new IllegalArgumentException("\"" + owner + "\" cannot own a store: " + problem);
        }
        return new Store(SafeFs.stateDir().resolve(area.folder()).resolve(owner), "", area.visibility);
    }

    /**
     * A store at a directory of your choosing. For the places that predate areas
     * and keep their own layout, and for tests.
     */
    public static Store at(Path directory, String suffix) {
        return new Store(directory, suffix == null ? "" : suffix, SafeFs.Visibility.NORMAL);
    }

    /** As {@link #at}, kept to this account alone. */
    public static Store secret(Path directory, String suffix) {
        return new Store(directory, suffix == null ? "" : suffix, SafeFs.Visibility.OWNER_ONLY);
    }

    public Path directory() { return directory; }

    public Path file(String name) { return directory.resolve(name + suffix); }

    public boolean has(String name) { return Files.isRegularFile(file(name)); }

    /** Every name kept here, in order, so listings and hints agree with each other. */
    public List<String> names() {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                String file = child.getFileName().toString();
                if (!suffix.isEmpty() && !file.endsWith(suffix)) continue;
                // A half-finished write is nobody's entry.
                if (file.endsWith(".mf-part") || file.startsWith(".")) continue;
                names.add(suffix.isEmpty() ? file : file.substring(0, file.length() - suffix.length()));
            }
        } catch (IOException e) {
            return names;
        }
        names.sort(String::compareTo);
        return names;
    }

    /** What is kept under this name, or null when nothing is. */
    public String read(String name) throws IOException {
        Path file = file(name);
        if (!Files.isRegularFile(file)) return null;
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public void write(String name, String text) throws IOException {
        write(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public void write(String name, byte[] bytes) throws IOException {
        SafeFs.atomicWrite(file(name), bytes, visibility);
    }

    /** Forgets one entry. Quiet when there was nothing to forget. */
    public void drop(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }

    /** Forgets everything here. Meant for {@link Area#STATE}, which is always safe to clear. */
    public void clear() throws IOException {
        for (String name : names()) drop(name);
    }

    /** When it was last written, as epoch millis, or 0 when that cannot be told. */
    public long savedAt(String name) {
        try {
            return Files.getLastModifiedTime(file(name)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public long size(String name) {
        try {
            return Files.size(file(name));
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Why {@code name} cannot be used here, or null when it can.
     *
     * <p>A name becomes a file or directory name, so it has to be one plain word.
     * Saying so is better than quietly writing somewhere else -- and a name that
     * can contain a dot or a slash is a name that can be made to point at
     * somebody else's store.
     */
    public static String problemWithName(String name) {
        if (name == null || name.isBlank()) return "a name is needed";
        if (name.contains("/") || name.contains("\\") || name.contains(".")) {
            return "a saved name should be a simple word, with no dots or slashes in it";
        }
        return null;
    }
}
