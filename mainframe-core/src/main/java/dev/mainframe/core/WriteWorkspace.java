package dev.mainframe.core;

import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;

/** A workspace's file, written whole, on the offload lane (docs/workspaces-page.md). */
@Job
public final class WriteWorkspace {

    private static final Log LOG = Log.of("mainframe.workspaces");

    private final Settings settings;

    public WriteWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Written run(Write w) {
        String name = w.workspace().name();
        try {
            WorkspaceStore.of(settings).save(w.workspace());
            return new Written(name, "");
        } catch (IOException e) {
            LOG.warn("cannot save workspace {}", name, e);
            return new Written(name, "Could not save " + name + ": " + e.getMessage());
        }
    }
}
