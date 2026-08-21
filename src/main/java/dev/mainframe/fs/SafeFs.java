package dev.mainframe.fs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import dev.mainframe.value.Value;

/**
 * Filesystem access with the sharp edges filed off: paths are always resolved
 * explicitly, writes land atomically, and deletes go to a recoverable trash.
 */
public final class SafeFs {

    private static final DateTimeFormatter TRASH_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private SafeFs() {}

    /** Turns whatever the user typed into one absolute, normalised path. */
    public static Path resolve(Path cwd, String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return cwd;
        if (text.equals("~")) return userHome();
        if (text.startsWith("~/") || text.startsWith("~\\")) {
            return userHome().resolve(text.substring(2)).normalize();
        }
        Path p = Path.of(text);
        return (p.isAbsolute() ? p : cwd.resolve(p)).normalize().toAbsolutePath();
    }

    public static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * Where MainFrame keeps its own state: indexes, trash, history. Point
     * MAINFRAME_HOME somewhere else to move it, which is also how the tests keep
     * out of your real home directory.
     */
    public static Path stateDir() {
        String property = System.getProperty("mainframe.home");
        if (property != null && !property.isBlank()) return Path.of(property);
        String environment = System.getenv("MAINFRAME_HOME");
        if (environment != null && !environment.isBlank()) return Path.of(environment);
        return userHome().resolve(".mainframe");
    }

    /**
     * Writes via a sibling temporary file and one rename, so a reader never sees
     * a half-written file and a failure leaves the original untouched.
     */
    public static void atomicWrite(Path target, byte[] data) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + target.getFileName() + ".", ".mf-part");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Moves a path into the trash and returns where it landed. */
    public static Path trash(Path victim) throws IOException {
        Path bin = stateDir().resolve("trash").resolve(TRASH_STAMP.format(Instant.now()));
        Files.createDirectories(bin);
        Path destination = bin.resolve(victim.getFileName().toString());
        int n = 1;
        while (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            destination = bin.resolve(victim.getFileName() + "-" + n++);
        }
        try {
            Files.move(victim, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Crossing devices: copy then remove, which is still recoverable.
            copyTree(victim, destination);
            deleteTree(victim);
        }
        return destination;
    }

    public static void copyTree(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (var children = Files.list(from)) {
                for (Path child : children.toList()) copyTree(child, to.resolve(child.getFileName().toString()));
            }
        } else {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    public static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) deleteTree(child);
            }
        }
        Files.deleteIfExists(path);
    }

    public static long treeSize(Path path) {
        try {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return Files.size(path);
            long[] total = {0};
            try (var walk = Files.walk(path)) {
                walk.forEach(p -> {
                    try {
                        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total[0] += Files.size(p);
                    } catch (IOException ignored) {
                        // an unreadable child should not fail the whole count
                    }
                });
            }
            return total[0];
        } catch (IOException e) {
            return 0;
        }
    }

    public static int countTree(Path path) {
        try {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return 1;
            try (var walk = Files.walk(path)) {
                return (int) walk.count();
            }
        } catch (IOException e) {
            return 1;
        }
    }

    /** The short form of a path: relative to {@code cwd} when it lives underneath it. */
    public static String describe(Path cwd, Path path) {
        try {
            if (path.startsWith(cwd)) {
                Path rel = cwd.relativize(path);
                return rel.toString().isEmpty() ? "." : rel.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // different roots on Windows; fall through to the absolute form
        }
        return path.toString();
    }

    /** How hard to work at naming a file's media type. */
    public enum MimeMode {
        /** Leave the column out entirely. */
        NONE,
        /** Go by the file name, which costs nothing. */
        NAME,
        /** Read the first few kilobytes and be sure. */
        CONTENT
    }

    /** The standard row shape for a file. Every filesystem command emits this. */
    public static Value.Rec row(Path path, MimeMode mimeMode) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        boolean dir;
        long size = 0;
        long modified = 0;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            dir = attrs.isDirectory();
            size = attrs.isDirectory() ? 0 : attrs.size();
            modified = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            dir = Files.isDirectory(path);
        }
        // The column set is deliberately identical to the one an index produces,
        // so rows from ls, find and from-index are interchangeable everywhere.
        Value.Rec row = Value.Rec.of(
                "name", new Value.Str(name),
                "kind", new Value.Str(dir ? "dir" : "file"),
                "size", new Value.Size(size),
                "modified", new Value.Time(modified),
                "ext", new Value.Str(dir ? "" : extensionOf(name)));
        if (mimeMode != MimeMode.NONE) {
            Value mime;
            if (dir) {
                mime = MimeDetector.DIRECTORY;
            } else if (mimeMode == MimeMode.CONTENT) {
                mime = MimeDetector.detect(path);
            } else {
                Value.Mime byName = MimeDetector.fromName(name);
                mime = byName == null ? Value.Nothing.INSTANCE : byName;
            }
            row = row.with("mime", mime);
        }
        return row.with("path", new Value.PathVal(path));
    }

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
