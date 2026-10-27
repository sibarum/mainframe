package dev.mainframe.core;

import dev.mainframe.core.Messages.Load;
import dev.mainframe.core.Messages.Loaded;
import dev.vexelray.framework.api.Job;

import java.util.List;

/**
 * A workspace, to be shown in the settings window, on the offload lane: every row given its script's key, and saved
 * if any was given ({@link WorkspaceStore#keyed}), so each step the window records holds rows with their keys.
 */
@Job(topic = Messages.LOAD, reply = Messages.LOADED)
public final class LoadWorkspace {

    private final WorkspaceStore workspaces;

    public LoadWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Loaded run(Load l) {
        return workspaces.keyed(l.name())
                .map(w -> new Loaded(l.name(), Workspace.format(w), true))
                .orElseGet(() -> new Loaded(l.name(), Workspace.format(new Workspace(l.name(), List.of())), false));
    }
}
