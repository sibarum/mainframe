package dev.mainframe.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The set of saved indexes, one file each under {@code ~/.mainframe/indexes}.
 *
 * <p>The keeping is {@link Store}'s. What is left here is what an index actually
 * is: a name, a suffix of its own, and a format that {@link FsIndex} reads and
 * writes.
 */
public final class IndexStore {

    private static final String SUFFIX = ".mfi";

    private final Store store;

    public IndexStore(Path directory) { this.store = Store.at(directory, SUFFIX); }

    public static IndexStore inState() { return new IndexStore(SafeFs.stateDir().resolve("indexes")); }

    public Path directory() { return store.directory(); }

    public boolean exists(String name) { return store.has(name); }

    public Path file(String name) { return store.file(name); }

    public List<String> names() { return store.names(); }

    public FsIndex load(String name) throws IOException {
        return FsIndex.parse(Files.readString(file(name), StandardCharsets.UTF_8));
    }

    public void save(FsIndex index) throws IOException {
        store.write(index.name(), index.serialize());
    }

    public void drop(String name) throws IOException { store.drop(name); }

    public long fileSize(String name) { return store.size(name); }
}
