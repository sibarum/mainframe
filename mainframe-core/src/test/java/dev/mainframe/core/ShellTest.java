package dev.mainframe.core;

import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Shown;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.PtyPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.atchung.Tick;
import sibarum.probe.Cause;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tab's shell against a real pseudo-terminal: this platform's (ConPTY or a Unix pty, from the test class path), with
 * the framework's part played by calling the halves as it would. {@code DestinationsTest}, in the framework, holds how
 * the wiring calls them; this holds what they do with a real program.
 */
class ShellTest {

    @TempDir
    Path dir;

    /** Every read sent, in order, as the bus delivers it when the tick runs. */
    private final BlockingQueue<Output> out = new LinkedBlockingQueue<>();
    private final Atchung bus = Atchung.create();
    private final Tick tick = bus.tick(null);

    /**
     * A shell sending on a bus of this test's, heard by an inbox on its tick: a {@code Sender} is not a functional
     * interface, so "collect what it sends" is a bus and a tick (Vexplore's FN-16; this project's FN-4).
     */
    private Shell shell() {
        tick.inbox("heard").subscribe(Output.TOPIC, out::add, 1024);
        return new Shell(Sender.outside(bus, Output.TOPIC, Cause.source("test.shell", Cause.Kind.IO)));
    }

    /** Read what the shell sends until {@code wanted} has come, saying {@link Shown} for each as the view would. */
    private String readUntil(Shell shell, Shell.Session s, String wanted) throws InterruptedException {
        StringBuilder seen = new StringBuilder();
        long until = System.nanoTime() + 20_000_000_000L;
        while (!seen.toString().contains(wanted)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("never read '" + wanted + "'; read: " + seen);
            }
            tick.run(System.nanoTime() + 1_000_000L, Tick.ONE_THREAD);
            for (Output o; (o = out.poll(10, TimeUnit.MILLISECONDS)) != null; ) {
                seen.append(o.text());
                shell.write(s, new Shown(o.id()));
            }
        }
        return seen.toString();
    }

    @Test
    void aProgramsOutputIsSentAsItIsReadAndItsEndIsTheReply() throws Exception {
        Shell shell = shell();
        OpenShell open = new OpenShell(1, PtyPlatform.current().runLine("echo mainframe-says-hi"), dir, 80, 24);
        Shell.Session s = shell.open(open);
        assertNotNull(s.pty(), s.problem());
        try {
            ShellEnded end = shell.run(open, s);
            assertEquals(new ShellEnded(1, 0, ""), end);
            readUntil(shell, s, "mainframe-says-hi");
        } finally {
            shell.close(s);
        }
    }

    @Test
    void whatIsTypedReachesTheProgram() throws Exception {
        Shell shell = shell();
        OpenShell open = new OpenShell(2, PtyPlatform.current().shells().getFirst().commandLine(), dir, 80, 24);
        Shell.Session s = shell.open(open);
        assertNotNull(s.pty(), s.problem());
        Thread reader = Thread.ofPlatform().start(() -> shell.run(open, s));
        try {
            shell.write(s, new Typed(2, "echo typed-ok\r"));
            readUntil(shell, s, "typed-ok");
        } finally {
            shell.close(s);
            reader.join(10_000);
        }
    }

    @Test
    void aProgramThatWillNotStartIsAReplyNotAFault() {
        Shell shell = shell();
        OpenShell open = new OpenShell(3, "no-such-program-mainframe.exe", dir, 80, 24);
        Shell.Session s = shell.open(open);
        if (s.pty() != null) {
            // A platform whose pty starts a shell that then fails to find the program: its exit is the answer.
            ShellEnded end = shell.run(open, s);
            shell.close(s);
            assertTrue(end.code() != 0, "a program that is not there does not exit 0");
            return;
        }
        assertNull(s.pty());
        ShellEnded end = shell.run(open, s);
        assertEquals(3, end.id());
        assertTrue(end.problem().startsWith("Could not start no-such-program-mainframe.exe"), end.problem());
        shell.close(s);
    }

    /** Run the tick a moment and take what it delivered. */
    private List<Output> delivered(long millis) throws InterruptedException {
        List<Output> got = new ArrayList<>();
        long until = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < until) {
            tick.run(System.nanoTime() + 1_000_000L, Tick.ONE_THREAD);
            out.drainTo(got);
            Thread.sleep(5);
        }
        return got;
    }

    /**
     * A program writing far faster than anyone shows it gets two outputs through and then waits, its reader stopping
     * at {@link Shell#HOLD}; each {@code Shown} lets everything waiting through as one, and nothing is lost (FN-9).
     */
    @Test
    void aTabsOutputIsPacedByWhatTheViewHasShown() throws Exception {
        String line = "0123456789 the quick brown fox jumps over the lazy dog 0123456789\n";
        int lines = 50_000;
        Path big = dir.resolve("big.txt");
        Files.writeString(big, line.repeat(lines));
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String cmd = (windows ? "type " : "cat ") + PtyPlatform.current().quote(big.toString());
        Shell shell = shell();
        OpenShell open = new OpenShell(4, PtyPlatform.current().runLine(cmd), dir, 120, 30);
        Shell.Session s = shell.open(open);
        assertNotNull(s.pty(), s.problem());
        CompletableFuture<ShellEnded> end = CompletableFuture.supplyAsync(() -> shell.run(open, s));
        try {
            List<Output> first = delivered(1500);
            assertEquals(Shell.IN_FLIGHT, first.size(), "no more than two before the view says it has shown one");
            assertTrue(s.pace().waiting() >= Shell.HOLD, "the reader held back: " + s.pace().waiting());
            assertTrue(!end.isDone(), "the program waits on its terminal");

            shell.write(s, new Shown(4));
            List<Output> next = delivered(300);
            assertEquals(1, next.size(), "one shown lets one through");
            assertTrue(next.getFirst().text().length() >= Shell.HOLD, "and it is everything that was waiting");

            StringBuilder all = new StringBuilder();
            first.forEach(o -> all.append(o.text()));
            next.forEach(o -> all.append(o.text()));
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!end.isDone() || s.pace().waiting() > 0 || !out.isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "the program never finished");
                shell.write(s, new Shown(4));
                delivered(20).forEach(o -> all.append(o.text()));
            }
            // The reader may publish its last Output after the loop's last tick and before run returns, so the loop can
            // see the program done with that Output still on the bus: one more tick delivers it. (It failed once in six
            // without this, 49,999 lines of 50,000.)
            delivered(100).forEach(o -> all.append(o.text()));
            assertEquals(0, end.get().code());
            int count = all.toString().split("quick brown fox", -1).length - 1;
            assertEquals(lines, count, "every line arrived");
        } finally {
            shell.close(s);
        }
    }
}
