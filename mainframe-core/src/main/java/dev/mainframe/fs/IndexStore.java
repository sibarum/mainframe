package dev.mainframe.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The set of saved indexes, one file each under {@code ~/.mainframe/indexes}. */
public final class IndexStore {

    private static final String SUFFIX = ".mfi";

    private final Path directory;

    public IndexStore(Path directory) { this.directory = directory; }

    public static IndexStore inState() { return new IndexStore(SafeFs.stateDir().resolve("indexes")); }

    public Path directory() { return directory; }

    public boolean exists(String name) { return Files.isRegularFile(file(name)); }

    public Path file(String name) { return directory.resolve(name + SUFFIX); }

    public List<String> names() {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (var children = Files.list(directory)) {
            for (Path p : children.toList()) {
                String file = p.getFileName().toString();
                if (file.endsWith(SUFFIX)) names.add(file.substring(0, file.length() - SUFFIX.length()));
            }
        } catch (IOException e) {
            return names;
        }
        names.sort(String::compareTo);
        return names;
    }

    public FsIndex load(String name) throws IOException {
        return FsIndex.parse(Files.readString(file(name), StandardCharsets.UTF_8));
    }

    public void save(FsIndex index) throws IOException {
        SafeFs.atomicWrite(file(index.name()), index.serialize());
    }

    public void drop(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }

    public long fileSize(String name) {
        try {
            return Files.size(file(name));
        } catch (IOException e) {
            return 0;
        }
    }
}
