package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.value.Value;

/** Changing the environment and the PATH while the shell is running. */
class EnvironmentTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    // ---- the environment itself --------------------------------------------------------

    @Test
    void settingAndReadingBack() {
        Mf mf = mf();
        mf.eval("env-set DEMO_TOKEN \"abc123\"");
        assertEquals(new Value.Str("abc123"), mf.eval("env DEMO_TOKEN"));
        assertEquals("abc123", mf.session().env().get("DEMO_TOKEN"));
    }

    @Test
    void aChangeIsVisibleToTheVeryNextCommand() {
        Mf mf = mf();
        assertEquals("E901", mf.errorCode("env DEMO_ONE"));
        mf.eval("env-set DEMO_ONE \"1\"");
        assertEquals(new Value.Str("1"), mf.eval("env DEMO_ONE"));
        mf.eval("env-remove DEMO_ONE");
        assertEquals("E901", mf.errorCode("env DEMO_ONE"));
    }

    @Test
    void envSetReportsWhatItReplaced() {
        Mf mf = mf();
        mf.eval("env-set DEMO_TWO \"first\"");
        Value again = mf.eval("env-set DEMO_TWO \"second\"");
        assertEquals(new Value.Str("first"), ((Value.Rec) again).get("was"));
    }

    @Test
    void theEnvironmentIsATableLikeEverythingElse() {
        Mf mf = mf();
        mf.eval("env-set DEMO_FIND_ME \"yes\"");
        Value found = mf.eval("env | where name == \"DEMO_FIND_ME\"");
        assertEquals(1, ((Value.ListVal) found).items().size());
    }

    @Test
    void variablesOtherThingsRelyOnNeedForce() {
        Mf mf = mf();
        assertEquals("E904", mf.errorCode("env-remove PATH"));
        assertNotNull(mf.session().env().get("PATH"));
        mf.eval("env-remove PATH --force");
        assertNull(mf.session().env().get("PATH"));
    }

    @Test
    void unsettingSomethingThatIsNotSetSaysSo() {
        assertEquals("E903", mf().errorCode("env-remove NOT_SET_ANYWHERE"));
    }

    @Test
    void unusableNamesAreRejected() {
        assertEquals("E902", mf().errorCode("env-set \"has space\" \"x\""));
        assertEquals("E902", mf().errorCode("env-set \"has=equals\" \"x\""));
    }

    @Test
    void aTypoSuggestsTheRealVariable() {
        Mf mf = mf();
        mf.eval("env-set DEMO_HOME \"/somewhere\"");
        MfError error = mf.error("env DEMO_HOM");
        assertEquals("E901", error.code());
        assertTrue(error.hints().getFirst().contains("DEMO_HOME"), error.hints().toString());
    }

    @Test
    void theLiveEnvironmentIsReadableAsARecord() {
        Mf mf = mf();
        mf.eval("env-set DEMO_THREE \"three\"");
        assertEquals(new Value.Str("three"), mf.eval("$env.DEMO_THREE"));
        // And it is live, not a snapshot taken at startup.
        mf.eval("env-set DEMO_THREE \"changed\"");
        assertEquals(new Value.Str("changed"), mf.eval("$env.DEMO_THREE"));
    }

    @Test
    void aBareVariableNamePointsAtTheEnvironmentOne() {
        Mf mf = mf();
        mf.eval("env-set DEMO_FOUR \"four\"");
        MfError error = mf.error("echo $DEMO_FOUR");
        assertEquals("E312", error.code());
        assertTrue(error.hints().getLast().contains("$env.DEMO_FOUR"), error.hints().toString());
    }

    // ---- the PATH ---------------------------------------------------------------------

    @Test
    void pathIsATableThatFlagsEntriesThatAreNotThere() {
        Mf mf = mf();
        mf.session().env().set("PATH", here.toString() + Environment.separator()
                + here.resolve("missing").toString());
        List<Value.Rec> rows = dev.mainframe.value.Values.rows(mf.eval("path"));
        assertEquals(2, rows.size());
        assertEquals(new Value.Bool(false), rows.getFirst().get("missing"));
        assertEquals(new Value.Bool(true), rows.getLast().get("missing"));
    }

    @Test
    void addingToThePath() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        Mf mf = mf();
        mf.session().env().set("PATH", here.toString());
        mf.eval("path-add ./tools");
        assertEquals(List.of(here.toString(), tools.toString()), mf.session().env().pathEntries());
    }

    @Test
    void addingToTheFrontLooksThereFirst() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        Mf mf = mf();
        mf.session().env().set("PATH", here.toString());
        mf.eval("path-add ./tools --front");
        assertEquals(tools.toString(), mf.session().env().pathEntries().getFirst());
    }

    @Test
    void aPathEntryHasToBeADirectory() throws IOException {
        Files.writeString(here.resolve("notadir.txt"), "x");
        Mf mf = mf();
        assertEquals("E905", mf.errorCode("path-add ./notadir.txt"));
        assertEquals("E905", mf.errorCode("path-add ./nowhere"));
        // ...unless you are about to create it.
        mf.eval("path-add ./nowhere --force");
        assertTrue(mf.session().env().onPath(here.resolve("nowhere")));
    }

    @Test
    void addingTheSameDirectoryTwiceChangesNothing() throws IOException {
        Files.createDirectories(here.resolve("tools"));
        Mf mf = mf();
        mf.session().env().set("PATH", here.toString());
        mf.eval("path-add ./tools");
        int size = mf.session().env().pathEntries().size();
        mf.eval("path-add ./tools");
        assertEquals(size, mf.session().env().pathEntries().size());
        assertTrue(mf.printed().contains("already on your PATH"), mf.printed());
    }

    @Test
    void removingFromThePath() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        Mf mf = mf();
        mf.session().env().set("PATH", here + Environment.separator() + tools);
        mf.eval("path-remove ./tools");
        assertEquals(List.of(here.toString()), mf.session().env().pathEntries());
        assertEquals("E906", mf.errorCode("path-remove ./tools"));
    }

    // ---- what the PATH is actually for -------------------------------------------------

    @Test
    void programLookupFollowsTheEditedPath() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        String program = Environment.onWindows() ? "mytool.cmd" : "mytool";
        Files.writeString(tools.resolve(program), "echo hi");

        Mf mf = mf();
        mf.session().env().set("PATH", "");
        mf.session().env().set("PATHEXT", ".CMD");
        assertNull(mf.session().env().findProgram("mytool"));

        mf.eval("path-add ./tools");
        assertNotNull(mf.session().env().findProgram("mytool"),
                "a program should be findable as soon as its directory is on the PATH");

        Value found = mf.eval("which mytool");
        assertEquals(new Value.Str("external program"), ((Value.Rec) found).get("kind"));
    }

    @Test
    void aMissingProgramSaysWhereItLooked() {
        Mf mf = mf();
        mf.session().env().set("PATH", "");
        MfError error = mf.error("^definitely-not-a-real-program");
        assertEquals("E325", error.code());
        assertTrue(error.hints().toString().contains("path-add"), error.hints().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aChildProcessSeesTheEditedEnvironment() {
        Mf mf = mf();
        mf.eval("env-set DEMO_CHILD \"passed through\"");
        Value output = mf.eval("^cmd \"/c\" \"echo %DEMO_CHILD%\"");
        assertEquals(new Value.Str("passed through"), output);
    }

    // ---- the environment class on its own ----------------------------------------------

    @Test
    void windowsNamesMatchWithoutRegardToCase() {
        Environment environment = new Environment(Map.of("Path", "one"), true);
        assertEquals("one", environment.get("PATH"));
        environment.set("PATH", "two");
        // The spelling the system used is kept, so nothing ends up listed twice.
        assertEquals(1, environment.size());
        assertEquals("Path", environment.pathName());
        assertEquals("two", environment.get("path"));
    }

    @Test
    void elsewhereNamesAreExact() {
        Environment environment = new Environment(Map.of("PATH", "one"), false);
        assertNull(environment.get("Path"));
        assertEquals("one", environment.get("PATH"));
    }

    @Test
    void emptyPathEntriesAreIgnored() {
        Environment environment = new Environment(
                Map.of("PATH", Environment.separator() + "one" + Environment.separator()), false);
        assertEquals(List.of("one"), environment.pathEntries());
    }

    @Test
    void everyEnvironmentCommandIsMarkedAsSessionOnly() {
        for (var builtin : dev.mainframe.eval.Registry.standard().all()) {
            var signature = builtin.signature();
            if (!signature.category().equals("environment")) continue;
            boolean changes = signature.name().startsWith("env-") || signature.name().startsWith("path-");
            if (!changes) continue;
            assertEquals(dev.mainframe.eval.Signature.Effect.SESSION, signature.effect(),
                    signature.name() + " should be marked as changing the session only");
            assertFalse(builtin instanceof dev.mainframe.eval.Builtin.Planning,
                    signature.name() + " touches no files, so it should not plan");
        }
    }
}
