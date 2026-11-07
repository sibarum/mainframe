package dev.mainframe.core;

import dev.mainframe.core.Messages.TabSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoldersTest {

    @TempDir
    Path root;

    private WorkspaceStore store() {
        return new WorkspaceStore(root.resolve("workspaces"));
    }

    private static List<Path> dirs(Folders.Opened o) {
        return o.tabs().stream().map(TabSpec::dir).toList();
    }

    @Test
    void dirsInOrderAndAListFromItsOwnFolderSkippingWhatIsNotThere() throws Exception {
        Path api = Files.createDirectories(root.resolve("api"));
        Path web = Files.createDirectories(root.resolve("web"));
        Path lists = Files.createDirectories(root.resolve("lists"));
        Files.writeString(lists.resolve("work.txt"), "# my projects\n\n../web\n\"../api\"\n../gone\n");
        Command c = Command.parse(new String[] {"--dir=api", "--dir=nowhere", "--dirs=lists/work.txt"});
        Folders.Opened o = Folders.of(c.sources(), root, store());
        assertEquals(List.of(api, web, api), dirs(o));
        assertEquals("", o.workspace());
    }

    @Test
    void anUnreadableListAndAMissingWorkspaceNameNothing() {
        Command c = Command.parse(new String[] {"--dirs=missing.txt", "--workspace=home"});
        Folders.Opened o = Folders.of(c.sources(), root, store());
        assertEquals(List.of(), o.tabs());
        assertEquals("", o.workspace());
    }

    @Test
    void aWorkspacesRowsAreItsTabsWithTheirNamesAndKeys() throws Exception {
        Path api = Files.createDirectories(root.resolve("api"));
        Path web = Files.createDirectories(root.resolve("web"));
        Files.createDirectories(root.resolve("workspaces"));
        Files.writeString(root.resolve("workspaces/day.csv"), "name,path\n," + api + "\nfront," + web + "\n");
        Folders.Opened o = Folders.of(Command.parse(new String[] {"--workspace=day"}).sources(), root, store());
        assertEquals("day", o.workspace());
        assertEquals(List.of(api, web), dirs(o));
        assertEquals(List.of("api", "front"), o.tabs().stream().map(TabSpec::name).toList(),
                "a row with no name is named for its folder");
        assertFalse(o.tabs().get(1).env().isBlank(), "every row is given its script key when opened");
    }
}
