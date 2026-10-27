package dev.mainframe.core;

import dev.mainframe.core.Messages.Prune;
import dev.vexelray.framework.api.Job;

/**
 * Deleted workspaces older than any undo can want, cleared once a run, on the offload lane. Run for its effect: what
 * cannot be cleared is logged and left for the next run.
 */
@Job(topic = Messages.PRUNE)
public final class PruneDeleted {

    private final WorkspaceStore workspaces;

    public PruneDeleted(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public void run(Prune p) {
        workspaces.pruneDeleted(WorkspaceStore.KEEP_DELETED);
    }
}
