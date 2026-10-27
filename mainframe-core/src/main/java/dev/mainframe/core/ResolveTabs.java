package dev.mainframe.core;

import dev.mainframe.core.Messages.Resolve;
import dev.mainframe.core.Messages.Resolved;
import dev.vexelray.framework.api.Job;

/**
 * The tabs a {@code --dir}, {@code --dirs} or {@code --workspace} names, on the offload lane: the folders that are
 * there, and for a workspace each row's name and the key of its script, given and saved if the row had none
 * ({@link Startup#tabs}). What is not there is logged and skipped, so this has no failure of its own to answer.
 */
@Job(topic = Messages.RESOLVE, reply = Messages.RESOLVED)
public final class ResolveTabs {

    private final WorkspaceStore workspaces;

    public ResolveTabs(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Resolved run(Resolve r) {
        Workspace tabs = Startup.tabs(new Command.Source(r.kind(), r.value()), workspaces);
        return new Resolved(r.seq(), tabs.name(), Workspace.format(tabs));
    }
}
