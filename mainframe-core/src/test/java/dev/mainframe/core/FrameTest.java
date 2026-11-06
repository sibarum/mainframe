package dev.mainframe.core;

import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.Front;
import dev.mainframe.core.Messages.OpenShell;
import dev.mainframe.core.Messages.Output;
import dev.mainframe.core.Messages.Resized;
import dev.mainframe.core.Messages.ShellEnded;
import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.HandClock;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame, alone: no window, no view and no shell. It owns an inbox on a tick of this test's, as it will own one on
 * the framework's; each test publishes what the view, the job or a shell would, runs the tick, and reads what the frame
 * sent. Replies are published by hand, which makes the odd orders (a shell that ends before its tab is shown, a size
 * for a tab already gone) as easy to test as the common one.
 */
class FrameTest {

    private static final Path WORK = Path.of("C:/work/api");
    private static final Path WEB = Path.of("C:/work/web");

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Frame frame;
    /** Everything the frame sent, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        tick = bus.tick(null).alarm(new HandClock(bus)).whenOverdue(Tick.Overdue.FAIL);
        frame = new Frame(tick.inbox("frame"));
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(StartupWanted.TOPIC, AddTab.TOPIC, Front.TOPIC, OpenShell.TOPIC, Output.TOPIC,
                CloseTab.TOPIC, WindowCommand.TOPIC)) {
            listen(heard, t);
        }
    }

    @AfterEach
    void restoreTheFatalPolicy() {
        Atchung.onFatal(previous);
    }

    private <T> void listen(Inbox heard, Topic<T> topic) {
        heard.subscribe(topic, sent::add, 256);
    }

    /** Publish {@code message} as its sender would, and run the tick until nothing is left to deliver. */
    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    private void started(Path... dirs) {
        tell(Startup.TOPIC, new Startup(List.of(dirs), "powershell.exe"));
    }

    @Test
    void theMainWindowOpeningAsksWhatTheLaunchWanted() {
        tell(WindowOpened.TOPIC, new WindowOpened("settings"));
        assertTrue(took(StartupWanted.class).isEmpty(), "another window opening starts nothing");
        tell(WindowOpened.TOPIC, new WindowOpened(CloseRequested.MAIN));
        assertEquals(1, took(StartupWanted.class).size());
    }

    @Test
    void aTabPerFolderTheFirstInFrontNamedForItsFolder() {
        started(WORK, WEB);
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new AddTab(1, "api"), new AddTab(2, "web")), took(AddTab.class));
        assertTrue(all.contains(new Front(1)));
        assertEquals(2, frame.state().tabs().size());
    }

    @Test
    void aTabsShellStartsAtTheSizeItsScreenWasFirstLaidOutAtAndOnlyOnce() {
        started(WORK);
        sent.clear();
        tell(Resized.TOPIC, new Resized(1, 120, 33));
        assertEquals(List.of(new OpenShell(1, "powershell.exe", WORK, 120, 33)), took(OpenShell.class));
        tell(Resized.TOPIC, new Resized(1, 100, 30));
        assertTrue(took(OpenShell.class).isEmpty(), "a later size is the shell's to hear, not a second start");
    }

    @Test
    void aSizeForNoTabStartsNothing() {
        tell(Resized.TOPIC, new Resized(9, 80, 24));
        assertTrue(took(OpenShell.class).isEmpty());
    }

    @Test
    void aShellEndingClosesItsTabAndTheNextComesToTheFront() {
        started(WORK, WEB);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new CloseTab(1)), took(CloseTab.class));
        assertTrue(all.contains(new Front(2)));
        assertTrue(all.stream().noneMatch(m -> m instanceof WindowCommand), "a tab is left, so the window stays");
    }

    @Test
    void theLastShellEndingClosesTheWindow() {
        started(WORK);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        assertEquals(List.of(WindowCommand.close(CloseRequested.MAIN)), took(WindowCommand.class));
        assertTrue(frame.state().tabs().isEmpty());
    }

    @Test
    void aProgramThatWouldNotStartKeepsItsTabAndSaysWhyInIt() {
        started(WORK);
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, -1, "Could not start nosuch.exe: not found"));
        List<Object> all = List.copyOf(sent);
        assertEquals(List.of(new Output(1, "\r\nCould not start nosuch.exe: not found\r\n")), took(Output.class));
        assertTrue(all.stream().noneMatch(m -> m instanceof CloseTab || m instanceof WindowCommand));
        assertEquals(1, frame.state().tabs().size());
    }

    @Test
    void aShellEndingForATabAlreadyGoneIsIgnored() {
        started(WORK, WEB);
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        sent.clear();
        tell(ShellEnded.TOPIC, new ShellEnded(1, 0, ""));
        assertTrue(sent.isEmpty());
    }
}
