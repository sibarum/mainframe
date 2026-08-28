package dev.mainframe.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.Span;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/** A form on a screen somebody else is painting. */
class PanelTest {

    @TempDir
    Path here;

    private static final String VISIT = """
            [{"name": "client", "required": true, "min": 2},
             {"name": "office", "choose": ["london", "berlin"], "required": true},
             {"name": "rate", "type": "size"},
             {"name": "expenses", "type": "table",
              "fields": [{"name": "what", "required": true},
                         {"name": "amount", "type": "int", "required": true}]}]
            """;

    private Form form(String source) {
        return Form.read(Json.parse(source, Span.NONE), Span.NONE);
    }

    private Value.Rec show(FakeEditor editor, String fields) {
        return FormPanel.show(form(fields), null, "New visit", editor, here);
    }

    // ---- what goes out -------------------------------------------------------------------

    @Test
    void everyFieldIsOnTheScreenAtOnce() {
        FakeEditor editor = FakeEditor.rich().submits("client", "Acme", "office", "berlin");
        show(editor, VISIT);

        String parts = editor.parts(0);
        assertTrue(parts.contains("text: CLIENT"), parts);
        assertTrue(parts.contains("entry: client"), parts);
        assertTrue(parts.contains("choice: office"), parts);
        assertTrue(parts.contains("entry: rate"), parts);
        // A screen says which keys end it, so the editor can show them.
        assertTrue(Values.display(editor.screen(0).get("keys")).contains("F12"), parts);
    }

    @Test
    void anEntrySaysWhatItHoldsSoTheEditorCanHelp() {
        FakeEditor editor = FakeEditor.rich().submits("client", "Acme", "office", "berlin");
        show(editor, VISIT);
        // A hint for the editor's own keyboard, never a rule -- MainFrame validates.
        assertTrue(editor.parts(0).contains("holds: size"), editor.parts(0));
    }

    @Test
    void theCursorStartsInTheFirstField() {
        FakeEditor editor = FakeEditor.rich().submits("client", "Acme", "office", "berlin");
        show(editor, VISIT);
        assertEquals("client", editor.focus(0));
    }

    // ---- what comes back -----------------------------------------------------------------

    @Test
    void theAnswersComeBackTypedFromTextTheEditorSent() {
        FakeEditor editor = FakeEditor.rich()
                .submits("client", "Acme", "office", "berlin", "rate", "1mb");
        Value.Rec answers = show(editor, VISIT);

        // The editor only ever sends text. It was never told what a size is.
        assertEquals("Acme", Values.display(answers.get("client")));
        assertEquals(new Value.Size(1024 * 1024), answers.get("rate"));
        assertEquals("[]", Values.display(answers.get("expenses")));
    }

    @Test
    void aProblemComesBackOnTheScreenAndTheCursorGoesToIt() {
        FakeEditor editor = FakeEditor.rich()
                .submits("client", "A", "office", "berlin", "rate", "lots")
                .submits("client", "Acme", "office", "berlin", "rate", "1mb");
        Value.Rec answers = show(editor, VISIT);

        assertEquals(2, editor.screenCount());
        String errors = editor.styled(1, "error");
        assertTrue(errors.contains("client needs at least 2 characters"), errors);
        assertTrue(errors.contains("cannot read \"lots\" as a size"), errors);
        // The first problem down the screen, not the first one noticed.
        assertEquals("client", editor.focus(1));
        assertEquals("Acme", Values.display(answers.get("client")));
    }

    @Test
    void whatWasTypedIsShownBackWhileItIsBeingRefused() {
        FakeEditor editor = FakeEditor.rich()
                .submits("client", "Acme", "office", "berlin", "rate", "lots")
                .submits("client", "Acme", "office", "berlin", "rate", "1mb");
        show(editor, VISIT);
        // Not the old value, and not blank: what they typed, next to why it is wrong.
        assertEquals("lots", editor.entry(1, "rate"));
    }

    @Test
    void cancelHandsBackNothingAtAll() {
        FakeEditor editor = FakeEditor.rich().cancels();
        assertNull(show(editor, VISIT));
    }

    @Test
    void anEditorThatGoesAwayIsACancel() {
        // Nothing queued, so the first show gets no answer at all.
        assertNull(show(FakeEditor.rich(), VISIT));
    }

    // ---- lists of details ----------------------------------------------------------------

    @Test
    void anEntryIsAddedOnAScreenOfItsOwn() {
        FakeEditor editor = FakeEditor.rich()
                .clicks("add:expenses", "client", "Acme", "office", "berlin")
                .submits("what", "train", "amount", "120")
                .submits("client", "Acme", "office", "berlin");
        Value.Rec answers = show(editor, VISIT);

        assertEquals("[{what: train, amount: 120}]", Values.display(answers.get("expenses")));
        // The sub-screen is the entry's own form, under the group's name.
        assertEquals("EXPENSES", Values.display(editor.screen(1).get("title")));
        assertTrue(editor.parts(1).contains("entry: amount"), editor.parts(1));
    }

    @Test
    void anEntryCanBeTakenBackOutAgain() {
        FakeEditor editor = FakeEditor.rich()
                .clicks("add:expenses", "client", "Acme", "office", "berlin")
                .submits("what", "train", "amount", "120")
                .clicks("add:expenses", "client", "Acme", "office", "berlin")
                .submits("what", "hotel", "amount", "300")
                .clicks("drop:expenses:1", "client", "Acme", "office", "berlin")
                .submits("client", "Acme", "office", "berlin");
        Value.Rec answers = show(editor, VISIT);

        // The thing the printed form cannot offer, because it has nowhere to put
        // the button.
        assertEquals("[{what: hotel, amount: 300}]", Values.display(answers.get("expenses")));
    }

    @Test
    void backingOutOfAnEntryLeavesTheFormAlone() {
        FakeEditor editor = FakeEditor.rich()
                .clicks("add:expenses", "client", "Acme", "office", "berlin")
                .cancels()
                .submits("client", "Acme", "office", "berlin");
        Value.Rec answers = show(editor, VISIT);

        assertEquals("[]", Values.display(answers.get("expenses")));
        assertEquals("Acme", Values.display(answers.get("client")));
    }

    @Test
    void theEditorNeverHoldsTheList() {
        FakeEditor editor = FakeEditor.rich()
                .clicks("add:expenses", "client", "Acme", "office", "berlin")
                .submits("what", "train", "amount", "120")
                // The editor claims the list is something else. It is not the
                // editor's to hold, so nothing happens.
                .submits("client", "Acme", "office", "berlin", "expenses", "nonsense");
        Value.Rec answers = show(editor, VISIT);
        assertEquals("[{what: train, amount: 120}]", Values.display(answers.get("expenses")));
    }

    // ---- rendering down for a simpler editor ------------------------------------------------

    @Test
    void anEditorWithoutChoiceGetsAnEntryAndTheOptionsInWriting() {
        FakeEditor editor = FakeEditor.plain().submits("client", "Acme", "office", "berlin");
        show(editor, VISIT);

        String parts = editor.parts(0);
        assertFalse(parts.contains("choice:"), parts);
        assertTrue(parts.contains("entry: office"), parts);
        assertTrue(parts.contains("london / berlin"), parts);
    }

    @Test
    void anEditorWithoutBoxesOrActionsStillGetsAWholeForm() {
        FakeEditor editor = FakeEditor.plain().submits("client", "Acme", "office", "berlin");
        Value.Rec answers = show(editor, VISIT);

        String parts = editor.parts(0);
        assertFalse(parts.contains("box:"), parts);
        assertFalse(parts.contains("action:"), parts);
        // And it is told why the list is only being shown, rather than left to wonder.
        assertTrue(parts.contains("cannot add entries"), parts);
        assertEquals("Acme", Values.display(answers.get("client")));
    }

    // ---- browsing for a file ----------------------------------------------------------------

    private static final String TOOLING = """
            [{"name": "jdk", "type": "path", "pick": "folder", "required": true},
             {"name": "log", "type": "path", "pick": "save"}]
            """;

    /** A folder to walk into, so a chooser has somewhere to go. */
    private void tree() throws Exception {
        Files.createDirectories(here.resolve("tools").resolve("jdk-21"));
    }

    /**
     * A path under the test's own directory, written the way it goes on the wire.
     *
     * <p>Forward slashes, because an event is JSON and a Windows path in a JSON
     * string is a run of escape sequences nobody meant. MainFrame reads either.
     */
    private String at(String name) {
        return Values.portable(here.resolve(name));
    }

    /** And the same path the way this machine writes one, which is what comes back. */
    private String local(String... names) {
        Path path = here;
        for (String name : names) path = path.resolve(name);
        return path.toString();
    }

    @Test
    void anEditorWithAChooserOfItsOwnIsHandedTheField() throws Exception {
        tree();
        FakeEditor editor = FakeEditor.choosing().submits("jdk", at("tools"));
        Value.Rec answers = show(editor, TOOLING);

        String parts = editor.parts(0);
        // The offer goes on the entry, and nothing else does: no button, because
        // this editor has something better than anything MainFrame could draw.
        assertTrue(parts.contains("pick: folder"), parts);
        assertTrue(parts.contains("pick: save"), parts);
        assertFalse(parts.contains("action: pick:jdk"), parts);
        // What comes back is text in an entry like anything else, read as a path.
        assertEquals(local("tools"), Values.display(answers.get("jdk")));
    }

    @Test
    void anEditorWithoutOneGetsAButtonAndAScreenBehindIt() throws Exception {
        tree();
        FakeEditor editor = FakeEditor.rich()
                .clicks("pick:jdk")                        // the button beside the field
                .clicks("at:1")                            // in the chooser: walk into tools
                .submits("where", at("tools"))             // and take it
                .submits("jdk", at("tools"));
        Value.Rec answers = show(editor, TOOLING);

        // No offer was made to this editor, because it did not claim one.
        assertFalse(editor.parts(0).contains("pick: folder"), editor.parts(0));
        assertTrue(editor.parts(0).contains("action: pick:jdk"), editor.parts(0));
        // The chooser is a screen of its own, made of nothing this editor had to
        // learn: text, an entry and things to click.
        assertEquals("CHOOSE A FOLDER", Values.display(editor.screen(1).get("title")));
        assertTrue(editor.parts(1).contains("entry: where"), editor.parts(1));
        assertTrue(editor.parts(1).contains("[tools]"), editor.parts(1));
        assertEquals(local("tools"), Values.display(answers.get("jdk")));
    }

    @Test
    void aChooserOnlyListsFoldersWhenAFolderIsWanted() throws Exception {
        tree();
        Files.writeString(here.resolve("notes.txt"), "hello");
        FakeEditor editor = FakeEditor.rich()
                .clicks("pick:jdk")
                .cancels()
                .submits("jdk", at("tools"));
        show(editor, TOOLING);

        assertTrue(editor.parts(1).contains("[tools]"), editor.parts(1));
        assertFalse(editor.parts(1).contains("notes.txt"), editor.parts(1));
    }

    @Test
    void backingOutOfTheChooserLeavesTheFieldAlone() throws Exception {
        tree();
        FakeEditor editor = FakeEditor.rich()
                .clicks("pick:jdk", "jdk", at("tools"))
                .cancels()
                .submits("jdk", at("tools"));
        Value.Rec answers = show(editor, TOOLING);

        // Backing out of a chooser is somebody deciding to type it after all, so
        // the form is still there with what was in it.
        assertEquals(local("tools"), Values.display(answers.get("jdk")));
    }

    @Test
    void savingAsWillTakeANameThatIsNotThereYet() throws Exception {
        tree();
        FakeEditor editor = FakeEditor.rich()
                .clicks("pick:log")
                .clicks("at:1")                            // into tools
                .submits("where", at("tools"), "name", "run.log")
                .submits("jdk", at("tools"));
        Value.Rec answers = show(editor, TOOLING);

        assertEquals(local("tools", "run.log"), Values.display(answers.get("log")));
    }

    @Test
    void aChooserSaysWhyItWillNotTakeSomething() throws Exception {
        tree();
        FakeEditor editor = FakeEditor.rich()
                .clicks("pick:jdk")
                .submits("where", at("nowhere"))
                .cancels()
                .submits("jdk", at("tools"));
        show(editor, TOOLING);

        // The refusal comes back as a new screen with an error on it, which is the
        // only way MainFrame ever says no to an editor.
        assertTrue(editor.styled(2, "error").contains("no folder called nowhere"),
                editor.parts(2));
    }

    // ---- the rules that keep it evergreen ---------------------------------------------------

    @Test
    void anEventFromANewerEditorIsReadNotRefused() {
        FakeEditor editor = FakeEditor.rich()
                .sends("{\"event\": {\"did\": \"submit\", \"key\": \"F12\", \"gestures\": [\"swipe\"],"
                        + " \"fields\": {\"client\": \"Acme\", \"office\": \"berlin\"},"
                        + " \"pressure\": 0.8}}");
        Value.Rec answers = show(editor, VISIT);
        assertEquals("Acme", Values.display(answers.get("client")));
    }

    @Test
    void anEventNobodyUnderstandsIsACancelRatherThanACrash() {
        assertNull(show(FakeEditor.rich().sends("{\"event\": {\"did\": \"levitate\"}}"), VISIT));
        assertNull(show(FakeEditor.rich().sends("{\"quux\": 12}"), VISIT));
    }

    @Test
    void closingThePanelIsACancel() {
        assertNull(show(FakeEditor.rich().sends("{\"bye\": {}}"), VISIT));
    }

    @Test
    void anEditorThatSaysNothingIsAssumedToDoTheLeast() {
        Editor.Hello silent = Editor.Hello.read(Value.Nothing.INSTANCE);
        assertTrue(silent.can("text"));
        assertTrue(silent.can("entry"));
        assertFalse(silent.can("choice"));
    }

    @Test
    void aScreenIsAnOrdinaryMainFrameValue() {
        FakeEditor editor = FakeEditor.rich().submits("client", "Acme", "office", "berlin");
        show(editor, VISIT);

        // Written out and read back, it is the same value -- which is the whole
        // reason there is no screen format, only the written form.
        Value.Rec screen = editor.screen(0);
        String json = Values.toJson(screen, 0);
        assertTrue(Values.equal(screen, Json.parse(json, Span.NONE)), json);
        // And it is source MainFrame can read, like everything else it writes.
        assertTrue(Parser.parse(Values.source(screen)) != null);
    }

    // ---- fitting the room ----------------------------------------------------------------

    /**
     * A form is laid out for the room the editor has <em>now</em>, not for the room it had when the form started.
     *
     * <p>Which is what makes a window draggable while a form is up. The editor reports a resize, MainFrame lays
     * the screen out again, and because a resize carries the screen's values like any other event, what had been
     * typed is still there afterwards. Nothing here is the editor's decision: an editor may not reflow a screen,
     * having been given cells rather than a paragraph.
     */
    @Test
    void aFormIsLaidOutAgainForTheRoomThereIsNow() {
        FakeEditor editor = FakeEditor.rich()               // 90 columns to begin with
                .resizesTo(24, 60, "client", "Acme")        // and then the window is dragged narrower
                .submits("client", "Acme", "office", "berlin");
        Value.Rec answers = show(editor, VISIT);

        assertEquals(2, editor.screenCount(), "a resize gets a new screen, not a reflowed one");
        assertTrue(cols(editor.screen(1)) < cols(editor.screen(0)),
                "the second screen is laid out narrower: " + cols(editor.screen(0))
                        + " then " + cols(editor.screen(1)));
        assertEquals("Acme", editor.entry(1, "client"), "what was typed survives the resize");
        assertEquals("Acme", Values.display(answers.get("client")));
    }

    /**
     * A hint too long for the room is wrapped, not written through whatever is to the right of it.
     *
     * <p>The room being a list of details, which draws a frame around itself: prose is the only thing on a screen
     * whose length MainFrame does not choose, so it is the only thing that can overrun. What that looked like, on
     * the display that found it, was the frame's own right edge printed through the middle of a word.
     */
    @Test
    void aHintTooLongForTheFrameIsWrappedInsideIt() {
        FakeEditor editor = FakeEditor.rich().cancels();
        show(editor, """
                [{"name": "expenses", "type": "table",
                  "help": "every expense this visit is claiming for, itemised, with the amount \
                in whole pounds and a description somebody in accounts will recognise a month from now",
                  "fields": [{"name": "what"}, {"name": "amount", "type": "int"}]}]
                """);

        Value.Rec box = part(editor.screen(0), "box");
        assertNotNull(box, "a list of details draws a frame around itself");
        int edge = at(box, 1) + wide(box) - 1;
        int first = at(box, 0) + 1;
        int last = at(box, 0) + high(box) - 2;
        boolean wrapped = false;
        for (Value part : ((Value.ListVal) editor.screen(0).get("parts")).items()) {
            Value.Rec record = (Value.Rec) part;
            if (record.get("box") != null || !(record.get("text") instanceof Value.Str text)) continue;
            int row = at(record, 0);
            // The form's own rules run the whole width, above and below the frame; what is being checked is what
            // is written *inside* it.
            if (row < first || row > last) continue;
            int ends = at(record, 1) + text.value().length() - 1;
            assertTrue(ends < edge, "this ran through the frame at column " + edge + ": "
                    + Values.display(record));
            if ("hint".equals(Values.display(record.get("style")))) wrapped = true;
        }
        assertTrue(wrapped, "and the hint is in there rather than dropped: " + editor.parts(0));
    }

    /** How wide MainFrame said it laid a screen out. */
    private static int cols(Value.Rec screen) {
        return (int) ((Value.Int) ((Value.Rec) screen.get("size")).get("cols")).value();
    }

    private static Value.Rec part(Value.Rec screen, String kind) {
        for (Value part : ((Value.ListVal) screen.get("parts")).items()) {
            if (((Value.Rec) part).get(kind) != null) return (Value.Rec) part;
        }
        return null;
    }

    /** One coordinate of a part's {@code at}: 0 for the row, 1 for the column. */
    private static int at(Value.Rec part, int axis) {
        return (int) ((Value.Int) ((Value.ListVal) part.get("at")).items().get(axis)).value();
    }

    private static int high(Value.Rec box) {
        return (int) ((Value.Int) ((Value.ListVal) box.get("box")).items().get(0)).value();
    }

    private static int wide(Value.Rec box) {
        return (int) ((Value.Int) ((Value.ListVal) box.get("box")).items().get(1)).value();
    }
}
