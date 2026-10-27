package dev.mainframe.core;

import dev.mainframe.core.Messages.Prepare;
import dev.mainframe.core.Messages.Prepared;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The command line a tab's shell starts with, on the offload lane: the shell's own, with the environment scripts of
 * the tab's workspace and of its row sourced ({@link EnvScripts}). Finding the scripts brings their regions up to date
 * and a bash or zsh line needs startup files written, so this touches the disk.
 *
 * <p>Scripts the line cannot source are not a failure: the tab starts without them and says why ({@code refused}).
 */
@Job(topic = Messages.PREPARE, reply = Messages.PREPARED)
public final class PrepareShell {

    private static final Log LOG = Log.of("mainframe.env");

    private final WorkspaceStore workspaces;

    public PrepareShell(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Prepared run(Prepare p) {
        String line = p.line();
        Path dir = p.dir() != null ? p.dir() : newTabDir(p.chosen());
        if (p.workspace().isBlank()) {
            return new Prepared(p.id(), dir, line, "");
        }
        Workspace.Entry row = p.env().isBlank() ? null
                : new Workspace.Entry(p.title(), dir.toString()).withEnv(p.env());
        List<Path> scripts = EnvScripts.forTab(workspaces, p.workspace(), row, EnvScripts.dialect(line));
        String refused = scripts.isEmpty() ? null : EnvScripts.refusal(line);
        if (refused == null) {
            try {
                return new Prepared(p.id(), dir, EnvScripts.command(line, scripts, workspaces.launchers()), "");
            } catch (IOException e) {
                refused = "MainFrame cannot write the files the shell starts on (" + e.getMessage() + ")";
            }
        }
        LOG.info("not sourcing the environment scripts of workspace {}: {}", p.workspace(), refused);
        return new Prepared(p.id(), dir, line, refused);
    }

    /**
     * Where a plain new tab starts: the folder chosen in Settings, while it is there, or else the user's profile. Not
     * MainFrame's own working directory, which for an installed copy is wherever its shortcut said.
     */
    private static Path newTabDir(Path chosen) {
        return chosen != null && Files.isDirectory(chosen) ? chosen : AppSettings.home();
    }
}
