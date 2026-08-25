package dev.mainframe.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import dev.mainframe.value.Value;

/**
 * A saved picture of part of the filesystem.
 *
 * <p>An index is just a list of file facts (path, size, time, extension, media
 * type) that can be rebuilt or brought up to date cheaply. Because it is an
 * ordinary table of records once loaded, it drops straight into any pipeline:
 * every filter, sort and command that works on {@code ls} output works on an
 * index too.
 */
public final class FsIndex {

    private static final String MAGIC = "#mainframe-index";
    private static final int FORMAT = 1;

    /** One file or directory, keyed by its path relative to the index root. */
    public record Entry(String relative, boolean dir, long size, long modified, String ext, String mime) {}

    /** What a {@link #sync} changed. */
    public record SyncResult(int added, int updated, int removed, int unchanged) {
        public int touched() { return added + updated + removed; }
    }

    private final String name;
    private final Path root;
    private final List<String> skips;
    private final boolean deep;
    private long updatedAt;
    private final SequencedMap<String, Entry> entries = new LinkedHashMap<>();

    public FsIndex(String name, Path root, List<String> skips, boolean deep, long updatedAt) {
        this.name = name;
        this.root = root;
        this.skips = List.copyOf(skips);
        this.deep = deep;
        this.updatedAt = updatedAt;
    }

    public String name() { return name; }
    public Path root() { return root; }
    public List<String> skips() { return skips; }
    public boolean deep() { return deep; }
    public long updatedAt() { return updatedAt; }
    public int size() { return entries.size(); }

    // ---- building ---------------------------------------------------------------------

    /** Walks the root and brings the index in line with what is on disk. */
    public SyncResult sync() throws IOException {
        Map<String, Entry> found = new LinkedHashMap<>();
        int[] counts = {0, 0, 0};  // added, updated, unchanged

        Files.walkFileTree(root, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && skipped(dir)) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(root)) record(dir, attrs, found, counts);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!skipped(file)) record(file, attrs, found, counts);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // An unreadable entry is skipped rather than failing the whole index.
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });

        int removed = 0;
        for (String key : new ArrayList<>(entries.keySet())) {
            if (!found.containsKey(key)) { entries.remove(key); removed++; }
        }
        updatedAt = System.currentTimeMillis();
        return new SyncResult(counts[0], counts[1], removed, counts[2]);
    }

    private void record(Path path, BasicFileAttributes attrs, Map<String, Entry> found, int[] counts) {
        String key = key(path);
        found.put(key, null);
        Entry existing = entries.get(key);
        long size = attrs.isDirectory() ? 0 : attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();
        if (existing != null && existing.size() == size && existing.modified() == modified) {
            counts[2]++;
            return;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        String ext = attrs.isDirectory() ? "" : SafeFs.extensionOf(name);
        String mime;
        if (attrs.isDirectory()) {
            mime = MimeDetector.DIRECTORY.full();
        } else if (deep) {
            mime = MimeDetector.detect(path).full();
        } else {
            Value.Mime byName = MimeDetector.fromName(name);
            mime = byName == null ? "" : byName.full();
        }
        entries.put(key, new Entry(key, attrs.isDirectory(), size, modified, ext, mime));
        counts[existing == null ? 0 : 1]++;
    }

    private String key(Path path) {
        String rel = root.relativize(path).toString();
        return rel.replace('\\', '/');
    }

    private boolean skipped(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        for (String skip : skips) {
            if (name.equals(skip)) return true;
            if (dev.mainframe.value.Values.matches(name, skip) && skip.indexOf('*') >= 0) return true;
        }
        return false;
    }

    // ---- reading ----------------------------------------------------------------------

    /** The index as a table, ready to pipe into where / sort-by / select. */
    public List<Value.Rec> rows() {
        List<Value.Rec> rows = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            Path full = root.resolve(e.relative());
            String name = full.getFileName() == null ? e.relative() : full.getFileName().toString();
            Value mime = e.mime().isEmpty()
                    ? Value.Nothing.INSTANCE
                    : MimeDetector.mime(e.mime(), deep ? "content" : "extension");
            rows.add(Value.Rec.of(
                    "name", new Value.Str(name),
                    "kind", new Value.Str(e.dir() ? "dir" : "file"),
                    "size", new Value.Size(e.size()),
                    "modified", new Value.Time(e.modified()),
                    "ext", new Value.Str(e.ext()),
                    "mime", mime,
                    "path", new Value.PathVal(full)));
        }
        return rows;
    }

    // ---- persistence -----------------------------------------------------------------

    public byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\t').append(FORMAT).append('\t').append(escape(name)).append('\t')
                .append(escape(root.toString())).append('\t').append(updatedAt).append('\t')
                .append(deep).append('\t').append(escape(String.join(",", skips))).append('\n');
        for (Entry e : entries.values()) {
            sb.append(e.dir() ? 'd' : 'f').append('\t')
                    .append(e.size()).append('\t')
                    .append(e.modified()).append('\t')
                    .append(escape(e.ext())).append('\t')
                    .append(escape(e.mime())).append('\t')
                    .append(escape(e.relative())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static FsIndex parse(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].startsWith(MAGIC)) {
            throw new IllegalArgumentException("not a MainFrame index file");
        }
        String[] header = lines[0].split("\t", -1);
        if (header.length < 7 || Integer.parseInt(header[1]) != FORMAT) {
            throw new IllegalArgumentException("index was written by a different version of MainFrame");
        }
        List<String> skips = header[6].isEmpty() ? List.of() : List.of(unescape(header[6]).split(","));
        FsIndex index = new FsIndex(unescape(header[2]), Path.of(unescape(header[3])), skips,
                Boolean.parseBoolean(header[5]), Long.parseLong(header[4]));
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length < 6) continue;
            String relative = unescape(f[5]);
            index.entries.put(relative, new Entry(relative, f[0].equals("d"),
                    Long.parseLong(f[1]), Long.parseLong(f[2]), unescape(f[3]), unescape(f[4])));
        }
        return index;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
