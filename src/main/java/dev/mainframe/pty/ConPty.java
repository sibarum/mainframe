package dev.mainframe.pty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A child process attached to a Windows pseudoconsole (ConPTY).
 *
 * <p>ConPTY is the OS-side terminal: the child believes it has a real console, and we get a stream of VT sequences
 * out and give a stream of VT/UTF-8 in. Everything here is kernel32 through the foreign function API, so there is
 * no native code of our own to build.
 */
public final class ConPty implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MethodHandle CREATE_PIPE = fn("CreatePipe",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CREATE_PSEUDO_CONSOLE = fn("CreatePseudoConsole",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle RESIZE_PSEUDO_CONSOLE = fn("ResizePseudoConsole",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CLOSE_PSEUDO_CONSOLE = fn("ClosePseudoConsole",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle INIT_ATTR_LIST = fn("InitializeProcThreadAttributeList",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle UPDATE_ATTR = fn("UpdateProcThreadAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_PROCESS = fn("CreateProcessW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                    ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle READ_FILE = fn("ReadFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WRITE_FILE = fn("WriteFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLOSE_HANDLE = fn("CloseHandle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle WAIT = fn("WaitForSingleObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle EXIT_CODE = fn("GetExitCodeProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle TERMINATE = fn("TerminateProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int STARTUPINFOEX_SIZE = 112;
    private static final int ATTRIBUTE_LIST_OFFSET = 104;
    private static final int INFINITE = -1;
    private static final int STARTF_USESTDHANDLES = 0x100;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment hpc;
    private final MemorySegment inputWrite;
    private final MemorySegment outputRead;
    private final MemorySegment process;
    private final MemorySegment thread;
    private volatile boolean closed;

    private ConPty(MemorySegment hpc, MemorySegment inputWrite, MemorySegment outputRead,
                   MemorySegment process, MemorySegment thread) {
        this.hpc = hpc;
        this.inputWrite = inputWrite;
        this.outputRead = outputRead;
        this.process = process;
        this.thread = thread;
    }

    /** Starts {@code commandLine} in a new pseudoconsole of the given size, in {@code cwd} (null: inherit). */
    public static ConPty start(String commandLine, int cols, int rows, String cwd) {
        Arena a = Arena.ofShared();
        try {
            MemorySegment inRead = handleSlot(a), inWrite = handleSlot(a);
            MemorySegment outRead = handleSlot(a), outWrite = handleSlot(a);
            check(call(CREATE_PIPE, inRead, inWrite, MemorySegment.NULL, 0), "CreatePipe(input)");
            check(call(CREATE_PIPE, outRead, outWrite, MemorySegment.NULL, 0), "CreatePipe(output)");

            MemorySegment hpcSlot = handleSlot(a);
            int hr = call(CREATE_PSEUDO_CONSOLE, coordValue(cols, rows),get(inRead), get(outWrite), 0, hpcSlot);
            if (hr != 0) throw new IllegalStateException("CreatePseudoConsole failed: HRESULT 0x" + Integer.toHexString(hr));
            MemorySegment hpc = get(hpcSlot);

            // Attribute list that tells CreateProcess to attach the child to the pseudoconsole.
            MemorySegment sizeSlot = a.allocate(8);
            call(INIT_ATTR_LIST, MemorySegment.NULL, 1, 0, sizeSlot);
            MemorySegment attrs = a.allocate(sizeSlot.get(JAVA_LONG, 0));
            check(call(INIT_ATTR_LIST, attrs, 1, 0, sizeSlot), "InitializeProcThreadAttributeList");
            check(call(UPDATE_ATTR, attrs, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, hpc, 8L,
                    MemorySegment.NULL, MemorySegment.NULL), "UpdateProcThreadAttribute");

            MemorySegment si = a.allocate(STARTUPINFOEX_SIZE);
            si.set(JAVA_INT, 0, STARTUPINFOEX_SIZE);
            si.set(JAVA_INT, 60, STARTF_USESTDHANDLES); // with null handles: stdio comes from the pseudoconsole, not from ours
            si.set(ADDRESS, ATTRIBUTE_LIST_OFFSET, attrs);
            MemorySegment pi = a.allocate(24);
            MemorySegment cmd = a.allocateFrom(commandLine, StandardCharsets.UTF_16LE);
            MemorySegment dir = cwd == null ? MemorySegment.NULL : a.allocateFrom(cwd, StandardCharsets.UTF_16LE);
            check(call(CREATE_PROCESS, MemorySegment.NULL, cmd, MemorySegment.NULL, MemorySegment.NULL, 0,
                    EXTENDED_STARTUPINFO_PRESENT, MemorySegment.NULL, dir, si, pi), "CreateProcess(" + commandLine + ")");

            // The pseudoconsole owns its ends of the pipes now; ours are the other two.
            close(get(inRead));
            close(get(outWrite));
            return new ConPty(hpc, get(inWrite), get(outRead), pi.get(ADDRESS, 0), pi.get(ADDRESS, 8));
        } finally {
            // attrs/si/pi are only needed during CreateProcess; the handles we keep live outside the arena.
            a.close();
        }
    }

    /** Blocks for output. Returns the byte count, or -1 once the child and its console are gone. */
    public int read(byte[] buf) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment mem = a.allocate(buf.length);
            MemorySegment n = a.allocate(4);
            int ok = call(READ_FILE, outputRead, mem, buf.length, n, MemorySegment.NULL);
            int count = n.get(JAVA_INT, 0);
            if (ok == 0 || count <= 0) return -1;
            MemorySegment.copy(mem, ValueLayout.JAVA_BYTE, 0, buf, 0, count);
            return count;
        }
    }

    public synchronized void write(byte[] data) {
        if (closed || data.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment mem = a.allocate(data.length);
            MemorySegment.copy(data, 0, mem, ValueLayout.JAVA_BYTE, 0, data.length);
            MemorySegment n = a.allocate(4);
            call(WRITE_FILE, inputWrite, mem, data.length, n, MemorySegment.NULL);
        }
    }

    public void write(String text) {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    public void resize(int cols, int rows) {
        if (!closed) call(RESIZE_PSEUDO_CONSOLE, hpc, coordValue(cols, rows));
    }

    /** Blocks until the child exits and returns its exit code. */
    public int waitFor() {
        call(WAIT, process, INFINITE);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment code = a.allocate(4);
            call(EXIT_CODE, process, code);
            return code.get(JAVA_INT, 0);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        call(TERMINATE, process, 1);
        // Closing the console can block until its output is drained, and our reader may already be gone.
        Thread t = new Thread(() -> {
            try {
                CLOSE_PSEUDO_CONSOLE.invokeExact(hpc);
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
            close(inputWrite);
            close(outputRead);
            close(process);
            close(thread);
            arena.close();
        }, "conpty-close");
        t.setDaemon(true);
        t.start();
    }

    // -- plumbing ---------------------------------------------------------------------------------------------

    private static MethodHandle fn(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(K32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    private static MemorySegment handleSlot(Arena a) {
        return a.allocate(8);
    }

    private static MemorySegment get(MemorySegment slot) {
        return slot.get(ADDRESS, 0);
    }

    /** A COORD is two shorts, which the x64 ABI passes in a single register: X in the low word, Y in the high. */
    private static int coordValue(int cols, int rows) {
        return (cols & 0xFFFF) | (rows << 16);
    }

    private static void close(MemorySegment handle) {
        call(CLOSE_HANDLE, handle);
    }

    private static void check(int ok, String what) {
        if (ok == 0) throw new IllegalStateException(what + " failed");
    }

    private static int call(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
