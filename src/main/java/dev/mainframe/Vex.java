package dev.mainframe;

import dev.vexelray.framework.shell.Apps;
import dev.vexelray.framework.shell.Dialogs;
import sibarum.probe.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Opens a file in VexelRay IDE, the suite's editor, as a process of its own: {@code text-editor <file>}, a plain
 * VexelRay IDE window on that one file. VexelRay IDE is installed as {@code text-editor}, its id and its command, and
 * is found as the suite finds any installed application, by its install record ({@link Apps}); failing that, as
 * {@code text-editor} on the {@code PATH}, where the installer also puts it.
 *
 * <p>As with {@link SelfLaunch}, the editor is not this process's child in any way that matters: it outlives it, and
 * its output goes nowhere, since nothing would read it and a full pipe would stall it. VexelRay IDE refuses a file that
 * is not there, so the caller makes it first.
 */
final class Vex {

    /** VexelRay IDE's install id, which is also its command. */
    static final String ID = "text-editor";

    private static final Log LOG = Log.of("mainframe.vex");

    private Vex() {
    }

    /**
     * Starts VexelRay IDE on {@code file}. Returns false, and says so in the log, when there is no VexelRay IDE to
     * start.
     *
     * @throws IOException if there is one and it could not be started
     */
    static boolean edit(Path file) throws IOException {
        Optional<Path> exe = find();
        if (exe.isEmpty()) {
            LOG.warn("VexelRay IDE is not installed (no install record for {}, and no {}.exe on the PATH); {} was not "
                    + "opened", ID, ID, file);
            return false;
        }
        Process p = new ProcessBuilder(command(exe.get(), file)).directory(exe.get().getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        return true;
    }

    /**
     * Makes {@code script} with {@code make}, then opens it in VexelRay IDE; whatever goes wrong, a script that could
     * not be made or a VexelRay IDE that is not there or would not start, is said in a dialog rather than thrown, with
     * the script's path, so it can still be edited some other way.
     */
    static void editScript(Dialogs dialogs, ScriptMaker make) {
        Path script;
        try {
            script = make.make();
        } catch (IOException | RuntimeException e) {
            LOG.warn("cannot make the environment script", e);
            dialogs.info("Could not make the script", String.valueOf(e.getMessage()));
            return;
        }
        try {
            if (!edit(script)) {
                dialogs.info("VexelRay IDE is not installed", "MainFrame opens environment scripts in VexelRay IDE, "
                        + "which is not installed. The script is " + script + ".");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not start VexelRay IDE on {}", script, e);
            dialogs.info("Could not start VexelRay IDE", e.getMessage() + "\nThe script is " + script + ".");
        }
    }

    /** Makes a script, or brings it up to date, and says where it is. */
    interface ScriptMaker {
        Path make() throws IOException;
    }

    /** The command line that opens {@code file} in the VexelRay IDE at {@code exe}. */
    static List<String> command(Path exe, Path file) {
        return List.of(exe.toString(), file.toAbsolutePath().toString());
    }

    /** The installed VexelRay IDE, or else the one on the {@code PATH}. */
    static Optional<Path> find() {
        Optional<Path> installed = Apps.find(ID);
        return installed.isPresent() ? installed : onPath(System.getenv("PATH"));
    }

    /** {@code text-editor.exe} in a folder of {@code path}, the first there is. */
    static Optional<Path> onPath(String path) {
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            try {
                Path exe = Path.of(dir.strip()).resolve(ID + ".exe");
                if (Files.isRegularFile(exe)) return Optional.of(exe);
            } catch (RuntimeException e) {
                // a PATH entry that is not a path: the next one
            }
        }
        return Optional.empty();
    }
}
