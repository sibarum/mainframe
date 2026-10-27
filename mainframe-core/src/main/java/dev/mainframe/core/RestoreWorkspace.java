package dev.mainframe.core;

import dev.mainframe.core.Messages.Restore;
import dev.mainframe.core.Messages.Restored;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace a delete put aside, brought back with its scripts, on the offload lane. */
@Job(topic = Messages.RESTORE, reply = Messages.RESTORED)
public final class RestoreWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final WorkspaceStore workspaces;

    public RestoreWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Restored run(Restore r) {
        try {
            workspaces.restore(r.stash(), r.name());
            return new Restored(r.name(), r.stash(), "");
        } catch (IOException e) {
            LOG.warn("cannot bring back workspace {}", r.name(), e);
            return new Restored(r.name(), r.stash(), String.valueOf(e.getMessage()));
        }
    }
}
