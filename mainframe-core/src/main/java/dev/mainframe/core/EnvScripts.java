package dev.mainframe.core;

import dev.sibarum.suite.tty.PtyPlatform;
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
 * A workspace's environment: scripts that a tab's shell dot-sources as it starts, the workspace's first and then the
 * tab's own, so what the tab's sets wins. They are in the language of the shell new tabs start ({@link Dialect}):
 * PowerShell for {@code powershell} and {@code pwsh}, {@code .ps1}; sh for bash and zsh, {@code .sh}. A workspace can
 * have both, one for each, and a tab sources the ones its shell reads. Where they are kept is {@link
 * WorkspaceStore}'s.
 *
 * <h2>The generated region</h2>
 *
 * <p>Each script has a region MainFrame writes, between {@link #REGION_START} and {@link #REGION_END}: the
 * workspace's name, the tab's name and folder, and where the tab scripts are, as {@code MAINFRAME_*} environment
 * variables. It is brought up to date whenever the script is about to be sourced or edited, so renaming a workspace or
 * a tab reaches it the next time either happens. Everything outside it is the user's and is kept byte for byte: the
 * file is spliced as bytes, not read as text and written back, so an encoding this does not know survives too. Only a
 * file that starts with a UTF-16 byte-order mark (what Windows PowerShell's {@code Out-File} writes) is read as text,
 * in UTF-16. A new PowerShell script is UTF-8 with a byte-order mark, which Windows PowerShell needs to read anything
 * but ASCII as UTF-8, and Windows line ends; a new sh script is plain UTF-8 with line feeds, as sh needs.
 *
 * <h2>The command line</h2>
 *
 * <p>PowerShell runs the scripts by adding {@code -NoExit -Command ". 'a'; . 'b'"} to its command line. bash and zsh
 * cannot be told to source a file after their own startup files from the command line, so MainFrame starts them on
 * startup files of its own ({@link #launchers}), which read the user's as the shell would have and then source the
 * scripts named in {@code MAINFRAME_SOURCE}, one per line: bash with {@code --rcfile}, zsh with {@code ZDOTDIR}. Either
 * way only a line with no command or file of its own is added to: anything else is refused ({@link #refusal}) and the
 * tab starts without them. They run after the profile, and an error in one is the shell's to print in the tab.
 */
public final class EnvScripts {

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

    /** bash's and zsh's switches a line may carry: what makes it a login shell, and an interactive one. */
    private static final Set<String> SH_SWITCHES = Set.of("-l", "--login", "-i");

    /** The language of a workspace's scripts, which is the shell's that sources them. */
    public enum Dialect {
        POWERSHELL(".ps1"),
        SH(".sh");

        /** The end of a script's file name. */
        final String extension;

        Dialect(String extension) {
            this.extension = extension;
        }

        /**
         * What the platform's own shell reads, the one the pty's OS module offers first: PowerShell on Windows, sh
         * elsewhere. A platform whose shell is sh runs a terminal's command line through {@code /bin/sh}, too.
         */
        static Dialect platform() {
            return Platform.DIALECT;
        }
    }

    /** Lazily, so a process that never opens a terminal never asks the pty module. */
    private static final class Platform {
        static final Dialect DIALECT = isPowerShell(PtyPlatform.current().shells().getFirst().commandLine())
                ? Dialect.POWERSHELL : Dialect.SH;
    }

    private static boolean isPowerShell(String shellLine) {
        List<String> tokens = tokens(shellLine);
        String exe = tokens.isEmpty() ? "" : program(tokens.getFirst());
        return exe.equals("powershell") || exe.equals("pwsh");
    }

    private EnvScripts() {
    }

    /**
     * The scripts {@code shellLine} would source: PowerShell's for PowerShell, sh's for bash and zsh, and for any
     * other shell the platform's ({@link Dialect#platform}), so that the scripts a shell cannot source are the ones
     * the tab says did not run.
     */
    public static Dialect dialect(String shellLine) {
        List<String> tokens = tokens(shellLine);
        String exe = tokens.isEmpty() ? "" : program(tokens.getFirst());
        return switch (exe) {
            case "powershell", "pwsh" -> Dialect.POWERSHELL;
            case "bash", "zsh" -> Dialect.SH;
            default -> Dialect.platform();
        };
    }

    /** The program's name, as {@code token} names it in either kind of path: no folder, no {@code .exe}, lower case. */
    private static String program(String token) {
        String exe = token.substring(Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return exe.endsWith(".exe") ? exe.substring(0, exe.length() - 4) : exe;
    }

    // -- what a tab sources -----------------------------------------------------------------------------------

    /**
     * The scripts a tab of workspace {@code workspace} (null for none) sources, in order, each brought up to date:
     * the workspace's, then the one {@code entry} names (null for a tab that is no row of it). One that is not there
     * is skipped, and so is one that cannot be brought up to date, since its region may be a renamed workspace's.
     * Only the scripts in {@code dialect} are looked for.
     */
    static List<Path> forTab(WorkspaceStore store, String workspace, Workspace.Entry entry, Dialect dialect) {
        List<Path> out = new ArrayList<>();
        if (workspace == null) return out;
        Path ws = store.envFile(workspace, dialect);
        if (Files.isRegularFile(ws) && refreshed(ws, workspaceRegion(store, workspace, dialect), dialect)) out.add(ws);
        if (entry != null) {
            store.tabEnvFile(workspace, entry.env(), dialect).filter(Files::isRegularFile)
                    .filter(f -> refreshed(f, tabRegion(workspace, entry, dialect), dialect)).ifPresent(out::add);
        }
        return out;
    }

    private static boolean refreshed(Path file, List<String> region, Dialect dialect) {
        try {
            refresh(file, region, dialect);
            return true;
        } catch (IOException e) {
            LOG.warn("cannot bring {} up to date; it is not sourced", file, e);
            return false;
        }
    }

    // -- editing ----------------------------------------------------------------------------------------------

    /** Workspace {@code name}'s script in {@code dialect}, made if it is not there and brought up to date if it is. */
    static Path workspaceScript(WorkspaceStore store, String name, Dialect dialect) throws IOException {
        Path f = store.envFile(name, dialect);
        ensure(f, workspaceRegion(store, name, dialect), dialect, List.of(
                "# Every tab of workspace " + oneLine(name) + " dot-sources this as its shell starts, before the",
                "# tab's own script, so a tab can override what is set here. Your lines go below the region;",
                "# MainFrame rewrites only the region."));
        return f;
    }

    /** The script of {@code entry}, a tab of workspace {@code name}, in {@code dialect}; made if need be, and brought up to date. */
    static Path tabScript(WorkspaceStore store, String name, Workspace.Entry entry, Dialect dialect) throws IOException {
        Path f = store.tabEnvFile(name, entry.env(), dialect)
                .orElseThrow(() -> new IOException("the tab " + entry.title() + " has no script name"));
        ensure(f, tabRegion(name, entry, dialect), dialect, List.of(
                "# Tab " + oneLine(entry.title()) + " of workspace " + oneLine(name) + " dot-sources this as its shell",
                "# starts, after the workspace's script, so what is set here wins. Your lines go below the region;",
                "# MainFrame rewrites only the region."));
        return f;
    }

    static List<String> workspaceRegion(WorkspaceStore store, String name, Dialect dialect) {
        return List.of(
                set(dialect, "MAINFRAME_WORKSPACE", name),
                set(dialect, "MAINFRAME_WORKSPACE_ENV_DIR", store.envDir(name).toAbsolutePath().toString()));
    }

    static List<String> tabRegion(String name, Workspace.Entry entry, Dialect dialect) {
        return List.of(
                set(dialect, "MAINFRAME_WORKSPACE", name),
                set(dialect, "MAINFRAME_TAB", entry.title()),
                set(dialect, "MAINFRAME_TAB_FOLDER", entry.path().strip()));
    }

    private static String set(Dialect dialect, String variable, String value) {
        return switch (dialect) {
            case POWERSHELL -> "$env:" + variable + " = " + quote(oneLine(value));
            case SH -> "export " + variable + "=" + shQuote(oneLine(value));
        };
    }

    /** A value on one line: a line break in a tab's name would otherwise end the region early. */
    private static String oneLine(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(InsertPath.hasControl(Character.toString(cp)) ? ' ' : cp));
        return out.toString();
    }

    // -- the file ---------------------------------------------------------------------------------------------

    /** Makes {@code file} with the region and {@code comment} under it, or brings the region of the one there up to date. */
    static void ensure(Path file, List<String> region, Dialect dialect, List<String> comment) throws IOException {
        if (Files.exists(file)) {
            refresh(file, region, dialect);
            return;
        }
        Files.createDirectories(file.getParent());
        write(file, fresh(region, dialect, comment));
    }

    /** Rewrites {@code file}'s region, when that changes anything. */
    static void refresh(Path file, List<String> region, Dialect dialect) throws IOException {
        byte[] before = Files.readAllBytes(file);
        byte[] after = rewrite(before, region, dialect);
        if (!java.util.Arrays.equals(before, after)) write(file, after);
    }

    /**
     * A new script: the region, then {@code comment}. PowerShell's is UTF-8 with a byte-order mark and Windows line
     * ends; sh's is UTF-8 with neither, since sh would take the mark for a command and a carriage return for text.
     */
    static byte[] fresh(List<String> region, Dialect dialect, List<String> comment) {
        String eol = defaultLineEnd(dialect);
        StringBuilder text = new StringBuilder(regionText(region, eol)).append(eol).append(eol);
        for (String line : comment) text.append(line).append(eol);
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        if (dialect == Dialect.SH) return body;
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    /**
     * {@code script} with its region replaced by one holding {@code region}'s lines; a script with none gets one at
     * the top. Everything else is kept as it was, byte for byte, and the region takes the script's own line ends, or
     * the dialect's in a script that has none yet.
     */
    static byte[] rewrite(byte[] script, List<String> region, Dialect dialect) {
        String dflt = defaultLineEnd(dialect);
        Charset utf16 = utf16(script);
        if (utf16 != null) {
            String text = new String(script, 2, script.length - 2, utf16);
            byte[] bom = {script[0], script[1]};
            byte[] body = splice(text, regionText(region, lineEnd(text, dflt)), 0, dflt).getBytes(utf16);
            byte[] out = new byte[2 + body.length];
            System.arraycopy(bom, 0, out, 0, 2);
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        // One char per byte, so the user's part goes back out exactly as it came in; the region is UTF-8 in it.
        String bytes = new String(script, StandardCharsets.ISO_8859_1);
        String regionBytes = new String(regionText(region, lineEnd(bytes, dflt)).getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        int skip = startsWith(script, UTF8_BOM) ? UTF8_BOM.length : 0;
        return splice(bytes, regionBytes, skip, dflt).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@code text} with its region replaced by {@code region}, or with {@code region} put in at {@code top}. */
    private static String splice(String text, String region, int top, String dflt) {
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
        String eol = lineEnd(text, dflt);
        return text.substring(0, top) + region + eol + (text.length() > top ? eol : "") + text.substring(top);
    }

    private static String regionText(List<String> region, String eol) {
        StringBuilder out = new StringBuilder(REGION_START).append(eol);
        for (String line : region) out.append(line).append(eol);
        return out.append(REGION_END).toString();
    }

    /** The script's own line end: a line feed when it uses bare ones only, Windows' when it uses those, else {@code dflt}. */
    private static String lineEnd(String text, String dflt) {
        if (text.contains("\r\n")) return "\r\n";
        return text.contains("\n") ? "\n" : dflt;
    }

    private static String defaultLineEnd(Dialect dialect) {
        return dialect == Dialect.SH ? "\n" : "\r\n";
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
     * or {@code pwsh}), or on Linux bash or zsh, with nothing but switches, so adding MainFrame's to it leaves its
     * meaning alone.
     */
    static String refusal(String shellLine) {
        return refusal(shellLine, Dialect.platform());
    }

    /** As {@link #refusal(String)}, on a platform whose own shell reads {@code platform}; for the tests, which run on either. */
    static String refusal(String shellLine, Dialect platform) {
        List<String> tokens = tokens(shellLine);
        if (tokens.isEmpty()) return "there is no shell";
        String exe = program(tokens.getFirst());
        if (exe.equals("bash") || exe.equals("zsh")) {
            // Its startup files are given by variables on a /bin/sh line, which a Windows command line is not.
            if (platform != Dialect.SH) return tokens.getFirst() + " sources workspace scripts only on Linux";
            for (String t : tokens.subList(1, tokens.size())) {
                if (!shSwitch(t)) return "its command line has " + t + ", which MainFrame does not add to";
            }
            return null;
        }
        if (!exe.equals("powershell") && !exe.equals("pwsh")) return tokens.getFirst() + " is not bash, zsh or PowerShell";
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

    /** One of {@code -l}, {@code -i} (alone or together, as {@code -il}) or {@code --login}. */
    private static boolean shSwitch(String t) {
        return t.equals("--login") || t.matches("-[il]+");
    }

    /**
     * {@code shellLine} made to source {@code scripts} in order and stay open, or {@code shellLine} itself when there
     * are none. Only for a line {@link #refusal} accepts. A bash or zsh line is started on the startup files in
     * {@code launchers}, which are written there first ({@link #launchers}).
     */
    static String command(String shellLine, List<Path> scripts, Path launchers) throws IOException {
        return command(shellLine, scripts, launchers, Dialect.platform());
    }

    /** As {@link #command(String, List, Path)}, on a platform whose own shell reads {@code platform}; for the tests. */
    static String command(String shellLine, List<Path> scripts, Path launchers, Dialect platform) throws IOException {
        if (scripts.isEmpty()) return shellLine;
        List<String> tokens = tokens(shellLine);
        String exe = program(tokens.getFirst());
        if (exe.equals("bash") || exe.equals("zsh")) return shCommand(tokens, scripts, launchers(launchers));
        return powerShellCommand(shellLine, scripts, platform == Dialect.POWERSHELL);
    }

    /**
     * PowerShell's line: {@code -NoExit -Command} and the scripts. Each path is in single quotes, inside which
     * PowerShell expands nothing; a quote in one is doubled, the curly ones too, since PowerShell takes those as
     * quotes as well. The whole command is one argument: double-quoted on Windows, which a Windows path cannot break
     * out of, and in sh's single quotes where the line is a {@code /bin/sh} one.
     */
    private static String powerShellCommand(String shellLine, List<Path> scripts, boolean windows) {
        StringBuilder body = new StringBuilder();
        for (Path p : scripts) {
            String s = p.toAbsolutePath().toString();
            if ((windows && s.indexOf('"') >= 0) || InsertPath.hasControl(s)) throw new IllegalArgumentException("not a path: " + s);
            if (!body.isEmpty()) body.append("; ");
            body.append(". ").append(quote(s));
        }
        boolean noExit = tokens(shellLine).stream().anyMatch(t -> t.equalsIgnoreCase("-NoExit") || t.equalsIgnoreCase("/NoExit"));
        String command = windows ? "\"" + body + "\"" : shQuote(body.toString());
        return shellLine.strip() + (noExit ? "" : " -NoExit") + " -Command " + command;
    }

    /**
     * A bash or zsh line, by way of {@code env}: the scripts in {@code MAINFRAME_SOURCE}, one per line, and the shell
     * on MainFrame's startup files. bash reads no rc file as a login shell, so its {@code -l} becomes {@code
     * MAINFRAME_LOGIN}, on which the rc file reads the profile instead. zsh keeps its switches; where the user's own
     * files are goes in {@code MAINFRAME_ZDOTDIR}, worked out by the {@code /bin/sh} that runs the line.
     */
    private static String shCommand(List<String> tokens, List<Path> scripts, Launchers launchers) {
        StringBuilder source = new StringBuilder();
        for (Path p : scripts) {
            String s = p.toAbsolutePath().toString();
            if (InsertPath.hasControl(s)) throw new IllegalArgumentException("not a path: " + s);
            if (!source.isEmpty()) source.append('\n');
            source.append(s);
        }
        List<String> switches = tokens.subList(1, tokens.size());
        StringBuilder out = new StringBuilder("env MAINFRAME_SOURCE=").append(shQuote(source.toString()));
        if (program(tokens.getFirst()).equals("bash")) {
            boolean login = switches.stream().anyMatch(t -> t.equals("--login") || (t.startsWith("-") && !t.startsWith("--") && t.contains("l")));
            boolean interactive = switches.stream().anyMatch(t -> !t.startsWith("--") && t.contains("i"));
            if (login) out.append(" MAINFRAME_LOGIN=1");
            // Long options go before bash's one-letter ones.
            out.append(' ').append(shQuote(tokens.getFirst())).append(" --rcfile ").append(shQuote(launchers.bashrc().toString()));
            if (interactive) out.append(" -i");
        } else {
            out.append(" MAINFRAME_ZDOTDIR=\"${ZDOTDIR:-$HOME}\" ZDOTDIR=").append(shQuote(launchers.zdotdir().toString()))
                    .append(' ').append(shQuote(tokens.getFirst()));
            for (String t : switches) out.append(' ').append(t);
        }
        return out.toString();
    }

    /** {@code s} as a PowerShell single-quoted string. */
    static String quote(String s) {
        return "'" + s.replaceAll("['‘’‚‛]", "$0$0") + "'";
    }

    /** {@code s} as an sh single-quoted string, inside which nothing is special but the quote itself. */
    static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // -- bash's and zsh's startup files -----------------------------------------------------------------------

    /** Where MainFrame's startup files are: bash's rc file, and the folder zsh is given as its {@code ZDOTDIR}. */
    record Launchers(Path bashrc, Path zdotdir) { }

    /** Sources the scripts {@code MAINFRAME_SOURCE} names, one per line, in this shell, and forgets it. */
    private static final String SOURCE = """
            __mainframe_source=$MAINFRAME_SOURCE
            unset MAINFRAME_SOURCE
            # On a descriptor of its own, so a script that reads its input reads the terminal, not this list.
            while IFS= read -r __mainframe_script <&9; do
                [ -n "$__mainframe_script" ] && . "$__mainframe_script"
            done 9<<__MAINFRAME__
            $__mainframe_source
            __MAINFRAME__
            unset __mainframe_source __mainframe_script
            """;

    private static final String HEADER = """
            # MainFrame starts %s with this, so that a workspace's tab sources its environment scripts after the
            # shell's own startup files, which this reads as the shell would have. MainFrame rewrites it as each
            # such tab opens: edits here are lost.
            """;

    /** bash's rc file: the profile for a login shell, as bash reads it, and else {@code ~/.bashrc}; then the scripts. */
    private static final String BASHRC = HEADER.formatted("bash") + """
            if [ -n "${MAINFRAME_LOGIN-}" ]; then
                unset MAINFRAME_LOGIN
                [ -r /etc/profile ] && . /etc/profile
                if [ -r ~/.bash_profile ]; then . ~/.bash_profile
                elif [ -r ~/.bash_login ]; then . ~/.bash_login
                elif [ -r ~/.profile ]; then . ~/.profile
                fi
            else
                [ -r ~/.bashrc ] && . ~/.bashrc
            fi
            """ + SOURCE;

    /**
     * zsh's: each of its startup files reads the user's of that name, with {@code ZDOTDIR} the user's while it does
     * and then MainFrame's again, so that zsh goes on to the next of these. The last one zsh reads, {@code .zlogin}
     * for a login shell and {@code .zshrc} otherwise, leaves {@code ZDOTDIR} the user's and sources the scripts.
     */
    private static String zsh(String name, String after) {
        return HEADER.formatted("zsh") + """
                ZDOTDIR=$MAINFRAME_ZDOTDIR
                [[ -r $ZDOTDIR/%s ]] && . $ZDOTDIR/%s
                """.formatted(name, name) + after;
    }

    private static final String ZSH_DONE = "unset MAINFRAME_ZDOTDIR __mainframe_dir\n" + SOURCE;
    private static final String ZSH_NEXT = "ZDOTDIR=$__mainframe_dir\n";

    private static final List<String[]> ZSH = List.of(
            // zsh found this one in MainFrame's folder; the user's .zshenv may move theirs, and is let to.
            new String[]{".zshenv", HEADER.formatted("zsh") + """
                    __mainframe_dir=$ZDOTDIR
                    ZDOTDIR=$MAINFRAME_ZDOTDIR
                    [[ -r $ZDOTDIR/.zshenv ]] && . $ZDOTDIR/.zshenv
                    MAINFRAME_ZDOTDIR=$ZDOTDIR
                    """ + ZSH_NEXT},
            new String[]{".zprofile", zsh(".zprofile", ZSH_NEXT)},
            new String[]{".zshrc", zsh(".zshrc", "if [[ -o login ]]; then\n    " + ZSH_NEXT + "else\n" + ZSH_DONE + "fi\n")},
            new String[]{".zlogin", zsh(".zlogin", ZSH_DONE)});

    /** Writes the startup files into {@code dir}, where they are not already as they should be, and says where. */
    static Launchers launchers(Path dir) throws IOException {
        Path bashrc = dir.resolve("bashrc");
        Path zdotdir = dir.resolve("zsh");
        Files.createDirectories(zdotdir);
        keep(bashrc, BASHRC);
        for (String[] f : ZSH) keep(zdotdir.resolve(f[0]), f[1]);
        return new Launchers(bashrc, zdotdir);
    }

    private static void keep(Path file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), bytes)) return;
        write(file, bytes);
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
