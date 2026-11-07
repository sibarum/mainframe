package dev.mainframe.core;

import dev.mainframe.core.Messages.Make;
import dev.mainframe.core.Messages.Made;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.List;

/** A new, empty workspace, refused when there is one of that name, on the offload lane. */
@Job
public final class MakeWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final Settings settings;

    public MakeWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Made run(Make m) {
        WorkspaceStore store = WorkspaceStore.of(settings);
        if (store.exists(m.name())) {
            return new Made(m.name(), m.entry(), m.turn(), "There is already a workspace called " + m.name() + ".");
        }
        try {
            store.save(new Workspace(m.name(), List.of()));
            return new Made(m.name(), m.entry(), m.turn(), "");
        } catch (IOException e) {
            LOG.warn("cannot make workspace {}", m.name(), e);
            return new Made(m.name(), m.entry(), m.turn(), "Could not make " + m.name() + ": " + e.getMessage());
        }
    }
}
