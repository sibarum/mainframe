package dev.mainframe.pty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A file's 8.3 short name, from kernel32's {@code GetShortPathNameW}. Empty when the volume does not make them, or
 * the file is gone; a name that already fits 8.3 comes back as itself.
 */
public final class ShortName {

    private static final MethodHandle GET_SHORT_PATH_NAME = Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("kernel32.dll", Arena.global()).find("GetShortPathNameW")
                    .orElseThrow(() -> new UnsatisfiedLinkError("GetShortPathNameW")),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    private ShortName() {
    }

    /** The last element of {@code file}'s short path. */
    public static Optional<String> of(Path file) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment longPath = a.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            int chars = 32_768;
            MemorySegment out = a.allocate(chars * 2L);
            int n = (int) GET_SHORT_PATH_NAME.invokeExact(longPath, out, chars);
            if (n <= 0 || n >= chars) {
                return Optional.empty();
            }
            String shortPath = new String(out.asSlice(0, n * 2L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_16LE);
            Path name = Path.of(shortPath).getFileName();
            return name == null ? Optional.empty() : Optional.of(name.toString());
        } catch (Throwable e) {
            return Optional.empty();
        }
    }
}
