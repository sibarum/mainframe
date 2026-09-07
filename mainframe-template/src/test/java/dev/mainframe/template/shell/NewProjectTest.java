package dev.mainframe.template.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.mainframe.MfError;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code new} and {@code templates}, driven the way somebody would type them. */
class NewProjectTest {

    /** The answers that make a project, minus wherever it is going. */
    private static final String ANSWERS =
            "--set={artifactId: \"plot-viewer\", groupId: \"dev.example\"} --no-ask";

    private static String make(Path where) {
        return "new vexel-desktop --in=" + portable(where) + " " + ANSWERS;
    }

    private static String portable(Path path) {
        return "\"" + path.toString().replace("\\", "/") + "\"";
    }

    // ---- listing -----------------------------------------------------------------------

    @Test
    void templatesListsWhatShips(@TempDir Path cwd) {
        Value listed = ShellTest.unattended(cwd).eval("templates");
        assertTrue(Values.rows(listed).stream()
                        .anyMatch(row -> Values.display(row.get("id")).equals("vexel-desktop")),
                "the bundled template should be listed");
    }

    @Test
    void templatesLooksInsideOne(@TempDir Path cwd) {
        Value slots = ShellTest.unattended(cwd).eval("templates vexel-desktop");
        assertTrue(Values.rows(slots).stream()
                .anyMatch(row -> Values.display(row.get("name")).equals("artifactId")));
        assertTrue(Values.rows(slots).stream()
                .anyMatch(row -> Values.display(row.get("name")).equals("where")));
    }

    @Test
    void anUnknownTemplateSuggestsARealOne(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd).error("templates vexel-desktopp");
        assertEquals("E1500", e.code());
        assertTrue(String.join(" ", e.hints()).contains("vexel-desktop"), e.hints().toString());
    }

    // ---- making one ---------------------------------------------------------------------

    @Test
    void itWritesTheProject(@TempDir Path cwd) {
        ShellTest shell = ShellTest.unattended(cwd);
        shell.eval(make(cwd));

        Path project = cwd.resolve("plot-viewer");
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(
                project.resolve("src/main/java/dev/example/plotviewer/PlotViewer.java")));
        assertTrue(Files.isRegularFile(project.resolve("docs/TODO.md")));
    }

    @Test
    void itSaysWhatToDoNext(@TempDir Path cwd) {
        ShellTest shell = ShellTest.unattended(cwd);
        shell.eval(make(cwd));
        assertTrue(shell.printed().contains("mvn compile exec:exec"), shell.printed());
    }

    @Test
    void aDryRunWritesNothing(@TempDir Path cwd) {
        ShellTest shell = ShellTest.unattended(cwd);
        shell.eval(make(cwd) + " --dry-run");

        assertFalse(Files.exists(cwd.resolve("plot-viewer")), "a dry run should create nothing");
        String printed = shell.printed();
        assertTrue(printed.contains("dry run"), printed);
        assertTrue(printed.contains("pom.xml"), "the plan should name the files, and says: " + printed);
    }

    // ---- refusing ------------------------------------------------------------------------

    @Test
    void itWillNotGuessWhenThereIsNobodyToAsk(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd)
                .error("new vexel-desktop --in=" + portable(cwd) + " --set={artifactId: \"plot-viewer\"}");
        assertEquals("E1503", e.code());
        assertTrue(String.join(" ", e.hints()).contains("--no-ask"), e.hints().toString());
    }

    @Test
    void aFolderThatAlreadyHasThingsInItIsRefused(@TempDir Path cwd) throws IOException {
        Files.createDirectories(cwd.resolve("plot-viewer"));
        Files.writeString(cwd.resolve("plot-viewer").resolve("mine.txt"), "keep me");

        MfError e = ShellTest.unattended(cwd).error(make(cwd));
        assertEquals("E1502", e.code());
        assertTrue(String.join(" ", e.hints()).contains("--into"), e.hints().toString());
        assertEquals("keep me", Files.readString(cwd.resolve("plot-viewer").resolve("mine.txt")));
    }

    @Test
    void intoAllowsTheFolderButStillNeverReplacesAFile(@TempDir Path cwd) throws IOException {
        Path project = Files.createDirectories(cwd.resolve("plot-viewer"));
        Files.writeString(project.resolve("notes.md"), "mine");

        // Something of theirs that is not one of ours: allowed, and left alone.
        ShellTest.unattended(cwd).eval(make(cwd) + " --into");
        assertEquals("mine", Files.readString(project.resolve("notes.md")));
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")));
    }

    @Test
    void aFileThisWouldLandOnStopsEverything(@TempDir Path cwd) throws IOException {
        Path project = Files.createDirectories(cwd.resolve("plot-viewer"));
        Files.writeString(project.resolve("README.md"), "mine, and not to be replaced");

        MfError e = ShellTest.unattended(cwd).error(make(cwd) + " --into");
        assertEquals("E1506", e.code());
        assertTrue(String.join(" ", e.hints()).contains("no flag for it"), e.hints().toString());

        assertEquals("mine, and not to be replaced", Files.readString(project.resolve("README.md")));
        assertFalse(Files.exists(project.resolve("pom.xml")), "and nothing else was written either");
    }

    @Test
    void runningItTwiceRefusesRatherThanOverwriting(@TempDir Path cwd) throws IOException {
        ShellTest.unattended(cwd).eval(make(cwd));
        Path pom = cwd.resolve("plot-viewer").resolve("pom.xml");
        String first = Files.readString(pom);

        MfError e = ShellTest.unattended(cwd).error(make(cwd));
        assertEquals("E1502", e.code());
        assertEquals(first, Files.readString(pom), "the first project is untouched");
    }

    @Test
    void aMissingAnswerIsNamed(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd)
                .error("new vexel-desktop --set={artifactId: \"plot-viewer\"} --no-ask");
        assertEquals("E1504", e.code());
    }

    @Test
    void aJavaKeywordInThePackageIsRefusedBeforeAnythingIsWritten(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd).error("new vexel-desktop --in=" + portable(cwd)
                + " --set={artifactId: \"plot-viewer\", packageName: \"dev.new.app\"} --no-ask");
        assertEquals("E1504", e.code());
        assertTrue(e.getMessage().contains("keyword"), e.getMessage());
        assertFalse(Files.exists(cwd.resolve("plot-viewer")));
    }

    @Test
    void aProjectNameThatIsNotAMavenIdIsRefused(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd).error("new vexel-desktop --in=" + portable(cwd)
                + " --set={artifactId: \"Plot_Viewer!\"} --no-ask");
        assertEquals("E1504", e.code());
        assertFalse(Files.exists(cwd.resolve("Plot_Viewer!")));
    }

    @Test
    void aSetThatIsNotARecordIsRefused(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd)
                .error("new vexel-desktop --in=" + portable(cwd) + " --set=\"not a record\" --no-ask");
        assertTrue(e.getMessage().length() > 0, "it should say what was wrong: " + e.getMessage());
    }

    @Test
    void somethingOtherThanARecordDownThePipeIsRefused(@TempDir Path cwd) {
        MfError e = ShellTest.unattended(cwd)
                .error("echo [1, 2] | new vexel-desktop --in=" + portable(cwd) + " --no-ask");
        assertEquals("E1501", e.code());
    }

    // ---- the other two ways in ------------------------------------------------------------

    @Test
    void answersCanArriveDownAPipe(@TempDir Path cwd) {
        ShellTest shell = ShellTest.unattended(cwd);
        shell.eval("echo {artifactId: \"plot-viewer\", groupId: \"dev.example\", where: "
                + portable(cwd) + "} | new vexel-desktop --no-ask");
        assertTrue(Files.isRegularFile(
                cwd.resolve("plot-viewer/src/main/java/dev/example/plotviewer/PlotViewer.java")));
    }

    @Test
    void aFlagOnTheLineBeatsTheRecord(@TempDir Path cwd) {
        ShellTest shell = ShellTest.unattended(cwd);
        shell.eval("echo {artifactId: \"from-the-pipe\"} | new vexel-desktop --in=" + portable(cwd)
                + " --set={artifactId: \"from-the-flag\"} --no-ask");
        assertTrue(Files.isDirectory(cwd.resolve("from-the-flag")));
        assertFalse(Files.exists(cwd.resolve("from-the-pipe")));
    }

    @Test
    void turningTheTestsOffLeavesThemOut(@TempDir Path cwd) {
        ShellTest.unattended(cwd).eval("new vexel-desktop --in=" + portable(cwd)
                + " --set={artifactId: \"plot-viewer\", groupId: \"dev.example\", tests: false} --no-ask");
        assertFalse(Files.exists(cwd.resolve("plot-viewer/src/test")));
        assertTrue(Files.isRegularFile(cwd.resolve("plot-viewer/pom.xml")));
    }

    // ---- the form ---------------------------------------------------------------------------

    /**
     * The whole point of the feature, down a terminal: MainFrame's own form asks for each slot in turn, an
     * empty line takes the offered default, and the answers become a project.
     */
    @Test
    void aPersonCanFillItInAtTheKeyboard(@TempDir Path cwd) {
        // where, artifactId, groupId, summary, packageName, className, title, width, height,
        // vexelray version, tactroller version, tests -- then the review.
        ShellTest shell = ShellTest.typing(cwd,
                cwd.toString().replace('\\', '/'),
                "plot-viewer",
                "dev.example",
                "",
                "", "", "",
                "", "",
                "", "",
                "",
                "y");
        shell.eval("new vexel-desktop");

        Path project = cwd.resolve("plot-viewer");
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")),
                "the form should have produced a project; the session said:\n" + shell.printed());
        assertTrue(Files.isRegularFile(
                project.resolve("src/main/java/dev/example/plotviewer/PlotViewer.java")));
    }
}
