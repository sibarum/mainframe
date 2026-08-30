package dev.mainframe.fs;

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
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Somewhere for a program to keep things, without asking anybody. */
class StoreTest {

    @TempDir
    Path here;

    private String previousHome;

    @AfterEach
    void putHomeBack() {
        if (previousHome == null) System.clearProperty("mainframe.home");
        else System.setProperty("mainframe.home", previousHome);
    }

    private void useTempHome() {
        previousHome = System.getProperty("mainframe.home");
        System.setProperty("mainframe.home", here.toString());
    }

    @Test
    void keepsAndGivesBack() throws IOException {
        Store store = Store.at(here, ".txt");
        assertFalse(store.has("notes"));
        assertNull(store.read("notes"));

        store.write("notes", "remember this");
        assertTrue(store.has("notes"));
        assertEquals("remember this", store.read("notes"));
        assertEquals(List.of("notes"), store.names());
    }

    @Test
    void readingSomethingAbsentIsNullRatherThanAThrow() throws IOException {
        // A program asking what it saved last time should not have to catch an
        // exception to find out it has not run before.
        assertNull(Store.at(here, "").read("never-written"));
    }

    @Test
    void namesComeBackInOrderAndWithoutTheSuffix() throws IOException {
        Store store = Store.at(here, ".mfi");
        store.write("beta", "b");
        store.write("alpha", "a");
        assertEquals(List.of("alpha", "beta"), store.names());
    }

    @Test
    void halfWrittenFilesAreNobodysEntry() throws IOException {
        Store store = Store.at(here, "");
        store.write("real", "x");
        Files.writeString(here.resolve(".real.mf-part"), "half");
        assertEquals(List.of("real"), store.names());
    }

    @Test
    void clearingLeavesNothingBehind() throws IOException {
        Store store = Store.at(here, "");
        store.write("one", "1");
        store.write("two", "2");
        store.clear();
        assertEquals(List.of(), store.names());
    }

    @Test
    void eachProgramGetsItsOwnCorner() {
        useTempHome();
        Path mine = Store.owned(Store.Area.STATE, "editor").directory();
        Path yours = Store.owned(Store.Area.STATE, "browser").directory();
        assertFalse(mine.equals(yours));
        assertTrue(mine.startsWith(here), mine.toString());
        // Settings and state are different corners for the same program, because
        // one may be cleared and the other may not.
        assertFalse(Store.owned(Store.Area.SETTINGS, "editor").directory().equals(mine));
    }

    @Test
    void anOwnerCannotClimbOutOfItsOwnCorner() {
        useTempHome();
        for (String bad : new String[] { "../other", "a/b", "a.b", "", "  " }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Store.owned(Store.Area.STATE, bad), bad);
        }
    }

    @Test
    void secretsAreKeptToThisAccount() throws IOException {
        Store store = Store.secret(here, "");
        store.write("credentials", "sk-ant-example");
        Path file = store.file("credentials");

        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posix.readAttributes().permissions());
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(acl != null, "no permission model to check");
        // Not "the owner was allowed" -- "nobody else was left over from what the
        // parent directory handed down".
        for (AclEntry entry : acl.getAcl()) {
            assertEquals(acl.getOwner(), entry.principal(),
                    "still readable by " + entry.principal().getName());
        }
    }

    @Test
    void anOrdinaryStoreIsNotSecretlyRestricted() throws IOException {
        // The restriction should be something a caller asked for, not something
        // that quietly happens to every file MainFrame writes.
        Store.at(here, "").write("public", "x");
        PosixFileAttributeView posix =
                Files.getFileAttributeView(here.resolve("public"), PosixFileAttributeView.class);
        if (posix == null) return;
        assertTrue(posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_READ));
    }
}
