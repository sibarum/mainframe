package dev.mainframe.dist;

import dev.mainframe.assistant.Assistants;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.desktop.Desktop;
import dev.mainframe.template.shell.Templates;
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
                        assistant(),
                        templates(),
                        new Calculator(memory),
                        new Editor(memory)),
                args);
    }

    /**
     * The assistant, as the list sees it.
     *
     * <p>Not a program: it opens no window and there is nothing to launch. What it
     * has is commands -- {@code ask} and {@code key} -- which is what
     * {@link ConsoleApp#of} is for, and it arrives through the same seam the
     * calculator and the editor use so it gets the same argument checking, the
     * same {@code help} and the same guardrails as everything else.
     *
     * <p>It is in the list rather than in {@code Desktop.run} on purpose. A shell
     * that always had an assistant would be a shell nobody could build without an
     * HTTP client; this way the ordinary MainFrame is exactly what it was, and
     * this distributable is the one that also answers questions.
     */
    private static ConsoleApp assistant() {
        return ConsoleApp.of("assistant", "ask for what you want in words",
                (registry, console) -> Assistants.install(registry));
    }

    /**
     * Project templates, as the list sees them.
     *
     * <p>Not a program either -- {@code templates} and {@code new} are commands, and
     * they arrive through the same seam as everything else, so they get the same
     * argument checking, the same {@code help}, the same {@code --dry-run} and the
     * same confirmation before anything is written.
     *
     * <p>Unlike the assistant this one is not a weight worth thinking about: the
     * module has no third-party dependency and the whole of the shipped template is
     * a folder of resources. What it buys is that this executable can start a
     * VexelRay project on a machine with nothing but a JDK and a Maven repository:
     * {@code new vexel-desktop}.
     */
    private static ConsoleApp templates() {
        return ConsoleApp.of("templates", "start a new project from a template",
                (registry, console) -> Templates.install(registry));
    }
}
