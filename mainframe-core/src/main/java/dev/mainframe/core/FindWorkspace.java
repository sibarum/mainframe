package dev.mainframe.core;

import dev.mainframe.core.Messages.Find;
import dev.mainframe.core.Messages.Found;
import dev.vexelray.framework.api.Job;

/** Whether a workspace is there, on the offload lane. */
@Job(topic = Messages.FIND, reply = Messages.FOUND)
public final class FindWorkspace {

    private final WorkspaceStore workspaces;

    public FindWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Found run(Find f) {
        return new Found(f.name(), f.offer(), workspaces.exists(f.name()));
    }
}
