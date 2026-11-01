package dev.mainframe.core;

import dev.mainframe.core.Messages.Item;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.Listing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ListFolder}: a level of the navigator's tree, read from the disk, and the way down to a file in it. */
class ListFolderTest {

    @TempDir
    Path dir;

    private static List<String> names(List<Item> items) {
        return items.stream().map(Item::name).toList();
    }

    @Test
    void foldersComeFirstThenFilesCaseInsensitively() throws IOException {
        Files.createFile(dir.resolve("b.txt"));
        Files.createFile(dir.resolve("A.txt"));
        Files.createDirectory(dir.resolve("zeta"));
        Files.createDirectory(dir.resolve("Alpha"));
        Listed listed = new ListFolder().run(new Listing("tree", dir, true));
        assertEquals(List.of("Alpha", "zeta", "A.txt", "b.txt"), names(listed.items()));
        assertEquals(List.of(true, true, false, false), listed.items().stream().map(Item::folder).toList());
        assertEquals("tree", listed.tree(), "the answer says which tree asked");
    }

    @Test
    void vcsAndBuildFoldersAreLeftOut() throws IOException {
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve("target"));
        Files.createDirectory(dir.resolve("src"));
        assertEquals(List.of("src"), names(ListFolder.list(dir)));
    }

    @Test
    void anUnreadableFolderIsEmpty() {
        assertTrue(ListFolder.list(dir.resolve("not-there")).isEmpty());
    }

    @Test
    void theChainToAFileIsEveryFolderOnTheWayAndTheFile() throws IOException {
        Path file = Files.createDirectories(dir.resolve("a/b")).resolve("c.txt");
        Files.createFile(file);
        List<Path> chain = ListFolder.chain(dir, file);
        assertEquals(List.of("a", "b", "c.txt"), chain.stream().map(p -> p.getFileName().toString()).toList());
        // Each step is the same Path the listing gave that row, or the tree's walk cannot find it.
        assertEquals(ListFolder.list(dir.toAbsolutePath().normalize()).getFirst().path(), chain.getFirst());
    }

    @Test
    void aFileOutsideTheFolderHasNoChain() {
        assertTrue(ListFolder.chain(dir.resolve("x"), dir.resolve("y.txt")).isEmpty());
        assertTrue(ListFolder.chain(null, dir).isEmpty());
    }
}
