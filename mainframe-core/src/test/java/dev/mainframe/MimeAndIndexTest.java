package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.fs.FsIndex;
import dev.mainframe.fs.MimeDetector;
import dev.mainframe.value.Value;

/** Media types and filesystem indexes. */
class MimeAndIndexTest {

    @TempDir
    Path here;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Test
    void contentBeatsTheFileName() throws IOException {
        Path lying = here.resolve("notes.txt");
        Files.write(lying, PNG);
        Value.Mime detected = MimeDetector.detect(lying);
        assertEquals("image/png", detected.full());
        assertEquals("content", detected.detectedBy());
    }

    @Test
    void theNameIsUsedWhenTheContentIsJustText() throws IOException {
        Path source = here.resolve("Thing.java");
        Files.writeString(source, "class Thing {}\n");
        Value.Mime detected = MimeDetector.detect(source);
        assertEquals("text/x-java-source", detected.full());
        assertEquals("extension", detected.detectedBy());
    }

    @Test
    void structuredTextIsRecognisedFromItsShape() throws IOException {
        Path json = here.resolve("mystery");
        Files.writeString(json, "{\"a\": 1}");
        assertEquals("application/json", MimeDetector.detect(json).full());

        Path script = here.resolve("runme");
        Files.writeString(script, "#!/usr/bin/env python3\nprint(1)\n");
        assertEquals("text/x-python", MimeDetector.detect(script).full());
    }

    @Test
    void extensionlessNamesStillGetATypeWhereItIsObvious() {
        assertNotNull(MimeDetector.fromName("Makefile"));
        assertEquals("text/x-dockerfile", MimeDetector.fromName("Dockerfile").full());
    }

    @Test
    void binaryFilesFallBackHonestly() throws IOException {
        Path blob = here.resolve("blob.bin");
        byte[] noise = new byte[512];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i % 7 == 0 ? 0 : i);
        Files.write(blob, noise);
        assertEquals("application/octet-stream", MimeDetector.detect(blob).full());
    }

    @Test
    void anIndexSurvivesBeingSavedAndRead() throws IOException {
        Files.writeString(here.resolve("a.txt"), "aaa");
        Files.createDirectories(here.resolve("sub"));
        Files.writeString(here.resolve("sub/b.md"), "bbb");

        FsIndex index = new FsIndex("demo", here, List.of(".mainframe"), false, 0);
        FsIndex.SyncResult first = index.sync();
        assertEquals(3, first.added());  // a.txt, sub, sub/b.md

        FsIndex reloaded = FsIndex.parse(new String(index.serialize(), StandardCharsets.UTF_8));
        assertEquals(index.size(), reloaded.size());
        assertEquals(here, reloaded.root());
        assertEquals(List.of(".mainframe"), reloaded.skips());
    }

    @Test
    void syncingNoticesWhatChanged() throws IOException {
        Files.writeString(here.resolve("a.txt"), "aaa");
        FsIndex index = new FsIndex("demo", here, List.of(), false, 0);
        index.sync();

        Files.writeString(here.resolve("b.txt"), "bbb");
        Files.delete(here.resolve("a.txt"));
        FsIndex.SyncResult second = index.sync();
        assertEquals(1, second.added());
        assertEquals(1, second.removed());
    }

    @Test
    void skippedDirectoriesStayOut() throws IOException {
        Files.createDirectories(here.resolve("target/classes"));
        Files.writeString(here.resolve("target/classes/x.class"), "x");
        Files.writeString(here.resolve("keep.txt"), "k");

        FsIndex index = new FsIndex("demo", here, List.of("target"), false, 0);
        index.sync();
        for (Value.Rec row : index.rows()) {
            assertTrue(!row.get("path").toString().contains("target"), row.toString());
        }
        assertEquals(1, index.size());
    }

    @Test
    void findUsesAnIndexAndFeedsOtherCommands() throws IOException {
        Files.writeString(here.resolve("one.log"), "1");
        Files.writeString(here.resolve("two.log"), "2");
        Files.writeString(here.resolve("keep.txt"), "3");

        Mf mf = new Mf(here);
        mf.eval("index-build logs . --skip=.mainframe");
        Value found = mf.eval("find --index=logs --ext=log");
        assertEquals(2, ((Value.ListVal) found).items().size());

        // The rows drive a file command without any conversion step.
        mf.eval("mkdir ./out");
        mf.eval("find --index=logs --ext=log | cp --to=./out");
        assertTrue(Files.exists(here.resolve("out/one.log")));
        assertTrue(Files.exists(here.resolve("out/two.log")));
    }

    @Test
    void lsFindAndAnIndexAllProduceTheSameColumns() throws IOException {
        Files.writeString(here.resolve("a.txt"), "a");
        Mf mf = new Mf(here);
        mf.eval("index-build one . --skip=.mainframe");

        var fromLs = columnsOf(mf.eval("ls"));
        var fromFind = columnsOf(mf.eval("find --kind=file"));
        var fromIndex = columnsOf(mf.eval("from-index one"));
        assertEquals(fromLs, fromFind, "ls and find must be interchangeable");
        assertEquals(fromLs, fromIndex, "an index must look like a listing");
    }

    private static java.util.Collection<String> columnsOf(Value value) {
        return ((Value.Rec) ((Value.ListVal) value).items().getFirst()).fields().keySet();
    }

    @Test
    void anIndexNameMustBeSimple() {
        assertEquals("E804", new Mf(here).errorCode("index-build ./nested/name ."));
    }

    @Test
    void searchingAMissingIndexSuggestsTheOneYouHave() throws IOException {
        Mf mf = new Mf(here);
        mf.eval("index-build code . --skip=.mainframe");
        MfError error = mf.error("find --index=cod");
        assertEquals("E801", error.code());
        assertTrue(error.hints().getFirst().contains("code"), error.hints().toString());
    }
}
