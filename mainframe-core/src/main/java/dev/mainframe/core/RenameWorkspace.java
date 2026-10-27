package dev.mainframe.core;

import dev.mainframe.core.Messages.Rename;
import dev.mainframe.core.Messages.Renamed;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace renamed, its file and its scripts, on the offload lane. Another workspace is never overwritten. */
@Job(topic = Messages.RENAME, reply = Messages.RENAMED)
public final class RenameWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final WorkspaceStore workspaces;

    public RenameWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Renamed run(Rename r) {
        try {
            workspaces.rename(r.from(), r.to());
            return new Renamed(r.from(), r.to(), r.record(), "");
        } catch (IOException e) {
            LOG.warn("cannot rename workspace {} to {}", r.from(), r.to(), e);
            return new Renamed(r.from(), r.to(), r.record(), String.valueOf(e.getMessage()));
        }
    }
}
