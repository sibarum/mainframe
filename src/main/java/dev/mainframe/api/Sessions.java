package dev.mainframe.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.Environment;
import dev.mainframe.Session;

/**
 * The one place the environment, the PATH and the directory are edited on a
 * host's behalf.
 *
 * <p>{@link MainFrame}, a hosted command and a hosted program all offer the same
 * four or five operations, and they had better mean the same thing in all three:
 * the same normalising of paths, the same refusal of a name a child process could
 * not receive, the same "it was already there" answer.
 */
final class Sessions {

    private Sessions() {}

    static void env(Session session, String name, String value) {
        String problem = Environment.problemWithName(name);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (value == null) throw new IllegalArgumentException(name + " cannot be set to null -- "
                + "to unset it, remove it");
        session.env().set(name, value);
    }

    static boolean envRemove(Session session, String name) {
        return session.env().remove(name) != null;
    }

    static List<Path> path(Session session) {
        List<Path> entries = new ArrayList<>();
        for (String entry : session.env().pathEntries()) {
            try {
                entries.add(Path.of(entry));
            } catch (RuntimeException e) {
                // An entry that is not a valid path is still on the PATH; it just
                // cannot be handed back as one. path shows it, warts and all.
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Adds a directory to the PATH, and says whether it had to.
     *
     * <p>Unlike the {@code path-add} command, this does not insist the directory
     * exists: a program that is about to create it, or that knows better than the
     * filesystem does, is not a person who has mistyped something.
     */
    static boolean pathAdd(Session session, Path directory, boolean first) {
        Path resolved = absolute(session, directory);
        Environment environment = session.env();
        if (environment.onPath(resolved)) return false;
        List<String> entries = new ArrayList<>(environment.pathEntries());
        if (first) entries.addFirst(resolved.toString());
        else entries.addLast(resolved.toString());
        environment.pathEntries(entries);
        return true;
    }

    static boolean pathRemove(Session session, Path directory) {
        Path resolved = absolute(session, directory);
        Environment environment = session.env();
        List<String> entries = environment.pathEntries();
        List<String> kept = new ArrayList<>(entries.size());
        for (String entry : entries) {
            if (!Environment.samePath(entry, resolved)) kept.add(entry);
        }
        if (kept.size() == entries.size()) return false;
        environment.pathEntries(kept);
        return true;
    }

    static void directory(Session session, Path directory) {
        if (directory == null) throw new IllegalArgumentException("a directory is needed");
        Path resolved = absolute(session, directory);
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException(resolved + " is not a directory");
        }
        session.cd(resolved);
    }

    /** Relative paths mean what they would mean to the user: relative to the shell. */
    private static Path absolute(Session session, Path directory) {
        if (directory == null) throw new IllegalArgumentException("a directory is needed");
        Path resolved = directory.isAbsolute() ? directory : session.cwd().resolve(directory);
        return resolved.toAbsolutePath().normalize();
    }
}
