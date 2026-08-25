package dev.mainframe.gui;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam an application plugs into, held to its word — with no window, no GPU and no keyboard.
 *
 * <p>What is checked here is the contract a host writes against: a {@link ConsoleApp}'s commands reach the
 * registry and run on the job thread, {@link ConsoleApp#started} fires once the session is up, and a command that
 * needs the frame loop gets there through {@link ConsoleContext#onGuiThread} and no sooner. Those are the three
 * promises that would silently rot, because an application that broke them would still compile.
 */
final class ConsoleAppTest {

    /** How long a test waits on the job thread before calling it a failure rather than a slow machine. */
    private static final long TIMEOUT_SECONDS = 10;

    /** An app that records what the console did to it, and one command that records that it ran. */
    private static final class Recorder implements ConsoleApp {

        final List<String> events = new ArrayList<>();
        final CountDownLatch ran = new CountDownLatch(1);
        volatile Path sawCwd;
        volatile boolean guiTaskRun;
        volatile int ticks;
        /** Whether this one claims to have a window. Off by default: most apps are only commands. */
        volatile boolean hasWindow;
        volatile int launched;

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public boolean launchable() {
            return hasWindow;
        }

        @Override
        public void launch(ConsoleContext console) {
            launched++;
        }

        @Override
        public String summary() {
            return "remembers being called";
        }

        @Override
        public void commands(Registry registry, ConsoleContext console) {
            events.add("commands");
            Signature signature = Signature.named("ping", name())
                    .summary("say that this app's command ran")
                    .input(ValueType.NOTHING)
                    .output(ValueType.NOTHING)
                    .effect(Signature.Effect.READS)
                    .build();
            registry.add(new Builtin() {
                @Override
                public Signature signature() {
                    return signature;
                }

                @Override
                public Value run(Args args) {
                    sawCwd = console.cwd();
                    console.onGuiThread(() -> guiTaskRun = true);
                    ran.countDown();
                    return Value.Nothing.INSTANCE;
                }
            });
        }

        @Override
        public void started(ConsoleContext console) {
            events.add("started");
        }
    }

    private static Console console(ConsoleApp... apps) {
        ConsoleSpec.Builder spec = ConsoleSpec.builder();
        for (ConsoleApp app : apps) {
            spec.app(app);
        }
        return new Console(spec.build());
    }

    @Test
    void anAppsCommandsAreRegisteredBeforeItIsStarted() throws Exception {
        Recorder recorder = new Recorder();
        Path here = Path.of("").toAbsolutePath();
        try (Console console = console(recorder)) {
            console.start(here);

            // Commands first, then started: an app's start-up line has to be able to run its own commands.
            assertEquals(List.of("commands", "started"), recorder.events);

            console.submit("ping");
            assertTrue(recorder.ran.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "ping never ran");
            assertEquals(here, recorder.sawCwd, "the command saw a different cwd than the console started in");

            // The task is queued, not run: a command body is on the job thread, and a window built from there
            // would be built while the frame loop was drawing one.
            assertFalse(recorder.guiTaskRun, "a command's GUI task ran before a frame");
            console.tick();
            assertTrue(recorder.guiTaskRun, "tick() did not service the queued GUI task");
        }
    }

    @Test
    void startingTwiceDoesNotRestartTheSession() {
        Recorder recorder = new Recorder();
        try (Console console = console(recorder)) {
            console.start(Path.of("").toAbsolutePath());
            console.start(Path.of("").toAbsolutePath());
            // Showing a console that is already up raises it; it must not greet again or double its commands.
            assertEquals(List.of("commands", "started"), recorder.events);
        }
    }

    @Test
    void anAppIsTickedByTheConsoleRatherThanByTheHost() {
        Recorder recorder = new Recorder();
        try (Console console = console(recorder)) {
            console.start(Path.of("").toAbsolutePath());
            // Not before the console is ticked: an app that is ticked from its own constructor would be running
            // before the frame loop it belongs to exists.
            assertEquals(0, recorder.ticks);
            console.tick();
            console.tick();
            assertEquals(2, recorder.ticks, "plugging an app in has to be the whole of wiring it up");
        }
    }

    @Test
    void launchRefusesWhatItCannotOpenRatherThanFailingQuietly() throws Exception {
        Recorder recorder = new Recorder();
        recorder.hasWindow = true;
        try (Console console = console(recorder)) {
            console.start(Path.of("").toAbsolutePath());

            // There is no window system here, so launch has nothing to open onto. What must not happen is the
            // app's launch() running anyway: it would build a window with no loop to draw it.
            console.submit("launch \"recorder\"");
            settle(console);
            assertEquals(0, recorder.launched, "launch ran with no application behind the console");

            // A name nothing answers to is refused before any of that is considered.
            console.submit("launch \"nothing-by-that-name\"");
            settle(console);
            assertEquals(0, recorder.launched);
        }
    }

    /** Wait for the job thread to finish whatever was submitted, then service the frame it asked for. */
    private static void settle(Console console) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        Thread.sleep(20);
        while (console.busy() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        console.tick();
    }

    @Test
    void aConsoleWithNothingPluggedInIsStillAShell() {
        try (Console console = console()) {
            console.start(Path.of("").toAbsolutePath());
            console.submit("version");
            console.tick();
            assertFalse(console.isOpen(), "no window was opened, so none should be reported open");
        }
    }
}
