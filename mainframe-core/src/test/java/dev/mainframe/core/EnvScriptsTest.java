package dev.mainframe.core;

import dev.mainframe.core.Workspace.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The environment scripts: the region MainFrame rewrites, everything else left alone, and the shell's command line. */
class EnvScriptsTest {

    private static final EnvScripts.Dialect PS = EnvScripts.Dialect.POWERSHELL;
    private static final EnvScripts.Dialect SH = EnvScripts.Dialect.SH;
    private static final Path NO_LAUNCHERS = Path.of("unused");

    private static final List<String> REGION = List.of("$env:MAINFRAME_WORKSPACE = 'day'");

    private static String rewrite(String text) {
        return new String(EnvScripts.rewrite(text.getBytes(StandardCharsets.UTF_8), REGION, PS), StandardCharsets.UTF_8);
    }

    @Test
    void theRegionIsReplacedAndEverythingElseKept() {
        String before = "# mine, above\r\n"
                + "# region MainFrame (generated; edits here are replaced)\r\n"
                + "$env:MAINFRAME_WORKSPACE = 'old name'\r\n"
                + "$env:SOMETHING_I_TYPED_IN_IT = 1\r\n"
                + "# endregion\r\n"
                + "\r\n"
                + "$env:FOO = 'bar'   \t# trailing space kept\r\n";
        String after = rewrite(before);
        assertEquals("# mine, above\r\n"
                + "# region MainFrame (generated; edits here are replaced)\r\n"
                + "$env:MAINFRAME_WORKSPACE = 'day'\r\n"
                + "# endregion\r\n"
                + "\r\n"
                + "$env:FOO = 'bar'   \t# trailing space kept\r\n", after);
        assertEquals(after, rewrite(after), "a second rewrite changes nothing");
    }

    @Test
    void aScriptWithNoRegionGetsOneOnTop() {
        assertEquals(EnvScripts.REGION_START + "\r\n$env:MAINFRAME_WORKSPACE = 'day'\r\n" + EnvScripts.REGION_END
                + "\r\n\r\n$env:FOO = 1\r\n", rewrite("$env:FOO = 1\r\n"));
        // Bare line feeds are the script's choice, and the region takes them.
        assertEquals(EnvScripts.REGION_START + "\n$env:MAINFRAME_WORKSPACE = 'day'\n" + EnvScripts.REGION_END
                + "\n\n$env:FOO = 1\n", rewrite("$env:FOO = 1\n"));
    }

    @Test
    void anUnfinishedRegionIsLeftAsTheUsersText() {
        String broken = "# region MainFrame (generated; edits here are replaced)\r\n$env:X = 1\r\n";
        String after = rewrite(broken);
        assertTrue(after.endsWith("\r\n\r\n" + broken), after);
        assertEquals(after, rewrite(after));
    }

    @Test
    void bytesOutsideTheRegionAreKeptExactly() {
        // A byte-order mark, a Windows-1252 é that is not UTF-8, and no line end at the last line.
        byte[] head = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] mine = {'#', ' ', 'c', 'a', 'f', (byte) 0xE9, '\r', '\n'};
        byte[] region = (EnvScripts.REGION_START + "\r\n# endregion").getBytes(StandardCharsets.UTF_8);
        byte[] tail = {'\r', '\n', '$', 'x', ' ', '=', ' ', (byte) 0xE9};
        byte[] script = concat(head, mine, region, tail);
        byte[] after = EnvScripts.rewrite(script, List.of("$env:MAINFRAME_TAB = 'café'"), PS);
        byte[] expected = concat(head, mine, (EnvScripts.REGION_START + "\r\n$env:MAINFRAME_TAB = 'café'\r\n"
                + EnvScripts.REGION_END).getBytes(StandardCharsets.UTF_8), tail);
        assertArrayEquals(expected, after);
    }

    @Test
    void aUtf16ScriptStaysUtf16() {
        String text = "$env:FOO = 'ü'\r\n";
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] after = EnvScripts.rewrite(concat(bom, text.getBytes(StandardCharsets.UTF_16LE)), REGION, PS);
        assertEquals((byte) 0xFF, after[0]);
        String back = new String(after, 2, after.length - 2, StandardCharsets.UTF_16LE);
        assertTrue(back.startsWith(EnvScripts.REGION_START) && back.endsWith("\r\n\r\n" + text), back);
    }

    @Test
    void aNewScriptIsTheRegionAndAComment(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Entry api = new Entry("api", "C:\\work\\api").withEnv("api-000001.ps1");
        Path f = EnvScripts.tabScript(store, "day", api, PS);
        assertEquals(dir.resolve("day.env").resolve("api-000001.ps1"), f);
        byte[] bytes = Files.readAllBytes(f);
        assertEquals((byte) 0xEF, bytes[0], "UTF-8 with a byte-order mark, which Windows PowerShell needs");
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(EnvScripts.REGION_START + "\r\n$env:MAINFRAME_WORKSPACE = 'day'\r\n"
                + "$env:MAINFRAME_TAB = 'api'\r\n$env:MAINFRAME_TAB_FOLDER = 'C:\\work\\api'\r\n# endregion\r\n"), text);
        assertTrue(text.contains("# Tab api of workspace day"));

        // The user writes below; a rename of the tab reaches the region and nothing else.
        Files.writeString(f, text + "$env:FOO = 'mine'\r\n");
        EnvScripts.tabScript(store, "day", api.withName("API"), PS);
        String now = Files.readString(f);
        assertTrue(now.contains("$env:MAINFRAME_TAB = 'API'") && now.endsWith("$env:FOO = 'mine'\r\n"), now);
        assertTrue(now.contains("# Tab api of workspace day"), "the comment is the user's now, and stays");
    }

    @Test
    void aTabSourcesWhatThereIsWorkspaceFirst(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Entry api = new Entry("api", "C:\\api").withEnv("api-000001.ps1");
        assertEquals(List.of(), EnvScripts.forTab(store, "day", api, PS), "nothing made yet");
        Path tab = EnvScripts.tabScript(store, "day", api, PS);
        assertEquals(List.of(tab), EnvScripts.forTab(store, "day", api, PS));
        Path ws = EnvScripts.workspaceScript(store, "day", PS);
        assertEquals(List.of(ws, tab), EnvScripts.forTab(store, "day", api, PS));
        assertEquals(List.of(ws), EnvScripts.forTab(store, "day", null, PS), "a + tab in the workspace's window");
        assertEquals(List.of(), EnvScripts.forTab(store, null, null, PS), "a plain tab");
    }

    @Test
    void aValueWithALineBreakCannotEndTheRegion() {
        String line = EnvScripts.tabRegion("day", new Entry("x\r\n# endregion\r\nRemove-Item", "C:\\a"), PS).get(1);
        assertEquals("$env:MAINFRAME_TAB = 'x  # endregion  Remove-Item'", line);
    }

    // -- the command line -------------------------------------------------------------------------------------

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows paths (C:\\...) are only absolute on Windows")
    void powerShellSourcesTheScriptsInOrderAndStaysOpen() throws Exception {
        List<Path> scripts = List.of(Path.of("C:\\Users\\me\\.mainframe\\workspaces\\day.env.ps1"),
                Path.of("C:\\Users\\me\\.mainframe\\workspaces\\day.env\\api-000001.ps1"));
        assertEquals("powershell.exe -NoLogo -NoExit -Command \". 'C:\\Users\\me\\.mainframe\\workspaces\\day.env.ps1';"
                        + " . 'C:\\Users\\me\\.mainframe\\workspaces\\day.env\\api-000001.ps1'\"",
                EnvScripts.command("powershell.exe -NoLogo", scripts, NO_LAUNCHERS, PS));
        assertEquals("pwsh.exe -NoLogo -NoExit -Command \". 'C:\\a.ps1'\"",
                EnvScripts.command("pwsh.exe -NoLogo", List.of(Path.of("C:\\a.ps1")), NO_LAUNCHERS, PS));
        assertEquals("pwsh -NoExit -Command \". 'C:\\a.ps1'\"",
                EnvScripts.command("pwsh -NoExit", List.of(Path.of("C:\\a.ps1")), NO_LAUNCHERS, PS), "-NoExit is not given twice");
        assertEquals("powershell.exe -NoLogo", EnvScripts.command("powershell.exe -NoLogo", List.of(), NO_LAUNCHERS, PS));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows paths (C:\\...) are only absolute on Windows")
    void quotesInAPathAreDoubledTheCurlyOnesToo() throws Exception {
        assertEquals("'C:\\it''s'", EnvScripts.quote("C:\\it's"));
        assertEquals("'C:\\it\u2019\u2019s \u2018\u2018x\u2018\u2018 $HOME `n'", EnvScripts.quote("C:\\it\u2019s \u2018x\u2018 $HOME `n"));
        assertEquals("pwsh -NoExit -Command \". 'C:\\Bob''s\\a.ps1'\"",
                EnvScripts.command("pwsh", List.of(Path.of("C:\\Bob's\\a.ps1")), NO_LAUNCHERS, PS));
    }

    @Test
    void onlyAPowerShellWithNoCommandOfItsOwnIsAddedTo() {
        assertNull(EnvScripts.refusal("powershell.exe -NoLogo", PS));
        assertNull(EnvScripts.refusal("pwsh.exe -NoLogo", PS));
        assertNull(EnvScripts.refusal("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -NoProfile -ExecutionPolicy Bypass", PS));
        assertNull(EnvScripts.refusal("C:/Windows/System32/WindowsPowerShell/v1.0/PowerShell.EXE", PS));
        assertNotNull(EnvScripts.refusal("cmd.exe", PS));
        assertNotNull(EnvScripts.refusal("bash -l", PS));
        assertNotNull(EnvScripts.refusal("pwsh -Command Get-Date", PS));
        assertNotNull(EnvScripts.refusal("pwsh -c Get-Date", PS));
        assertNotNull(EnvScripts.refusal("powershell -File x.ps1", PS));
        assertNotNull(EnvScripts.refusal("pwsh x.ps1", PS));
        assertNotNull(EnvScripts.refusal("pwsh -ExecutionPolicy", PS), "a parameter missing its value");
        assertNotNull(EnvScripts.refusal("", PS));
    }

    // -- sh, for bash and zsh ---------------------------------------------------------------------------------

    @Test
    void theShellSaysWhichScriptsItReads() {
        assertEquals(PS, EnvScripts.dialect("C:/Windows/System32/WindowsPowerShell/v1.0/PowerShell.EXE"));
        assertEquals(PS, EnvScripts.dialect("/usr/bin/pwsh -l"), "a Linux path is a path too");
        assertEquals(SH, EnvScripts.dialect("/bin/bash -l"));
        assertEquals(SH, EnvScripts.dialect("/usr/bin/zsh -l"));
        assertEquals(EnvScripts.Dialect.platform(), EnvScripts.dialect("/usr/bin/fish -l"), "neither: the platform's");
    }

    @Test
    void anShScriptHasNoByteOrderMarkAndLineFeeds(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Entry api = new Entry("api", "/home/me/it's").withEnv("api-000001.ps1");
        Path f = EnvScripts.tabScript(store, "day", api, SH);
        assertEquals(dir.resolve("day.env").resolve("api-000001.sh"), f, "the key's name, sh's ending");
        String text = Files.readString(f);
        assertTrue(text.startsWith(EnvScripts.REGION_START + "\nexport MAINFRAME_WORKSPACE='day'\n"
                + "export MAINFRAME_TAB='api'\nexport MAINFRAME_TAB_FOLDER='/home/me/it'\\''s'\n# endregion\n"), text);
        assertTrue(!text.contains("\r") && text.charAt(0) == '#', text);
        assertEquals(dir.resolve("day.env.sh"), EnvScripts.workspaceScript(store, "day", SH));
        assertEquals(List.of(dir.resolve("day.env.sh"), f), EnvScripts.forTab(store, "day", api, SH));
        assertEquals(List.of(), EnvScripts.forTab(store, "day", api, PS), "PowerShell's are other files");
    }

    @Test
    void onlyABashOrZshWithNoCommandOfItsOwnIsAddedTo() {
        assertNull(EnvScripts.refusal("/bin/bash -l", SH));
        assertNull(EnvScripts.refusal("/usr/bin/zsh --login -i", SH));
        assertNull(EnvScripts.refusal("bash -il", SH));
        assertNull(EnvScripts.refusal("/usr/bin/pwsh -l", SH), "PowerShell on Linux");
        assertNotNull(EnvScripts.refusal("bash -c ls", SH));
        assertNotNull(EnvScripts.refusal("bash script.sh", SH));
        assertNotNull(EnvScripts.refusal("bash --norc", SH));
        assertNotNull(EnvScripts.refusal("/usr/bin/fish -l", SH));
        assertNotNull(EnvScripts.refusal("bash -l", PS), "a Windows command line has no env to start it with");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "Linux paths (/...) are only absolute on Linux")
    void bashAndZshStartOnMainFramesFilesWithTheScriptsInAVariable(@TempDir Path dir) throws Exception {
        List<Path> scripts = List.of(Path.of("/w/day.env.sh"), Path.of("/w/it's.sh"));
        Path shell = dir.resolve("shell");
        String source = "env MAINFRAME_SOURCE='/w/day.env.sh\n/w/it'\\''s.sh'";
        assertEquals(source + " MAINFRAME_LOGIN=1 '/bin/bash' --rcfile '" + shell.resolve("bashrc") + "'",
                EnvScripts.command("/bin/bash -l", scripts, shell, SH));
        assertEquals(source + " '/bin/bash' --rcfile '" + shell.resolve("bashrc") + "' -i",
                EnvScripts.command("/bin/bash -i", scripts, shell, SH));
        assertEquals(source + " MAINFRAME_ZDOTDIR=\"${ZDOTDIR:-$HOME}\" ZDOTDIR='" + shell.resolve("zsh")
                + "' '/usr/bin/zsh' -l", EnvScripts.command("/usr/bin/zsh -l", scripts, shell, SH));
        assertEquals("/usr/bin/pwsh -l -NoExit -Command '. '\\''/w/it'\\'''\\''s.sh'\\'''",
                EnvScripts.command("/usr/bin/pwsh -l", List.of(Path.of("/w/it's.sh")), shell, SH),
                "PowerShell's command, in sh's quotes");
        for (String f : List.of("bashrc", "zsh/.zshenv", "zsh/.zprofile", "zsh/.zshrc", "zsh/.zlogin")) {
            assertTrue(Files.isRegularFile(shell.resolve(f)), f);
        }
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "runs bash")
    void bashReadsItsOwnFilesThenTheScripts(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".profile"), "ORDER=profile\n");
        Files.writeString(home.resolve(".bashrc"), "ORDER=bashrc\n");
        // A script that reads its input must not read the list of scripts, or the tab's would go unsourced.
        Path ws = dir.resolve("it's ws.sh");
        Files.writeString(ws, "ORDER=\"$ORDER ws\"\nread -r -t 1 LINE || true\n");
        Path tab = dir.resolve("tab.sh");
        Files.writeString(tab, "ORDER=\"$ORDER tab\"\n");
        EnvScripts.Launchers l = EnvScripts.launchers(dir.resolve("shell"));

        assertEquals("profile ws tab||", bash(home, l, ws + "\n" + tab, true));
        assertEquals("bashrc ws tab||", bash(home, l, ws + "\n" + tab, false));
    }

    /** {@code $ORDER} in a bash on MainFrame's rc file, then the variables it should have forgotten. */
    private static String bash(Path home, EnvScripts.Launchers l, String source, boolean login) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "--rcfile", l.bashrc().toString(), "-i", "-c",
                "printf '%s|%s|%s' \"$ORDER\" \"$MAINFRAME_SOURCE\" \"$MAINFRAME_LOGIN\"");
        pb.environment().put("HOME", home.toString());
        pb.environment().put("MAINFRAME_SOURCE", source);
        if (login) pb.environment().put("MAINFRAME_LOGIN", "1");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().write("a line of input\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
