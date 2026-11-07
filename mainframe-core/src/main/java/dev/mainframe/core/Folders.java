package dev.mainframe.core;

import dev.mainframe.core.Messages.TabSpec;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The tabs {@code --dir}, {@code --dirs} and {@code --workspace} name, found on disk: 0.1's {@code Startup}, without
 * its {@code --insert-path} (M4.4). A test calls it; {@link ReadStartup} is the job that does.
 *
 * <p>Nothing here stops a launch. A folder that is not there, a list that cannot be read, a workspace with no file:
 * each is logged and skipped, so one stale line does not cost the other tabs, and a launch left with no tab at all gets
 * the plain one.
 */
final class Folders {

    private static final Log LOG = Log.of("mainframe.startup");

    /**
     * What a launch opens: its tabs in order, and the workspace this window is, the first {@code --workspace} named
     * (blank for none). A workspace's tabs keep its rows' names and script keys.
     */
    record Opened(List<TabSpec> tabs, String workspace) {
        Opened {
            tabs = List.copyOf(tabs);
        }
    }

    private Folders() {
    }

    /** Every tab the sources name, in order, by its folder's absolute path; relative ones from {@code here}. */
    static Opened of(List<Command.Source> sources, Path here, WorkspaceStore workspaces) {
        List<TabSpec> out = new ArrayList<>();
        String workspace = "";
        for (Command.Source s : sources) {
            if (s.kind() == Command.Kind.DIR) {
                add(out, s.value(), here, "", "");
            } else if (s.kind() == Command.Kind.DIRS) {
                readList(out, s.value(), here);
            } else {
                Optional<Workspace> w = workspaces.keyed(s.value());
                if (w.isEmpty()) {
                    LOG.warn("no workspace called {} in {}", s.value(), workspaces.dir());
                    continue;
                }
                for (Workspace.Entry e : w.get().entries()) {
                    // A row with no name is named for its folder, as 0.1 named it, so the shell's title does not replace it.
                    add(out, e.path(), here, e.title(), e.env());
                }
                if (workspace.isEmpty()) {
                    workspace = w.get().name();
                }
            }
        }
        return new Opened(out, workspace);
    }

    /**
     * One folder per line; blank lines and lines starting with # are skipped, and a relative path is taken from the
     * file's own folder, so a list can sit beside the projects it names.
     */
    private static void readList(List<TabSpec> out, String spec, Path here) {
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
                add(out, t, base, "", "");
            }
        }
    }

    /** A folder that is not there is said and skipped. Quotes round it are dropped, and a leading ~ is the home folder. */
    private static void add(List<TabSpec> out, String spec, Path base, String name, String env) {
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
        out.add(new TabSpec(dir, name == null ? "" : name, env == null ? "" : env));
    }
}
