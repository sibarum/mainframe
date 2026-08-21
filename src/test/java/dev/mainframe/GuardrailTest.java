package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The promises about not losing data. */
class GuardrailTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    private Path file(String name, String contents) throws IOException {
        Path path = here.resolve(name);
        Files.createDirectories(path.getParent() == null ? here : path.getParent());
        Files.writeString(path, contents);
        return path;
    }

    @Test
    void deletingUnattendedIsRefused() throws IOException {
        Path victim = file("gone.txt", "x");
        assertEquals("E302", mf().errorCode("rm gone.txt"));
        assertTrue(Files.exists(victim), "the file must still be there");
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        Path victim = file("gone.txt", "x");
        Mf mf = mf();
        mf.eval("rm gone.txt --dry-run");
        assertTrue(Files.exists(victim));
        assertTrue(mf.printed().contains("dry run"), mf.printed());
        assertTrue(mf.printed().contains("nothing was changed"), mf.printed());
    }

    @Test
    void everyWritingCommandUnderstandsDryRun() {
        Mf mf = mf();
        for (var builtin : dev.mainframe.eval.Registry.standard().all()) {
            var signature = builtin.signature();
            boolean changes = signature.effect() == dev.mainframe.eval.Signature.Effect.WRITES
                    || signature.effect() == dev.mainframe.eval.Signature.Effect.DESTRUCTIVE;
            if (!changes) continue;
            assertTrue(signature.flag("dry-run") != null,
                    signature.name() + " changes things but has no --dry-run");
            assertTrue(builtin instanceof dev.mainframe.eval.Builtin.Planning,
                    signature.name() + " changes things but does not plan first");
        }
        assertFalse(mf.session().dryRun());
    }

    @Test
    void deletingWithYesUsesTheTrash() throws IOException {
        Path victim = file("bye.txt", "x");
        Mf mf = mf();
        mf.eval("rm bye.txt --yes");
        assertFalse(Files.exists(victim));
        assertTrue(mf.printed().contains("recoverable"), mf.printed());
    }

    @Test
    void deletingADirectoryNeedsRecurse() throws IOException {
        file("tree/inner.txt", "x");
        assertEquals("E615", mf().errorCode("rm tree --yes"));
        assertTrue(Files.exists(here.resolve("tree")));
    }

    @Test
    void savingWillNotClobberBySilence() throws IOException {
        file("out.txt", "original");
        assertEquals("E608", mf().errorCode("echo \"new\" | save out.txt"));
        assertEquals("original", Files.readString(here.resolve("out.txt")));
        mf().eval("echo \"new\" | save out.txt --force");
        assertEquals("new\n", Files.readString(here.resolve("out.txt")));
    }

    @Test
    void copyingNeedsAnExplicitDestination() throws IOException {
        file("a.txt", "a");
        assertEquals("E611", mf().errorCode("cp a.txt"));
    }

    @Test
    void copyingWillNotReplaceWithoutForce() throws IOException {
        file("a.txt", "a");
        file("backup/a.txt", "older");
        assertEquals("E614", mf().errorCode("cp a.txt --to=./backup"));
        assertEquals("older", Files.readString(here.resolve("backup/a.txt")));
    }

    @Test
    void aFailedPlanChangesNothingAtAll() throws IOException {
        file("one.txt", "1");
        file("two.txt", "2");
        file("backup/two.txt", "old");
        // The second file cannot be copied, so neither is.
        assertEquals("E614", mf().errorCode("cp one.txt two.txt --to=./backup"));
        assertFalse(Files.exists(here.resolve("backup/one.txt")));
        assertEquals("old", Files.readString(here.resolve("backup/two.txt")));
    }

    @Test
    void readingABinaryFileIsRefusedUntilYouInsist() throws IOException {
        Files.write(here.resolve("image.txt"), new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13});
        assertEquals("E605", mf().errorCode("cat image.txt"));
        assertEquals(null, mf().errorCode("cat image.txt --force"));
    }

    @Test
    void writesLandAtomically() throws IOException {
        file("target.txt", "before");
        mf().eval("echo \"after\" | save target.txt --force");
        // No leftover part-files from the temp-then-rename dance.
        try (var listing = Files.list(here)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().contains("mf-part")));
        }
    }
}
