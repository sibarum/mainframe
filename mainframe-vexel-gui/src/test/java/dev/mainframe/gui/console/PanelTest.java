package dev.mainframe.gui.console;

import dev.mainframe.Span;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.panel.Editor;
import dev.mainframe.panel.Event;
import dev.mainframe.panel.Panels;
import dev.mainframe.panel.Screen;
import dev.mainframe.value.Value;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel protocol, held to its word — with no window, no GPU and no keyboard.
 *
 * <p>What is checked here is the half of {@code PROTOCOL.md} an editor is responsible for: a screen described in
 * cells comes back as an event carrying every entry on it, the screen decides what a key means, an unknown part
 * is skipped rather than refused, and a display that goes away is a cancel and never an error. Those are the
 * promises MainFrame is written against, and every one of them would still compile if it broke.
 *
 * <p>The last test is the one that matters most: a whole form, driven end to end through {@link FormPanel} with
 * nothing standing in for anything. It fails a required field on purpose, because a form that could only be
 * filled in correctly would not be exercising the loop at all — the turn-by-turn shape of the protocol only shows
 * up on the second screen.
 *
 * <h2>Standing in for two threads</h2>
 * MainFrame asks on the job thread and blocks; the editor answers on the frame loop. Here the job thread is an
 * executor and the frame loop is the test thread, which calls {@link Panel#flush} the way {@code Console.tick}
 * does. That is the real arrangement rather than a simulation of one — the only thing missing is a window to draw
 * into, and nothing below needs one.
 */
final class PanelTest {

    /** How long the test waits on the other thread before calling it a failure rather than a slow machine. */
    private static final long TIMEOUT_SECONDS = 10;

    private Gui gui;
    private Panel panel;
    private ExecutorService job;
    /** Every raise and lower of the curtain, so the console's half of the swap can be checked too. */
    private final List<Boolean> curtain = new ArrayList<>();
    /** Stands in for the shell being busy: true keeps the panel up between screens, as a running command does. */
    private volatile boolean busy = true;
    /** The clock the caret blinks by, wound by hand so a blink test takes no time at all. */
    private volatile long now;
    /** Screens this test has already watched go up. See {@link #raised}. */
    private int seen;

    @BeforeEach
    void setUp() {
        gui = new Gui();
        gui.theme(Phosphor.THEME);
        panel = newPanel(null);
        job = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-job"));
    }

    /**
     * A panel on this Gui, with the file chooser it should claim -- or null for the machine that has none.
     *
     * <p>A whole new one rather than a setter, because whether there is a chooser is answered in {@code hello},
     * which MainFrame asks before the first screen. An editor that could grow the capability halfway through a
     * session would be an editor that had been guessing on the way in.
     */
    private Panel newPanel(Panel.Chooser chooser) {
        Ansi ansi = Ansi.of(gui.theme());
        Node host = gui.column();
        seen = 0;
        return new Panel(gui, host, ansi, new Scrollback(gui, gui.column(), ansi),
                up -> curtain.add(up), () -> busy, chooser, () -> now);
    }

    /** Start again on a panel that has a chooser, which is the machine with a native file dialog. */
    private void chooserIs(Panel.Chooser chooser) {
        panel.close();
        panel = newPanel(chooser);
    }

    @AfterEach
    void tearDown() {
        panel.close();
        job.shutdownNow();
        gui.close();
    }

    @Test
    void saysWhatItCanDoAndNotWhatItCannot() {
        Editor.Hello said = panel.hello();
        assertEquals(1, said.protocol());
        assertTrue(said.can("text"), "text is the one part every editor has");
        assertTrue(said.can("entry"));
        assertTrue(said.can("action"));
        assertTrue(said.can("box"));
        assertTrue(said.can("click"));
        // It measures the tube and reports a resize, so it says so -- and a form is laid out for the room there
        // actually is rather than for a guess. Rule five is a rule about not claiming what you cannot do.
        assertTrue(said.can("resize"), "an editor that measures its own room reports a resize");
        // And rule four: a choice it could paint but could not paint well is left to MainFrame to render down.
        assertFalse(said.can("choice"), "an editor with nowhere to open a list must not ask for one");
        // And pick, which is the same rule answered per machine: this panel was built without a chooser, which
        // is what a machine with no native file dialog gets. See aClaimedChooserOpens...
        assertFalse(said.can("pick"), "an editor with no file dialog must not claim one");
    }

    @Test
    void everyEntryComesBackNotJustTheOnesThatChanged() throws Exception {
        Screen screen = new Screen(7).title("NEW CONTACT").focus("name");
        screen.text(1, 3, "FULL NAME", "label");
        screen.entry(1, 20, "name", 20, "", "string");
        screen.text(2, 3, "EMAIL", "label");
        screen.entry(2, 20, "email", 20, "ada@example.com", "string");
        screen.key("F12", Event.SUBMIT, "Submit");
        screen.key("F3", Event.CANCEL, "Cancel");

        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        type("Ada");
        panel.ended("F12");
        Event answer = settled(asked);

        assertEquals(7, answer.screen(), "the event says which screen it is about");
        assertTrue(answer.is(Event.SUBMIT));
        assertEquals("F12", answer.key());
        assertEquals("name", answer.focus());
        assertEquals("Ada", answer.field("name"));
        // The one that was never touched comes back all the same: fields carries the whole screen, because an
        // optimisation that can disagree with itself is not worth the bytes.
        assertEquals("ada@example.com", answer.field("email"));
    }

    @Test
    void theScreenDecidesWhatAKeyMeans() throws Exception {
        Screen screen = new Screen(1).focus("only");
        screen.entry(1, 3, "only", 10, "", "string");
        screen.key("F5", Event.CANCEL, "Give up");

        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        // F4 is not on this screen's list, so it does nothing at all -- an editor inventing a key is an editor
        // holding a rule.
        panel.ended("F4");
        assertTrue(panel.up(), "an unlisted key must not end the transaction");
        panel.ended("F5");
        Event answer = settled(asked);
        assertTrue(answer.is(Event.CANCEL));
        assertEquals("F5", answer.key());
    }

    @Test
    void submitAndCancelWorkOnAScreenThatListedNoKeys() throws Exception {
        Screen screen = new Screen(1).focus("only");
        screen.entry(1, 3, "only", 10, "", "string");

        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        panel.ended("Esc");
        assertTrue(settled(asked).is(Event.CANCEL));
    }

    @Test
    void anUnknownPartIsSkippedAndTheRestOfTheScreenStands() throws Exception {
        // A part from a newer MainFrame: not a text, entry, choice, box or action. Rule two says its cells are
        // left blank and the screen is still a screen.
        Value.Rec strange = Value.Rec.of(
                "at", new Value.ListVal(List.of(new Value.Int(1), new Value.Int(3))),
                "hologram", new Value.Str("nobody-here-knows-what-this-is"));
        Screen screen = new Screen(4).focus("name");
        screen.text(2, 3, "NAME", "label");
        screen.entry(2, 20, "name", 12, "Grace", "string");
        Value.Rec message = withPart(screen.message(), strange);

        Future<Event> asked = ask(() -> panel.show(message));
        raised();
        panel.ended("Enter");
        Event answer = settled(asked);
        assertEquals("Grace", answer.field("name"), "one unreadable part must not cost the screen its fields");
        assertFalse(String.join("\n", panel.lines()).contains("hologram"),
                "an unknown part leaves its cells blank rather than printing itself");
    }

    @Test
    void aClosedPanelCancelsWhoeverIsWaiting() throws Exception {
        Screen screen = new Screen(1).focus("only");
        screen.entry(1, 3, "only", 10, "", "string");
        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        panel.close();
        Event answer = settled(asked);
        assertTrue(answer.is(Event.CANCEL), "a display that went away is a cancel, never an error");
    }

    @Test
    void theCurtainGoesUpOnceAndDownWhenTheAskingIsOver() throws Exception {
        Screen screen = new Screen(1).focus("only");
        screen.entry(1, 3, "only", 10, "", "string");
        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        assertEquals(List.of(true), curtain);
        panel.ended("Enter");
        settled(asked);
        // Still up: the command has not finished, so another screen may be a frame away.
        panel.flush();
        assertEquals(List.of(true), curtain);
        assertTrue(panel.up());
        busy = false;
        panel.flush();
        assertEquals(List.of(true, false), curtain);
        assertFalse(panel.up());
    }

    @Test
    void aWholeFormAsksAgainWhenAnAnswerWillNotDo() throws Exception {
        Form form = Form.read(new Value.ListVal(List.of(
                Value.Rec.of("name", new Value.Str("name"), "required", new Value.Bool(true)),
                new Value.Str("email"))), Span.NONE);

        Future<Value.Rec> filled = ask(() ->
                FormPanel.show(form, null, "New contact", panel, Path.of("").toAbsolutePath()));

        // First screen: submit with both fields empty. Whether that is acceptable is MainFrame's decision and
        // this editor has no opinion on it -- it sends what is on the screen and waits to be told.
        raised();
        int first = panel.showing();
        assertTrue(String.join("\n", panel.lines()).contains("NEW CONTACT"),
                "the form's title goes on the screen");
        panel.ended("F12");

        // Second screen: the form's answer to that. The required field is now marked wrong, and it is marked on a
        // screen rather than in a return value -- which is the turn-by-turn shape the protocol is, and rule six:
        // there is exactly one place the rules live and it is not this editor.
        raised();
        int second = panel.showing();
        assertNotEquals(first, second, "a form that asks again asks on a new screen");
        assertTrue(String.join("\n", panel.lines()).toLowerCase().contains("is required"),
                "what was wrong with the answer comes back on the screen");

        type("Ada Lovelace");
        panel.pressed(new KeyEvent(Key.DOWN, Set.of()));
        type("ada@example.com");
        panel.ended("F12");

        Value.Rec answers = settled(filled);
        assertEquals("Ada Lovelace", text(answers, "name"));
        assertEquals("ada@example.com", text(answers, "email"));
    }

    /**
     * A part is allowed to be taller than the row it was placed at, and the form has to lay out around that.
     *
     * <p>This editor does not claim {@code choice}, so MainFrame renders one down into the options as text and an
     * entry on the row beneath them — two rows where the form had counted one. What that cost, before the form
     * started asking the screen how far it had actually got, was whatever came next: the rule closing the form
     * came out with an entry punched through it, which is obvious in a picture and invisible in a source file.
     */
    @Test
    void aFieldThatTakesTwoRowsDoesNotLandOnWhatComesAfterIt() throws Exception {
        // The choice last, because what it lands on is whatever the form put next and the rule that closes the
        // form is the one thing every form has. A choice in the middle collides with the following field instead,
        // which is the same fault and a harder one to state.
        Form form = Form.read(new Value.ListVal(List.of(
                new Value.Str("email"),
                Value.Rec.of("name", new Value.Str("tier"), "choose", new Value.ListVal(List.of(
                        new Value.Str("free"), new Value.Str("team"), new Value.Str("enterprise")))))),
                Span.NONE);

        Future<Value.Rec> filled = ask(() ->
                FormPanel.show(form, null, "New customer", panel, Path.of("").toAbsolutePath()));
        raised();
        List<String> glass = panel.lines();
        for (String line : glass) {
            if (line.contains("===")) {
                assertFalse(line.contains("_"),
                        "a field was written through the rule that closes the form: |" + line + "|");
            }
        }
        // And the two rows really are two: the options on one, somewhere to type on the next.
        int options = -1;
        for (int i = 0; i < glass.size(); i++) {
            if (glass.get(i).contains("free / team / enterprise")) {
                options = i;
            }
        }
        assertTrue(options >= 0, "the options MainFrame rendered down are on the screen");
        assertTrue(glass.get(options + 1).contains("_"),
                "the entry the options belong to is on the row beneath them: |" + glass.get(options + 1) + "|");
        panel.ended("F3");
        assertNull(settled(filled));
    }

    @Test
    void givingUpOnAFormHandsBackNothing() throws Exception {
        Form form = Form.read(new Value.ListVal(List.of(new Value.Str("name"))), Span.NONE);
        Future<Value.Rec> filled = ask(() ->
                FormPanel.show(form, null, "New contact", panel, Path.of("").toAbsolutePath()));
        raised();
        panel.ended("F3");
        assertNull(settled(filled), "a cancel is a result, and the result is nothing");
    }

    // ---- getting about a screen ------------------------------------------------------------

    /**
     * Tab reaches the buttons, and reaching one is not pressing it.
     *
     * <p>The two halves matter equally. A screen whose Submit can only be clicked needs a mouse; a Tab that
     * pressed what it landed on would make walking a screen to look at it a way to lose data.
     */
    @Test
    void tabReachesAButtonWithoutPressingIt() throws Exception {
        Future<Event> asked = ask(() -> panel.show(twoFieldsAndAButton()));
        raised();
        assertEquals("name", panel.focused(), "a screen opens on the first thing to type in, never on a button");

        panel.pressed(key(Key.DOWN));
        assertEquals("email", panel.focused());
        panel.pressed(key(Key.DOWN));
        assertEquals("go", panel.focused(), "Tab stops at the button");
        assertTrue(panel.up(), "landing on a button must not press it");

        panel.pressed(key(Key.DOWN));
        assertEquals("name", panel.focused(), "the ring wraps");
        assertTrue(panel.up());
        panel.ended("Esc");
        settled(asked);
    }

    @Test
    void enterMovesOnAndThenPresses() throws Exception {
        Future<Event> asked = ask(() -> panel.show(twoFieldsAndAButton()));
        raised();
        type("Ada");

        panel.entered();
        assertEquals("email", panel.focused(), "Enter on a field is the next field, not a submit");
        assertTrue(panel.up());
        panel.entered();
        assertEquals("go", panel.focused());
        assertTrue(panel.up(), "Enter that arrives at a button has not pressed it yet");

        panel.entered();
        Event answer = settled(asked);
        assertTrue(answer.is(Event.CLICK), "Enter on a button is a press");
        assertEquals("go", answer.on());
        assertEquals("Ada", answer.field("name"), "and it carries the screen with it");
    }

    /**
     * The property the whole arrangement is for: Enter, over and over, fills in and sends a form.
     *
     * <p>Nothing here knows how many fields there are or where the button is. It types, then presses Enter until
     * something comes back — which is what somebody in a hurry does, and it has to be both possible and impossible
     * to do by accident. Both, because the first Enter after the last field lands <em>on</em> Submit rather than
     * pressing it.
     */
    @Test
    void enterEnoughTimesAndAValidFormSubmitsItself() throws Exception {
        Form form = Form.read(new Value.ListVal(List.of(
                Value.Rec.of("name", new Value.Str("name"), "required", new Value.Bool(true)),
                new Value.Str("email"))), Span.NONE);
        Future<Value.Rec> filled = ask(() ->
                FormPanel.show(form, null, "New contact", panel, Path.of("").toAbsolutePath()));

        raised();
        type("Ada Lovelace");
        panel.entered();
        type("ada@example.com");

        for (int presses = 0; presses < 8 && !filled.isDone(); presses++) {
            panel.entered();
            panel.flush();
            Thread.sleep(5);
        }
        Value.Rec answers = settled(filled);
        assertEquals("Ada Lovelace", text(answers, "name"));
        assertEquals("ada@example.com", text(answers, "email"));
    }

    /**
     * Hovering asks, clicking answers.
     *
     * <p>Driven by cell rather than by pixel, because a headless panel has no layout to hit-test against — the
     * two framework lookups that turn a pointer into a cell are the only part not exercised here, and everything
     * that decides what a pointer <em>does</em> is.
     */
    @Test
    void hoverHighlightsAndOnlyAClickMovesTheCaret() throws Exception {
        Future<Event> asked = ask(() -> panel.show(twoFieldsAndAButton()));
        raised();
        assertEquals("name", panel.focused());
        assertNull(panel.hovered());

        panel.hoverAt(1, 20);                           // over the second field
        assertEquals("email", panel.hovered(), "the pointer highlights what it is over");
        assertEquals("name", panel.focused(), "and moves nothing");

        panel.hoverAt(2, 3);                            // over the button
        assertEquals("go", panel.hovered());
        assertEquals("name", panel.focused());
        assertTrue(panel.up(), "hovering a button is not pressing it either");

        panel.hoverAt(9, 9);                            // off everything
        assertNull(panel.hovered());

        panel.clickAt(1, 22);                           // and now a click
        assertEquals("email", panel.focused(), "a click is what moves the caret");
        panel.ended("Esc");
        settled(asked);
    }

    @Test
    void aClickInsideAFieldLandsWhereThePointerWas() throws Exception {
        Screen screen = new Screen(1).focus("name");
        screen.entry(1, 3, "name", 20, "Ada Lovelace", "string");
        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        panel.clickAt(0, 4);                            // between the d and the a of "Ada"
        panel.typed('!');
        panel.ended("Esc");
        settled(asked);
        assertTrue(panel.lines().get(0).contains("Ad!a"),
                "the caret went where the pointer was: |" + panel.lines().get(0) + "|");
    }

    @Test
    void theCaretBlinksAndTypingHoldsItLit() throws Exception {
        Future<Event> asked = ask(() -> panel.show(twoFieldsAndAButton()));
        raised();
        assertTrue(panel.caret(), "a screen arrives with its caret lit");

        wind(600);
        assertFalse(panel.caret(), "and half a second later it is not");
        wind(600);
        assertTrue(panel.caret());

        wind(400);
        panel.typed('A');                               // typing puts it back on, mid-blink
        panel.flush();
        assertTrue(panel.caret(), "typing holds the caret solid rather than leaving it mid-flash");

        // A button has no caret to blink, so nothing flashes on a screen that is only buttons.
        panel.pressed(key(Key.DOWN));
        panel.pressed(key(Key.DOWN));
        assertEquals("go", panel.focused());
        wind(600);
        assertFalse(panel.caret());
        panel.ended("Esc");
        settled(asked);
    }

    /** The hint line says what Enter will do <em>now</em>, which is the cheapest way to be self-explanatory. */
    @Test
    void theHintLineFollowsTheCaret() throws Exception {
        Future<Event> asked = ask(() -> panel.show(twoFieldsAndAButton()));
        raised();
        assertTrue(hint().contains("next field"), "on a field: |" + hint() + "|");

        panel.pressed(key(Key.DOWN));
        panel.pressed(key(Key.DOWN));
        panel.flush();
        assertEquals("go", panel.focused());
        assertTrue(hint().contains("press Go"), "on a button, it names the button: |" + hint() + "|");
        panel.ended("Esc");
        settled(asked);
    }

    /**
     * A confirmation opens on the answer that changes nothing.
     *
     * <p>Which matters more now than it did: Enter presses whatever the caret is on, so where a yes/no screen
     * opens is the difference between a keystroke that does nothing and a keystroke that deletes something.
     */
    @Test
    void aConfirmationOpensOnNo() throws Exception {
        Future<Boolean> asked = ask(() ->
                Panels.confirm(panel, "go ahead?", List.of("trash old-invoice.txt")));
        raised();
        assertEquals("no", panel.focused());
        panel.entered();
        assertEquals(Boolean.FALSE, settled(asked), "Enter on an unmoved confirmation is a no");

        Future<Boolean> again = ask(() ->
                Panels.confirm(panel, "go ahead?", List.of("trash old-invoice.txt")));
        raised();
        panel.pressed(key(Key.DOWN));
        assertEquals("yes", panel.focused());
        panel.entered();
        assertEquals(Boolean.TRUE, settled(again), "and yes is one deliberate move away");
    }

    /**
     * Ctrl+V, which this editor has to do for itself.
     *
     * <p>A field on a screen is painted characters rather than a widget, so there is no text box underneath for
     * a paste to land in — nothing arrives unless this file makes it arrive. What it does is what typing does:
     * in at the caret, and the field's width is still the limit.
     */
    @Test
    void pasteGoesInAtTheCaretAndStopsWhereTypingWould() throws Exception {
        Screen screen = new Screen(4).focus("path");
        screen.text(1, 3, "JDK", "label");
        screen.entry(1, 12, "path", 30, "", "path");
        screen.text(2, 3, "NOTE", "label");
        screen.entry(2, 12, "note", 8, "", "string");
        screen.key("F12", Event.SUBMIT, "Submit");

        Future<Event> asked = ask(() -> panel.show(screen));
        raised();

        gui.clipboard().set("/opt/jdk-21");
        panel.pasted();
        type("/bin");                                   // and typing carries on from where the paste left off

        // A field is one line, so a clipboard with more than one on it gives up the rest rather than running
        // them together, and a field takes as much as it has room for rather than refusing the lot.
        panel.pressed(key(Key.DOWN));
        assertEquals("note", panel.focused());
        gui.clipboard().set("chosen by hand\nand then some");
        panel.pasted();
        panel.flush();

        panel.ended("F12");
        Event answer = settled(asked);
        assertEquals("/opt/jdk-21/bin", answer.field("path"));
        assertEquals("chosen b", answer.field("note"), "eight cells of field take eight characters");
    }

    /** A button is not somewhere text goes, so Ctrl+V on one is nothing rather than a stray paste elsewhere. */
    @Test
    void pasteOnSomethingThatIsNotAFieldDoesNothing() throws Exception {
        Screen screen = new Screen(5).focus("go");
        screen.entry(1, 3, "name", 10, "Ada", "string");
        screen.action(2, 3, "go", "[ Go ]", "F12");

        Future<Event> asked = ask(() -> panel.show(screen));
        raised();
        gui.clipboard().set("Grace");
        panel.pasted();
        panel.flush();
        panel.ended("Esc");
        assertEquals("Ada", settled(asked).field("name"), "a paste with nowhere to go goes nowhere");
    }

    /**
     * The file dialog, and the two things this editor owes MainFrame for claiming it.
     *
     * <p>The capability, so a form knows to send the offer instead of drawing its own chooser; and a way to the
     * dialog that somebody could actually find, because the Browse button MainFrame draws for every other editor
     * is exactly what claiming {@code pick} tells it to stop sending.
     */
    @Test
    void aClaimedChooserOpensAndWhatItGivesBackGoesInTheField() throws Exception {
        List<String> opened = new ArrayList<>();
        chooserIs((pick, answer) -> {
            opened.add(pick + " at |" + answer + "|");
            return Path.of("/opt/jdk-21");
        });
        assertTrue(panel.hello().can("pick"), "an editor that has a chooser says so");

        Screen screen = new Screen(9).focus("jdk");
        screen.text(1, 3, "JDK", "label");
        screen.entry(1, 12, "jdk", 30, "/opt", "path", "folder");
        screen.key("F12", Event.SUBMIT, "Submit");

        Future<Event> shown = ask(() -> panel.show(screen));
        raised();
        assertTrue(hint().contains("Ctrl+O"), "the key line names the way in: |" + hint() + "|");

        panel.browsed();
        panel.flush();
        panel.ended("F12");
        Event answer = settled(shown);

        assertEquals(Path.of("/opt/jdk-21").toString(), answer.field("jdk"),
                "what the dialog gave back is the answer, whole");
        // The word off the wire and the field as it stands, both untouched: the chooser is told where to open
        // and never told what may be chosen.
        assertEquals(List.of("folder at |/opt|"), opened);
    }

    /** Backing out of a dialog is somebody deciding to type it after all, and not an answer of any kind. */
    @Test
    void backingOutOfTheChooserLeavesTheFieldExactlyAsItWas() throws Exception {
        chooserIs((pick, answer) -> null);
        Screen screen = new Screen(10).focus("jdk");
        screen.entry(1, 3, "jdk", 20, "/opt", "path", "folder");

        Future<Event> shown = ask(() -> panel.show(screen));
        raised();
        panel.browsed();
        panel.flush();
        panel.ended("Esc");
        assertEquals("/opt", settled(shown).field("jdk"));
    }

    /**
     * Two fields with nothing to offer: one that was never offered a chooser, and one offered a chooser in a
     * word this editor has never heard of. Neither opens anything, and neither says it would — rule three, which
     * is the same rule that skips an unknown part rather than refusing the screen.
     */
    @Test
    void anOfferThisEditorCannotHonourIsNoOfferAtAll() throws Exception {
        chooserIs((pick, answer) -> Path.of("/chosen"));
        Screen screen = new Screen(11).focus("plain");
        screen.entry(1, 3, "plain", 20, "as typed", "string");
        screen.entry(2, 3, "odd", 20, "left alone", "path", "sideways");

        Future<Event> shown = ask(() -> panel.show(screen));
        raised();
        assertFalse(hint().contains("Ctrl+O"), "a field with no offer must not advertise one: |" + hint() + "|");
        panel.browsed();

        panel.pressed(key(Key.DOWN));
        panel.flush();
        assertEquals("odd", panel.focused());
        assertFalse(hint().contains("Ctrl+O"), "nor a field offering a word from the future: |" + hint() + "|");
        panel.browsed();

        panel.flush();
        panel.ended("Esc");
        Event answer = settled(shown);
        assertEquals("as typed", answer.field("plain"));
        assertEquals("left alone", answer.field("odd"));
    }

    // ---- driving the two threads -----------------------------------------------------------

    /** Two fields and a button, which is the shape of every form and of nothing else. */
    private static Screen twoFieldsAndAButton() {
        Screen screen = new Screen(3).title("PEOPLE");
        screen.entry(1, 20, "name", 20, "", "string");
        screen.entry(2, 20, "email", 20, "", "string");
        screen.action(3, 3, "go", "Go", null);
        return screen;
    }

    private static KeyEvent key(Key which) {
        return new KeyEvent(which, Set.of());
    }

    /** The editor's own row, under the screen: what it says the keys do. */
    private String hint() {
        for (String line : panel.lines()) {
            if (line.startsWith("Tab")) {
                return line;
            }
        }
        return "";
    }

    /** Move the blink clock on by {@code millis} and let the frame loop notice. */
    private void wind(long millis) {
        now += millis * 1_000_000L;
        panel.flush();
    }

    private <T> Future<T> ask(Callable<T> question) {
        return job.submit(question);
    }

    /**
     * The frame loop, until the next screen is on the glass.
     *
     * <p>Counted rather than compared by id, because an id does not tell one screen from the next in general: two
     * confirmations running one after the other are both screen 0, that being the only screen either of them has.
     * What the test wants to know is whether the display has painted again, so it asks that.
     */
    private void raised() throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            panel.flush();
            if (panel.up() && panel.painted() > seen) {
                seen = panel.painted();
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("no screen went up inside " + TIMEOUT_SECONDS + " seconds");
    }

    /**
     * What the job thread came back with, flushing while waiting.
     *
     * <p>The flushing is not politeness: a form answers one screen and immediately puts up the next, so a test
     * that only waited would be waiting for a thread that is waiting for it.
     */
    private <T> T settled(Future<T> asked) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (asked.isDone()) {
                return asked.get();
            }
            panel.flush();
            Thread.sleep(1);
        }
        throw new AssertionError("nothing came back inside " + TIMEOUT_SECONDS + " seconds");
    }

    private void type(String text) {
        for (int i = 0; i < text.length(); i++) {
            panel.typed(text.charAt(i));
        }
    }

    private static String text(Value.Rec record, String name) {
        Value value = record.get(name);
        return value instanceof Value.Str string ? string.value() : String.valueOf(value);
    }

    /** One more part on a screen's message, for the parts this editor is not supposed to understand. */
    private static Value.Rec withPart(Value.Rec message, Value.Rec extra) {
        Value.Rec screen = (Value.Rec) message.get("screen");
        List<Value> parts = new ArrayList<>(((Value.ListVal) screen.get("parts")).items());
        parts.add(extra);
        return Value.Rec.of("screen", screen.with("parts", new Value.ListVal(List.copyOf(parts))));
    }
}
