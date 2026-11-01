package dev.mainframe.core;

import dev.mainframe.core.Messages.Item;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.Listing;
import dev.vexelray.framework.api.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One level of the navigator's tree, read from the disk on the offload lane: folders first, then files, each
 * case-insensitively by name.
 *
 * <p>The tree asks for a level the first time it is opened, and the view asks this; the answer is data (each entry's
 * path, name and whether it is a folder), so the view can tell the tree what to draw without touching the disk on the
 * GUI thread. An unreadable folder answers empty rather than taking the tree down.
 */
@Job
public final class ListFolder {

    /** Directories nobody opens a file in, left out of the listing so a project's tree is the project. */
    static final Set<String> HIDDEN = Set.of(".git", ".svn", ".hg", ".idea", "node_modules", "target", "target-linux",
            "__pycache__");

    private static final Comparator<Item> ORDER = Comparator
            .comparing((Item i) -> !i.folder())
            .thenComparing(i -> i.name().toLowerCase(Locale.ROOT));

    public Listed run(Listing l) {
        return new Listed(l.tree(), l.dir(), l.roots(), list(l.dir()));
    }

    static List<Item> list(Path dir) {
        List<Item> items = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> !HIDDEN.contains(String.valueOf(p.getFileName())))
                    .forEach(p -> items.add(new Item(p, name(p), Files.isDirectory(p))));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        items.sort(ORDER);
        return items;
    }

    private static String name(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : p.toString();
    }

    /**
     * The chain of paths from just under {@code base} down to {@code file}, inclusive: what the tree's
     * {@code revealPath} walks. Each is the path this job lists for that row, so the walk finds it. Empty when the file
     * is not under the base.
     */
    public static List<Path> chain(Path base, Path file) {
        if (base == null || file == null) {
            return List.of();
        }
        Path root = base.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return List.of();
        }
        List<Path> chain = new ArrayList<>();
        Path at = root;
        for (Path part : root.relativize(target)) {
            at = at.resolve(part);
            chain.add(at);
        }
        return chain;
    }
}
