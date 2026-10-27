package dev.mainframe.core;

import dev.mainframe.core.Messages.Launch;
import dev.mainframe.core.Messages.Launched;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.List;

/** A MainFrame of its own for a workspace, a process of its own, started on the offload lane ({@link SelfLaunch}). */
@Job(topic = Messages.LAUNCH, reply = Messages.LAUNCHED)
public final class LaunchWindow {

    private static final Log LOG = Log.of("mainframe");

    public Launched run(Launch l) {
        try {
            SelfLaunch.start(List.of("--workspace=" + l.name()));
            return new Launched(l.name(), l.spare(), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not start a MainFrame for {}", l.name(), e);
            return new Launched(l.name(), l.spare(), "A new MainFrame did not start: " + e.getMessage());
        }
    }
}
