package dev.mainframe.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

}
