package dev.mainframe.core;

import dev.mainframe.core.Messages.Delete;
import dev.mainframe.core.Messages.Deleted;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace put aside, its file and its scripts, where undoing the delete finds them, on the offload lane. */
@Job(topic = Messages.DELETE, reply = Messages.DELETED)
public final class DeleteWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final WorkspaceStore workspaces;

    public DeleteWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Deleted run(Delete d) {
        try {
            workspaces.delete(d.name(), d.stash());
            return new Deleted(d.name(), d.stash(), d.record(), "");
        } catch (IOException e) {
            LOG.warn("cannot delete workspace {}", d.name(), e);
            return new Deleted(d.name(), d.stash(), d.record(), String.valueOf(e.getMessage()));
        }
    }
}
