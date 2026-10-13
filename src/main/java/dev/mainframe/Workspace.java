package dev.mainframe;

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
 */
record Workspace(String name, List<Entry> entries) {

    static final String NAME = "name";
    static final String PATH = "path";

    /** One folder: the tab's name (blank for the folder's own), where it starts, and any columns not known here. */
    record Entry(String name, String path, Map<String, String> extra) {

        Entry {
            name = name == null ? "" : name;
            path = path == null ? "" : path;
            // In column order, so the file is written back the way it was read.
            extra = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra == null ? Map.of() : extra));
        }

        Entry(String name, String path) {
            this(name, path, Map.of());
        }

        /** What the tab is called: its own name, or else the folder's. */
        String title() {
            if (!name.isBlank()) return name.strip();
            String p = path.strip();
            while (p.length() > 1 && (p.endsWith("\\") || p.endsWith("/"))) p = p.substring(0, p.length() - 1);
            int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
            return slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : path.strip();   // a drive root keeps its slash
        }

        Entry withName(String newName) {
            return new Entry(newName, path, extra);
        }

        Entry withPath(String newPath) {
            return new Entry(name, newPath, extra);
        }
    }

    Workspace {
        entries = List.copyOf(entries);
    }

    Workspace withName(String newName) {
        return new Workspace(newName, entries);
    }

    Workspace withEntries(List<Entry> newEntries) {
        return new Workspace(name, newEntries);
    }

    // -- the file ---------------------------------------------------------------------------------------------

    /** The workspace in {@code file}, named after it. */
    static Workspace read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String name = fileName.endsWith(".csv") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return parse(name, Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes {@code workspace} to {@code file}, whole: to a sibling first, then moved into place. */
    static void write(Path file, Workspace workspace) throws IOException {
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
    static Workspace parse(String name, String text) {
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
    static String format(Workspace workspace) {
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
