package dev.mainframe.fs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import dev.mainframe.value.Times;
import dev.mainframe.value.Value;

/**
 * Filesystem access with the sharp edges filed off: paths are always resolved
 * explicitly, writes land atomically, and deletes go to a recoverable trash.
 */
public final class SafeFs {

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

    /** Who is allowed to read a file MainFrame writes. */
    public enum Visibility {
        /** Whatever the system would normally give it. Right for everything that is not a secret. */
        NORMAL,
        /** This account and nobody else. */
        OWNER_ONLY
    }

    /**
     * Writes via a sibling temporary file and one rename, so a reader never sees
     * a half-written file and a failure leaves the original untouched.
     */
    public static void atomicWrite(Path target, byte[] data) throws IOException {
        atomicWrite(target, data, Visibility.NORMAL);
    }

    /**
     * The same write, with a say in who can read the result.
     *
     * <p>The permissions go on the temporary file before the bytes do. Setting
     * them on the target afterwards would leave the contents readable for the
     * length of the write, and a rename cannot take that back.
     */
    public static void atomicWrite(Path target, byte[] data, Visibility visibility) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + target.getFileName() + ".", ".mf-part");
        try {
            if (visibility == Visibility.OWNER_ONLY) restrictToOwner(temp);
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

    /**
     * Cuts a file down to this account, on whichever of the two permission models
     * the filesystem has.
     *
     * <p>POSIX is a set of bits. Windows is an access control list, and
     * restricting one means replacing the entries inherited from the parent
     * directory rather than adding to them -- a list that still carries Users
     * read is not restricted, it is decorated.
     *
     * @throws IOException when the filesystem offers neither model, so that a
     *                     caller who asked for this cannot quietly not get it
     */
    public static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("this filesystem offers no way to keep " + path
                    + " to your account alone");
        }
        UserPrincipal owner = acl.getOwner();
        acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.of(
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.WRITE_ACL,
                        AclEntryPermission.DELETE,
                        AclEntryPermission.SYNCHRONIZE))
                .build()));
    }

    /** Moves a path into the trash and returns where it landed. */
    public static Path trash(Path victim) throws IOException {
        Path bin = stateDir().resolve("trash").resolve(Times.stamp(System.currentTimeMillis()));
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
        // A timestamp we could not read is unknown, not the first moment of 1970.
        Value modified = Value.Nothing.INSTANCE;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            dir = attrs.isDirectory();
            size = attrs.isDirectory() ? 0 : attrs.size();
            modified = new Value.Time(attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            dir = Files.isDirectory(path);
        }
        // The column set is deliberately identical to the one an index produces,
        // so rows from ls, find and from-index are interchangeable everywhere.
        Value.Rec row = Value.Rec.of(
                "name", new Value.Str(name),
                "kind", new Value.Str(dir ? "dir" : "file"),
                "size", new Value.Size(size),
                "modified", modified,
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
