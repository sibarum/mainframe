package dev.mainframe.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.mainframe.value.Value;

/**
 * Works out what a file actually is.
 *
 * <p>Media type is a first-class value in MainFrame, so this runs on content
 * first and the file name second: a README with no extension is still
 * text/markdown, and a .txt full of PNG bytes is still image/png. Every result
 * records how it was decided, so {@code mime} can show its reasoning.
 */
public final class MimeDetector {

    /** How many bytes we are willing to read to make up our mind. */
    private static final int SNIFF = 8192;

    public static final Value.Mime DIRECTORY = new Value.Mime("inode", "directory", "kind");
    public static final Value.Mime UNKNOWN = new Value.Mime("application", "octet-stream", "fallback");
    public static final Value.Mime EMPTY = new Value.Mime("application", "x-empty", "kind");

    private record Magic(String hex, int offset, String type) {}

    /** Content signatures, checked in order; longer prefixes first. */
    private static final Magic[] MAGIC = {
            new Magic("89504e470d0a1a0a", 0, "image/png"),
            new Magic("ffd8ff", 0, "image/jpeg"),
            new Magic("474946383761", 0, "image/gif"),
            new Magic("474946383961", 0, "image/gif"),
            new Magic("424d", 0, "image/bmp"),
            new Magic("00000100", 0, "image/vnd.microsoft.icon"),
            new Magic("49492a00", 0, "image/tiff"),
            new Magic("4d4d002a", 0, "image/tiff"),
            new Magic("25504446", 0, "application/pdf"),
            new Magic("504b0304", 0, "application/zip"),
            new Magic("504b0506", 0, "application/zip"),
            new Magic("1f8b", 0, "application/gzip"),
            new Magic("425a68", 0, "application/x-bzip2"),
            new Magic("fd377a585a00", 0, "application/x-xz"),
            new Magic("28b52ffd", 0, "application/zstd"),
            new Magic("377abcaf271c", 0, "application/x-7z-compressed"),
            new Magic("526172211a07", 0, "application/vnd.rar"),
            new Magic("7f454c46", 0, "application/x-elf"),
            new Magic("cafebabe", 0, "application/java-vm"),
            new Magic("0061736d", 0, "application/wasm"),
            new Magic("4d5a", 0, "application/vnd.microsoft.portable-executable"),
            new Magic("53514c69746520666f726d6174203300", 0, "application/vnd.sqlite3"),
            new Magic("494433", 0, "audio/mpeg"),
            new Magic("fffb", 0, "audio/mpeg"),
            new Magic("664c6143", 0, "audio/flac"),
            new Magic("4f676753", 0, "audio/ogg"),
            new Magic("667479706d7034", 4, "video/mp4"),
            new Magic("6674797069736f6d", 4, "video/mp4"),
            new Magic("667479704d3441", 4, "audio/mp4"),
            new Magic("1a45dfa3", 0, "video/x-matroska"),
    };

    private static final Map<String, String> BY_EXTENSION = byExtension();

    private MimeDetector() {}

    /** The media type of {@code path}, never null. */
    public static Value.Mime detect(Path path) {
        try {
            if (Files.isDirectory(path)) return DIRECTORY;
            long size = Files.size(path);
            if (size == 0) return EMPTY;
            byte[] head = head(path);
            Value.Mime byContent = fromContent(head);
            if (byContent != null) return byContent;
            Value.Mime byName = fromName(path.getFileName().toString());
            if (byName != null) return byName;
            return looksTextual(head) ? mime("text/plain", "content") : UNKNOWN;
        } catch (IOException e) {
            Value.Mime byName = fromName(path.getFileName().toString());
            return byName != null ? byName : UNKNOWN;
        }
    }

    /** The media type implied by a file name alone, or null if the name says nothing. */
    public static Value.Mime fromName(String fileName) {
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot > 0 && dot < lower.length() - 1) {
            String type = BY_EXTENSION.get(lower.substring(dot + 1));
            if (type != null) return mime(type, "extension");
        }
        String type = BY_EXTENSION.get(lower);
        return type == null ? null : mime(type, "name");
    }

    /** Sniffs bytes only. Returns null when the bytes are not recognisable. */
    public static Value.Mime fromContent(byte[] head) {
        HexFormat hex = HexFormat.of();
        String asHex = hex.formatHex(head, 0, Math.min(head.length, 32));
        for (Magic m : MAGIC) {
            int start = m.offset() * 2;
            if (asHex.length() >= start + m.hex().length()
                    && asHex.startsWith(m.hex(), start)) {
                if (m.type().equals("application/zip")) return mime("application/zip", "content");
                return mime(m.type(), "content");
            }
        }
        if (startsWith(head, "RIFF") && head.length > 11) {
            String form = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (form.equals("WEBP")) return mime("image/webp", "content");
            if (form.equals("WAVE")) return mime("audio/wav", "content");
            if (form.equals("AVI ")) return mime("video/x-msvideo", "content");
        }
        if (!looksTextual(head)) return null;
        return fromText(new String(head, StandardCharsets.UTF_8));
    }

    /** Structured text formats are worth naming precisely. */
    private static Value.Mime fromText(String text) {
        String t = text.stripLeading();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase();
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) return mime("text/html", "content");
        if (t.startsWith("<?xml")) return mime("application/xml", "content");
        if (t.startsWith("#!")) {
            int end = t.indexOf('\n');
            String line = (end < 0 ? t : t.substring(0, end)).toLowerCase();
            if (line.contains("python")) return mime("text/x-python", "shebang");
            if (line.contains("bash") || line.contains("/sh") || line.contains("zsh")) {
                return mime("application/x-shellscript", "shebang");
            }
            if (line.contains("node")) return mime("text/javascript", "shebang");
            return mime("text/x-script", "shebang");
        }
        if ((t.startsWith("{") && t.contains(":")) || t.startsWith("[{")) return mime("application/json", "content");
        return null;
    }

    /**
     * True when the bytes read as text: valid UTF-8, no NUL bytes and not
     * peppered with control characters.
     */
    public static boolean looksTextual(byte[] bytes) {
        if (bytes.length == 0) return true;
        int control = 0;
        for (byte b : bytes) {
            if (b == 0) return false;
            int c = b & 0xff;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != 0x0c && c != 0x1b) control++;
        }
        if (control * 100 / bytes.length > 5) return false;
        return isUtf8(bytes);
    }

    private static boolean isUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            // A cut-off multi-byte character at the end of the sniff window is fine:
            // retry without the last few bytes before calling it binary.
            if (bytes.length <= 4) return false;
            byte[] trimmed = new byte[bytes.length - 4];
            System.arraycopy(bytes, 0, trimmed, 0, trimmed.length);
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(trimmed));
                return true;
            } catch (CharacterCodingException e2) {
                return false;
            }
        }
    }

    public static byte[] head(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(SNIFF);
        }
    }

    private static boolean startsWith(byte[] bytes, String ascii) {
        if (bytes.length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((bytes[i] & 0xff) != ascii.charAt(i)) return false;
        }
        return true;
    }

    public static Value.Mime mime(String full, String detectedBy) {
        int slash = full.indexOf('/');
        return new Value.Mime(full.substring(0, slash), full.substring(slash + 1), detectedBy);
    }

    private static Map<String, String> byExtension() {
        Map<String, String> m = new LinkedHashMap<>();
        // text and code
        m.put("txt", "text/plain");
        m.put("md", "text/markdown");
        m.put("markdown", "text/markdown");
        m.put("rst", "text/x-rst");
        m.put("csv", "text/csv");
        m.put("tsv", "text/tab-separated-values");
        m.put("json", "application/json");
        m.put("jsonl", "application/x-ndjson");
        m.put("yaml", "application/yaml");
        m.put("yml", "application/yaml");
        m.put("toml", "application/toml");
        m.put("xml", "application/xml");
        m.put("html", "text/html");
        m.put("htm", "text/html");
        m.put("css", "text/css");
        m.put("js", "text/javascript");
        m.put("mjs", "text/javascript");
        m.put("ts", "text/typescript");
        m.put("tsx", "text/typescript");
        m.put("jsx", "text/javascript");
        m.put("java", "text/x-java-source");
        m.put("kt", "text/x-kotlin");
        m.put("scala", "text/x-scala");
        m.put("py", "text/x-python");
        m.put("rb", "text/x-ruby");
        m.put("go", "text/x-go");
        m.put("rs", "text/x-rust");
        m.put("c", "text/x-c");
        m.put("h", "text/x-c");
        m.put("cpp", "text/x-c++");
        m.put("cc", "text/x-c++");
        m.put("hpp", "text/x-c++");
        m.put("cs", "text/x-csharp");
        m.put("swift", "text/x-swift");
        m.put("php", "text/x-php");
        m.put("pl", "text/x-perl");
        m.put("lua", "text/x-lua");
        m.put("sql", "application/sql");
        m.put("sh", "application/x-shellscript");
        m.put("bash", "application/x-shellscript");
        m.put("zsh", "application/x-shellscript");
        m.put("ps1", "application/x-powershell");
        m.put("bat", "application/x-bat");
        m.put("cmd", "application/x-bat");
        m.put("mf", "text/x-mainframe");
        m.put("ini", "text/plain");
        m.put("cfg", "text/plain");
        m.put("conf", "text/plain");
        m.put("log", "text/plain");
        m.put("diff", "text/x-diff");
        m.put("patch", "text/x-diff");
        // names without extensions that still mean something
        m.put("readme", "text/markdown");
        m.put("license", "text/plain");
        m.put("makefile", "text/x-makefile");
        m.put("dockerfile", "text/x-dockerfile");
        m.put(".gitignore", "text/plain");
        m.put(".gitattributes", "text/plain");
        // documents
        m.put("pdf", "application/pdf");
        m.put("doc", "application/msword");
        m.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        m.put("xls", "application/vnd.ms-excel");
        m.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        m.put("ppt", "application/vnd.ms-powerpoint");
        m.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        m.put("epub", "application/epub+zip");
        // images, audio, video
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("gif", "image/gif");
        m.put("webp", "image/webp");
        m.put("svg", "image/svg+xml");
        m.put("bmp", "image/bmp");
        m.put("ico", "image/vnd.microsoft.icon");
        m.put("tif", "image/tiff");
        m.put("tiff", "image/tiff");
        m.put("heic", "image/heic");
        m.put("avif", "image/avif");
        m.put("mp3", "audio/mpeg");
        m.put("wav", "audio/wav");
        m.put("flac", "audio/flac");
        m.put("ogg", "audio/ogg");
        m.put("m4a", "audio/mp4");
        m.put("mp4", "video/mp4");
        m.put("mov", "video/quicktime");
        m.put("mkv", "video/x-matroska");
        m.put("webm", "video/webm");
        m.put("avi", "video/x-msvideo");
        // archives and binaries
        m.put("zip", "application/zip");
        m.put("gz", "application/gzip");
        m.put("tgz", "application/gzip");
        m.put("bz2", "application/x-bzip2");
        m.put("xz", "application/x-xz");
        m.put("zst", "application/zstd");
        m.put("7z", "application/x-7z-compressed");
        m.put("rar", "application/vnd.rar");
        m.put("tar", "application/x-tar");
        m.put("jar", "application/java-archive");
        m.put("class", "application/java-vm");
        m.put("exe", "application/vnd.microsoft.portable-executable");
        m.put("dll", "application/vnd.microsoft.portable-executable");
        m.put("so", "application/x-elf");
        m.put("wasm", "application/wasm");
        m.put("db", "application/vnd.sqlite3");
        m.put("sqlite", "application/vnd.sqlite3");
        m.put("ttf", "font/ttf");
        m.put("otf", "font/otf");
        m.put("woff", "font/woff");
        m.put("woff2", "font/woff2");
        return Map.copyOf(m);
    }
}
