package dev.mainframe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.Environment;

/**
 * Programs a host provides in-process: invoked with a caret like anything on the
 * PATH, run inside this JVM.
 */
class ProgramTest {

    @TempDir
    Path here;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private MainFrame.Builder shell() {
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        return MainFrame.builder()
                .directory(here)
                .indexDirectory(here.resolve(".indexes"))
                .output(stream, stream)
                .color(false);
    }

    private String printed() { return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }

    private static ProgramSpec spec(String name) {
        return ProgramSpec.named(name).summary("a program for the tests").build();
    }

    // ---- the basic thing ---------------------------------------------------------------

    @Test
    void aHostedProgramRunsAndItsOutputCarriesOn() {
        MainFrame shell = shell()
                .program(ProgramSpec.named("greet")
                        .summary("say hello to someone")
                        .usage("greet <name>")
                        .build(), call -> {
                    call.writeLine("hello " + call.argument(0, "world"));
                    return 0;
                })
                .build();

        assertEquals("hello Ada", shell.run("^greet Ada").text());
        assertEquals("hello world", shell.run("^greet").text());
        // Its output is a value like any other, so the rest of the line applies.
        assertEquals(9, shell.run("^greet Ada | length").number());
    }

    @Test
    void argumentsArriveTheWayAProcessWouldSeeThem() {
        List<String> seen = new ArrayList<>();
        MainFrame shell = shell()
                .program(spec("tool"), call -> {
                    seen.addAll(call.arguments());
                    return 0;
                })
                .build();

        // A flag carrying a number comes last: 4 -v would read as a subtraction,
        // which is the same rule the rest of the language follows. A relative path
        // arrives as it was written, because that is what a process would get --
        // the program is in the shell's directory, so it means the same thing.
        shell.run("^tool build ./src --release -v --jobs=4");
        assertEquals(List.of("build", "./src", "--release", "-v", "--jobs=4"), seen);
    }

    @Test
    void whatCameDownThePipeArrivesAsTextAndAsData() {
        MainFrame shell = shell()
                .program(spec("count"), call -> {
                    call.writeLine("text lines: " + call.input().strip().lines().count());
                    call.writeLine("rows: " + call.inputData().rows().size());
                    call.writeLine("first name: " + call.inputData().rows().getFirst().field("name").text());
                    return 0;
                })
                .build();

        Data result = shell.run("[{name: \"Ada\"} {name: \"Grace\"}] | ^count");
        assertEquals("""
                text lines: 2
                rows: 2
                first name: Ada""", result.text());
    }

    @Test
    void nothingIsPipedInWhenTheProgramStartsTheLine() {
        MainFrame shell = shell()
                .program(spec("check"), call -> {
                    call.write(call.hasInput() + " " + call.input().isEmpty());
                    return 0;
                })
                .build();
        assertEquals("false true", shell.run("^check").text());
    }

    @Test
    void onAnInteractiveLineTheOutputGoesStraightToTheTerminal() {
        MainFrame shell = shell()
                .interactive(true)
                .program(spec("slow"), call -> {
                    call.writeLine("first");
                    call.writeLine("second");
                    return 0;
                })
                .build();

        // Nothing was spawned, so there is nothing to hold back: the program
        // writes as it goes, and the line has no value to pass on.
        assertTrue(shell.run("^slow").isNothing());
        assertEquals("first\nsecond\n", printed());

        // Piped into something, it is captured like anything else.
        assertEquals(2, shell.run("^slow | lines | length").number());
    }

    @Test
    void namesMatchTheWayTheSystemMatchesProgramNames() {
        MainFrame shell = shell()
                .program(spec("mytool"), call -> {
                    call.write("ran");
                    return 0;
                })
                .build();

        assertEquals("ran", shell.run("^mytool").text());
        if (Environment.onWindows()) {
            assertEquals("ran", shell.run("^MyTool").text());
            assertTrue(shell.hasProgram("MYTOOL"));
        } else {
            shell.env("PATH", "");
            assertEquals("E325", assertThrows(ShellError.class, () -> shell.run("^MyTool")).code());
        }
    }

    // ---- failing like a program --------------------------------------------------------

    @Test
    void aNonZeroExitStopsThePipelineAndSaysWhy() {
        MainFrame shell = shell()
                .program(spec("jdk"), call -> {
                    call.writeError("no JDK called " + call.argument(0, "") + " is installed");
                    return 2;
                })
                .build();

        ShellError error = assertThrows(ShellError.class, () -> shell.run("^jdk 99 | length"));
        assertEquals("E322", error.code());
        assertTrue(error.getMessage().contains("exit code 2"), error.getMessage());
        assertTrue(error.hints().toString().contains("no JDK called 99"), error.hints().toString());
    }

    @Test
    void writingToStandardErrorAndSucceedingIsJustAWarning() {
        MainFrame shell = shell()
                .program(spec("noisy"), call -> {
                    call.writeError("this took a while");
                    call.write("done");
                    return 0;
                })
                .build();

        assertEquals("done", shell.run("^noisy").text());
        assertTrue(printed().contains("this took a while"), printed());
    }

    @Test
    void anExceptionSaysItCameFromTheHost() {
        MainFrame shell = shell()
                .program(spec("broken"), call -> {
                    throw new IllegalStateException("the database is not connected");
                })
                .build();

        ShellError error = assertThrows(ShellError.class, () -> shell.run("^broken"));
        assertEquals("E327", error.code());
        assertTrue(error.getMessage().contains("the database is not connected"), error.getMessage());
        assertTrue(error.hints().toString().contains("hosting MainFrame"), error.hints().toString());
    }

    // ---- what only in-process code can do ----------------------------------------------

    @Test
    void aProgramCanChangeTheEnvironmentForEverythingAfterIt() {
        Path jdk = here.resolve("jdk-25");
        MainFrame shell = shell()
                .program(ProgramSpec.named("jdk")
                        .summary("switch the JDK this session uses")
                        .usage("jdk <version>")
                        .build(), call -> {
                    call.env("DEMO_JAVA_HOME", jdk.toString());
                    call.pathAddFirst(jdk.resolve("bin"));
                    call.writeLine("DEMO_JAVA_HOME is now " + jdk);
                    return 0;
                })
                .build();

        assertNull(shell.env("DEMO_JAVA_HOME"));
        shell.run("^jdk 25");

        // A spawned program could not have done this: it would have edited a copy.
        assertEquals(jdk.toString(), shell.env("DEMO_JAVA_HOME"));
        assertEquals(jdk.resolve("bin"), shell.path().getFirst());
        assertEquals(jdk.toString(), shell.run("env DEMO_JAVA_HOME").text());
    }

    @Test
    void aProgramSeesTheEnvironmentAsItStandsNow() {
        MainFrame shell = shell()
                .env("APP_MODE", "live")
                .program(spec("mode"), call -> {
                    call.write(call.env("APP_MODE") + " in " + call.directory().getFileName());
                    return 0;
                })
                .build();

        assertEquals("live in " + here.getFileName(), shell.run("^mode").text());
        shell.env("APP_MODE", "rehearsal");
        assertEquals("rehearsal in " + here.getFileName(), shell.run("^mode").text());
    }

    @Test
    void aProgramCanBeToldToChangeNothing() {
        MainFrame shell = shell()
                .dryRun(true)
                .program(spec("deploy"), call -> {
                    if (call.dryRun()) {
                        call.writeLine("would deploy");
                        return 0;
                    }
                    call.writeLine("deployed");
                    return 0;
                })
                .build();
        assertEquals("would deploy", shell.run("^deploy").text());
    }

    // ---- living alongside the PATH -----------------------------------------------------

    @Test
    void whichSaysWhereAProgramComesFrom() {
        MainFrame shell = shell()
                .program(ProgramSpec.named("jdk")
                        .summary("switch the JDK this session uses")
                        .usage("jdk <version>")
                        .build(), call -> 0)
                .build();

        Data found = shell.run("which jdk");
        assertEquals("hosted program", found.field("kind").text());
        assertEquals("jdk <version>", found.field("usage").text());
        assertEquals("^jdk", found.field("run-it-with").text());
    }

    @Test
    void aHostedProgramTakesThePlaceOfARealOneAndWhichSaysSo() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        String file = Environment.onWindows() ? "mytool.cmd" : "mytool";
        Files.writeString(tools.resolve(file), "echo from disk");

        MainFrame shell = shell()
                .program(spec("mytool"), call -> {
                    call.write("from the host");
                    return 0;
                })
                .build();
        shell.env("PATHEXT", ".CMD");
        shell.env("PATH", tools.toString());

        assertEquals("from the host", shell.run("^mytool").text());
        Data found = shell.run("which mytool");
        assertEquals("hosted program", found.field("kind").text());
        assertTrue(found.field("instead-of").text().contains(file),
                "which should say what the hosted program stands in front of");
    }

    @Test
    void aNameThatSpellsOutAFileIsNeverTheHostedProgram() {
        MainFrame shell = shell()
                .program(spec("tool"), call -> {
                    call.write("from the host");
                    return 0;
                })
                .build();

        // ./tool is a file that does not exist, and asking for it says so rather
        // than quietly running something else.
        ShellError error = assertThrows(ShellError.class, () -> shell.run("^./tool"));
        assertEquals("E323", error.code());
    }

    @Test
    void programsAreListedSoTheyCanBeFound() {
        MainFrame shell = shell()
                .program(ProgramSpec.named("jdk").summary("switch the JDK").build(), call -> 0)
                .program(ProgramSpec.named("seed").summary("seed the database").build(), call -> 0)
                .build();

        Data listed = shell.run("programs");
        assertEquals(2, listed.rows().size());
        assertEquals(List.of("jdk", "seed"),
                List.of(listed.rows().get(0).field("name").text(),
                        listed.rows().get(1).field("name").text()));
        assertTrue(shell.run("help") != null);
        assertTrue(printed().contains("programs"), printed());
    }

    @Test
    void anEmptyShellSaysWhereProgramsWouldComeFrom() {
        MainFrame shell = shell().build();
        assertEquals(0, shell.run("programs").rows().size());
        assertTrue(printed().contains("no programs of its own"), printed());
    }

    @Test
    void askingForHelpOnAProgramSaysItIsAProgram() {
        MainFrame shell = shell()
                .program(ProgramSpec.named("jdk")
                        .summary("switch the JDK this session uses")
                        .usage("jdk <version>")
                        .build(), call -> 0)
                .build();

        ShellError error = assertThrows(ShellError.class, () -> shell.run("help jdk"));
        assertEquals("E501", error.code());
        assertTrue(error.hints().toString().contains("^jdk"), error.hints().toString());
        assertTrue(error.hints().toString().contains("jdk <version>"), error.hints().toString());
    }

    @Test
    void aTypoPointsAtTheProgramTheHostProvides() {
        MainFrame shell = shell()
                .program(spec("seed-database"), call -> 0)
                .build();

        ShellError error = assertThrows(ShellError.class, () -> shell.run("^seed-databse"));
        assertEquals("E323", error.code());
        assertTrue(error.hints().toString().contains("^seed-database"), error.hints().toString());

        ShellError missing = assertThrows(ShellError.class, () -> shell.run("which seed-databse"));
        assertEquals("E504", missing.code());
        assertTrue(missing.hints().toString().contains("^seed-database"), missing.hints().toString());
    }

    // ---- installing and uninstalling ---------------------------------------------------

    @Test
    void aProgramCanBeInstalledAfterTheShellIsBuilt() {
        MainFrame shell = shell().build();
        assertFalse(shell.hasProgram("later"));

        shell.program(spec("later"), call -> {
            call.write("installed at run time");
            return 0;
        });
        assertTrue(shell.hasProgram("later"));
        assertEquals(List.of("later"), shell.programs());
        assertEquals("installed at run time", shell.run("^later").text());

        assertTrue(shell.programRemove("later"));
        assertFalse(shell.programRemove("later"));
        assertEquals("E323", assertThrows(ShellError.class, () -> shell.run("^later")).code());
    }

    @Test
    void installingTwiceAtBuildTimeIsAMistakeWorthCatching() {
        MainFrame.Builder builder = shell().program(spec("tool"), call -> 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> builder.program(spec("tool"), call -> 0));
        assertTrue(e.getMessage().contains("already registered"), e.getMessage());
    }

    @Test
    void aProgramNeedsANameALineCouldCarryAndSomethingToSayAboutItself() {
        assertThrows(IllegalArgumentException.class, () -> ProgramSpec.named("bin/tool"));
        assertThrows(IllegalArgumentException.class, () -> ProgramSpec.named("my tool"));
        assertThrows(IllegalArgumentException.class, () -> ProgramSpec.named("tool").build());
        assertEquals("^tool", ProgramSpec.named("tool").summary("x").build().invocation());
        assertEquals("^\"7z\"", ProgramSpec.named("7z").summary("x").build().invocation());
        // A name that is not a plain word still works -- it just has to be quoted.
        MainFrame shell = shell()
                .program(ProgramSpec.named("7z").summary("unpack things").build(), call -> {
                    call.write("unpacked");
                    return 0;
                })
                .build();
        assertEquals("unpacked", shell.run("^\"7z\"").text());
    }

    // ---- the host side of the environment ----------------------------------------------

    @Test
    void theHostCanEditTheEnvironmentAndThePath() {
        MainFrame shell = shell().build();

        shell.env("JDK_HOME", here.resolve("jdk").toString());
        assertEquals(here.resolve("jdk").toString(), shell.env("JDK_HOME"));
        assertEquals(here.resolve("jdk").toString(), shell.environment().get("JDK_HOME"));

        // The PATH entry the app cares about, spelled out of the variable it just set.
        Path bin = Path.of(shell.env("JDK_HOME"), "bin");
        assertTrue(shell.pathAddFirst(bin));
        assertFalse(shell.pathAddFirst(bin), "adding the same entry twice should do nothing");
        assertEquals(bin, shell.path().getFirst());

        assertTrue(shell.pathRemove(bin));
        assertFalse(shell.pathRemove(bin));
        assertFalse(shell.path().contains(bin));

        assertTrue(shell.envRemove("JDK_HOME"));
        assertFalse(shell.envRemove("JDK_HOME"));
        assertNull(shell.env("JDK_HOME"));

        assertThrows(IllegalArgumentException.class, () -> shell.env("has space", "x"));
    }

    @Test
    void theBuilderCanSetUpTheEnvironmentAndThePathBeforeAnythingRuns() throws IOException {
        Path first = Files.createDirectories(here.resolve("first"));
        Path second = Files.createDirectories(here.resolve("second"));
        Path last = Files.createDirectories(here.resolve("last"));

        MainFrame shell = shell()
                .env("APP_MODE", "live")
                .pathAddFirst(first)
                .pathAddFirst(second)
                .pathAdd(last)
                .build();

        assertEquals("live", shell.env("APP_MODE"));
        // Declared first means searched first, whichever end they were added at.
        assertEquals(List.of(first, second), shell.path().subList(0, 2));
        assertEquals(last, shell.path().getLast());
    }

    @Test
    void theHostCanAskWhereAProgramWouldComeFrom() throws IOException {
        Path tools = Files.createDirectories(here.resolve("tools"));
        String file = Environment.onWindows() ? "mytool.cmd" : "mytool";
        Files.writeString(tools.resolve(file), "echo hi");

        MainFrame shell = shell().build();
        shell.env("PATH", "");
        shell.env("PATHEXT", ".CMD");
        assertNull(shell.onPath("mytool"));

        shell.pathAdd(tools);
        assertEquals(tools.resolve(file), shell.onPath("mytool"));
    }
}
