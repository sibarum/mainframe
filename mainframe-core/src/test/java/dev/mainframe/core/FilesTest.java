package dev.mainframe.core;

import dev.mainframe.core.Messages.Create;
import dev.mainframe.core.Messages.Created;
import dev.mainframe.core.Messages.FileRead;
import dev.mainframe.core.Messages.Find;
import dev.mainframe.core.Messages.Kind;
import dev.mainframe.core.Messages.Read;
import dev.mainframe.core.Messages.Why;
import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jobs, called as the offload lane calls them: a request in, a reply out, against a real folder. What can go wrong
 * is in the reply, never thrown.
 */
class FilesTest {

    @TempDir
    Path dir;

    @Test
    void readingAFileAnswersWithItsTextAndItsLineEndings() throws Exception {
        Path file = Files.write(dir.resolve("crlf.txt"), "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        FileRead r = new ReadFile().run(new Read(file, Why.OPEN, true));
        assertEquals("", r.problem());
        assertEquals("one\ntwo\n", r.text());
        assertTrue(r.crlf());
    }

    @Test
    void aBinaryFileIsAnsweredWithWhyNot() throws Exception {
        Path file = Files.write(dir.resolve("blob.bin"), new byte[] {0, 1, 2, 3});
        FileRead r = new ReadFile().run(new Read(file, Why.OPEN, true));
        assertFalse(r.problem().isEmpty());
    }

    @Test
    void aMissingFileIsAnsweredWithWhyNot() {
        FileRead r = new ReadFile().run(new Read(dir.resolve("gone.txt"), Why.RESTORE_FILE, true));
        assertFalse(r.problem().isEmpty());
        assertEquals(Why.RESTORE_FILE, r.why(), "the reason travels with the request, so the answer can be acted on");
    }

    @Test
    void writingKeepsTheFilesLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Written w = new WriteFile().run(new Write(1, file, "one\ntwo\n", true));
        assertEquals("", w.problem());
        assertEquals("one\r\ntwo\r\n", Files.readString(file));
    }

    @Test
    void findingSaysWhatIsThere() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        FindPath find = new FindPath();
        assertEquals(Kind.FOLDER, find.run(new Find(dir, Why.GO)).kind());
        assertEquals(Kind.FILE, find.run(new Find(file, Why.GO)).kind());
        assertEquals(Kind.NEW, find.run(new Find(dir.resolve("new.txt"), Why.GO)).kind());
        assertEquals(Kind.NOWHERE, find.run(new Find(dir.resolve("no/such/new.txt"), Why.GO)).kind());
    }

    @Test
    void creatingMakesAnEmptyFileAndRefusesOneThatIsThere() throws Exception {
        Path file = dir.resolve("new.txt");
        Created made = new CreateFile().run(new Create(file));
        assertEquals("", made.problem());
        assertEquals(0, Files.size(file));
        assertFalse(new CreateFile().run(new Create(file)).problem().isEmpty());
    }
}
