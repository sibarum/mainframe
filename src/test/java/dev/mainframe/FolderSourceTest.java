package dev.mainframe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The navigator's view of the disk: order, what is left out, and the way down to a file. */
class FolderSourceTest {

    @TempDir
    Path dir;

    private static List<String> names(FolderSource s, List<Path> items) {
        return items.stream().map(s::label).toList();
    }

    @Test
    void foldersComeFirstThenFilesCaseInsensitively() throws IOException {
        Files.createFile(dir.resolve("b.txt"));
        Files.createFile(dir.resolve("A.txt"));
        Files.createDirectory(dir.resolve("zeta"));
        Files.createDirectory(dir.resolve("Alpha"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("Alpha", "zeta", "A.txt", "b.txt"), names(s, s.roots()));
    }

    @Test
    void vcsAndBuildFoldersAreLeftOut() throws IOException {
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve("target"));
        Files.createDirectory(dir.resolve("src"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("src"), names(s, s.roots()));
    }

    @Test
    void noFolderIsNoRoots() {
        assertTrue(new FolderSource(null).roots().isEmpty());
    }

    @Test
    void theChainToAFileIsEveryFolderOnTheWayAndTheFile() throws IOException {
        Path file = Files.createDirectories(dir.resolve("a/b")).resolve("c.txt");
        Files.createFile(file);
        FolderSource s = new FolderSource(dir);
        List<Path> chain = s.chainTo(file);
        assertEquals(List.of("a", "b", "c.txt"), names(s, chain));
        // Each step is the same Path the tree was handed for that row, or revealPath cannot find it.
        assertEquals(s.roots().getFirst(), chain.getFirst());
        assertEquals(s.children(chain.get(0)).getFirst(), chain.get(1));
    }

    @Test
    void aFileOutsideTheFolderHasNoChain() throws IOException {
        Path other = Files.createTempFile("outside", ".txt");
        try {
            assertTrue(new FolderSource(dir.resolve("x")).chainTo(other).isEmpty());
        } finally {
            Files.deleteIfExists(other);
        }
    }
}
