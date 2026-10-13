package dev.mainframe;

import dev.mainframe.pty.ShortName;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The tabs a {@link Command} asks for, found on disk, and what {@code --insert-path} may type into the first of them.
 *
 * <p>Nothing here stops a launch. A folder that is not there, a list that cannot be read, a workspace with no file:
 * each is said and skipped, so one stale line does not cost the other tabs, and a launch that ends up with no tab at
 * all still gets the plain one.
 */
final class Startup {

    private static final Log LOG = Log.of("mainframe.startup");

    private Startup() {
    }

    /** {@code command}'s sources as tabs, in order. */
    static List<Terminals.Opening> openings(Command command, WorkspaceStore workspaces) {
        List<Terminals.Opening> out = new ArrayList<>();
        Path here = Path.of("");
        for (Command.Source s : command.sources()) {
            if (s.kind() == Command.Kind.DIR) {
                add(out, s.value(), here, null);
            } else if (s.kind() == Command.Kind.DIRS) {
                readList(out, s.value());
            } else {
                workspace(out, workspaces, s.value());
            }
        }
        return out;
    }

    /** {@code w}'s folders as tabs, each named as {@code w} names it. */
    static List<Terminals.Opening> openings(Workspace w) {
        List<Terminals.Opening> out = new ArrayList<>();
        for (Workspace.Entry e : w.entries()) {
            add(out, e.path(), Path.of(""), e.title());
        }
        return out;
    }

    /**
     * Put {@code spec}'s name on {@code tab}'s input line, or say on the tab why not. Only a file that exists, named by
     * its full path, and sits directly in the first tab's folder (which is where its shell starts) is considered at
     * all; then {@link InsertPath} decides what, if anything, may be typed for it. {@code dialect} is null when
     * MainFrame cannot tell how the shell quotes: a command line given at launch.
     */
    static void insert(TerminalView tab, String spec, List<Terminals.Opening> openings, InsertPath.Dialect dialect) {
        Path file;
        try {
            file = Path.of(spec);
        } catch (InvalidPathException e) {
            tab.notice("--insert-path is not a path, so nothing was typed.");
            return;
        }
        if (!file.isAbsolute() || !file.normalize().equals(file) || !Files.isRegularFile(file)
                || file.getFileName() == null) {
            tab.notice("--insert-path does not name a file by its full path, so nothing was typed.");
            return;
        }
        if (openings.isEmpty() || !openings.getFirst().dir().equals(file.getParent())) {
            tab.notice("--insert-path names a file outside this tab's folder, so nothing was typed.");
            return;
        }
        InsertPath.Outcome o = InsertPath.decide(dialect, file.getFileName().toString(), () -> ShortName.of(file));
        if (o.typesSomething()) {
            tab.typeWhenReady(o.text());
        }
        if (o.notice() != null) {
            tab.notice(o.notice());
        }
    }

    /**
     * One folder per line; blank lines and lines starting with # are skipped, and a relative path is taken from the
     * file's own folder, so a list can sit beside the projects it names.
     */
    private static void readList(List<Terminals.Opening> out, String spec) {
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

    private static void workspace(List<Terminals.Opening> out, WorkspaceStore store, String name) {
        Workspace w = store.load(name).orElse(null);
        if (w == null) {
            LOG.warn("no workspace called {} in {}", name, store.dir());
            return;
        }
        out.addAll(openings(w));
    }

    /** A folder that is not there is said and skipped. A leading ~ is the home folder. */
    private static void add(List<Terminals.Opening> out, String spec, Path base, String title) {
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
        if (Files.isDirectory(dir)) {
            out.add(new Terminals.Opening(dir, title));
        } else {
            LOG.warn("no such folder, skipped: {}", dir);
        }
    }
}
