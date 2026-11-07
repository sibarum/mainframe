package dev.mainframe.core;

import dev.mainframe.core.Messages.Gather;
import dev.mainframe.core.Messages.Gathered;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

/**
 * Every workspace, keyed, for the Workspaces window: {@link ListWorkspaces}' list on a topic of its own, since what
 * that one answers opens the Open dialog.
 */
@Job
public final class GatherWorkspaces {

    private final Settings settings;

    public GatherWorkspaces(Settings settings) {
        this.settings = settings;
    }

    public Gathered run(Gather g) {
        return new Gathered(ListWorkspaces.all(WorkspaceStore.of(settings)));
    }
}
