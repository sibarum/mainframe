package dev.mainframe;

import dev.mainframe.Workspace.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a workspace tile says, and the command line that opens a workspace in a MainFrame of its own. */
class OpenDialogTest {

    @Test
    void aTileNamesTheFoldersAsTheirTabsWillBeNamed() {
        Workspace w = new Workspace("w", List.of(new Entry("api", "C:\\work\\api"), new Entry("", "C:\\work\\web")));
        assertEquals("2 folders: api, web", OpenDialog.describe(w));
    }

    @Test
    void aLongListIsCutAfterFour() {
        List<Entry> e = List.of(new Entry("a", "C:\\a"), new Entry("b", "C:\\b"), new Entry("c", "C:\\c"),
                new Entry("d", "C:\\d"), new Entry("e", "C:\\e"));
        assertEquals("5 folders: a, b, c, d, …", OpenDialog.describe(new Workspace("w", e)));
    }

    @Test
    void oneFolderAndNone() {
        assertEquals("1 folder: a", OpenDialog.describe(new Workspace("w", List.of(new Entry("a", "C:\\a")))));
        assertEquals("No folders yet", OpenDialog.describe(new Workspace("w", List.of())));
    }

    /** On the JVM: this Java, this class path, MainFrame's main class, then the arguments, unsplit. */
    @Test
    void theJvmRelaunchesItself() {
        List<String> cmd = SelfLaunch.command(List.of("--workspace=Suite apps"));
        assertTrue(cmd.get(0).startsWith(System.getProperty("java.home")), cmd.get(0));
        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"), cmd.toString());
        int cp = cmd.indexOf("-cp");
        assertEquals(System.getProperty("java.class.path"), cmd.get(cp + 1));
        assertEquals(List.of(MainFrame.class.getName(), "--workspace=Suite apps"), cmd.subList(cp + 2, cmd.size()));
    }
}
