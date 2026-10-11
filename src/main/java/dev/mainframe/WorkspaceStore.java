package dev.mainframe;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The saved workspaces: one CSV file each (see {@link Workspace}) in the {@code workspaces} folder of MainFrame's
 * settings directory. The folder is created by the first save, not by looking.
 */
final class WorkspaceStore {

    private static final String RESERVED = "<>:\"/\\|?*";

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
            System.err.println("mainframe: cannot list workspaces in " + dir + ": " + e.getMessage());
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
            System.err.println("mainframe: cannot read workspace " + f + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    void save(Workspace workspace) throws IOException {
        Workspace.write(file(workspace.name()), workspace);
    }

    void delete(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }

    /** Moves the file, so whatever columns it holds go with it. Refuses to overwrite another workspace. */
    void rename(String from, String to) throws IOException {
        if (from.equals(to)) return;
        // A change of case only is the same file on Windows, where a move straight there keeps the old spelling, so
        // it goes by way of a name of its own.
        if (from.equalsIgnoreCase(to)) {
            Path hop = dir.resolve(from + ".csv.renaming");
            Files.move(file(from), hop);
            Files.move(hop, file(to));
            return;
        }
        if (exists(to)) throw new IOException("there is already a workspace called " + to);
        Files.move(file(from), file(to));
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
