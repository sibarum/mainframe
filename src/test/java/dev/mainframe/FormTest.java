package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/** Asking a person for a record, and holding one to the same rules. */
class FormTest {

    @TempDir
    Path here;

    /** A form worth reusing: text with rules, a menu, and a list of details. */
    private static final String CONTACT = """
            let contact = [
              {name: "name", label: "full name", required: true, min: 2},
              {name: "email", required: true, match: "[^@ ]+@[^@ ]+"},
              {name: "phones", type: "table", fields: [
                 {name: "kind", choose: ["mobile", "home"]},
                 {name: "number", required: true}
              ]}
            ]
            """;

    private Value.Rec answers(Value value) {
        return assertInstanceOf(Value.Rec.class, value, "a form hands back a record");
    }

    private String text(Value.Rec record, String field) {
        return Values.display(record.get(field));
    }

    // ---- filling one in ------------------------------------------------------------------

    @Test
    void aFormCollectsWhatIsTypedIntoIt() {
        Mf mf = Mf.typing(here, "Ada Lovelace", "ada@example.com", "");
        Value.Rec record = answers(mf.eval("form [\"name\", \"email\"]"));
        assertEquals("Ada Lovelace", text(record, "name"));
        assertEquals("ada@example.com", text(record, "email"));
        assertEquals(2, record.fields().size(), mf.printed());
    }

    @Test
    void theFieldsAreShownWithTheirRules() {
        Mf mf = Mf.typing(here, "Ada", "ada@example.com", "n", "");
        mf.eval(CONTACT + "form $contact --title=\"New contact\"");
        String shown = mf.printed();
        assertTrue(shown.contains("NEW CONTACT"), shown);
        assertTrue(shown.contains("FULL NAME"), shown);
        assertTrue(shown.contains("required"), shown);
        assertTrue(shown.contains("at least 2 characters"), shown);
        assertTrue(shown.contains("matching [^@ ]+@[^@ ]+"), shown);
        assertTrue(shown.contains("!cancel"), shown);
    }

    @Test
    void everyAnswerKeepsItsType() {
        Mf mf = Mf.typing(here, "42", "4mb", "y", "2026-01-01", "7d");
        Value.Rec record = answers(mf.eval("""
                form [{name: "age", type: "int"},
                      {name: "quota", type: "size"},
                      {name: "active", type: "bool"},
                      {name: "joined", type: "time"},
                      {name: "every", type: "duration"}] --no-review
                """));
        assertEquals(new Value.Int(42), record.get("age"));
        assertEquals(new Value.Size(4L * 1024 * 1024), record.get("quota"));
        assertEquals(new Value.Bool(true), record.get("active"));
        assertInstanceOf(Value.Time.class, record.get("joined"));
        assertEquals(new Value.Duration(7L * 24 * 60 * 60 * 1000), record.get("every"));
    }

    @Test
    void theWordNothingStaysTextInATextField() {
        Mf mf = Mf.typing(here, "nothing");
        Value.Rec record = answers(mf.eval("form [\"note\"] --no-review"));
        assertEquals(new Value.Str("nothing"), record.get("note"));
    }

    @Test
    void anOptionalFieldLeftBlankHoldsNothing() {
        Mf mf = Mf.typing(here, "");
        Value.Rec record = answers(mf.eval("form [\"note\"] --no-review"));
        assertEquals(Value.Nothing.INSTANCE, record.get("note"));
    }

    // ---- saying what is wrong ------------------------------------------------------------

    @Test
    void aRequiredFieldWillNotTakeABlank() {
        Mf mf = Mf.typing(here, "", "Ada", "");
        Value.Rec record = answers(mf.eval("form [{name: \"name\", required: true}]"));
        assertTrue(mf.printed().contains("name is required"), mf.printed());
        assertEquals("Ada", text(record, "name"));
    }

    @Test
    void tooFewCharactersIsSaidPlainly() {
        Mf mf = Mf.typing(here, "A", "Ada", "");
        Value.Rec record = answers(mf.eval("form [{name: \"name\", required: true, min: 2}]"));
        assertTrue(mf.printed().contains("name needs at least 2 characters, and that is 1 character"),
                mf.printed());
        assertEquals("Ada", text(record, "name"));
    }

    @Test
    void aPatternHasToMatchTheWholeAnswer() {
        Mf mf = Mf.typing(here, "ada@example.com and more", "ada@example.com", "");
        Value.Rec record = answers(mf.eval(
                "form [{name: \"email\", required: true, match: \"[^@ ]+@[^@ ]+\"}]"));
        assertTrue(mf.printed().contains("email does not match [^@ ]+@[^@ ]+"), mf.printed());
        assertEquals("ada@example.com", text(record, "email"));
    }

    @Test
    void aBoundOnANumberComparesValuesNotCharacters() {
        Mf mf = Mf.typing(here, "500kb", "4mb", "");
        Value.Rec record = answers(mf.eval(
                "form [{name: \"quota\", type: \"size\", required: true, min: 1mb}]"));
        assertTrue(mf.printed().contains("quota has to be at least 1.0 MB"), mf.printed());
        assertEquals(new Value.Size(4L * 1024 * 1024), record.get("quota"));
    }

    @Test
    void theLanguageExplainsATypeItCannotRead() {
        Mf mf = Mf.typing(here, "soon", "2026-01-01", "");
        answers(mf.eval("form [{name: \"joined\", type: \"time\", required: true}]"));
        assertTrue(mf.printed().contains("cannot read \"soon\""), mf.printed());
        assertTrue(mf.printed().contains("2026-08-21"), mf.printed());
    }

    @Test
    void aMenuTakesTheTextOrTheNumberBesideIt() {
        Mf mf = Mf.typing(here, "3", "2");
        Value.Rec record = answers(mf.eval(
                "form [{name: \"status\", required: true, choose: [\"open\", \"closed\"]}] --no-review"));
        assertTrue(mf.printed().contains("status has to be one of: open, closed"), mf.printed());
        assertEquals("closed", text(record, "status"));
    }

    // ---- lists of details ---------------------------------------------------------------

    @Test
    void aGroupCollectsAsManyEntriesAsYouLike() {
        Mf mf = Mf.typing(here,
                "Ada", "ada@example.com",
                "y", "mobile", "555-0100",
                "y", "2", "555-0200",
                "n", "");
        Value.Rec record = answers(mf.eval(CONTACT + "form $contact"));
        Value phones = record.get("phones");
        assertEquals("[{kind: mobile, number: 555-0100}, {kind: home, number: 555-0200}]",
                Values.display(phones), mf.printed());
    }

    @Test
    void aGroupThatIsRequiredAsksForItsFirstEntryWithoutAsking() {
        Mf mf = Mf.typing(here, "mobile", "555-0100", "n", "");
        Value.Rec record = answers(mf.eval("""
                form [{name: "phones", type: "table", required: true,
                       fields: ["kind", "number"]}]
                """));
        assertEquals("[{kind: mobile, number: 555-0100}]", Values.display(record.get("phones")));
        assertTrue(mf.printed().contains("at least 1 entry"), mf.printed());
    }

    @Test
    void anOptionalGroupCanBeLeftEmpty() {
        Mf mf = Mf.typing(here, "n", "");
        Value.Rec record = answers(mf.eval("""
                form [{name: "phones", type: "table", fields: ["kind", "number"]}]
                """));
        assertEquals("[]", Values.display(record.get("phones")));
        assertTrue(mf.printed().contains("(no entries)"), mf.printed());
    }

    @Test
    void clearStartsAListOfDetailsAgain() {
        Mf mf = Mf.typing(here, "y", "mobile", "555-0100", "!clear", "y", "home", "555-0200", "n", "");
        Value.Rec record = answers(mf.eval("""
                form [{name: "phones", type: "table", fields: ["kind", "number"]}]
                """));
        assertEquals("[{kind: home, number: 555-0200}]", Values.display(record.get("phones")),
                mf.printed());
        assertTrue(mf.printed().contains("the entries were cleared"), mf.printed());
    }

    // ---- the ways out --------------------------------------------------------------------

    @Test
    void cancelAbandonsTheWholeForm() {
        Mf mf = Mf.typing(here, "Ada", "!cancel");
        assertEquals(Value.Nothing.INSTANCE, mf.eval("form [\"name\", \"email\"]"));
        assertTrue(mf.printed().contains("cancelled -- nothing was entered"), mf.printed());
    }

    @Test
    void runningOutOfInputIsACancelRatherThanAGuess() {
        Mf mf = Mf.typing(here);
        assertEquals(Value.Nothing.INSTANCE, mf.eval("form [\"name\"]"));
        assertTrue(mf.printed().contains("cancelled"), mf.printed());
    }

    @Test
    void backGoesToTheFieldBefore() {
        Mf mf = Mf.typing(here, "one", "!back", "uno", "two");
        Value.Rec record = answers(mf.eval("form [\"a\", \"b\"] --no-review"));
        assertEquals("uno", text(record, "a"));
        assertEquals("two", text(record, "b"));
    }

    @Test
    void notSubmittingWalksTheFieldsAgain() {
        Mf mf = Mf.typing(here, "one", "n", "two", "");
        Value.Rec record = answers(mf.eval("form [\"a\"]"));
        assertEquals("two", text(record, "a"));
        assertTrue(mf.printed().contains("what you entered"), mf.printed());
    }

    @Test
    void aBackslashMakesTheRestOfTheLineData() {
        Mf mf = Mf.typing(here, "\\!cancel");
        Value.Rec record = answers(mf.eval("form [\"name\"] --no-review"));
        assertEquals("!cancel", text(record, "name"));
    }

    // ---- editing what is already there --------------------------------------------------

    @Test
    void blankKeepsWhatWasPipedIn() {
        Mf mf = Mf.typing(here, "", "");
        Value.Rec record = answers(mf.eval(
                "echo {name: \"Ada\", email: \"ada@example.com\"} | form [\"name\", \"email\"] --no-review"));
        assertEquals("Ada", text(record, "name"));
        assertEquals("ada@example.com", text(record, "email"));
        assertTrue(mf.printed().contains("blank keeps Ada"), mf.printed());
    }

    @Test
    void fieldsTheFormDoesNotAskAboutAreCarriedThrough() {
        Mf mf = Mf.typing(here, "");
        Value.Rec record = answers(mf.eval(
                "echo {name: \"Ada\", id: 7} | form [\"name\"] --no-review"));
        assertEquals(new Value.Int(7), record.get("id"));
        assertTrue(mf.printed().contains("carried through untouched: id"), mf.printed());
    }

    @Test
    void clearEmptiesAFieldThatCameInWithAValue() {
        Mf mf = Mf.typing(here, "!clear");
        Value.Rec record = answers(mf.eval("echo {note: \"old\"} | form [\"note\"] --no-review"));
        assertEquals(Value.Nothing.INSTANCE, record.get("note"));
    }

    @Test
    void aDefaultIsOfferedTheSameWay() {
        Mf mf = Mf.typing(here, "");
        Value.Rec record = answers(mf.eval(
                "form [{name: \"kind\", default: \"note\"}] --no-review"));
        assertEquals("note", text(record, "kind"));
    }

    @Test
    void aValueOfTheWrongTypeIsNotOfferedQuietly() {
        Mf mf = Mf.typing(here, "42");
        Value.Rec record = answers(mf.eval(
                "echo {age: \"not a number\"} | form [{name: \"age\", type: \"int\"}] --no-review"));
        assertEquals(new Value.Int(42), record.get("age"));
        assertTrue(mf.warnings().contains("age arrived as a string"), mf.warnings());
    }

    // ---- the definition is checked before anything is asked -------------------------------

    @Test
    void aFieldKeyThatDoesNotExistGetsADidYouMean() {
        MfError error = mf().error("form [{name: \"x\", requred: true}]");
        assertEquals("E1203", error.code());
        assertTrue(error.hints().contains("did you mean required?"), error.hints().toString());
    }

    @Test
    void aTypeThatDoesNotExistGetsADidYouMean() {
        MfError error = mf().error("form [{name: \"x\", type: \"strng\"}]");
        assertEquals("E1204", error.code());
        assertTrue(error.hints().contains("did you mean string?"), error.hints().toString());
    }

    @Test
    void twoFieldsCannotShareAName() {
        assertEquals("E1205", mf().errorCode("form [\"a\", \"a\"]"));
    }

    @Test
    void aFormNeedsAtLeastOneField() {
        assertEquals("E1201", mf().errorCode("form []"));
    }

    @Test
    void aFieldIsANameOrARecord() {
        assertEquals("E1202", mf().errorCode("form [1, 2]"));
        assertEquals("E1202", mf().errorCode("form [{label: \"no name\"}]"));
    }

    @Test
    void aPatternOnANumberIsRefusedWithTheFix() {
        MfError error = mf().error("form [{name: \"code\", type: \"int\", match: \"[0-9]{5}\"}]");
        assertEquals("E1206", error.code());
        assertTrue(error.hints().toString().contains("type: \"string\""), error.hints().toString());
    }

    @Test
    void aBoundHasToBeComparableWithTheField() {
        assertEquals("E1206", mf().errorCode("form [{name: \"n\", min: \"two\"}]"));
        assertEquals("E1206", mf().errorCode("form [{name: \"n\", type: \"time\", min: 3}]"));
    }

    @Test
    void boundsThatCancelEachOutAreRefused() {
        MfError error = mf().error("form [{name: \"n\", min: 5, max: 2}]");
        assertEquals("E1206", error.code());
        assertTrue(error.getMessage().contains("larger than its max"), error.getMessage());
    }

    @Test
    void aDefaultThatBreaksItsOwnRulesIsRefused() {
        MfError error = mf().error("form [{name: \"n\", min: 5, default: \"ab\"}]");
        assertEquals("E1206", error.code());
        assertTrue(error.getMessage().contains("breaks its own rules"), error.getMessage());
    }

    @Test
    void aTableOfEntriesHasToSayWhatAnEntryHolds() {
        assertEquals("E1207", mf().errorCode("form [{name: \"phones\", type: \"table\"}]"));
    }

    @Test
    void aGroupInsideAGroupIsRefused() {
        assertEquals("E1207", mf().errorCode("""
                form [{name: "a", type: "table", fields: [
                        {name: "b", type: "table", fields: ["c"]}]}]
                """));
    }

    @Test
    void aBadPatternIsRefusedBeforeAnythingIsAsked() {
        assertEquals("E1206", mf().errorCode("form [{name: \"x\", match: \"[unclosed\"}]"));
    }

    // ---- when there is nobody to ask ------------------------------------------------------

    @Test
    void aFormWithNobodyToAskStopsRatherThanAssuming() {
        MfError error = mf().error("form [\"name\"]");
        assertEquals("E1209", error.code());
        assertTrue(error.hints().toString().contains("form-check"), error.hints().toString());
    }

    @Test
    void formFillsInOneRecordAndSaysSo() {
        assertEquals("E1208", mf().errorCode("echo 1 2 | form [\"name\"]"));
    }

    // ---- the same rules, without a keyboard ------------------------------------------------

    @Test
    void formCheckPassesGoodDataStraightThrough() {
        Mf mf = mf();
        Value result = mf.eval(CONTACT
                + "echo {name: \"Ada\", email: \"ada@example.com\", phones: [{number: \"555\"}]}"
                + " | form-check $contact");
        assertEquals("{name: Ada, email: ada@example.com, phones: [{number: 555}]}",
                Values.display(result));
    }

    @Test
    void formCheckReportsEveryProblemAtOnce() {
        MfError error = mf().error(CONTACT
                + "echo {name: \"A\", email: \"nope\", phones: [{kind: \"home\"}]}"
                + " | form-check $contact");
        assertEquals("E1210", error.code());
        assertEquals("3 answers do not fit these fields", error.getMessage());
        String hints = error.hints().toString();
        assertTrue(hints.contains("full name needs at least 2 characters"), hints);
        assertTrue(hints.contains("email does not match"), hints);
        assertTrue(hints.contains("phones entry 1: number is required"), hints);
    }

    @Test
    void formCheckSaysWhichRowIsWrong() {
        MfError error = mf().error("""
                let people = [{name: "Ada"}, {name: "B"}]
                echo $people | form-check [{name: "name", required: true, min: 2}]
                """);
        assertEquals("E1210", error.code());
        assertTrue(error.getMessage().contains("row 2"), error.getMessage());
    }

    @Test
    void formCheckSaysHowToGiveAColumnItsType() {
        MfError error = mf().error(
                "echo {joined: \"2026-01-01\"} | form-check [{name: \"joined\", type: \"time\"}]");
        assertEquals("E1210", error.code());
        assertTrue(error.getMessage().contains("cast {joined: time}"), error.getMessage());
    }

    @Test
    void aFormSurvivesBeingWrittenDownAndReadBack() {
        Mf mf = Mf.typing(here, "Ada", "ada@example.com", "n", "");
        mf.eval(CONTACT + "echo $contact | save ./contact-form.json");
        Value.Rec record = answers(mf.eval(
                "form (open ./contact-form.json) --title=\"New contact\""));
        assertEquals("Ada", text(record, "name"));
        assertTrue(mf.printed().contains("at least 2 characters"), mf.printed());
    }

    // ---- saving the answers and starting from them again ----------------------------------

    @Test
    void savedAnswersComeBackWithTheirTypes() {
        Mf mf = mf();
        mf.eval("echo {client: \"Acme\", rate: 1mb, seen: 2026-01-01, visits: 3} | form-save defaults");
        Value.Rec saved = answers(mf.eval("form-recall defaults"));
        assertEquals("Acme", text(saved, "client"));
        assertEquals(new Value.Size(1024 * 1024), saved.get("rate"));
        assertEquals(new Value.Int(3), saved.get("visits"));
        assertInstanceOf(Value.Time.class, saved.get("seen"));
    }

    @Test
    void aFormStartsFromWhatWasSaved() {
        Mf setup = mf();
        setup.eval("echo {name: \"Ada\", email: \"ada@example.com\"} | form-save defaults");

        Mf mf = Mf.typing(here, "", "");
        Value.Rec record = answers(mf.eval(
                "form [\"name\", \"email\"] --prefill=defaults --no-review"));
        assertEquals("Ada", text(record, "name"));
        assertEquals("ada@example.com", text(record, "email"));
        assertTrue(mf.printed().contains("blank keeps Ada"), mf.printed());
    }

    @Test
    void aListOfDetailsStartsFromWhatWasSaved() {
        Mf setup = mf();
        setup.eval("echo {phones: [{kind: \"mobile\", number: \"555-0100\"}]} | form-save defaults");

        Mf mf = Mf.typing(here, "y", "work", "555-0200", "n");
        Value.Rec record = answers(mf.eval(
                "form [{name: \"phones\", type: \"table\", fields: [\"kind\", \"number\"]}]"
                        + " --prefill=defaults --no-review"));
        assertEquals("[{kind: mobile, number: 555-0100}, {kind: work, number: 555-0200}]",
                Values.display(record.get("phones")), mf.printed());
        assertTrue(mf.printed().contains("[1] {kind: mobile, number: 555-0100}"), mf.printed());
    }

    @Test
    void whatIsPipedInBeatsWhatWasSaved() {
        Mf setup = mf();
        setup.eval("echo {name: \"Ada\", tier: \"free\"} | form-save defaults");

        Mf mf = Mf.typing(here, "", "");
        Value.Rec record = answers(mf.eval(
                "echo {name: \"Grace\"} | form [\"name\", \"tier\"] --prefill=defaults --no-review"));
        assertEquals("Grace", text(record, "name"));
        assertEquals("free", text(record, "tier"));
    }

    @Test
    void savedFieldsTheFormDoesNotAskAboutAreCarriedThrough() {
        Mf setup = mf();
        setup.eval("echo {name: \"Ada\", tier: \"team\"} | form-save defaults");

        Mf mf = Mf.typing(here, "");
        Value.Rec record = answers(mf.eval("form [\"name\"] --prefill=defaults --no-review"));
        assertEquals("team", text(record, "tier"));
        assertTrue(mf.printed().contains("carried through untouched: tier"), mf.printed());
    }

    @Test
    void theFirstTimeRoundThereIsNothingSavedAndThatIsFine() {
        Mf mf = Mf.typing(here, "Ada");
        Value.Rec record = answers(mf.eval("form [\"name\"] --prefill=defaults --no-review"));
        assertEquals("Ada", text(record, "name"));
        assertTrue(mf.printed().contains("nothing is saved as defaults yet"), mf.printed());
    }

    @Test
    void aMisspeltPrefillNameSaysWhatIsThere() {
        Mf setup = mf();
        setup.eval("echo {name: \"Ada\"} | form-save defaults");

        Mf mf = Mf.typing(here, "Grace");
        mf.eval("form [\"name\"] --prefill=defualts --no-review");
        assertTrue(mf.printed().contains("did you mean defaults?"), mf.printed());
    }

    @Test
    void formRecallListsWhatHasBeenSaved() {
        Mf mf = mf();
        mf.eval("echo {name: \"Ada\", tier: \"team\"} | form-save defaults");
        mf.eval("echo {name: \"Grace\"} | form-save last-contact");
        Value listed = mf.eval("form-recall");
        assertEquals(2, Values.rows(listed).size(), Values.display(listed));
        assertEquals("defaults", Values.display(Values.rows(listed).getFirst().get("name")));
        assertEquals("name, tier", Values.display(Values.rows(listed).getFirst().get("fields")));
    }

    @Test
    void nothingSavedYetIsSaidRatherThanShownAsAnEmptyTable() {
        Mf mf = mf();
        mf.eval("form-recall");
        assertTrue(mf.printed().contains("nothing saved yet"), mf.printed());
    }

    @Test
    void recallingAnUnknownNameSaysWhatThereIs() {
        Mf mf = mf();
        mf.eval("echo {name: \"Ada\"} | form-save defaults");
        MfError error = mf.error("form-recall defualts");
        assertEquals("E1212", error.code());
        assertTrue(error.hints().contains("did you mean defaults?"), error.hints().toString());
        assertTrue(error.hints().toString().contains("you have: defaults"), error.hints().toString());
    }

    @Test
    void savingSaysWhatItWouldDoAndDoesNothing() {
        Mf mf = mf();
        mf.eval("echo {name: \"Ada\"} | form-save defaults --dry-run");
        assertTrue(mf.printed().contains("dry run"), mf.printed());
        assertTrue(mf.printed().contains("save the answers saved as defaults (1 field)"), mf.printed());
        assertEquals("E1212", mf.errorCode("form-recall defaults"));
    }

    @Test
    void savingOverSomethingSaysThatIsWhatItIsDoing() {
        Mf mf = mf();
        mf.eval("echo {name: \"Ada\"} | form-save defaults");
        mf.eval("echo {name: \"Grace\"} | form-save defaults --dry-run");
        assertTrue(mf.printed().contains("replace the answers saved as defaults"), mf.printed());
        // The dry run left the first one alone.
        assertEquals("Ada", text(answers(mf.eval("form-recall defaults")), "name"));
    }

    @Test
    void forgettingIsDestructiveSoItAsksFirst() {
        Mf mf = mf();
        mf.eval("echo {name: \"Ada\"} | form-save defaults");
        assertEquals("E302", mf.errorCode("form-forget defaults"));
        assertEquals("Ada", text(answers(mf.eval("form-recall defaults")), "name"));

        mf.eval("form-forget defaults --yes");
        assertEquals("E1212", mf.errorCode("form-recall defaults"));
    }

    @Test
    void forgettingSomethingThatIsNotThereSaysSo() {
        assertEquals("E1212", mf().errorCode("form-forget defaults --yes"));
    }

    @Test
    void aSavedNameIsOneWord() {
        Mf mf = mf();
        assertEquals("E1211", mf.errorCode("echo {a: 1} | form-save ../escape"));
        assertEquals("E1211", mf.errorCode("echo {a: 1} | form-save prefs.json"));
        assertEquals("E1211", mf.errorCode("form-recall \"a/b\""));
        assertEquals("E1211", mf.errorCode("form [\"name\"] --prefill=\"a.b\""));
    }

    @Test
    void formSaveKeepsARecordAndNothingElse() {
        assertEquals("E307", mf().errorCode("echo [{a: 1}, {a: 2}] | form-save defaults"));
    }

    @Test
    void savedFormDataIsAnOrdinaryFileYouCanOpen() {
        Mf mf = mf();
        mf.eval("echo {client: \"Acme\", rate: 1mb} | form-save defaults");
        // The store holds MainFrame's own written form, so open reads it like
        // anything else -- and the size is still a size.
        Value reopened = mf.eval("open \"" + portable(here.resolve(".mainframe/forms/defaults.mf"))
                + "\" | get rate | first");
        assertEquals(new Value.Size(1024 * 1024), reopened);
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private Mf mf() { return new Mf(here); }
}
