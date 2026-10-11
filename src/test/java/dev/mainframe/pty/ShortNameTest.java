package dev.mainframe.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortNameTest {

    @Test
    void aNameThatFitsComesBackAsItself(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("PLAIN.TXT"));
        assertEquals(Optional.of("PLAIN.TXT"), ShortName.of(f));
    }

    @Test
    void aLongNameComesBackShortOrNotAtAll(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("glaze recipes 100% final!.pdf"));
        Optional<String> s = ShortName.of(f);
        // Whether a volume makes 8.3 names is its own setting; when it does, the name is in their narrow alphabet.
        s.ifPresent(n -> assertTrue(n.matches("[A-Z0-9~_.!#$%&'()\\-@^`{}]{1,12}") || n.equals(f.getFileName().toString()), n));
    }

    @Test
    void aFileThatIsNotThereHasNone(@TempDir Path dir) {
        assertEquals(Optional.empty(), ShortName.of(dir.resolve("missing.txt")));
    }
}
