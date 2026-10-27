package dev.mainframe.core;

import dev.mainframe.core.Messages.Create;
import dev.mainframe.core.Messages.Created;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.List;

/** A new, empty workspace under the first name not taken, on the offload lane: what New makes. */
@Job(topic = Messages.CREATE, reply = Messages.CREATED)
public final class CreateWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final WorkspaceStore workspaces;

    public CreateWorkspace(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Created run(Create c) {
        String name = workspaces.freshName();
        try {
            workspaces.save(new Workspace(name, List.of()));
            return new Created(name, "");
        } catch (IOException e) {
            LOG.warn("cannot make workspace {}", name, e);
            return new Created(name, String.valueOf(e.getMessage()));
        }
    }
}
