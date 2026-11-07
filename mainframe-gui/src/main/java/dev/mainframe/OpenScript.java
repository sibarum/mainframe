package dev.mainframe;

import dev.mainframe.core.EnvScripts;
import dev.mainframe.core.Messages.EditScript;
import dev.mainframe.core.Messages.ScriptOpened;
import dev.mainframe.core.WorkspaceStore;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;

/**
 * <i>Edit environment…</i>, on the offload lane: the script made if it is not there and its region brought up to date
 * ({@link EnvScripts#edit}), then opened in VexelRay IDE, a process of its own ({@link Vex}). 0.1's {@code MakeScript}
 * and {@code OpenInIde} as one job, since nothing happens between them. Here rather than in {@code -core} because
 * finding the editor is the suite's install record, which is the framework shell's to read.
 *
 * <p>Whatever stops it is answered as a dialog would say it, with the script's path when it was made, so it can still
 * be edited some other way. A run that may start no other program ({@code -Dmainframe.launch=off}, every test run)
 * makes the script and says so rather than starting the IDE (FN-15).
 */
@Job
public final class OpenScript {

    private static final Log LOG = Log.of("mainframe.env");

    private final Settings settings;

    public OpenScript(Settings settings) {
        this.settings = settings;
    }

    public ScriptOpened run(EditScript e) {
        Path script;
        try {
            script = EnvScripts.edit(WorkspaceStore.of(settings), e.workspace(), e.env(), e.title(), e.path(),
                    e.dialect().orElseGet(EnvScripts.Dialect::platform));
        } catch (EnvScripts.Gone g) {
            return new ScriptOpened(g.title(), g.getMessage());
        } catch (IOException | RuntimeException x) {
            LOG.warn("cannot make the environment script", x);
            return new ScriptOpened("Could not make the script", String.valueOf(x.getMessage()));
        }
        if ("off".equals(System.getProperty("mainframe.launch"))) {
            return new ScriptOpened("VexelRay IDE was not started",
                    "Starting other programs is off for this run (-Dmainframe.launch=off). The script is " + script + ".");
        }
        try {
            if (!Vex.edit(script)) {
                return new ScriptOpened("VexelRay IDE is not installed", "MainFrame opens environment scripts in "
                        + "VexelRay IDE, which is not installed. The script is " + script + ".");
            }
            return new ScriptOpened("", "");
        } catch (IOException | RuntimeException x) {
            LOG.warn("cannot start VexelRay IDE on {}", script, x);
            return new ScriptOpened("Could not start VexelRay IDE", x.getMessage() + "\nThe script is " + script + ".");
        }
    }
}
