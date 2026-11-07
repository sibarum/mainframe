package dev.mainframe.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice 4's disk work: the script <i>Edit environment…</i> opens, and what {@code --insert-path} may type. */
class EditAndInsertTest {

    @TempDir
    Path home;

    private WorkspaceStore store() {
        return new WorkspaceStore(home.resolve("workspaces"));
    }

    @Test
    void theWorkspacesScriptAndARowsAreMadeWhereEveryTabFindsThem() throws IOException {
        WorkspaceStore store = store();
        store.save(new Workspace("day", List.of(new Workspace.Entry("", "C:/work/api").withEnv("api-1.ps1"))));
        Path ws = EnvScripts.edit(store, "day", "", "", "", EnvScripts.Dialect.POWERSHELL);
        assertEquals(store.envFile("day", EnvScripts.Dialect.POWERSHELL), ws);
        assertTrue(Files.readString(ws).contains("MAINFRAME_WORKSPACE"));
        Path row = EnvScripts.edit(store, "day", "API-1.ps1", "api", "", EnvScripts.Dialect.POWERSHELL);
        assertEquals(store.tabEnvFile("day", "api-1.ps1", EnvScripts.Dialect.POWERSHELL).orElseThrow(), row);
        assertTrue(Files.isRegularFile(row));
    }

    @Test
    void aWorkspaceOrRowThatHasGoneIsSaid() throws IOException {
        WorkspaceStore store = store();
        EnvScripts.Gone g = assertThrows(EnvScripts.Gone.class,
                () -> EnvScripts.edit(store, "night", "", "", "", EnvScripts.Dialect.POWERSHELL));
        assertEquals("No workspace called night", g.title());
        store.save(new Workspace("day", List.of()));
        g = assertThrows(EnvScripts.Gone.class,
                () -> EnvScripts.edit(store, "day", "web-2.ps1", "front", "", EnvScripts.Dialect.POWERSHELL));
        assertEquals("front is no longer in day", g.title());
        // The Workspaces window knows the row it shows, so it can still make its script.
        assertTrue(Files.isRegularFile(
                EnvScripts.edit(store, "day", "web-2.ps1", "front", "C:/work/web", EnvScripts.Dialect.POWERSHELL)));
    }

    @Test
    void insertPathTypesAFileInTheTabsFolderQuotedAndRefusesAnyOther() throws IOException {
        Path file = Files.writeString(home.resolve("my notes.txt"), "x");
        InsertPath.Outcome ok = InsertPath.check(file.toString(), home, InsertPath.Dialect.POWERSHELL);
        assertTrue(ok.typesSomething());
        assertEquals("'my notes.txt'", ok.text());

        Path other = Files.createDirectories(home.resolve("other"));
        InsertPath.Outcome outside = InsertPath.check(file.toString(), other, InsertPath.Dialect.POWERSHELL);
        assertFalse(outside.typesSomething());
        assertTrue(outside.notice().contains("outside this tab's folder"));
        assertFalse(InsertPath.check("notes.txt", home, InsertPath.Dialect.POWERSHELL).typesSomething(),
                "not a full path");
    }
}
