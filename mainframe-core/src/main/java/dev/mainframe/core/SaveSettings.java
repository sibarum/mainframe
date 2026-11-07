package dev.mainframe.core;

import dev.mainframe.core.Messages.PrefsSaved;
import dev.mainframe.core.Messages.SavePrefs;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.UncheckedIOException;

/**
 * Write the settings and save the file, on the offload lane: a job, because the store is a file.
 *
 * <p>Jobs are in no order, so the frame keeps one save in flight at a time and sends the next when this one answers;
 * each holds every setting, not the one that changed, so the file always ends at the last change (docs/settings.md).
 */
@Job
public final class SaveSettings {

    private static final Log LOG = Log.of("mainframe.settings");

    private final Settings settings;

    public SaveSettings(Settings settings) {
        this.settings = settings;
    }

    public PrefsSaved run(SavePrefs save) {
        try {
            PrefsStore.write(settings, save.prefs());
            return new PrefsSaved(save.seq(), "");
        } catch (UncheckedIOException e) {
            LOG.warn("could not save the settings", e);
            String why = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            return new PrefsSaved(save.seq(), "Could not save settings: " + why);
        }
    }
}
