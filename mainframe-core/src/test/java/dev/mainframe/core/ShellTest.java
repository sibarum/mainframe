package dev.mainframe.core;

import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Typed;
import dev.sibarum.suite.tty.PtyPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.atchung.Tick;
import sibarum.probe.Cause;

import java.nio.file.Path;
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

    private String readUntil(String wanted) throws InterruptedException {
        StringBuilder seen = new StringBuilder();
        long until = System.nanoTime() + 20_000_000_000L;
        while (!seen.toString().contains(wanted)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("never read '" + wanted + "'; read: " + seen);
            }
            tick.run(System.nanoTime() + 1_000_000L, Tick.ONE_THREAD);
            for (Output o; (o = out.poll(10, TimeUnit.MILLISECONDS)) != null; ) {
                seen.append(o.text());
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
            readUntil("mainframe-says-hi");
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
            readUntil("typed-ok");
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
}
