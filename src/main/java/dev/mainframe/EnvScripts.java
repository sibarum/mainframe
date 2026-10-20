package dev.mainframe;

import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A workspace's environment: PowerShell scripts that a tab's shell dot-sources as it starts, the workspace's first
 * and then the tab's own, so what the tab's sets wins. Where they are kept is {@link WorkspaceStore}'s.
 *
 * <h2>The generated region</h2>
 *
 * <p>Each script has a region MainFrame writes, between {@link #REGION_START} and {@link #REGION_END}: the
 * workspace's name, the tab's name and folder, and where the tab scripts are, as {@code $env:MAINFRAME_*}. It is
 * brought up to date whenever the script is about to be sourced or edited, so renaming a workspace or a tab reaches
 * it the next time either happens. Everything outside it is the user's and is kept byte for byte: the file is spliced
 * as bytes, not read as text and written back, so an encoding this does not know survives too. Only a file that
 * starts with a UTF-16 byte-order mark (what Windows PowerShell's {@code Out-File} writes) is read as text, in UTF-16.
 * A new script is UTF-8 with a byte-order mark, which Windows PowerShell needs to read anything but ASCII as UTF-8.
 *
 * <h2>The command line</h2>
 *
 * <p>The scripts are run by adding {@code -NoExit -Command ". 'a'; . 'b'"} to the shell's command line ({@link
 * #command}), which only PowerShell understands, and only when the line has no command or file of its own: anything
 * else is refused ({@link #refusal}) and the tab starts without them. They run after the profile, under the user's own
 * execution policy, and an error in one is PowerShell's to print in the tab.
 */
final class EnvScripts {

    private static final Log LOG = Log.of("mainframe.env");

    static final String REGION_START = "# region MainFrame (generated; edits here are replaced)";
    static final String REGION_END = "# endregion";
    /** What marks a region start, however the rest of its line was edited. */
    private static final String START_MARK = "# region MainFrame";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** PowerShell's switches that take no value, as the command line may already carry them. */
    private static final Set<String> SWITCHES = Set.of("nologo", "noprofile", "noninteractive", "noexit", "mta", "sta",
            "interactive", "i", "login", "l");
    /** Its parameters that take one value. Anything else (a command, a file, a script) is the line's own business. */
    private static final Set<String> VALUED = Set.of("executionpolicy", "ep", "ex", "windowstyle", "w",
            "workingdirectory", "wd", "configurationname", "config", "settingsfile", "settings", "outputformat", "of",
            "o", "inputformat", "if", "version", "v", "psconsolefile");

    private EnvScripts() {
    }

    // -- what a tab sources -----------------------------------------------------------------------------------

    /**
     * The scripts a tab of workspace {@code workspace} (null for none) sources, in order, each brought up to date:
     * the workspace's, then the one {@code entry} names (null for a tab that is no row of it). One that is not there
     * is skipped, and so is one that cannot be brought up to date, since its region may be a renamed workspace's.
     */
    static List<Path> forTab(WorkspaceStore store, String workspace, Workspace.Entry entry) {
        List<Path> out = new ArrayList<>();
        if (workspace == null) return out;
        Path ws = store.envFile(workspace);
        if (Files.isRegularFile(ws) && refreshed(ws, workspaceRegion(store, workspace))) out.add(ws);
        if (entry != null) {
            store.tabEnvFile(workspace, entry.env()).filter(Files::isRegularFile)
                    .filter(f -> refreshed(f, tabRegion(workspace, entry))).ifPresent(out::add);
        }
        return out;
    }

    private static boolean refreshed(Path file, List<String> region) {
        try {
            refresh(file, region);
            return true;
        } catch (IOException e) {
            LOG.warn("cannot bring {} up to date; it is not sourced", file, e);
            return false;
        }
    }

    // -- editing ----------------------------------------------------------------------------------------------

    /** Workspace {@code name}'s script, made if it is not there and brought up to date if it is. */
    static Path workspaceScript(WorkspaceStore store, String name) throws IOException {
        Path f = store.envFile(name);
        ensure(f, workspaceRegion(store, name), List.of(
                "# Every tab of workspace " + oneLine(name) + " dot-sources this as its shell starts, before the",
                "# tab's own script, so a tab can override what is set here. Your lines go below the region;",
                "# MainFrame rewrites only the region."));
        return f;
    }

    /** The script of {@code entry}, a tab of workspace {@code name}; made if need be, and brought up to date. */
    static Path tabScript(WorkspaceStore store, String name, Workspace.Entry entry) throws IOException {
        Path f = store.tabEnvFile(name, entry.env())
                .orElseThrow(() -> new IOException("the tab " + entry.title() + " has no script name"));
        ensure(f, tabRegion(name, entry), List.of(
                "# Tab " + oneLine(entry.title()) + " of workspace " + oneLine(name) + " dot-sources this as its shell",
                "# starts, after the workspace's script, so what is set here wins. Your lines go below the region;",
                "# MainFrame rewrites only the region."));
        return f;
    }

    static List<String> workspaceRegion(WorkspaceStore store, String name) {
        return List.of(
                set("MAINFRAME_WORKSPACE", name),
                set("MAINFRAME_WORKSPACE_ENV_DIR", store.envDir(name).toAbsolutePath().toString()));
    }

    static List<String> tabRegion(String name, Workspace.Entry entry) {
        return List.of(
                set("MAINFRAME_WORKSPACE", name),
                set("MAINFRAME_TAB", entry.title()),
                set("MAINFRAME_TAB_FOLDER", entry.path().strip()));
    }

    private static String set(String variable, String value) {
        return "$env:" + variable + " = " + quote(oneLine(value));
    }

    /** A value on one line: a line break in a tab's name would otherwise end the region early. */
    private static String oneLine(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(InsertPath.hasControl(Character.toString(cp)) ? ' ' : cp));
        return out.toString();
    }

    // -- the file ---------------------------------------------------------------------------------------------

    /** Makes {@code file} with the region and {@code comment} under it, or brings the region of the one there up to date. */
    static void ensure(Path file, List<String> region, List<String> comment) throws IOException {
        if (Files.exists(file)) {
            refresh(file, region);
            return;
        }
        Files.createDirectories(file.getParent());
        write(file, fresh(region, comment));
    }

    /** Rewrites {@code file}'s region, when that changes anything. */
    static void refresh(Path file, List<String> region) throws IOException {
        byte[] before = Files.readAllBytes(file);
        byte[] after = rewrite(before, region);
        if (!java.util.Arrays.equals(before, after)) write(file, after);
    }

    /** A new script: the region, then {@code comment}, in UTF-8 with a byte-order mark and Windows line ends. */
    static byte[] fresh(List<String> region, List<String> comment) {
        StringBuilder text = new StringBuilder(regionText(region, "\r\n")).append("\r\n\r\n");
        for (String line : comment) text.append(line).append("\r\n");
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    /**
     * {@code script} with its region replaced by one holding {@code region}'s lines; a script with none gets one at
     * the top. Everything else is kept as it was, byte for byte, and the region takes the script's own line ends.
     */
    static byte[] rewrite(byte[] script, List<String> region) {
        Charset utf16 = utf16(script);
        if (utf16 != null) {
            String text = new String(script, 2, script.length - 2, utf16);
            byte[] bom = {script[0], script[1]};
            byte[] body = splice(text, regionText(region, lineEnd(text)), 0).getBytes(utf16);
            byte[] out = new byte[2 + body.length];
            System.arraycopy(bom, 0, out, 0, 2);
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        // One char per byte, so the user's part goes back out exactly as it came in; the region is UTF-8 in it.
        String bytes = new String(script, StandardCharsets.ISO_8859_1);
        String regionBytes = new String(regionText(region, lineEnd(bytes)).getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        int skip = startsWith(script, UTF8_BOM) ? UTF8_BOM.length : 0;
        return splice(bytes, regionBytes, skip).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@code text} with its region replaced by {@code region}, or with {@code region} put in at {@code top}. */
    private static String splice(String text, String region, int top) {
        int start = -1;
        int lineStart = top;
        while (lineStart <= text.length()) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            int contentEnd = lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            String line = text.substring(lineStart, contentEnd).strip();
            if (start < 0 && line.startsWith(START_MARK)) {
                start = lineStart;
            } else if (start >= 0 && line.toLowerCase(Locale.ROOT).startsWith(REGION_END)) {
                return text.substring(0, start) + region + text.substring(contentEnd);
            }
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        // No region, or one with no end: a new one on top, which leaves an unfinished one as the user's text.
        String eol = lineEnd(text);
        return text.substring(0, top) + region + eol + (text.length() > top ? eol : "") + text.substring(top);
    }

    private static String regionText(List<String> region, String eol) {
        StringBuilder out = new StringBuilder(REGION_START).append(eol);
        for (String line : region) out.append(line).append(eol);
        return out.append(REGION_END).toString();
    }

    /** The script's own line end: Windows' unless it uses bare line feeds only. */
    private static String lineEnd(String text) {
        return text.contains("\n") && !text.contains("\r\n") ? "\n" : "\r\n";
    }

    private static Charset utf16(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return StandardCharsets.UTF_16LE;
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return StandardCharsets.UTF_16BE;
        return null;
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) return false;
        }
        return true;
    }

    /** Whole: to a sibling first, then moved into place, so a shell starting meanwhile never sources half of it. */
    private static void write(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // -- the command line -------------------------------------------------------------------------------------

    /**
     * Why {@code shellLine} cannot source scripts, or null when it can: it must start PowerShell ({@code powershell}
     * or {@code pwsh}) with nothing but switches, so adding a command of MainFrame's leaves its meaning alone.
     */
    static String refusal(String shellLine) {
        List<String> tokens = tokens(shellLine);
        if (tokens.isEmpty()) return "there is no shell";
        String exe = Path.of(tokens.getFirst().replace('/', '\\')).getFileName().toString().toLowerCase(Locale.ROOT);
        if (exe.endsWith(".exe")) exe = exe.substring(0, exe.length() - 4);
        if (!exe.equals("powershell") && !exe.equals("pwsh")) return tokens.getFirst() + " is not PowerShell";
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String p = t.length() > 1 && (t.charAt(0) == '-' || t.charAt(0) == '/')
                    ? t.substring(1).toLowerCase(Locale.ROOT) : null;
            if (p != null && SWITCHES.contains(p)) continue;
            if (p != null && VALUED.contains(p) && i + 1 < tokens.size()) {
                i++;
                continue;
            }
            return "its command line has " + t + ", which MainFrame does not add to";
        }
        return null;
    }

    /**
     * {@code shellLine} made to dot-source {@code scripts} in order and stay open, or {@code shellLine} itself when
     * there are none. Only for a line {@link #refusal} accepts. Each path is in single quotes, inside which PowerShell
     * expands nothing; a quote in one is doubled, the curly ones too, since PowerShell takes those as quotes as well.
     * The whole command is one double-quoted argument, which a Windows path cannot break out of.
     */
    static String command(String shellLine, List<Path> scripts) {
        if (scripts.isEmpty()) return shellLine;
        StringBuilder body = new StringBuilder();
        for (Path p : scripts) {
            String s = p.toAbsolutePath().toString();
            if (s.indexOf('"') >= 0 || InsertPath.hasControl(s)) throw new IllegalArgumentException("not a path: " + s);
            if (!body.isEmpty()) body.append("; ");
            body.append(". ").append(quote(s));
        }
        boolean noExit = tokens(shellLine).stream().anyMatch(t -> t.equalsIgnoreCase("-NoExit") || t.equalsIgnoreCase("/NoExit"));
        return shellLine.strip() + (noExit ? "" : " -NoExit") + " -Command \"" + body + "\"";
    }

    /** {@code s} as a PowerShell single-quoted string. */
    static String quote(String s) {
        return "'" + s.replaceAll("['‘’‚‛]", "$0$0") + "'";
    }

    /** A Windows command line's arguments, as far as this needs them: split on spaces, double quotes kept together. */
    private static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (any) out.add(t.toString());
                t.setLength(0);
                any = false;
            } else {
                t.append(c);
                any = true;
            }
        }
        if (any) out.add(t.toString());
        return out;
    }
}
