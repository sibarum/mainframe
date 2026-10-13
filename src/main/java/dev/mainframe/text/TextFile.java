package dev.mainframe.text;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that can go wrong between bytes on disk and text in a field, decided before the field is touched.
 *
 * <h2>Why this is in a package of its own</h2>
 *
 * <p>It is policy over bytes: no {@code Gui}, no window, no thread. Code like that goes in a sub-package with public
 * types so it can be tested in milliseconds and grown without dragging the view along — the generated classes one
 * package up are package-private because a tree is only ever wired by its own {@code Recipes}, and this is not
 * part of the tree.
 *
 * <h2>The policy</h2>
 *
 * <p>A file is either loaded <em>normalised</em> or refused with a reason ({@link Unsupported}), never loaded
 * wrong. A wrong decode looks fine until the save, and then the bytes the editor never understood are gone.
 * <ul>
 *   <li><b>Refused:</b> over {@link #MAX_BYTES}; not valid UTF-8; a control character other than tab, newline or
 *       carriage return, which is binary data that happened to decode; a line over {@link #MAX_LINE_CHARS}, which
 *       a text field lays out whole however little of it shows.</li>
 *   <li><b>Normalised:</b> a UTF-8 byte-order mark is dropped; {@code \r\n} becomes {@code \n}, and is put back on
 *       save; tabs become four spaces, because the field's document is soft-tab only.</li>
 * </ul>
 */
public final class TextFile {

    /** A refusal, with a reason meant to be shown to a person as it is. */
    public static final class Unsupported extends Exception {
        public Unsupported(String reason) {
            super(reason);
        }
    }

    /**
     * A file's text and what a save must know to write it back.
     *
     * @param text  normalised: {@code \n} line endings, no tabs, no byte-order mark
     * @param crlf  whether the file used {@code \r\n}, restored by {@link #encode}
     * @param notes what normalising changed ("tabs converted to spaces"), for the status line
     */
    public record Loaded(String text, boolean crlf, List<String> notes) {
        public Loaded {
            notes = List.copyOf(notes);
        }

        /** An empty file, with this platform's line endings. */
        public static Loaded empty() {
            return new Loaded("", System.lineSeparator().equals("\r\n"), List.of());
        }
    }

    public static final int MAX_BYTES = 8 * 1024 * 1024;

    /**
     * The longest line accepted. A field lays a line out whole, at a cost that grows with the square of its length,
     * so a minified bundle or a one-line JSON dump would stop the window rather than merely be slow in it.
     */
    public static final int MAX_LINE_CHARS = 100_000;

    private TextFile() {
    }

    /** Read and normalise {@code file}, or refuse it with a reason. */
    public static Loaded load(Path file) throws IOException, Unsupported {
        long size = Files.size(file);
        if (size > MAX_BYTES) {
            throw new Unsupported("the file is " + size / (1024 * 1024) + " MB, and this opens files up to "
                    + MAX_BYTES / (1024 * 1024) + " MB");
        }
        return decode(Files.readAllBytes(file));
    }

    /** Decode and normalise bytes — separate from the disk, so it can be tested without one. */
    public static Loaded decode(byte[] bytes) throws Unsupported {
        List<String> notes = new ArrayList<>();
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
            notes.add("byte-order mark removed");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Unsupported("this is not UTF-8 text — is it a binary file?");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F) {
                throw new Unsupported(String.format("it contains a control character (0x%02X) — is it a binary file?",
                        (int) c));
            }
        }
        boolean crlf = text.contains("\r\n");
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
        if (text.indexOf('\t') >= 0) {
            text = text.replace("\t", "    ");
            notes.add("tabs converted to spaces");
        }
        // Last, so it measures the lines the field will be given: before the two normalisations above, a file
        // with only \r endings is one unbroken line, and a line of tabs is a quarter of its real width.
        int line = 1;
        for (int start = 0; start <= text.length(); line++) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end;
            if (end - start > MAX_LINE_CHARS) {
                throw new Unsupported("line " + line + " is " + (end - start) + " characters long, and this opens"
                        + " lines up to " + MAX_LINE_CHARS);
            }
            start = end + 1;
        }
        return new Loaded(text, crlf, notes);
    }

    /** The bytes to write for {@code text}: UTF-8, no byte-order mark, the file's own line endings. */
    public static byte[] encode(String text, boolean crlf) {
        return (crlf ? text.replace("\n", "\r\n") : text).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Write {@code bytes} to {@code target} beside it first and then over it, so a failure part way leaves the old
     * file whole rather than half-written.
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
