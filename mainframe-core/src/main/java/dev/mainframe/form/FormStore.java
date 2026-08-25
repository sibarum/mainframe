package dev.mainframe.form;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.fs.SafeFs;

/**
 * Form data saved by name, one file each under {@code ~/.mainframe/forms}.
 *
 * <p>What is in a file is the record's own written form and nothing else -- no
 * wrapper, no format of its own -- so a saved answer sheet is a plain {@code .mf}
 * file that {@code open} reads like any other. The point of the store is the name:
 * {@code prefs} instead of a path somebody has to keep track of, and a slot that
 * is allowed not to exist yet.
 *
 * <p>Nothing here knows what a {@link Form} is. It keeps text under a name; the
 * commands turn that text into a value and back, using the same reader and writer
 * every other file in MainFrame goes through.
 */
public final class FormStore {

    /** MainFrame's own written form, which is what these files hold. */
    private static final String SUFFIX = ".mf";

    private final Path directory;

    public FormStore(Path directory) { this.directory = directory; }

    public static FormStore inState() { return new FormStore(SafeFs.stateDir().resolve("forms")); }

    public Path directory() { return directory; }

    public Path file(String name) { return directory.resolve(name + SUFFIX); }

    public boolean exists(String name) { return Files.isRegularFile(file(name)); }

    /** Every saved name, in order, so listings and "you have:" hints agree. */
    public List<String> names() {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                String file = child.getFileName().toString();
                if (file.endsWith(SUFFIX)) names.add(file.substring(0, file.length() - SUFFIX.length()));
            }
        } catch (IOException e) {
            return names;
        }
        names.sort(String::compareTo);
        return names;
    }

    public String read(String name) throws IOException {
        return Files.readString(file(name), StandardCharsets.UTF_8);
    }

    public void write(String name, String source) throws IOException {
        SafeFs.atomicWrite(file(name), (source + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public void drop(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }

    /** When it was last saved, as epoch millis, or 0 when that cannot be told. */
    public long savedAt(String name) {
        try {
            return Files.getLastModifiedTime(file(name)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public long fileSize(String name) {
        try {
            return Files.size(file(name));
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Why {@code name} cannot be a saved name, or null when it can.
     *
     * <p>A name becomes a file name, so it has to be one word. Saying so is
     * better than quietly writing somewhere else.
     */
    public static String problemWithName(String name) {
        if (name == null || name.isBlank()) return "a name is needed";
        if (name.contains("/") || name.contains("\\") || name.contains(".")) {
            return "a saved name should be a simple word, with no dots or slashes in it";
        }
        return null;
    }
}
