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

        @Override
        public String name() {
            return "recorder";
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
    void aConsoleWithNothingPluggedInIsStillAShell() {
        try (Console console = console()) {
            console.start(Path.of("").toAbsolutePath());
            console.submit("version");
            console.tick();
            assertFalse(console.isOpen(), "no window was opened, so none should be reported open");
        }
    }
}
