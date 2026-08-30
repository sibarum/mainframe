package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.mainframe.fs.Store;
import org.junit.jupiter.api.io.TempDir;

/** Keeping one key, and being straight about what that means. */
class SecretsTest {

    @TempDir
    Path here;

    private Secrets secrets() { return new Secrets(Store.secret(here, "")); }

    @Test
    void remembersWhatItWasGiven() throws IOException {
        Secrets secrets = secrets();
        assertFalse(secrets.isSet());
        assertNull(secrets.read());

        secrets.write("sk-ant-example");
        assertTrue(secrets.isSet());
        assertEquals("sk-ant-example", secrets.read());
    }

    @Test
    void forgettingIsQuietWhenThereIsNothingToForget() throws IOException {
        secrets().clear();
        assertFalse(secrets().isSet());
    }

    @Test
    void forgettingActuallyRemovesTheFile() throws IOException {
        Secrets secrets = secrets();
        secrets.write("sk-ant-example");
        secrets.clear();
        assertFalse(Files.exists(secrets.file()));
        assertNull(secrets.read());
    }

    @Test
    void aBlankKeyIsRefusedRatherThanStored() {
        assertThrows(IllegalArgumentException.class, () -> secrets().write("  "));
    }

    @Test
    void surroundingSpaceIsNotPartOfTheKey() throws IOException {
        // Keys arrive pasted, and a trailing newline is the commonest way for one
        // to stop working with no visible reason.
        secrets().write("  sk-ant-example\n");
        assertEquals("sk-ant-example", secrets().read());
    }

    @Test
    void replacingAKeyLeavesNoPartFileBehind() throws IOException {
        Secrets secrets = secrets();
        secrets.write("first");
        secrets.write("second");
        assertEquals("second", secrets.read());
        try (var children = Files.list(here)) {
            assertEquals(1, children.count(), "a temporary file was left in the directory");
        }
    }

    @Test
    void theStoredFileIsReadableOnlyByItsOwner() throws IOException {
        Secrets secrets = secrets();
        secrets.write("sk-ant-example");
        Path file = secrets.file();

        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions);
            return;
        }

        AclFileAttributeView acl =
                Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(acl != null, "no permission model to check");
        // The point is not that the owner was added. It is that nobody else was
        // left over from what the parent directory allowed.
        for (AclEntry entry : acl.getAcl()) {
            assertEquals(acl.getOwner(), entry.principal(),
                    "the key file still grants access to " + entry.principal().getName());
        }
    }

    @Test
    void theWarningSaysWhereAndSaysPlainText() {
        // Shown where the key is typed. A risk explained afterwards was not
        // explained.
        String risk = secrets().risk();
        assertTrue(risk.contains(secrets().file().toString()), risk);
        assertTrue(risk.contains("plain text"), risk);
        assertTrue(risk.toLowerCase().contains("forget"), risk);
    }
}
