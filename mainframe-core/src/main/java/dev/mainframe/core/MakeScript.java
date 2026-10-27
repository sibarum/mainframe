package dev.mainframe.core;

import dev.mainframe.core.Messages.Script;
import dev.mainframe.core.Messages.ScriptMade;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An environment script, made if it is not there and brought up to date if it is, on the offload lane: the
 * workspace's, or a row's, found again by its key, so a tab relabelled or moved since it opened still finds its own.
 * Whatever stops it is answered as a dialog would say it.
 */
@Job(topic = Messages.MAKE_SCRIPT, reply = Messages.SCRIPT_MADE)
public final class MakeScript {

    private static final Log LOG = Log.of("mainframe.env");

    private final WorkspaceStore workspaces;

    public MakeScript(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public ScriptMade run(Script s) {
        String name = s.workspace();
        try {
            if (s.env().isBlank()) {
                if (!workspaces.exists(name)) {
                    return gone(name);
                }
                return made(EnvScripts.workspaceScript(workspaces, name, s.dialect()));
            }
            if (!s.path().isBlank()) {
                Workspace.Entry given = new Workspace.Entry(s.tab(), s.path()).withEnv(s.env());
                return made(EnvScripts.tabScript(workspaces, name, given, s.dialect()));
            }
            Workspace w = workspaces.load(name).orElse(null);
            if (w == null) {
                return gone(name);
            }
            Workspace.Entry row = w.entries().stream().filter(e -> e.env().equalsIgnoreCase(s.env()))
                    .findFirst().orElse(null);
            if (row == null) {
                return new ScriptMade(null, s.tab() + " is no longer in " + name,
                        "Its folder was removed from the workspace since this tab opened, so it has no script to edit.");
            }
            return made(EnvScripts.tabScript(workspaces, name, row, s.dialect()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("cannot make the environment script", e);
            return new ScriptMade(null, "Could not make the script", String.valueOf(e.getMessage()));
        }
    }

    private static ScriptMade made(Path script) {
        return new ScriptMade(script, "", "");
    }

    private static ScriptMade gone(String name) {
        return new ScriptMade(null, "No workspace called " + name, "It was renamed or deleted since this tab opened.");
    }
}
