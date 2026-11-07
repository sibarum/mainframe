package dev.mainframe.core;

import dev.mainframe.core.Messages.Move;
import dev.mainframe.core.Messages.Moved;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace renamed, its file and its scripts, on the offload lane. Another workspace is never overwritten. */
@Job
public final class MoveWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final Settings settings;

    public MoveWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Moved run(Move m) {
        try {
            WorkspaceStore.of(settings).rename(m.from(), m.to());
            return new Moved(m.from(), m.to(), m.entry(), m.turn(), "");
        } catch (IOException e) {
            LOG.warn("cannot rename workspace {} to {}", m.from(), m.to(), e);
            return new Moved(m.from(), m.to(), m.entry(), m.turn(), String.valueOf(e.getMessage()));
        }
    }
}
