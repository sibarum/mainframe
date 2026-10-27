package dev.mainframe.core;

import dev.sibarum.suite.tty.PtyPlatform;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The tabs a {@link Command.Source} names, found on disk, and what {@code --insert-path} may type into the first of
 * them: the work of {@link ResolveTabs} and {@link CheckInsert}, kept apart from them so a test can call it.
 *
 * <p>Nothing here stops a launch. A folder that is not there, a list that cannot be read, a workspace with no file:
 * each is said and skipped, so one stale line does not cost the other tabs, and a launch that ends up with no tab at
 * all still gets the plain one.
 *
 * <p>What a source names is answered as a {@link Workspace}, whatever kind it is: a list of folders, each a tab, is
 * what a workspace is. Its name is the workspace's for a {@code --workspace}, whose tabs source its scripts, and blank
 * for folders, whose tabs source none.
 */
final class Startup {

    private static final Log LOG = Log.of("mainframe.startup");

    private Startup() {
    }

    /**
     * The tabs {@code source} names, in order: each row a folder that is there, by its absolute path, with the name
     * its tab takes (blank for the folder's own) and, for a workspace's, the key of its script.
     */
    static Workspace tabs(Command.Source source, WorkspaceStore workspaces) {
        List<Workspace.Entry> out = new ArrayList<>();
        Path here = Path.of("");
        switch (source.kind()) {
            case DIR -> add(out, source.value(), here, null);
            case DIRS -> readList(out, source.value());
            case WORKSPACE -> {
                Workspace w = workspaces.keyed(source.value()).orElse(null);
                if (w == null) {
                    LOG.warn("no workspace called {} in {}", source.value(), workspaces.dir());
                } else {
                    for (Workspace.Entry e : w.entries()) {
                        add(out, e.path(), here, e);
                    }
                    return new Workspace(w.name(), out);
                }
            }
        }
        return new Workspace("", out);
    }

    /**
     * What may be typed for {@code spec} on the input line of the first tab, whose shell starts in {@code dir} (null
     * when the launch opened none of its own), or why nothing is. Only a file that exists, named by its full path,
     * and sits directly in that folder is considered at all; then {@link InsertPath} decides what, if anything, may
     * be typed for it. {@code dialect} is null when MainFrame cannot tell how the shell quotes: a command line given
     * at launch.
     */
    static InsertPath.Outcome insertion(String spec, Path dir, InsertPath.Dialect dialect) {
        Path file;
        try {
            file = Path.of(spec);
        } catch (InvalidPathException e) {
            return InsertPath.Outcome.refused("--insert-path is not a path, so nothing was typed.");
        }
        if (!file.isAbsolute() || !file.normalize().equals(file) || !Files.isRegularFile(file)
                || file.getFileName() == null) {
            return InsertPath.Outcome.refused("--insert-path does not name a file by its full path, so nothing was typed.");
        }
        if (dir == null || !dir.equals(file.getParent())) {
            return InsertPath.Outcome.refused("--insert-path names a file outside this tab's folder, so nothing was typed.");
        }
        return InsertPath.decide(dialect, file.getFileName().toString(), () -> PtyPlatform.current().shortName(file));
    }

    /**
     * One folder per line; blank lines and lines starting with # are skipped, and a relative path is taken from the
     * file's own folder, so a list can sit beside the projects it names.
     */
    private static void readList(List<Workspace.Entry> out, String spec) {
        Path file;
        List<String> lines;
        try {
            file = Path.of(spec);
            lines = Files.readAllLines(file);
        } catch (IOException | InvalidPathException e) {
            LOG.warn("cannot read the folder list {}", spec, e);
            return;
        }
        Path base = file.toAbsolutePath().getParent();
        for (String line : lines) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                add(out, t, base, null);
            }
        }
    }

    /**
     * A folder that is not there is said and skipped. A leading ~ is the home folder. A workspace's {@code row} keeps
     * its name, as its title, and its key.
     */
    private static void add(List<Workspace.Entry> out, String spec, Path base, Workspace.Entry row) {
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
        Workspace.Entry tab = new Workspace.Entry(row == null ? "" : row.title(), dir.toString());
        out.add(row == null ? tab : tab.withEnv(row.env()));
    }
}
