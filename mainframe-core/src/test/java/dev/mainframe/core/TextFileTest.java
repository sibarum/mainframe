package dev.mainframe.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The policy between bytes on disk and text in a field: what is refused, what is normalised, what survives a save. */
class TextFileTest {

    private static TextFile.Loaded decode(String s) throws TextFile.Unsupported {
        return TextFile.decode(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void plainUtf8LoadsAsItIs() throws Exception {
        TextFile.Loaded loaded = decode("héllo\nworld\n");
        assertEquals("héllo\nworld\n", loaded.text());
        assertFalse(loaded.crlf());
        assertTrue(loaded.notes().isEmpty());
    }

    @Test
    void crlfIsNormalisedAndPutBackOnSave() throws Exception {
        TextFile.Loaded loaded = decode("one\r\ntwo\r\n");
        assertEquals("one\ntwo\n", loaded.text());
        assertTrue(loaded.crlf());
        assertArrayEquals("one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8), TextFile.encode(loaded.text(), true));
    }

    @Test
    void tabsBecomeSpacesAndSaySo() throws Exception {
        TextFile.Loaded loaded = decode("\tindented");
        assertEquals("    indented", loaded.text());
        assertEquals(1, loaded.notes().size());
    }

    @Test
    void aByteOrderMarkIsDropped() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals("hi", TextFile.decode(bom).text());
    }

    @Test
    void invalidUtf8IsRefusedRatherThanGuessed() {
        assertThrows(TextFile.Unsupported.class, () -> TextFile.decode(new byte[] {'a', (byte) 0xC3, 'b'}));
    }

    @Test
    void controlCharactersMeanBinaryAndAreRefused() {
        assertThrows(TextFile.Unsupported.class, () -> decode("PK\u0003\u0004"));
    }

    @Test
    void aLineTooLongToLayOutIsRefused() {
        TextFile.Unsupported refused = assertThrows(TextFile.Unsupported.class,
                () -> decode("ok\n" + "x".repeat(TextFile.MAX_LINE_CHARS + 1)));
        assertTrue(refused.getMessage().startsWith("line 2 "), refused.getMessage());
    }

    @Test
    void writingReplacesTheFileAndLeavesNothingBeside(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "old");
        TextFile.write(file, "new".getBytes(StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(file));
        try (var listing = Files.list(dir)) {
            assertEquals(1, listing.count(), "the temporary file should be gone");
        }
    }
}
