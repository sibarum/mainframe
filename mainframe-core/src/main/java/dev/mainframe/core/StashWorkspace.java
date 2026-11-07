package dev.mainframe.core;

import dev.mainframe.core.Messages.Stash;
import dev.mainframe.core.Messages.Stashed;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace put aside, its file and its scripts, where undoing the delete finds them, on the offload lane. */
@Job
public final class StashWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final Settings settings;

    public StashWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Stashed run(Stash s) {
        String stash = WorkspaceStore.stashName(s.name());
        try {
            WorkspaceStore.of(settings).delete(s.name(), stash);
            return new Stashed(s.name(), stash, s.entry(), s.turn(), "");
        } catch (IOException e) {
            LOG.warn("cannot delete workspace {}", s.name(), e);
            return new Stashed(s.name(), stash, s.entry(), s.turn(), "Could not delete " + s.name() + ": " + e.getMessage());
        }
    }
}
