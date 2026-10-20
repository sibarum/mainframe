package dev.mainframe;

import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The saved workspaces: one CSV file each (see {@link Workspace}) in the {@code workspaces} folder of MainFrame's
 * settings directory. The folder is created by the first save, not by looking.
 *
 * <h2>The environment scripts beside them</h2>
 *
 * <p>A workspace's scripts ({@link EnvScripts}) are named after it, beside its file: {@code <name>.env.ps1}, which
 * every tab sources, and the folder {@code <name>.env}, which holds one script per tab, named by the tab's {@code env}
 * column. Renaming a workspace moves all three. Deleting one moves its scripts into {@code .deleted}, where undoing
 * the delete finds them; what is there is cleared a month later ({@link #pruneDeleted}), since an undo lasts only as
 * long as the window that can make it. A folder removed from a workspace keeps its script where it is, for the same
 * undo, and nothing deletes it.
 */
final class WorkspaceStore {

    private static final Log LOG = Log.of("mainframe.workspaces");
    private static final String RESERVED = "<>:\"/\\|?*";
    /** Where a deleted workspace's scripts wait for an undo. */
    static final String DELETED = ".deleted";
    /** How long they wait. */
    static final Duration KEEP_DELETED = Duration.ofDays(30);

    private final Path dir;

    WorkspaceStore(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    Path file(String name) {
        return dir.resolve(name + ".csv");
    }

    /** The script every tab of {@code name} sources: there or not. */
    Path envFile(String name) {
        return dir.resolve(name + ".env.ps1");
    }

    /** The folder of {@code name}'s per-tab scripts: there or not. */
    Path envDir(String name) {
        return dir.resolve(name + ".env");
    }

    /**
     * The script of the tab of workspace {@code name} whose {@code env} column is {@code key}, or empty when the key
     * is blank or is not a plain script name in that folder: a hand-edited file cannot point a tab outside it.
     */
    Optional<Path> tabEnvFile(String name, String key) {
        return validKey(key) ? Optional.of(envDir(name).resolve(key)) : Optional.empty();
    }

    /** The workspaces there are, by name, in alphabetical order ignoring case. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) return names;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                names.add(n.substring(0, n.length() - 4));
            }
        } catch (IOException e) {
            LOG.warn("cannot list workspaces in {}", dir, e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    /** The workspace called {@code name}, or empty, and said, if there is none or it cannot be read. */
    Optional<Workspace> load(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(Workspace.read(f));
        } catch (IOException e) {
            LOG.warn("cannot read workspace {}", f, e);
            return Optional.empty();
        }
    }

    /**
     * The workspace called {@code name}, with an {@code env} key given to every row that has none, and saved if any
     * was given. Done when a workspace is opened or shown in the editor rather than when a script is first edited:
     * the editor's undo keeps rows as they were, and a key added under it would be undone with whatever came before.
     * A file that cannot be written opens as it is, keys and all, and is said.
     */
    Optional<Workspace> keyed(String name) {
        Optional<Workspace> loaded = load(name);
        if (loaded.isEmpty()) return loaded;
        Workspace w = withKeys(loaded.get());
        if (!w.equals(loaded.get())) {
            try {
                save(w);
            } catch (IOException e) {
                LOG.warn("cannot save the env keys of workspace {}", name, e);
            }
        }
        return Optional.of(w);
    }

    /** {@code w} with a fresh key for each row whose key is blank or unusable; the others as they are. */
    Workspace withKeys(Workspace w) {
        Set<String> taken = new HashSet<>();
        for (Workspace.Entry e : w.entries()) {
            if (validKey(e.env())) taken.add(e.env().toLowerCase(Locale.ROOT));
        }
        List<Workspace.Entry> out = new ArrayList<>();
        boolean changed = false;
        for (Workspace.Entry e : w.entries()) {
            if (validKey(e.env())) {
                out.add(e);
            } else {
                out.add(e.withEnv(freshKey(w.name(), e.title(), taken)));
                changed = true;
            }
        }
        return changed ? w.withEntries(out) : w;
    }

    /**
     * A key for a new tab of workspace {@code name} called {@code title}: the title in letters a file name always
     * takes, and a random tail, so the folder reads as its tabs and two tabs of one name are still two scripts.
     */
    String freshKey(String name, String title, Set<String> taken) {
        StringBuilder stem = new StringBuilder();
        for (char c : title.toLowerCase(Locale.ROOT).toCharArray()) {
            if (stem.length() >= 24) break;
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                stem.append(c);
            } else if (!stem.isEmpty() && stem.charAt(stem.length() - 1) != '-') {
                stem.append('-');
            }
        }
        while (!stem.isEmpty() && stem.charAt(stem.length() - 1) == '-') stem.setLength(stem.length() - 1);
        if (stem.isEmpty()) stem.append("tab");
        while (true) {
            String key = stem + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24)) + ".ps1";
            if (taken.add(key) && !Files.exists(envDir(name).resolve(key))) return key;
        }
    }

    /** A plain file name ending in {@code .ps1}: no folder in it, no way out of the folder, nothing hidden. */
    static boolean validKey(String key) {
        if (key == null || key.isBlank() || key.startsWith(".") || key.length() > 100) return false;
        if (!key.toLowerCase(Locale.ROOT).endsWith(".ps1")) return false;
        for (char c : key.toCharArray()) {
            if (c < 32 || RESERVED.indexOf(c) >= 0) return false;
        }
        return !key.endsWith(" ");
    }

    void save(Workspace workspace) throws IOException {
        Workspace.write(file(workspace.name()), workspace);
    }

    /**
     * Deletes the workspace's file, and moves its scripts aside into {@link #DELETED}, where {@link #restore} finds
     * them. Returns where they went, or null when it had none.
     */
    Path delete(String name) throws IOException {
        Path stash = stash(name);
        Files.deleteIfExists(file(name));
        return stash;
    }

    /** Puts back as workspace {@code name}'s the scripts {@link #delete} moved to {@code stash}. Null is none. */
    void restore(Path stash, String name) throws IOException {
        if (stash == null || !Files.isDirectory(stash)) return;
        Path f = stash.resolve(envFile(name).getFileName());
        Path d = stash.resolve(envDir(name).getFileName());
        if (Files.exists(envFile(name)) || Files.exists(envDir(name))) {
            throw new IOException("workspace " + name + " has scripts again; the old ones are still in " + stash);
        }
        if (Files.exists(f)) Files.move(f, envFile(name));
        if (Files.exists(d)) Files.move(d, envDir(name));
        Files.deleteIfExists(stash);
    }

    /** Moves the workspace's scripts into a folder of their own under {@link #DELETED}; null when it has none. */
    private Path stash(String name) throws IOException {
        Path f = envFile(name);
        Path d = envDir(name);
        if (!Files.exists(f) && !Files.exists(d)) return null;
        Path stash = dir.resolve(DELETED).resolve(name + " " + System.currentTimeMillis());
        Files.createDirectories(stash);
        if (Files.exists(f)) Files.move(f, stash.resolve(f.getFileName()));
        if (Files.exists(d)) Files.move(d, stash.resolve(d.getFileName()));
        return stash;
    }

    /**
     * Clears what deleting workspaces put aside more than {@code age} ago. Asked once at start; another MainFrame
     * window's undo may still want what is newer, since each window is a process with a history of its own.
     */
    void pruneDeleted(Duration age) {
        Path deleted = dir.resolve(DELETED);
        if (!Files.isDirectory(deleted)) return;
        FileTime before = FileTime.from(Instant.now().minus(age));
        try (DirectoryStream<Path> stashes = Files.newDirectoryStream(deleted)) {
            for (Path s : stashes) {
                if (Files.getLastModifiedTime(s).compareTo(before) < 0) deleteTree(s);
            }
        } catch (IOException e) {
            LOG.warn("cannot clear old scripts in {}", deleted, e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /**
     * Moves the file and the scripts beside it, so whatever columns and scripts it has go with it. Refuses to
     * overwrite another workspace. Scripts named after {@code to} with no workspace of that name are left over from
     * outside MainFrame, and are put aside as a deleted workspace's would be rather than taken over.
     */
    void rename(String from, String to) throws IOException {
        if (from.equals(to)) return;
        // A change of case only is the same file on Windows, where a move straight there keeps the old spelling, so
        // it goes by way of a name of its own.
        boolean caseOnly = from.equalsIgnoreCase(to);
        if (!caseOnly && exists(to)) throw new IOException("there is already a workspace called " + to);
        if (!caseOnly && stash(to) != null) LOG.info("put aside scripts named after {}, which had no workspace", to);
        move(envFile(from), envFile(to), caseOnly);
        move(envDir(from), envDir(to), caseOnly);
        try {
            move(file(from), file(to), caseOnly);
        } catch (IOException e) {
            move(envFile(to), envFile(from), caseOnly);   // the scripts back where the workspace still is
            move(envDir(to), envDir(from), caseOnly);
            throw e;
        }
    }

    private void move(Path from, Path to, boolean byHop) throws IOException {
        if (!Files.exists(from)) return;
        if (byHop) {
            Path hop = from.resolveSibling(from.getFileName() + ".renaming");
            Files.move(from, hop);
            Files.move(hop, to);
        } else {
            Files.move(from, to);
        }
    }

    /** The first of "Workspace", "Workspace 2", "Workspace 3", ... that is not taken. */
    String freshName() {
        if (!exists("Workspace")) return "Workspace";
        for (int i = 2; ; i++) {
            if (!exists("Workspace " + i)) return "Workspace " + i;
        }
    }

    /**
     * Why {@code name} cannot name a workspace, or null if it can. It is a file name, so it has to be one on
     * Windows: none of {@code <>:"/\|?*}, no trailing dot or space, and not a device name such as CON.
     */
    static String problem(String name) {
        if (name == null || name.isBlank()) return "A workspace needs a name.";
        for (char c : name.toCharArray()) {
            if (c < 32 || RESERVED.indexOf(c) >= 0) return "A name cannot contain " + (c < 32 ? "control characters" : "\"" + c + "\"") + ".";
        }
        if (name.endsWith(".") || name.endsWith(" ") || name.startsWith(" ")) return "A name cannot start or end with a space, or end with a dot.";
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (base.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return name + " is reserved by Windows.";
        return null;
    }
}
