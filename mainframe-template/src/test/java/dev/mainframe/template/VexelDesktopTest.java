package dev.mainframe.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled template, generated and inspected.
 *
 * <p>The last test is the one that matters most and is the least obvious: a bundled template's files are
 * classpath resources, and the manifest's {@code file} lines are the only record of what is in it. Nothing at
 * runtime can notice a file that was added to the folder and not to the list -- it simply never ships. This
 * holds the two together at build time, which is the only place it can be held.
 */
class VexelDesktopTest {

    private static final String ID = "vexel-desktop";

    private static Template template() { return Catalogue.bundled().get(ID); }

    private static Answers answers(Map<String, String> overrides) {
        Map<String, String> given = new LinkedHashMap<>(Answers.presets(template()).values());
        given.put("where", "/tmp");
        given.putAll(overrides);
        return Answers.of(given).filled(template());
    }

    private static Blueprint blueprint(Map<String, String> overrides) {
        return Scaffold.of(template(), answers(overrides), Catalogue.bundled());
    }

    // ---- what it asks for -------------------------------------------------------------

    @Test
    void theTemplateReads() {
        Template template = template();
        assertEquals(ID, template.id());
        assertFalse(template.slots().isEmpty());
        assertFalse(template.items().isEmpty());
        assertFalse(template.needs().isEmpty());
    }

    @Test
    void theNamesAreWorkedOutFromTheProjectName() {
        Answers answers = answers(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        assertEquals("dev.example.plotviewer", answers.get("packageName"));
        assertEquals("dev/example/plotviewer", answers.get("packagePath"));
        assertEquals("PlotViewer", answers.get("className"));
        assertEquals("Plot viewer", answers.get("title"));
        assertEquals("plot-viewer", answers.get("appName"));
    }

    @Test
    void namesGivenExplicitlyWin() {
        Answers answers = answers(Map.of(
                "artifactId", "plot-viewer",
                "packageName", "com.acme.viewer",
                "className", "Viewer",
                "title", "The Viewer"));
        assertEquals("com.acme.viewer", answers.get("packageName"));
        assertEquals("com/acme/viewer", answers.get("packagePath"));
        assertEquals("Viewer", answers.get("className"));
        assertEquals("The Viewer", answers.get("title"));
    }

    // ---- what it writes ---------------------------------------------------------------

    @Test
    void theMainClassLandsAtItsPackagePath() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        assertNotNull(blueprint.entry("src/main/java/dev/example/plotviewer/PlotViewer.java"),
                "expected the main class; got " + blueprint.paths());
        assertNotNull(blueprint.entry("src/main/java/dev/example/plotviewer/Ui.java"));
        assertNotNull(blueprint.entry("pom.xml"));
        assertNotNull(blueprint.entry(".gitignore"));
        assertNotNull(blueprint.entry("docs/framework-notes.md"));
    }

    @Test
    void nothingGeneratedStillHasAPlaceholderInIt() {
        for (Blueprint.Entry entry : blueprint(Map.of()).entries()) {
            String text = new String(entry.bytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Maven's own ${} survives on purpose; the template's escape is what tells them apart.
            for (String name : Answers.namesIn(text)) {
                assertFalse(template().placeholders().contains(name),
                        entry.path() + " still has ${" + name + "} in it");
            }
        }
    }

    @Test
    void thePomKeepsMavensOwnProperties() {
        String pom = blueprint(Map.of()).text("pom.xml");
        assertTrue(pom.contains("${vexelray-gui.version}"), "Maven's own property should survive");
        assertTrue(pom.contains("${java.home}/bin/java"), "the exec plugin's command line should survive");
        assertFalse(pom.contains("$${"), "no escape should be left in the output");
    }

    /**
     * The one that would have caught the template's own first bug: an em dash in an
     * XML comment, which is two hyphens, which XML will not have.
     */
    @Test
    void everyGeneratedXmlFileParses() {
        assertEquals(List.of(), Checks.malformedXml(blueprint(Map.of())));
    }

    @Test
    void anAnswerThatWouldBreakThePomIsCaught() {
        Blueprint broken = blueprint(Map.of("summary", "a <thing> & another"));
        assertFalse(Checks.malformedXml(broken).isEmpty(),
                "an answer carrying markup should not quietly produce an unparseable pom");
    }

    @Test
    void thePomCarriesTheAnswers() {
        String pom = blueprint(Map.of(
                "artifactId", "plot-viewer",
                "groupId", "dev.example",
                "vexelrayVersion", "0.2.0")).text("pom.xml");
        assertTrue(pom.contains("<artifactId>plot-viewer</artifactId>"));
        assertTrue(pom.contains("<groupId>dev.example</groupId>"));
        assertTrue(pom.contains("<vexelray.version>0.2.0</vexelray.version>"));
        assertTrue(pom.contains("<app.mainClass>dev.example.plotviewer.PlotViewer</app.mainClass>"));
    }

    @Test
    void everyGeneratedSourceDeclaresItsPackage() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        for (Blueprint.Entry entry : blueprint.entries()) {
            if (!entry.path().endsWith(".java")) continue;
            String text = blueprint.text(entry.path());
            assertTrue(text.startsWith("package dev.example.plotviewer;"),
                    entry.path() + " should declare its package, and starts: "
                            + text.substring(0, Math.min(60, text.length())));
        }
    }

    @Test
    void turningTheTestsOffLeavesThemOut() {
        Map<String, String> off = new LinkedHashMap<>();
        off.put("tests", "no");
        Blueprint without = blueprint(off);
        assertTrue(without.paths().stream().noneMatch(p -> p.startsWith("src/test/")),
                "no tests should be written; got " + without.paths());
        assertFalse(without.notes().isEmpty(), "leaving files out should be said out loud");
        assertTrue(blueprint(Map.of("tests", "yes")).paths().stream()
                .anyMatch(p -> p.startsWith("src/test/")));
    }

    @Test
    void theFolderIsTheProjectName() {
        Path where = Path.of("/tmp/checkouts");
        assertEquals(where.resolve("plot-viewer"),
                Scaffold.folder(template(), answers(Map.of("artifactId", "plot-viewer")), where));
    }

    @Test
    void aProjectNameThatIsAPathIsRefused() {
        assertThrows(TemplateError.class, () -> Scaffold.folder(template(),
                answers(Map.of("artifactId", "../elsewhere")), Path.of("/tmp")));
    }

    // ---- writing, and taking it back ---------------------------------------------------

    @Test
    void itWritesTheWholeTreeAndOnlyItsOwnFiles(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = temp.resolve("plot-viewer");
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) writing.write(entry);

        for (String path : blueprint.paths()) {
            assertTrue(Files.isRegularFile(target.resolve(path)), path + " should be on disk");
        }
        assertEquals(blueprint.size(), writing.written().size());
    }

    @Test
    void undoTakesBackEverythingItMade(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path target = temp.resolve("nested").resolve("plot-viewer");
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer"));
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) writing.write(entry);
        writing.undo();

        assertFalse(Files.exists(target), "the project folder should be gone");
        assertFalse(Files.exists(temp.resolve("nested")), "and the folders made on the way to it");
        assertTrue(Files.isDirectory(temp), "but not the folder that was already there");
    }

    @Test
    void aFolderThatWasAlreadyThereSurvivesUndo(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = Files.createDirectory(temp.resolve("plot-viewer"));
        Path theirs = Files.writeString(target.resolve("NOTES.txt"), "mine");
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        writing.write(new Blueprint.Entry("pom.xml", "x".getBytes()));
        writing.undo();

        assertTrue(Files.isDirectory(target), "a folder this did not create is not removed");
        assertEquals("mine", Files.readString(theirs));
        assertFalse(Files.exists(target.resolve("pom.xml")));
    }

    // ---- the checks ---------------------------------------------------------------------

    @Test
    void aFolderWithThingsInItIsRefusedUnlessAskedFor(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = Files.createDirectory(temp.resolve("taken"));
        Files.writeString(target.resolve("something.txt"), "mine");
        assertNotNull(Checks.destinationProblem(target, false, null));
        assertNull(Checks.destinationProblem(target, true, null));
    }

    @Test
    void aFolderThatIsAFileIsAlwaysRefused(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path file = Files.writeString(temp.resolve("thing"), "x");
        assertNotNull(Checks.destinationProblem(file, true, null));
    }

    @Test
    void anExistingFileIsNeverWrittenOver(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path target = Files.createDirectory(temp.resolve("plot-viewer"));
        Files.writeString(target.resolve("README.md"), "mine");
        List<String> hit = Checks.collisions(target, blueprint(Map.of("artifactId", "plot-viewer")));
        assertEquals(List.of("README.md"), hit);
    }

    @Test
    void aMissingLibraryIsFoundBeforeAnythingIsWritten(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        // An empty repository that exists: every declared artifact is missing from it.
        Path repository = Files.createDirectory(temp.resolve("repository"));
        assertEquals(template().needs().size(),
                Checks.missingArtifacts(template(), answers(Map.of()), repository).size());
        // A repository that does not exist establishes nothing, so it says nothing.
        assertTrue(Checks.missingArtifacts(template(), answers(Map.of()),
                temp.resolve("nowhere")).isEmpty());
    }

    // ---- the list and the folder ------------------------------------------------------------

    /**
     * Every file in the template folder is named by the manifest, and every file the manifest names is in the
     * folder.
     *
     * <p>Reaches for the source tree rather than the classpath on purpose: the failure this catches is a file
     * that was added to the folder and not to the list, and on the classpath that file is indistinguishable
     * from one that does not exist. Skipped rather than failed when the source tree is not there, so a run
     * against a packaged jar is not a false alarm.
     */
    @Test
    void theManifestAndTheFolderAgree() throws IOException {
        Path folder = Path.of("src/main/resources/dev/mainframe/template", ID, "files");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(folder),
                "only meaningful against the source tree");

        Set<String> onDisk = new TreeSet<>();
        try (var walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> onDisk.add(folder.relativize(p).toString().replace('\\', '/')));
        }
        Set<String> named = new TreeSet<>();
        for (Template.Item item : template().items()) named.add(item.source());

        Set<String> unlisted = new HashSet<>(onDisk);
        unlisted.removeAll(named);
        assertTrue(unlisted.isEmpty(),
                "these files are in the folder and would never ship, because no manifest line names "
                        + "them: " + unlisted);

        Set<String> absent = new HashSet<>(named);
        absent.removeAll(onDisk);
        assertTrue(absent.isEmpty(), "the manifest names files that are not there: " + absent);
    }

}
