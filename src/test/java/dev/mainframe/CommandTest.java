package dev.mainframe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MainFrame's command line: what is MainFrame's, what is the framework's, and the tabs it comes to on disk. */
class CommandTest {

    @TempDir
    Path tmp;

    @Test
    void optionsComeOutAndTheRestIsTheFrameworks() {
        Command c = Command.parse(new String[] {"--dir=a", "--automation=0", "--workspace=w", "--dirs=list.txt",
                "--log=debug", "--insert-path=C:/x/y.txt"});
        assertEquals(List.of(new Command.Source(Command.Kind.DIR, "a"), new Command.Source(Command.Kind.WORKSPACE, "w"),
                new Command.Source(Command.Kind.DIRS, "list.txt")), c.sources());
        assertEquals(List.of("--automation=0", "--log=debug"), c.framework());
        assertEquals("C:/x/y.txt", c.insertPath());
        assertNull(c.shellLine());
    }

    @Test
    void theFirstArgumentThatIsNotAnOptionStartsACommandLineThatKeepsItsOwnOptions() {
        Command c = Command.parse(new String[] {"--dir=a", "pwsh", "-NoProfile", "--dir=b", "240"});
        assertEquals(List.of(new Command.Source(Command.Kind.DIR, "a")), c.sources());
        assertEquals("pwsh -NoProfile --dir=b 240", c.shellLine());
        assertTrue(c.framework().isEmpty(), "nothing after the command line is the framework's");
    }

    @Test
    void anUnknownOptionIsLeftForTheFrameworkToRefuse() {
        assertEquals(List.of("--dri=a"), Command.parse(new String[] {"--dri=a"}).framework());
    }

    @Test
    void aSecondInsertPathIsIgnoredAndSaid() {
        Command c = Command.parse(new String[] {"--insert-path=a", "--insert-path=b", ""});
        assertEquals("a", c.insertPath());
        assertEquals(1, c.notes().size());
    }

    @Test
    void foldersListsAndWorkspacesOpenInOrderAndWhatIsMissingIsSkipped() throws IOException {
        Path api = Files.createDirectories(tmp.resolve("api"));
        Path web = Files.createDirectories(tmp.resolve("web"));
        Path docs = Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("list.txt"), "# projects\n\nweb\n  missing  \n");
        WorkspaceStore store = new WorkspaceStore(tmp.resolve("workspaces"));
        store.save(new Workspace("w", List.of(new Workspace.Entry("Docs!", docs.toString()))));

        Command c = Command.parse(new String[] {"--dir=" + api, "--dirs=" + tmp.resolve("list.txt"),
                "--workspace=w", "--workspace=nope", "--dir=\"" + tmp.resolve("gone") + "\""});
        List<Terminals.Opening> tabs = Startup.openings(c, store);

        assertEquals(List.of(new Terminals.Opening(api, null), new Terminals.Opening(web, null),
                new Terminals.Opening(docs, "Docs!")), tabs);
    }
}
