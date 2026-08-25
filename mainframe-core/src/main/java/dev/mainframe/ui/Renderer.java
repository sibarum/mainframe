package dev.mainframe.ui;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import dev.mainframe.MfError;
import dev.mainframe.Span;
import dev.mainframe.eval.Plan;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/** Everything MainFrame prints goes through here, so output stays consistent. */
public final class Renderer {

    private static final int MIN_COLUMN = 3;

    private final PrintStream out;
    private final PrintStream err;
    private final boolean color;
    private final int width;

    public Renderer(PrintStream out, PrintStream err, boolean color) {
        this.out = out;
        this.err = err;
        this.color = color;
        this.width = detectWidth();
    }

    private static int detectWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int n = Integer.parseInt(columns.trim());
                if (n > 20) return n;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 100;
    }

    public static boolean colorSupported() {
        if (System.getenv("NO_COLOR") != null) return false;
        String term = System.getenv("TERM");
        if ("dumb".equals(term)) return false;
        return true;
    }

    public PrintStream out() { return out; }
    public int width() { return width; }

    // ---- values --------------------------------------------------------------------

    /** Prints a pipeline result the way a person wants to read it. */
    public void print(Value value) {
        switch (value) {
            case Value.Nothing _ -> { }
            case Value.ListVal list -> {
                if (list.items().isEmpty()) {
                    out.println(dim("(empty)"));
                } else if (Values.isTable(list)) {
                    table(Values.rows(list));
                } else {
                    for (Value item : list.items()) out.println(Values.display(item));
                }
            }
            case Value.Rec rec -> record(rec);
            default -> out.println(Values.display(value));
        }
    }

    private void record(Value.Rec rec) {
        if (rec.fields().isEmpty()) { out.println(dim("(empty record)")); return; }
        int keyWidth = 0;
        for (String k : rec.fields().keySet()) keyWidth = Math.max(keyWidth, k.length());
        for (var e : rec.fields().entrySet()) {
            out.println(dim(pad(e.getKey(), keyWidth)) + "  " + cell(e.getValue()));
        }
    }

    public void table(List<Value.Rec> rows) {
        List<String> columns = new ArrayList<>(Values.columns(rows));
        if (columns.isEmpty()) { out.println(dim("(no columns)")); return; }

        List<List<String>> cells = new ArrayList<>();
        // A column of paths is clipped from the left: the tail is the useful half.
        boolean[] paths = new boolean[columns.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = true;
        for (Value.Rec row : rows) {
            List<String> line = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Value v = row.get(columns.get(i));
                if (!(v instanceof Value.PathVal)) paths[i] = false;
                line.add(v == null ? "" : cell(v));
            }
            cells.add(line);
        }

        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) widths[i] = columns.get(i).length();
        for (List<String> line : cells) {
            for (int i = 0; i < line.size(); i++) widths[i] = Math.max(widths[i], line.get(i).length());
        }
        shrinkToFit(widths);

        StringBuilder header = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) header.append("  ");
            header.append(pad(clip(columns.get(i), widths[i]), widths[i]));
        }
        out.println(bold(header.toString()));

        for (List<String> line : cells) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) sb.append("  ");
                String text = paths[i] ? clipLeft(line.get(i), widths[i]) : clip(line.get(i), widths[i]);
                sb.append(pad(text, widths[i]));
            }
            out.println(stripTrailing(sb.toString()));
        }
    }

    /** Narrows the widest columns until the row fits the terminal. */
    private void shrinkToFit(int[] widths) {
        int gaps = (widths.length - 1) * 2;
        while (total(widths) + gaps > width) {
            int widest = 0;
            for (int i = 1; i < widths.length; i++) if (widths[i] > widths[widest]) widest = i;
            if (widths[widest] <= MIN_COLUMN) return;
            widths[widest]--;
        }
    }

    private static int total(int[] widths) {
        int sum = 0;
        for (int w : widths) sum += w;
        return sum;
    }

    private String cell(Value v) {
        String s = Values.display(v);
        int newline = s.indexOf('\n');
        return newline >= 0 ? s.substring(0, newline) + "..." : s;
    }

    // ---- plans and messages ----------------------------------------------------------

    /** Shows what a command would do, for --dry-run. */
    public void plan(Plan plan) {
        out.println(yellow("dry run") + " " + plan.summary() + dim(" -- nothing was changed"));
        for (Plan.Step step : plan.steps()) out.println("  " + dim("would") + " " + step.description());
        for (String note : plan.notes()) out.println("  " + dim(note));
    }

    public void info(String message) { out.println(message); }

    public void note(String message) { out.println(dim(message)); }

    public void warn(String message) { err.println(yellow("warning") + " " + message); }

    /** The full error report: what happened, where, and what to do about it. */
    public void error(MfError e, String source) {
        err.println(red("error[" + e.code() + "]") + " " + bold(e.getMessage()));
        Span span = e.span();
        if (span.known() && source != null) {
            String[] lines = source.split("\n", -1);
            if (span.line() - 1 < lines.length) {
                String line = lines[span.line() - 1].replace("\t", " ");
                String gutter = String.valueOf(span.line());
                String blank = " ".repeat(gutter.length());
                err.println(dim(blank + " --> line " + span.line() + ":" + span.col()));
                err.println(dim(blank + " |"));
                err.println(dim(gutter + " | ") + line);
                int col = Math.max(1, Math.min(span.col(), line.length() + 1));
                err.println(dim(blank + " | ") + " ".repeat(col - 1)
                        + red("^".repeat(Math.max(1, Math.min(span.length(), line.length() - col + 2)))));
            }
        }
        for (String hint : e.hints()) err.println(cyan("help") + " " + hint);
    }

    // ---- text helpers ----------------------------------------------------------------

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String clip(String s, int w) {
        if (s.length() <= w) return s;
        return w <= 1 ? s.substring(0, w) : s.substring(0, w - 1) + "~";
    }

    /** Keeps the end of the text, which is where the file name lives. */
    private static String clipLeft(String s, int w) {
        if (s.length() <= w) return s;
        return w <= 1 ? s.substring(s.length() - w) : "~" + s.substring(s.length() - w + 1);
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    private static final String ESC = "\033[";

    public String bold(String s) { return wrap(s, "1m"); }
    public String dim(String s) { return wrap(s, "2m"); }
    public String red(String s) { return wrap(s, "31m"); }
    public String green(String s) { return wrap(s, "32m"); }
    public String yellow(String s) { return wrap(s, "33m"); }
    public String cyan(String s) { return wrap(s, "36m"); }

    private String wrap(String s, String code) {
        return color ? ESC + code + s + ESC + "0m" : s;
    }
}
