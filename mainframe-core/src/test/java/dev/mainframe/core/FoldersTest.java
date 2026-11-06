package dev.mainframe.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoldersTest {

    @TempDir
    Path root;

    @Test
    void dirsInOrderAndAListFromItsOwnFolderSkippingWhatIsNotThere() throws Exception {
        Path api = Files.createDirectories(root.resolve("api"));
        Path web = Files.createDirectories(root.resolve("web"));
        Path lists = Files.createDirectories(root.resolve("lists"));
        Files.writeString(lists.resolve("work.txt"), "# my projects\n\n../web\n\"../api\"\n../gone\n");
        Command c = Command.parse(new String[] {"--dir=api", "--dir=nowhere", "--dirs=lists/work.txt"});
        assertEquals(List.of(api, web, api), Folders.of(c.sources(), root));
    }

    @Test
    void anUnreadableListAndAWorkspaceNameNothingYet() {
        Command c = Command.parse(new String[] {"--dirs=missing.txt", "--workspace=home"});
        assertEquals(List.of(), Folders.of(c.sources(), root));
    }
}
