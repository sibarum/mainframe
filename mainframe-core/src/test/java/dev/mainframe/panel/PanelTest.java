package dev.mainframe.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
