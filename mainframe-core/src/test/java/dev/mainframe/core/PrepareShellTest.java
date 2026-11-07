package dev.mainframe.core;

import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The job on a home of its own: no workspace, a workspace's scripts sourced, a line that cannot source them. */
class PrepareShellTest {

    @TempDir
    Path home;

    private PrepareShell job() {
        return new PrepareShell(Settings.at(home.resolve("settings.properties")));
    }

    @Test
    void withNoWorkspaceTheLineIsAsAskedAndAFolderThatHasGoneIsHome() {
        Prepared p = job().run(new Prepare(1, "powershell.exe", home.resolve("gone"), "", ""));
        assertEquals(new Prepared(1, Path.of(System.getProperty("user.home")), "powershell.exe", ""), p);
    }

    @Test
    void aWorkspacesScriptIsSourcedByPowerShell() throws Exception {
        Path ws = Files.createDirectories(home.resolve("workspaces"));
        Files.writeString(ws.resolve("day.csv"), "name,path\n," + home + "\n");
        Files.writeString(ws.resolve("day.env.ps1"), "$env:X = 1\n");
        Prepared p = job().run(new Prepare(2, "powershell.exe -NoLogo", home, "day", ""));
        assertEquals("", p.refused());
        assertTrue(p.line().contains("day.env.ps1"), p.line());
        assertTrue(p.line().contains("-NoExit"), p.line());
    }

    @Test
    void aLineThatCannotSourceThemStartsWithoutThemAndSaysWhy() throws Exception {
        Path ws = Files.createDirectories(home.resolve("workspaces"));
        Files.writeString(ws.resolve("day.csv"), "name,path\n," + home + "\n");
        Files.writeString(ws.resolve("day.env.ps1"), "$env:X = 1\n");
        Prepared p = job().run(new Prepare(3, "cmd.exe", home, "day", ""));
        assertEquals("cmd.exe", p.line());
        assertTrue(p.refused().startsWith("This tab's environment scripts did not run: "), p.refused());
    }
}
