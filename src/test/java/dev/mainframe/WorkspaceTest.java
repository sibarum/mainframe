package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace file: CSV with a header, read and written back without losing anything it does not understand. */
class WorkspaceTest {

    @Test
    void readsNameAndPathByHeader() {
        Workspace w = Workspace.parse("day", "path,name\r\nC:\\a,first\r\nC:\\b,\r\n");
        assertEquals(List.of(new Entry("first", "C:\\a"), new Entry("", "C:\\b")), w.entries());
    }

    @Test
    void quotedFieldsHoldCommasQuotesAndLineBreaks() {
        Workspace w = new Workspace("q", List.of(new Entry("say \"hi\", twice", "C:\\odd, folder"),
                new Entry("two\nlines", "C:\\x")));
        assertEquals(w, Workspace.parse("q", Workspace.format(w)));
        assertTrue(Workspace.format(w).contains("\"C:\\odd, folder\""));
    }

    @Test
    void unknownColumnsSurviveARewrite() {
        String text = "name,path,command\r\na,C:\\a,git status\r\n";
        Workspace w = Workspace.parse("x", text);
        assertEquals(Map.of("command", "git status"), w.entries().get(0).extra());
        assertEquals(text, Workspace.format(w));
        Workspace renamed = w.withEntries(List.of(w.entries().get(0).withName("b")));
        assertEquals("name,path,command\r\nb,C:\\a,git status\r\n", Workspace.format(renamed));
    }

    @Test
    void severalUnknownColumnsKeepTheirOrder() {
        String text = "name,path,zeta,alpha,mid\r\na,C:\\a,1,2,3\r\n";
        assertEquals(text, Workspace.format(Workspace.parse("x", text)));
    }

    @Test
    void blankLinesRowsWithoutAPathAndABomAreSkipped() {
        Workspace w = Workspace.parse("x", "\uFEFFname,path\r\n\r\nlonely,\r\n,C:\\a\r\n\r\n");
        assertEquals(List.of(new Entry("", "C:\\a")), w.entries());
    }

    @Test
    void aFileWithNoPathColumnOpensNothing() {
        assertEquals(List.of(), Workspace.parse("x", "folder\r\nC:\\a\r\n").entries());
        assertEquals(List.of(), Workspace.parse("x", "").entries());
    }

    @Test
    void anEmptyNameIsTheFoldersName() {
        assertEquals("mainframe", new Entry("", "C:\\GitHub\\mainframe\\").title());
        assertEquals("mainframe", new Entry(" ", "C:/GitHub/mainframe").title());
        assertEquals("C:\\", new Entry("", "C:\\").title());
        assertEquals("mine", new Entry(" mine ", "C:\\GitHub\\mainframe").title());
    }

    @Test
    void theStoreSavesListsRenamesAndDeletes(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir.resolve("workspaces"));
        assertEquals(List.of(), store.names());
        assertEquals("Workspace", store.freshName());
        store.save(new Workspace("Workspace", List.of(new Entry("", "C:\\a"))));
        store.save(new Workspace("alpha", List.of()));
        assertEquals(List.of("alpha", "Workspace"), store.names());
        assertEquals("Workspace 2", store.freshName());

        store.rename("Workspace", "daily");
        assertEquals(List.of(new Entry("", "C:\\a")), store.load("daily").orElseThrow().entries());
        assertThrows(java.io.IOException.class, () -> store.rename("daily", "alpha"));
        store.rename("daily", "Daily");
        assertEquals(List.of("alpha", "Daily"), store.names());

        store.delete("alpha");
        assertEquals(List.of("Daily"), store.names());
        assertTrue(Files.isRegularFile(dir.resolve("workspaces").resolve("Daily.csv")));
    }

    @Test
    void theEnvColumnIsKeptAndRewritten() {
        String text = "name,path,env\r\napi,C:\\api,api-0a1b2c.ps1\r\nweb,C:\\web,\r\n";
        Workspace w = Workspace.parse("x", text);
        assertEquals("api-0a1b2c.ps1", w.entries().get(0).env());
        assertEquals("", w.entries().get(1).env());
        assertEquals(text, Workspace.format(w));
        Workspace keyed = w.withEntries(List.of(w.entries().get(0), w.entries().get(1).withEnv("web-ffffff.ps1")));
        assertEquals("name,path,env\r\napi,C:\\api,api-0a1b2c.ps1\r\nweb,C:\\web,web-ffffff.ps1\r\n",
                Workspace.format(keyed));
        // The key goes along when the tab is relabelled or repointed: it is the script's, not the row's look.
        Entry moved = keyed.entries().get(1).withName("front").withPath("D:\\web");
        assertEquals("web-ffffff.ps1", moved.env());
    }

    @Test
    void theEnvColumnGoesAfterOthersAddedEarlier() {
        Workspace w = Workspace.parse("x", "name,path,command\r\na,C:\\a,ls\r\n");
        Workspace keyed = w.withEntries(List.of(w.entries().get(0).withEnv("a-000001.ps1")));
        assertEquals("name,path,command,env\r\na,C:\\a,ls,a-000001.ps1\r\n", Workspace.format(keyed));
    }

    @Test
    void openingAWorkspaceGivesEveryRowAKeyOnce(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Files.writeString(dir.resolve("day.csv"), "name,path\r\nMy API!,C:\\api\r\n,C:\\web\r\n,C:\\x\r\n");
        Workspace w = store.keyed("day").orElseThrow();
        List<String> keys = w.entries().stream().map(Entry::env).toList();
        assertTrue(keys.get(0).matches("my-api-[0-9a-f]{6}\\.ps1"), keys.get(0));
        assertTrue(keys.get(1).matches("web-[0-9a-f]{6}\\.ps1"), keys.get(1));
        assertEquals(3, keys.stream().distinct().count());
        assertEquals(w, store.load("day").orElseThrow(), "saved");
        assertEquals(w, store.keyed("day").orElseThrow(), "and not given again");
    }

    @Test
    void aKeyCannotLeaveTheScriptFolder(@TempDir Path dir) {
        WorkspaceStore store = new WorkspaceStore(dir);
        assertTrue(store.tabEnvFile("w", "api-0a1b2c.ps1").isPresent());
        assertEquals(java.util.Optional.empty(), store.tabEnvFile("w", "..\\..\\evil.ps1"));
        assertEquals(java.util.Optional.empty(), store.tabEnvFile("w", "sub/evil.ps1"));
        assertEquals(java.util.Optional.empty(), store.tabEnvFile("w", "evil.cmd"));
        assertEquals(java.util.Optional.empty(), store.tabEnvFile("w", ""));
    }

    @Test
    void renamingMovesTheScriptsAndDeletingPutsThemAsideForUndo(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        store.save(new Workspace("day", List.of(new Entry("", "C:\\a").withEnv("a-000001.ps1"))));
        Files.writeString(store.envFile("day"), "$env:FOO = 'ws'");
        Files.createDirectories(store.envDir("day"));
        Files.writeString(store.envDir("day").resolve("a-000001.ps1"), "$env:FOO = 'tab'");

        store.rename("day", "work");
        assertEquals("$env:FOO = 'ws'", Files.readString(store.envFile("work")));
        assertEquals("$env:FOO = 'tab'", Files.readString(store.tabEnvFile("work", "a-000001.ps1").orElseThrow()));
        assertTrue(!Files.exists(store.envFile("day")) && !Files.exists(store.envDir("day")));
        store.rename("work", "Work");
        assertEquals(List.of("Work.env", "Work.env.ps1", "Work.csv").stream().sorted().toList(),
                Files.list(dir).map(p -> p.getFileName().toString()).sorted().toList());

        Path aside = store.delete("Work");
        assertTrue(!Files.exists(store.envFile("Work")) && !Files.exists(store.envDir("Work")));
        assertTrue(aside.startsWith(dir.resolve(WorkspaceStore.DELETED)));
        store.save(new Workspace("Work", List.of()));   // what undoing the delete does first
        store.restore(aside, "Work");
        assertEquals("$env:FOO = 'ws'", Files.readString(store.envFile("Work")));
        assertTrue(Files.isRegularFile(store.envDir("Work").resolve("a-000001.ps1")));
        assertTrue(!Files.exists(aside));

        // A workspace with no scripts has nothing to put aside.
        store.save(new Workspace("bare", List.of()));
        assertNull(store.delete("bare"));
    }

    @Test
    void oldDeletedScriptsAreCleared(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(dir);
        Path old = Files.createDirectories(dir.resolve(WorkspaceStore.DELETED).resolve("gone 1"));
        Files.writeString(old.resolve("gone.env.ps1"), "x");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofDays(40))));
        Path recent = Files.createDirectories(dir.resolve(WorkspaceStore.DELETED).resolve("gone 2"));
        store.pruneDeleted(WorkspaceStore.KEEP_DELETED);
        assertTrue(!Files.exists(old));
        assertTrue(Files.isDirectory(recent));
    }

    @Test
    void namesMustBeWindowsFileNames() {
        assertNull(WorkspaceStore.problem("Day job"));
        assertNull(WorkspaceStore.problem("v1.2 work"));
        assertNotNull(WorkspaceStore.problem(""));
        assertNotNull(WorkspaceStore.problem("a/b"));
        assertNotNull(WorkspaceStore.problem("what?"));
        assertNotNull(WorkspaceStore.problem("trailing."));
        assertNotNull(WorkspaceStore.problem("con"));
        assertNotNull(WorkspaceStore.problem("COM1.txt"));
    }
}
