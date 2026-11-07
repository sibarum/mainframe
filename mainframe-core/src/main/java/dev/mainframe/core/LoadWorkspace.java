package dev.mainframe.core;

import dev.mainframe.core.Messages.Load;
import dev.mainframe.core.Messages.Loaded;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

/** One workspace, keyed, for opening its tabs in this window; none when its file has gone since it was listed. */
@Job
public final class LoadWorkspace {

    private final Settings settings;

    public LoadWorkspace(Settings settings) {
        this.settings = settings;
    }

    public Loaded run(Load l) {
        return new Loaded(l.name(), WorkspaceStore.of(settings).keyed(l.name()));
    }
}
