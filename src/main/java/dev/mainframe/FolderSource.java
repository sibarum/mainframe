package dev.mainframe;

import dev.vexelray.gui.widget.TreeView;

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
 * The filesystem under one folder, as a lazy {@link TreeView.Source}: directories first, then files, each
 * case-insensitively by name. Nothing is read until a level is opened.
 *
 * <p>The folder can be changed, because a tree cannot be handed a new source: pointing the navigator somewhere
 * else is {@link #base(Path)} followed by {@code TreeView.refresh()}, which re-reads the roots and drops every
 * row that is no longer among them.
 */
final class FolderSource implements TreeView.Source<Path> {

    /** Directories nobody opens a file in, left out of the listing so a project's tree is the project. */
    static final Set<String> HIDDEN = Set.of(".git", ".svn", ".hg", ".idea", "node_modules", "target", "__pycache__");

    private static final Comparator<Path> ORDER = Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT));

    private volatile Path base;

    FolderSource(Path base) {
        base(base);
    }

    /** The folder whose contents are the roots, or null for none. */
    Path base() {
        return base;
    }

    /** Point at {@code folder}, held absolute and normalised so every item listed under it is too. */
    void base(Path folder) {
        this.base = folder == null ? null : folder.toAbsolutePath().normalize();
    }

    @Override
    public List<Path> roots() {
        Path b = base;
        return b == null ? List.of() : children(b);
    }

    @Override
    public String label(Path item) {
        Path name = item.getFileName();
        return name != null ? name.toString() : item.toString();
    }

    @Override
    public boolean hasChildren(Path item) {
        return Files.isDirectory(item);
    }

    @Override
    public boolean acceptsChildren(Path item) {
        return Files.isDirectory(item);
    }

    @Override
    public List<Path> children(Path item) {
        try (Stream<Path> s = Files.list(item)) {
            List<Path> listed = new ArrayList<>();
            s.filter(p -> !HIDDEN.contains(String.valueOf(p.getFileName()))).forEach(listed::add);
            listed.sort(ORDER);
            return listed;
        } catch (IOException | RuntimeException e) {
            return List.of();   // an unreadable directory shows as empty rather than taking the tree down
        }
    }

    /**
     * The chain of folders from just under the base down to {@code file}, inclusive — what
     * {@code TreeView.revealPath} walks. Empty if the file is not under the base.
     */
    List<Path> chainTo(Path file) {
        Path b = base;
        if (b == null || file == null) {
            return List.of();
        }
        Path root = b.toAbsolutePath().normalize();
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
