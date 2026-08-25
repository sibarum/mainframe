package dev.mainframe.gui.app;

import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The settings that belong to a <b>project</b> rather than to the user: a small file in the project's own
 * directory, holding the decisions that are about this tree of files and would be wrong anywhere else.
 *
 * <h2>What a project is, and why the console cannot decide</h2>
 * A project is whatever the application says it is. In an editor it is the folder the file tree is showing; in
 * something else it might be the directory a document was opened from, or there might be no such idea at all.
 * That is a decision the host owns, so the console is handed one of these rather than working one out — through
 * a {@code Supplier}, because it changes under a running shell and a console told the answer at startup would go
 * on writing the old project's file.
 *
 * <p>A project is <em>not</em> the shell's working directory. A working directory is a place you visit.
 *
 * <p>With no project open, {@link #none()} stands in: every read answers with the caller's default and every
 * write is dropped. A null object rather than a null, because every caller would otherwise have to ask first,
 * and the one that forgot would write a settings file into whatever directory the JVM was launched from.
 *
 * <h2>Why the same boring format</h2>
 * {@link Settings#at} over Java properties, which is what the user's own settings already are. Line-diffable,
 * hand-editable, and forgiving of keys it does not know — so a newer build can put something in here that an
 * older one ignores instead of failing. It is atomic on write, so a crash leaves the previous file rather than
 * half of this one.
 *
 * <p><b>It is meant to be committed.</b> So nothing machine-specific goes in it: this file records that the
 * project prefers the profile <em>named</em> {@code rust-nightly}, and never what that profile contains. The
 * contents are the user's, in the user's own settings, because a directory of toolchains on one machine is not a
 * directory of toolchains on another. A checkout that names a profile nobody has is told so, once, and carries
 * on.
 */
public final class ProjectScope {

    /** The profile this project prefers, by name. */
    private static final String PROFILE = "profile";

    private final Path root;
    private final String fileName;
    private final Settings store;

    private ProjectScope(Path root, String fileName, Settings store) {
        this.root = root;
        this.fileName = fileName;
        this.store = store;
    }

    /**
     * The project rooted at {@code folder}, reading {@code folder/fileName}.
     *
     * @param fileName what the host calls its project file — dotted, conventionally, so it sorts out of the way
     *                 of the project's own files
     */
    public static ProjectScope at(Path folder, String fileName) {
        Path root = folder.toAbsolutePath().normalize();
        return new ProjectScope(root, fileName, Settings.at(root.resolve(fileName)));
    }

    /** No project open: reads answer with the default, writes go nowhere. */
    public static ProjectScope none() {
        return new ProjectScope(null, "", null);
    }

    /** Whether there is a project here at all. */
    public boolean present() {
        return root != null;
    }

    /** The project's directory, or {@code null} when there is no project. */
    public Path root() {
        return root;
    }

    /** The project's name, as it is worth showing: the directory's own name. */
    public String name() {
        if (root == null) {
            return "";
        }
        Path leaf = root.getFileName();
        return leaf == null ? root.toString() : leaf.toString();
    }

    /** Where the file is, or would be. Useful in a message about what was just written. */
    public Path file() {
        return root == null ? null : root.resolve(fileName);
    }

    /** Whether the file exists yet. It is only written when something is actually set. */
    public boolean written() {
        return root != null && Files.isRegularFile(file());
    }

    /** The profile this project prefers, or {@code ""} if it does not name one. */
    public String profile() {
        return store == null ? "" : store.getString(PROFILE, "");
    }

    /**
     * Name the profile this project prefers, and write the file. An empty name clears the preference and leaves
     * the file behind — a project file that exists with nothing in it says "this project has been configured and
     * wants the default", which is a different statement from a file that was never written.
     */
    public void profile(String name) {
        if (store == null) {
            return;
        }
        if (name == null || name.isBlank()) {
            store.remove(PROFILE);
        } else {
            store.putString(PROFILE, name.trim());
        }
        store.save();
    }
}
