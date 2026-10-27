package dev.mainframe;

import dev.mainframe.core.SelfLaunch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command lines MainFrame starts other programs with: another MainFrame, and VexelRay IDE on a script. */
class LaunchTest {

    /** On the JVM: this Java, this class path, MainFrame's main class, then the arguments, unsplit. */
    @Test
    void theJvmRelaunchesItself() {
        List<String> cmd = SelfLaunch.command(List.of("--workspace=Suite apps"));
        assertTrue(cmd.get(0).startsWith(System.getProperty("java.home")), cmd.get(0));
        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"), cmd.toString());
        int cp = cmd.indexOf("-cp");
        assertEquals(System.getProperty("java.class.path"), cmd.get(cp + 1));
        assertEquals(List.of(MainFrame.class.getName(), "--workspace=Suite apps"), cmd.subList(cp + 2, cmd.size()));
    }

    /** {@code -core} names the entry point it cannot see; this holds the name to the class. */
    @Test
    void theEntryPointCoreNamesIsThisOne() {
        assertEquals(MainFrame.class.getName(), SelfLaunch.MAIN);
    }

    @Test
    void vexIsTextEditorOnThePath(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("Vex"));
        assertEquals(Optional.empty(), Vex.onPath(dir + java.io.File.pathSeparator + bin));
        Path exe = Files.createFile(bin.resolve("text-editor.exe"));
        assertEquals(Optional.of(exe), Vex.onPath(dir + java.io.File.pathSeparator + bin));
        assertEquals(List.of(exe.toString(), dir.resolve("a b.ps1").toString()), Vex.command(exe, dir.resolve("a b.ps1")));
    }
}
