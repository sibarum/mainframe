package dev.mainframe.core;

import dev.mainframe.core.Messages.LaunchWorkspace;
import dev.mainframe.core.Messages.Launched;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.List;

/** Another MainFrame, a process of its own, on a workspace ({@link SelfLaunch}). 0.1's, with its declaration changed. */
@Job
public final class LaunchWindow {

    private static final Log LOG = Log.of("mainframe");

    public Launched run(LaunchWorkspace l) {
        try {
            SelfLaunch.start(List.of("--workspace=" + l.workspace()));
            return new Launched(l.workspace(), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not start a MainFrame for {}", l.workspace(), e);
            return new Launched(l.workspace(), "A new MainFrame did not start: " + e.getMessage());
        }
    }
}
