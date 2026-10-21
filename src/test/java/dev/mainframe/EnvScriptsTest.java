package dev.mainframe;

import dev.mainframe.Workspace.Entry;
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

    private static final List<String> REGION = List.of("$env:MAINFRAME_WORKSPACE = 'day'");

    private static String rewrite(String text) {
        return new String(EnvScripts.rewrite(text.getBytes(StandardCharsets.UTF_8), REGION), StandardCharsets.UTF_8);
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
        byte[] after = EnvScripts.rewrite(script, List.of("$env:MAINFRAME_TAB = 'café'"));
        byte[] expected = concat(head, mine, (EnvScripts.REGION_START + "\r\n$env:MAINFRAME_TAB = 'café'\r\n"
                + EnvScripts.REGION_END).getBytes(StandardCharsets.UTF_8), tail);
        assertArrayEquals(expected, after);
    }

    @Test
    void aUtf16ScriptStaysUtf16() {
        String text = "$env:FOO = 'ü'\r\n";
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] after = EnvScripts.rewrite(concat(bom, text.getBytes(StandardCharsets.UTF_16LE)), REGION);
        assertEquals((byte) 0xFF, after[0]);
        String back = new String(after, 2, after.length - 2, StandardCharsets.UTF_16LE);
        assertTrue(back.startsWith(EnvScripts.REGION_START) && back.endsWith("\r\n\r\n" + text), back);
    }

    @Test
    void aNewScriptIsTheRegionAndAComment(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Entry api = new Entry("api", "C:\\work\\api").withEnv("api-000001.ps1");
        Path f = EnvScripts.tabScript(store, "day", api);
        assertEquals(dir.resolve("day.env").resolve("api-000001.ps1"), f);
        byte[] bytes = Files.readAllBytes(f);
        assertEquals((byte) 0xEF, bytes[0], "UTF-8 with a byte-order mark, which Windows PowerShell needs");
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(EnvScripts.REGION_START + "\r\n$env:MAINFRAME_WORKSPACE = 'day'\r\n"
                + "$env:MAINFRAME_TAB = 'api'\r\n$env:MAINFRAME_TAB_FOLDER = 'C:\\work\\api'\r\n# endregion\r\n"), text);
        assertTrue(text.contains("# Tab api of workspace day"));

        // The user writes below; a rename of the tab reaches the region and nothing else.
        Files.writeString(f, text + "$env:FOO = 'mine'\r\n");
        EnvScripts.tabScript(store, "day", api.withName("API"));
        String now = Files.readString(f);
        assertTrue(now.contains("$env:MAINFRAME_TAB = 'API'") && now.endsWith("$env:FOO = 'mine'\r\n"), now);
        assertTrue(now.contains("# Tab api of workspace day"), "the comment is the user's now, and stays");
    }

    @Test
    void aTabSourcesWhatThereIsWorkspaceFirst(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Entry api = new Entry("api", "C:\\api").withEnv("api-000001.ps1");
        assertEquals(List.of(), EnvScripts.forTab(store, "day", api), "nothing made yet");
        Path tab = EnvScripts.tabScript(store, "day", api);
        assertEquals(List.of(tab), EnvScripts.forTab(store, "day", api));
        Path ws = EnvScripts.workspaceScript(store, "day");
        assertEquals(List.of(ws, tab), EnvScripts.forTab(store, "day", api));
        assertEquals(List.of(ws), EnvScripts.forTab(store, "day", null), "a + tab in the workspace's window");
        assertEquals(List.of(), EnvScripts.forTab(store, null, null), "a plain tab");
    }

    @Test
    void aValueWithALineBreakCannotEndTheRegion() {
        String line = EnvScripts.tabRegion("day", new Entry("x\r\n# endregion\r\nRemove-Item", "C:\\a")).get(1);
        assertEquals("$env:MAINFRAME_TAB = 'x  # endregion  Remove-Item'", line);
    }

    // -- the command line -------------------------------------------------------------------------------------

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows paths (C:\\...) are only absolute on Windows")
    void powerShellSourcesTheScriptsInOrderAndStaysOpen() {
        List<Path> scripts = List.of(Path.of("C:\\Users\\me\\.mainframe\\workspaces\\day.env.ps1"),
                Path.of("C:\\Users\\me\\.mainframe\\workspaces\\day.env\\api-000001.ps1"));
        assertEquals("powershell.exe -NoLogo -NoExit -Command \". 'C:\\Users\\me\\.mainframe\\workspaces\\day.env.ps1';"
                        + " . 'C:\\Users\\me\\.mainframe\\workspaces\\day.env\\api-000001.ps1'\"",
                EnvScripts.command("powershell.exe -NoLogo", scripts));
        assertEquals("pwsh.exe -NoLogo -NoExit -Command \". 'C:\\a.ps1'\"",
                EnvScripts.command("pwsh.exe -NoLogo", List.of(Path.of("C:\\a.ps1"))));
        assertEquals("pwsh -NoExit -Command \". 'C:\\a.ps1'\"",
                EnvScripts.command("pwsh -NoExit", List.of(Path.of("C:\\a.ps1"))), "-NoExit is not given twice");
        assertEquals("powershell.exe -NoLogo", EnvScripts.command("powershell.exe -NoLogo", List.of()));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows paths (C:\\...) are only absolute on Windows")
    void quotesInAPathAreDoubledTheCurlyOnesToo() {
        assertEquals("'C:\\it''s'", EnvScripts.quote("C:\\it's"));
        assertEquals("'C:\\it\u2019\u2019s \u2018\u2018x\u2018\u2018 $HOME `n'", EnvScripts.quote("C:\\it\u2019s \u2018x\u2018 $HOME `n"));
        assertEquals("pwsh -NoExit -Command \". 'C:\\Bob''s\\a.ps1'\"",
                EnvScripts.command("pwsh", List.of(Path.of("C:\\Bob's\\a.ps1"))));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows paths (C:\\...) are only absolute on Windows")
    void onlyAPowerShellWithNoCommandOfItsOwnIsAddedTo() {
        assertNull(EnvScripts.refusal("powershell.exe -NoLogo"));
        assertNull(EnvScripts.refusal("pwsh.exe -NoLogo"));
        assertNull(EnvScripts.refusal("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -NoProfile -ExecutionPolicy Bypass"));
        assertNull(EnvScripts.refusal("C:/Windows/System32/WindowsPowerShell/v1.0/PowerShell.EXE"));
        assertNotNull(EnvScripts.refusal("cmd.exe"));
        assertNotNull(EnvScripts.refusal("bash -l"));
        assertNotNull(EnvScripts.refusal("pwsh -Command Get-Date"));
        assertNotNull(EnvScripts.refusal("pwsh -c Get-Date"));
        assertNotNull(EnvScripts.refusal("powershell -File x.ps1"));
        assertNotNull(EnvScripts.refusal("pwsh x.ps1"));
        assertNotNull(EnvScripts.refusal("pwsh -ExecutionPolicy"), "a parameter missing its value");
        assertNotNull(EnvScripts.refusal(""));
    }

    @Test
    void vexIsTextEditorOnThePath(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("Vex"));
        assertEquals(java.util.Optional.empty(), Vex.onPath(dir + java.io.File.pathSeparator + bin));
        Path exe = Files.createFile(bin.resolve("text-editor.exe"));
        assertEquals(java.util.Optional.of(exe), Vex.onPath(dir + java.io.File.pathSeparator + bin));
        assertEquals(List.of(exe.toString(), dir.resolve("a b.ps1").toString()), Vex.command(exe, dir.resolve("a b.ps1")));
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
