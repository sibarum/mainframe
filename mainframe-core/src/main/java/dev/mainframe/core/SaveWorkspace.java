package dev.mainframe.core;

import dev.mainframe.core.Messages.Save;
import dev.mainframe.core.Messages.Saved;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace's file, written whole, on the offload lane. */
@Job(topic = Messages.SAVE, reply = Messages.SAVED)
public final class SaveWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final WorkspaceStore workspaces;

    public SaveWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Saved run(Save s) {
        try {
            workspaces.save(Workspace.parse(s.name(), s.text()));
            return new Saved(s.name(), "");
        } catch (IOException e) {
            LOG.warn("cannot save workspace {}", s.name(), e);
            return new Saved(s.name(), String.valueOf(e.getMessage()));
        }
    }
}
