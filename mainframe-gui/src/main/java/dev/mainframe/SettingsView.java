package dev.mainframe;

import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.Created;
import dev.mainframe.core.Messages.Deleted;
import dev.mainframe.core.Messages.EditName;
import dev.mainframe.core.Messages.Listing;
import dev.mainframe.core.Messages.Loaded;
import dev.mainframe.core.Messages.Renamed;
import dev.mainframe.core.Messages.Restored;
import dev.mainframe.core.Messages.Saved;
import dev.mainframe.core.Messages.ShowSettings;
import dev.mainframe.core.Messages.Who;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Subscribe;

/**
 * The settings window's view: what the workspace jobs did, shown, on a lane of its own.
 *
 * <p>The Workspaces page asks the jobs in {@code -core} itself (see {@link WorkspacesPage}), and their answers arrive
 * here, one at a time, to be handed to the page or the window. The frame hears the same answers on its own lane, for
 * what follows from them in the main window. The Open dialog's tiles ask for the window here too
 * ({@link ShowSettings}).
 */
@Component(lane = "settings")
final class SettingsView {

    private final SettingsWindow window;

    SettingsView(SettingsWindow window) {
        this.window = window;
    }

    @Subscribe(topic = Messages.SHOW_SETTINGS)
    public void show(ShowSettings m) {
        if (m.create()) {
            window.newWorkspace();
        } else if (m.workspace().isBlank()) {
            window.show();
        } else {
            window.showWorkspace(m.workspace());
        }
    }

    @Subscribe(topic = Messages.LISTED, capacity = Messages.SET)
    public void listed(Listing m) {
        if (m.who() == Who.SETTINGS) {
            window.listed(m);
        }
    }

    @Subscribe(topic = Messages.LOADED)
    public void loaded(Loaded m) {
        window.workspaces().loaded(m);
    }

    @Subscribe(topic = Messages.SAVED, capacity = Messages.SET)
    public void saved(Saved m) {
        window.workspaces().saved(m);
    }

    @Subscribe(topic = Messages.RENAMED)
    public void renamed(Renamed m) {
        window.workspaces().renamed(m);
    }

    @Subscribe(topic = Messages.DELETED)
    public void deleted(Deleted m) {
        window.workspaces().deleted(m);
    }

    @Subscribe(topic = Messages.RESTORED)
    public void restored(Restored m) {
        window.workspaces().restored(m);
    }

    @Subscribe(topic = Messages.CREATED)
    public void created(Created m) {
        window.workspaces().created(m);
    }

    /** The question New raised in the frame has been answered: the new name, ready to type over. */
    @Subscribe(topic = Messages.EDIT_NAME)
    public void editName(EditName m) {
        window.workspaces().editName();
    }
}
