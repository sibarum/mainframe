package dev.mainframe.dist;

import dev.mainframe.gui.desktop.Desktop;
import dev.vexelray.demo.calculator.Calculator;
import dev.vexelray.demo.editor.Editor;

import java.util.List;

/**
 * MainFrame as one executable, with the programs it opens built into it.
 *
 * <h2>What this file is</h2>
 * Everything that is not "which programs are in this MainFrame" belongs to somebody else. The frame loop, the
 * window memory, the input backend, the clipboard, the title bar, the shell and the whole
 * command language are in {@link Desktop#run}; the calculator and the editor are each an ordinary
 * {@code ConsoleApp} out of its own repository. What is left here is the one fact this distributable has that
 * no other build does: the list.
 *
 * <p>So this is a list. It is supposed to be a list -- a distributable whose entry point had logic in it would
 * be a third arrangement of MainFrame to keep in step with the other two, and the point of
 * {@code Desktop.run} is that there is not one.
 *
 * <h2>What comes up</h2>
 * <pre>{@code
 * ~ > apps
 * name        launchable  summary
 * calculator  true        a keypad, a tape, and a plotter for anything with a variable in it
 * editor      true        open files in tabs, and point the file tree at a directory
 *
 * ~ > calc "2^10"
 * 1024
 * ~ > calc                      # the keypad, in a window of its own
 * ~ > edit ./pom.xml            # the editor, with that file in a tab
 * ~ > ls | where ext == "java" | first 3 | edit
 * }</pre>
 *
 * <p>{@code calc} and {@code edit} are not aliases this module invented and they are not wired here: they are
 * the commands those two apps register for themselves, which is why they arrive with the same argument
 * checking, the same {@code help}, the same {@code --dry-run} and the same errors as every built-in. Everything
 * stock in MainFrame is stock in this: {@code ls}, {@code where}, {@code select}, the pipes, the forms, the
 * panels.
 *
 * <h2>Building it</h2>
 * <pre>{@code
 * mvn -Pdist,native package                          # mainframe-dist/target/mainframe.exe
 * mvn -Pdist -pl mainframe-dist compile exec:exec    # the same program on the JVM
 * }</pre>
 *
 * Needs {@code --enable-native-access=ALL-UNNAMED} on the JVM. The executable needs nothing.
 */
public final class Dist {

    private Dist() {
    }

    public static void main(String[] args) throws Exception {
        // "mainframe" is where this application's settings live -- where each of its windows
        // was left. The apps keep their own windows under their own keys in that one file, which is why they
        // are handed the memory rather than opening one each.
        Desktop.run("mainframe", "MainFrame",
                (settings, memory) -> List.of(
                        new Calculator(memory),
                        new Editor(memory)),
                args);
    }
}
