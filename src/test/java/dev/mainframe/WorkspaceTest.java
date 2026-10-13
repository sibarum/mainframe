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
