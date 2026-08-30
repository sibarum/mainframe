package dev.mainframe.form;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.mainframe.fs.SafeFs;
import dev.mainframe.fs.Store;

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
 *
 * <p>The keeping itself is {@link Store}'s, which is where that shape lives now.
 * This stays because the location and the suffix are its own, and because saved
 * forms predate areas -- they sit at {@code ~/.mainframe/forms} rather than under
 * a program's name, and moving them would be moving somebody's files.
 */
public final class FormStore {

    /** MainFrame's own written form, which is what these files hold. */
    private static final String SUFFIX = ".mf";

    private final Store store;

    public FormStore(Path directory) { this.store = Store.at(directory, SUFFIX); }

    public static FormStore inState() { return new FormStore(SafeFs.stateDir().resolve("forms")); }

    public Path directory() { return store.directory(); }

    public Path file(String name) { return store.file(name); }

    public boolean exists(String name) { return store.has(name); }

    /** Every saved name, in order, so listings and "you have:" hints agree. */
    public List<String> names() { return store.names(); }

    /** @throws IOException when there is nothing saved under that name. */
    public String read(String name) throws IOException {
        return Files.readString(file(name), StandardCharsets.UTF_8);
    }

    public void write(String name, String source) throws IOException {
        store.write(name, source + System.lineSeparator());
    }

    public void drop(String name) throws IOException { store.drop(name); }

    /** When it was last saved, as epoch millis, or 0 when that cannot be told. */
    public long savedAt(String name) { return store.savedAt(name); }

    public long fileSize(String name) { return store.size(name); }

    /**
     * Why {@code name} cannot be a saved name, or null when it can.
     *
     * <p>A name becomes a file name, so it has to be one word. Saying so is
     * better than quietly writing somewhere else.
     */
    public static String problemWithName(String name) { return Store.problemWithName(name); }
}
