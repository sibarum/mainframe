package dev.mainframe.core;

import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The command line a tab's shell starts with, on the offload lane: the shell's own, with the environment scripts of
 * the tab's workspace and of its row sourced ({@link EnvScripts}). Finding the scripts brings their regions up to date
 * and a bash or zsh line needs startup files written, so this touches the disk. 0.1's, with its declaration changed
 * (docs/workspaces.md).
 *
 * <p>Scripts the line cannot source are not a failure: the tab starts without them and says why ({@code refused}).
 * A folder that has gone is not one either: the tab starts at home.
 */
@Job
public final class PrepareShell {

    private static final Log LOG = Log.of("mainframe.env");

    private final Settings settings;

    public PrepareShell(Settings settings) {
        this.settings = settings;
    }

    public Prepared run(Prepare p) {
        Path dir = Files.isDirectory(p.dir()) ? p.dir() : Path.of(System.getProperty("user.home"));
        if (p.workspace().isBlank()) {
            return new Prepared(p.id(), dir, p.line(), "");
        }
        WorkspaceStore workspaces = WorkspaceStore.of(settings);
        Workspace.Entry row = p.env().isBlank() ? null : new Workspace.Entry("", dir.toString()).withEnv(p.env());
        List<Path> scripts = EnvScripts.forTab(workspaces, p.workspace(), row, EnvScripts.dialect(p.line()));
        String refused = scripts.isEmpty() ? null : EnvScripts.refusal(p.line());
        if (refused == null) {
            try {
                return new Prepared(p.id(), dir, EnvScripts.command(p.line(), scripts, workspaces.launchers()), "");
            } catch (IOException e) {
                refused = "MainFrame cannot write the files the shell starts on (" + e.getMessage() + ")";
            }
        }
        LOG.info("not sourcing the environment scripts of workspace {}: {}", p.workspace(), refused);
        return new Prepared(p.id(), dir, p.line(), "This tab's environment scripts did not run: " + refused + ".");
    }
}
