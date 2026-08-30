package dev.mainframe.concordance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.api.MainFrame;

/**
 * The join, not the index. What is worth a test here is what this module actually
 * decides -- where the project is, and what MainFrame gets back -- because
 * everything else is Concordance's and is tested in Concordance.
 */
class ConcordanceProgramTest {

    @TempDir
    Path here;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private MainFrame shell;

    @BeforeEach
    void setUp() throws IOException {
        project(here);
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = MainFrame.builder()
                .directory(here)
                .indexDirectory(here.resolve(".indexes"))
                .output(stream, stream)
                .color(false)
                .build();
        Concordances.install(shell);
    }

    /** The smallest thing Concordance calls a project: a pom, and a class under it. */
    private static void project(Path root) throws IOException {
        write(root.resolve("pom.xml"), "<project>\n  <artifactId>demo</artifactId>\n</project>\n");
        write(root.resolve("src/main/java/a/Area.java"), """
                package a;
                class Area {
                    int area(int w, int h) { return w * h; }
                    int twice(int w, int h) { return area(w, h) + area(w, h); }
                }
                """);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ---- standing somewhere ------------------------------------------------------------

    @Test
    void aLineWithNoProjectMeansTheDirectoryTheShellIsIn() {
        String text = shell.run("^concordance summary").text();

        assertTrue(text.contains(here.toString()), () -> "the shell's directory, not the JVM's: " + text);
        assertTrue(text.contains("1 modules"), () -> text);
    }

    @Test
    void aRelativeProjectIsResolvedAgainstTheShellNotTheProcess() throws IOException {
        Path nested = here.resolve("inner");
        project(nested);
        shell.directory(here);

        String text = shell.run("^concordance ./inner summary").text();

        assertTrue(text.contains(nested.toString()), () -> "resolved against cd, not user.dir: " + text);
    }

    @Test
    void cdMovesWhatAProjectlessLineMeans() throws IOException {
        Path nested = here.resolve("inner");
        project(nested);
        shell.directory(nested);

        assertTrue(shell.run("^concordance summary").text().contains(nested.toString()));
    }

    // ---- what comes back ---------------------------------------------------------------

    @Test
    void aQueryAnswersAndItsOutputCarriesOnDownThePipe() {
        String text = shell.run("^concordance usages area").text();

        assertTrue(text.contains("Area.java"), () -> text);
        assertTrue(text.contains("uses of area"), () -> text);
        // Lines, not rows -- which is the whole of what invoking it with a caret means.
        assertTrue(shell.run("^concordance names Area | length").number() > 0);
    }

    @Test
    void aQueryThatDoesNotExistFailsTheWayAProgramFails() {
        // Concordance exits 2, and MainFrame stops the line the way it stops any failing
        // program: the guardrails do not apply inside a caret, but the exit code still does.
        shell.execute("^concordance nonsense");

        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("exit code 2"), () -> said);
        assertTrue(said.contains("concordance <project> <query> [args]"),
                () -> "Concordance's own usage is relayed, not replaced: " + said);
    }

    @Test
    void itIsListedWithTheOtherPrograms() {
        assertTrue(shell.hasProgram("concordance"));
        assertEquals("concordance [<project>] <summary|names|usages|impls> [args]",
                Query.SPEC.usage());
    }
}
