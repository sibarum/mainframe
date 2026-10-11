package dev.mainframe.pty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The process's arguments as Windows holds them, in UTF-16.
 *
 * <p>A native image hands {@code main} arguments decoded through the ANSI code page, so any character outside it
 * arrives as something else: {@code ’} in a file name becomes a different file, or none. The java launcher does not
 * have this problem, which is why it shows only in the native build. Here the command line is read back with
 * {@code GetCommandLineW} and split by {@code CommandLineToArgvW}, the same rules the C runtime splits it by.
 */
public final class CommandLine {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle GET_COMMAND_LINE = LINKER.downcallHandle(
            lookup("kernel32.dll", "GetCommandLineW"), FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle TO_ARGV = LINKER.downcallHandle(
            lookup("shell32.dll", "CommandLineToArgvW"), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
            lookup("kernel32.dll", "LocalFree"), FunctionDescriptor.of(ADDRESS, ADDRESS));

    private CommandLine() {
    }

    /**
     * {@code args} as they really were: re-read from the process's UTF-16 command line in a native image, and
     * unchanged under the JVM, whose command line also carries the launcher's own options. Also unchanged if the
     * re-read does not come to the same count, since then it is not the same arguments.
     */
    public static String[] unicode(String[] args) {
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return args;
        }
        try {
            String[] all = split((MemorySegment) GET_COMMAND_LINE.invokeExact());
            if (all == null || all.length != args.length + 1) {
                return args;
            }
            return java.util.Arrays.copyOfRange(all, 1, all.length);
        } catch (Throwable e) {
            return args;
        }
    }

    /** {@code commandLine} split as Windows splits a process's command line, program name first. */
    static String[] split(String commandLine) {
        try (Arena a = Arena.ofConfined()) {
            return split(a.allocateFrom(commandLine, StandardCharsets.UTF_16LE));
        }
    }

    private static String[] split(MemorySegment line) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment argc = a.allocate(JAVA_INT);
            MemorySegment argv = (MemorySegment) TO_ARGV.invokeExact(line, argc);
            if (argv.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                int n = argc.get(JAVA_INT, 0);
                MemorySegment slots = argv.reinterpret(n * ADDRESS.byteSize());
                String[] out = new String[n];
                for (int i = 0; i < n; i++) {
                    out[i] = slots.getAtIndex(ADDRESS, i).reinterpret(Long.MAX_VALUE)
                            .getString(0, StandardCharsets.UTF_16LE);
                }
                return out;
            } finally {
                MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(argv);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static MemorySegment lookup(String library, String name) {
        return SymbolLookup.libraryLookup(library, Arena.global()).find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(name));
    }
}
