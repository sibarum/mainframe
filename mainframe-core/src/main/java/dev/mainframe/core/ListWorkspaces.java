package dev.mainframe.core;

import dev.mainframe.core.Messages.ListAt;
import dev.mainframe.core.Messages.Listing;
import dev.vexelray.framework.api.Job;

import java.util.List;

/**
 * One workspace of those there are, by its place in alphabetical order, on the offload lane: its name and its file's
 * text. A list cannot cross, so a listing is a request per workspace, each reading the folder again; a workspace made
 * or deleted between two of them shifts the rest by one, which the next listing puts right.
 */
@Job(topic = Messages.LIST, reply = Messages.LISTED)
public final class ListWorkspaces {

    private final WorkspaceStore workspaces;

    public ListWorkspaces(WorkspaceStore workspaces) {
        this.workspaces = workspaces;
    }

    public Listing run(ListAt l) {
        List<String> names = workspaces.names();
        if (l.index() < 0 || l.index() >= names.size()) {
            return new Listing(l.who(), l.batch(), l.index(), names.size(), "", "");
        }
        String name = names.get(l.index());
        Workspace w = workspaces.load(name).orElse(new Workspace(name, List.of()));
        return new Listing(l.who(), l.batch(), l.index(), names.size(), name, Workspace.format(w));
    }
}
