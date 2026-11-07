package dev.mainframe.core;

import dev.mainframe.core.Messages.Prune;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

/**
 * Deleted workspaces older than any undo can want, cleared once a run, on the offload lane. Run for its effect: what
 * cannot be cleared is logged and left for the next run.
 */
@Job
public final class PruneDeleted {

    private final Settings settings;

    public PruneDeleted(Settings settings) {
        this.settings = settings;
    }

    public void run(Prune p) {
        WorkspaceStore.of(settings).pruneDeleted(WorkspaceStore.KEEP_DELETED);
    }
}
