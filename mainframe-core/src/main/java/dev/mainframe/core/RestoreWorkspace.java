package dev.mainframe.core;

import dev.mainframe.core.Messages.Restore;
import dev.mainframe.core.Messages.Restored;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.Optional;

/** A workspace a delete put aside, brought back with its scripts, on the offload lane. */
@Job
public final class RestoreWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final Settings settings;

    public RestoreWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Restored run(Restore r) {
        WorkspaceStore store = WorkspaceStore.of(settings);
        try {
            store.restore(r.stash(), r.name());
            return new Restored(r.name(), store.keyed(r.name()), r.entry(), r.turn(), "");
        } catch (IOException e) {
            LOG.warn("cannot bring back workspace {}", r.name(), e);
            return new Restored(r.name(), Optional.empty(), r.entry(), r.turn(), String.valueOf(e.getMessage()));
        }
    }
}
