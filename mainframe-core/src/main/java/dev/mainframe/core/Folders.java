package dev.mainframe.core;

import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The folders {@code --dir} and {@code --dirs} name, found on disk: 0.1's {@code Startup}, without its workspaces
 * (M4) and its {@code --insert-path} (M4.4). A test calls it; {@link ReadStartup} is the job that does.
 *
 * <p>Nothing here stops a launch. A folder that is not there, or a list that cannot be read, is logged and skipped, so
 * one stale line does not cost the other tabs, and a launch left with no folder at all gets the plain tab.
 */
final class Folders {

    private static final Log LOG = Log.of("mainframe.startup");

    private Folders() {
    }

    /**
     * Every folder the sources name, in order, by its absolute path; relative ones from {@code here}. A
     * {@code --workspace} names none yet: workspaces come back in M4, and until then one is logged and skipped.
     */
    static List<Path> of(List<Command.Source> sources, Path here) {
        List<Path> out = new ArrayList<>();
        for (Command.Source s : sources) {
            if (s.kind() == Command.Kind.DIR) {
                add(out, s.value(), here);
            } else if (s.kind() == Command.Kind.DIRS) {
                readList(out, s.value(), here);
            } else {
                LOG.warn("--workspace={} skipped: workspaces come back in M4", s.value());
            }
        }
        return out;
    }

    /**
     * One folder per line; blank lines and lines starting with # are skipped, and a relative path is taken from the
     * file's own folder, so a list can sit beside the projects it names.
     */
    private static void readList(List<Path> out, String spec, Path here) {
        Path file;
        List<String> lines;
        try {
            file = here.resolve(spec);
            lines = Files.readAllLines(file);
        } catch (IOException | InvalidPathException e) {
            LOG.warn("cannot read the folder list {}", spec, e);
            return;
        }
        Path base = file.toAbsolutePath().getParent();
        for (String line : lines) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                add(out, t, base);
            }
        }
    }

    /** A folder that is not there is said and skipped. Quotes round it are dropped, and a leading ~ is the home folder. */
    private static void add(List<Path> out, String spec, Path base) {
        String s = spec;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        Path dir;
        try {
            dir = base.resolve(s).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOG.warn("not a path, skipped: {}", spec);
            return;
        }
        if (!Files.isDirectory(dir)) {
            LOG.warn("no such folder, skipped: {}", dir);
            return;
        }
        out.add(dir);
    }
}
