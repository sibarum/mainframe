package dev.mainframe.core;

import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.WorkspacesWanted;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Every workspace, keyed (each row given its script key, as opening one does), in name order: what Ctrl+O shows. A
 * workspace whose file will not read is left out; {@link WorkspaceStore} logs it.
 */
@Job
public final class ListWorkspaces {

    private final Settings settings;

    public ListWorkspaces(Settings settings) {
        this.settings = settings;
    }

    public Listed run(WorkspacesWanted w) {
        WorkspaceStore store = WorkspaceStore.of(settings);
        List<Workspace> out = new ArrayList<>();
        for (String name : store.names()) {
            store.keyed(name).ifPresent(out::add);
        }
        return new Listed(out);
    }
}
