package dev.mainframe.core;

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
 * <p>A workspace's scripts ({@link EnvScripts}) are named after it, beside its file: {@code <name>.env.ps1} and {@code
 * <name>.env.sh}, which every tab sources, PowerShell's and sh's, and the folder {@code <name>.env}, which holds the
 * tabs' own, named by the tab's {@code env} column: {@code api-3f9c2a.ps1}, and for sh the same name ending {@code
 * .sh}. The column keeps its {@code .ps1} wherever MainFrame runs, so a workspace file means the same on Windows and
 * Linux. Renaming a workspace moves them all. Deleting one moves its file and its scripts into {@code .deleted}, under
 * a name the caller chooses, where undoing the delete finds them; what is there is cleared a month later
 * ({@link #pruneDeleted}), since an undo lasts only as long as the window that can make it. A folder removed from a
 * workspace keeps its script where it is, for the same undo, and nothing deletes it.
 *
 * <h2>A value</h2>
 *
 * <p>A record of its folder and nothing else, so the jobs that touch the disk can hold it: two requests may run at
 * once, and this has nothing of its own for them to share.
 */
public record WorkspaceStore(Path dir) {

    private static final Log LOG = Log.of("mainframe.workspaces");
    private static final String RESERVED = "<>:\"/\\|?*";
    /** Where a deleted workspace waits for an undo. */
    public static final String DELETED = ".deleted";
    /** How long it waits. */
    public static final Duration KEEP_DELETED = Duration.ofDays(30);

    public Path file(String name) {
        return dir.resolve(name + ".csv");
    }

    /** The script in {@code dialect} every tab of {@code name} sources: there or not. */
    public Path envFile(String name, EnvScripts.Dialect dialect) {
        return dir.resolve(name + ".env" + dialect.extension);
    }

    /** The folder of {@code name}'s per-tab scripts: there or not. */
    public Path envDir(String name) {
        return dir.resolve(name + ".env");
    }

    /**
     * The script of the tab of workspace {@code name} whose {@code env} column is {@code key}, or empty when the key
     * is blank or is not a plain script name in that folder: a hand-edited file cannot point a tab outside it. The
     * key names the PowerShell script; another dialect's is the key with that dialect's ending for {@code .ps1}.
     */
    public Optional<Path> tabEnvFile(String name, String key, EnvScripts.Dialect dialect) {
        if (!validKey(key)) return Optional.empty();
        return Optional.of(envDir(name).resolve(key.substring(0, key.length() - 4) + dialect.extension));
    }

    /** {@link #envFile} in every dialect. */
    private List<Path> envFiles(String name) {
        return Stream.of(EnvScripts.Dialect.values()).map(d -> envFile(name, d)).toList();
    }

    /** Whether any of {@code name}'s scripts is there: a workspace script, or the folder of the tabs'. */
    private boolean hasScripts(String name) {
        return Files.exists(envDir(name)) || envFiles(name).stream().anyMatch(Files::exists);
    }

    /** The workspaces there are, by name, in alphabetical order ignoring case. */
    public List<String> names() {
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

    public boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    /** The workspace called {@code name}, or empty, and said, if there is none or it cannot be read. */
    public Optional<Workspace> load(String name) {
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
    public Optional<Workspace> keyed(String name) {
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
    public Workspace withKeys(Workspace w) {
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
    public String freshKey(String name, String title, Set<String> taken) {
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
            String k = key;
            if (taken.add(key) && Stream.of(EnvScripts.Dialect.values())
                    .noneMatch(d -> Files.exists(tabEnvFile(name, k, d).orElseThrow()))) return key;
        }
    }

    /** A plain file name ending in {@code .ps1}: no folder in it, no way out of the folder, nothing hidden. */
    public static boolean validKey(String key) {
        if (key == null || key.isBlank() || key.startsWith(".") || key.length() > 100) return false;
        if (!key.toLowerCase(Locale.ROOT).endsWith(".ps1")) return false;
        for (char c : key.toCharArray()) {
            if (c < 32 || RESERVED.indexOf(c) >= 0) return false;
        }
        return !key.endsWith(" ");
    }

    public void save(Workspace workspace) throws IOException {
        Workspace.write(file(workspace.name()), workspace);
    }

    /**
     * A name for the folder under {@link #DELETED} that deleting {@code name} now would put it in: chosen by whoever
     * deletes it, before the delete is asked for, so the undo that brings it back can be recorded with it.
     */
    public static String stashName(String name) {
        return name + " " + System.currentTimeMillis();
    }

    /**
     * Moves the workspace, its file and its scripts, aside into {@code stash}, a folder of {@link #DELETED}, where
     * {@link #restore} finds them. A workspace that is not there leaves nothing to find.
     */
    public void delete(String name, String stash) throws IOException {
        Path to = dir.resolve(DELETED).resolve(stash);
        Files.createDirectories(to);
        if (Files.exists(file(name))) Files.move(file(name), to.resolve(file(name).getFileName()));
        stashScripts(name, to);
    }

    /**
     * Puts back as workspace {@code name} what {@link #delete} moved to {@code stash}: its file and its scripts.
     * Refuses while there is a workspace of that name, or scripts of one, again.
     */
    public void restore(String stash, String name) throws IOException {
        Path from = dir.resolve(DELETED).resolve(stash);
        if (!Files.isDirectory(from)) throw new IOException("nothing of " + name + " was put aside in " + from);
        if (exists(name) || hasScripts(name)) {
            throw new IOException("there is a workspace called " + name + " again; the old one is still in " + from);
        }
        Path csv = from.resolve(file(name).getFileName());
        if (Files.exists(csv)) Files.move(csv, file(name));
        for (Path f : envFiles(name)) {
            Path stashed = from.resolve(f.getFileName());
            if (Files.exists(stashed)) Files.move(stashed, f);
        }
        Path d = from.resolve(envDir(name).getFileName());
        if (Files.exists(d)) Files.move(d, envDir(name));
        Files.deleteIfExists(from);
    }

    /** Moves the workspace's scripts into a folder of their own under {@link #DELETED}; null when it has none. */
    private Path stash(String name) throws IOException {
        if (!hasScripts(name)) return null;
        Path stash = dir.resolve(DELETED).resolve(stashName(name));
        Files.createDirectories(stash);
        stashScripts(name, stash);
        return stash;
    }

    private void stashScripts(String name, Path stash) throws IOException {
        for (Path f : envFiles(name)) {
            if (Files.exists(f)) Files.move(f, stash.resolve(f.getFileName()));
        }
        Path d = envDir(name);
        if (Files.exists(d)) Files.move(d, stash.resolve(d.getFileName()));
    }

    /**
     * Clears what deleting workspaces put aside more than {@code age} ago. Asked once at start; another MainFrame
     * window's undo may still want what is newer, since each window is a process with a history of its own.
     */
    public void pruneDeleted(Duration age) {
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
    public void rename(String from, String to) throws IOException {
        if (from.equals(to)) return;
        // A change of case only is the same file on Windows, where a move straight there keeps the old spelling, so
        // it goes by way of a name of its own.
        boolean caseOnly = from.equalsIgnoreCase(to);
        if (!caseOnly && exists(to)) throw new IOException("there is already a workspace called " + to);
        if (!caseOnly && stash(to) != null) LOG.info("put aside scripts named after {}, which had no workspace", to);
        for (EnvScripts.Dialect d : EnvScripts.Dialect.values()) move(envFile(from, d), envFile(to, d), caseOnly);
        move(envDir(from), envDir(to), caseOnly);
        try {
            move(file(from), file(to), caseOnly);
        } catch (IOException e) {
            // the scripts back where the workspace still is
            for (EnvScripts.Dialect d : EnvScripts.Dialect.values()) move(envFile(to, d), envFile(from, d), caseOnly);
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

    /**
     * Where MainFrame keeps the startup files it gives bash and zsh ({@link EnvScripts#launchers}): beside this
     * folder, in MainFrame's settings directory, since they are no workspace's.
     */
    public Path launchers() {
        return dir.resolveSibling("shell");
    }

    /** The first of "Workspace", "Workspace 2", "Workspace 3", ... that is not taken. */
    public String freshName() {
        if (!exists("Workspace")) return "Workspace";
        for (int i = 2; ; i++) {
            if (!exists("Workspace " + i)) return "Workspace " + i;
        }
    }

    /**
     * Why {@code name} cannot name a workspace, or null if it can. It is a file name, so it has to be one on
     * Windows: none of {@code <>:"/\|?*}, no trailing dot or space, and not a device name such as CON.
     */
    public static String problem(String name) {
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
