package dev.mainframe.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A workspace: a named list of folders, one tab each, kept as a CSV file whose name is the workspace's.
 *
 * <p>The file has a header row, so columns can be added later without breaking files written now:
 * <pre>
 * name,path
 * ,C:\Users\me\GitHub\mainframe
 * gui,C:\Users\me\GitHub\vexelray-gui
 * </pre>
 * {@code path} is the folder the tab's shell starts in; {@code name} is the tab's name, and an empty one means the
 * folder's own name. A column this build does not know is kept, value for value, when the file is written back, so
 * an older MainFrame editing a newer file loses nothing. Fields are quoted the usual CSV way when they hold a comma,
 * a quote or a line break.
 *
 * <p>One column is MainFrame's own: {@code env}, the file name of the tab's environment script in the workspace's
 * script folder ({@link EnvScripts}). It is a key rather than anything worked out from the row, so the script stays
 * the tab's through relabelling, reordering and changing its folder. A row gets one the first time its workspace is
 * opened or shown in the editor ({@link WorkspaceStore#keyed}), and the script itself the first time it is edited.
 */
public record Workspace(String name, List<Entry> entries) {

    static final String NAME = "name";
    static final String PATH = "path";
    static final String ENV = "env";

    /** One folder: the tab's name (blank for the folder's own), where it starts, and any columns not known here. */
    public record Entry(String name, String path, Map<String, String> extra) {

        public Entry {
            name = name == null ? "" : name;
            path = path == null ? "" : path;
            // In column order, so the file is written back the way it was read.
            extra = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra == null ? Map.of() : extra));
        }

        public Entry(String name, String path) {
            this(name, path, Map.of());
        }

        /** What the tab is called: its own name, or else the folder's. */
        public String title() {
            if (!name.isBlank()) return name.strip();
            String p = path.strip();
            while (p.length() > 1 && (p.endsWith("\\") || p.endsWith("/"))) p = p.substring(0, p.length() - 1);
            int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
            return slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : path.strip();   // a drive root keeps its slash
        }

        public Entry withName(String newName) {
            return new Entry(newName, path, extra);
        }

        public Entry withPath(String newPath) {
            return new Entry(name, newPath, extra);
        }

        /** The file name of this tab's environment script, or blank while it has none. */
        public String env() {
            return extra.getOrDefault(ENV, "").strip();
        }

        /** The same tab with {@code key} as its script's file name, kept among the other columns. */
        public Entry withEnv(String key) {
            Map<String, String> more = new LinkedHashMap<>(extra);
            more.put(ENV, key);
            return new Entry(name, path, more);
        }
    }

    public Workspace {
        entries = List.copyOf(entries);
    }

    public Workspace withName(String newName) {
        return new Workspace(newName, entries);
    }

    public Workspace withEntries(List<Entry> newEntries) {
        return new Workspace(name, newEntries);
    }

    /** What its tile in the Open dialog says: "3 folders: api, web, docs", shortened when long. */
    public String describe() {
        if (entries.isEmpty()) {
            return "No folders yet";
        }
        String names = entries.stream().limit(4).map(Entry::title).collect(java.util.stream.Collectors.joining(", "));
        if (entries.size() > 4) {
            names += ", …";
        }
        return entries.size() + (entries.size() == 1 ? " folder: " : " folders: ") + names;
    }

    /**
     * A key for a new tab called {@code title}, none of {@code taken} (lower case), which it joins: the title in
     * letters a file name always takes, and a random tail, so the folder reads as its tabs and two tabs of one name
     * are still two scripts. Looks at nothing on disk, so the settings window can give a row its key the moment it is
     * added, before the step that holds it is recorded; {@link WorkspaceStore#freshKey} also steers clear of scripts
     * already there.
     */
    public static String freshKey(String title, java.util.Set<String> taken) {
        StringBuilder stem = new StringBuilder();
        for (char c : title.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
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
            String key = stem + "-" + String.format("%06x",
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24)) + ".ps1";
            if (taken.add(key)) return key;
        }
    }

    // -- the file ---------------------------------------------------------------------------------------------

    /** The workspace in {@code file}, named after it. */
    public static Workspace read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String name = fileName.endsWith(".csv") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return parse(name, Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes {@code workspace} to {@code file}, whole: to a sibling first, then moved into place. */
    public static void write(Path file, Workspace workspace) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(workspace), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Rows under the header, by column name. A file without a {@code path} column has nothing to open, and reads as
     * empty; a row whose path is blank is skipped. Neither stops the rest from opening.
     */
    public static Workspace parse(String name, String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = rows(text);
        if (rows.isEmpty()) return new Workspace(name, List.of());
        List<String> header = rows.get(0).stream().map(h -> h.strip().toLowerCase(java.util.Locale.ROOT)).toList();
        int pathCol = header.indexOf(PATH);
        int nameCol = header.indexOf(NAME);
        List<Entry> entries = new ArrayList<>();
        if (pathCol < 0) return new Workspace(name, entries);
        for (List<String> row : rows.subList(1, rows.size())) {
            String path = field(row, pathCol).strip();
            if (path.isEmpty()) continue;
            Map<String, String> extra = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                if (c != pathCol && c != nameCol && !header.get(c).isEmpty()) extra.put(header.get(c), field(row, c));
            }
            entries.add(new Entry(nameCol < 0 ? "" : field(row, nameCol), path, extra));
        }
        return new Workspace(name, entries);
    }

    /** The file's text: {@code name,path}, then the kept columns in the order they first appear. */
    public static String format(Workspace workspace) {
        Set<String> extraCols = new LinkedHashSet<>();
        for (Entry e : workspace.entries()) extraCols.addAll(e.extra().keySet());
        List<String> header = new ArrayList<>(List.of(NAME, PATH));
        header.addAll(extraCols);
        StringBuilder out = new StringBuilder();
        line(out, header);
        for (Entry e : workspace.entries()) {
            List<String> row = new ArrayList<>(List.of(e.name(), e.path()));
            for (String col : extraCols) row.add(e.extra().getOrDefault(col, ""));
            line(out, row);
        }
        return out.toString();
    }

    private static String field(List<String> row, int col) {
        return col < row.size() ? row.get(col) : "";
    }

    private static void line(StringBuilder out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append(',');
            String f = fields.get(i);
            if (f.indexOf(',') >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0) {
                out.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                out.append(f);
            }
        }
        out.append("\r\n");
    }

    /** CSV rows: commas between fields, double quotes around a field that needs them, "" for a quote inside one. */
    private static List<List<String>> rows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;   // whether this row has anything in it, so blank lines are not rows
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || !field.isEmpty()) {
                    row.add(field.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
