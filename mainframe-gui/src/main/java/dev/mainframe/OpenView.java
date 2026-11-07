package dev.mainframe;

import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.Tiles;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.ListView;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The Open dialog's view (Ctrl+O): the frame's {@link Tiles} shown, and the row the person opens told back as
 * {@link OpenWorkspace} or, through the folder picker, {@link OpenFolder}. With no workspace saved there is nothing to
 * choose, so it asks the picker straight away and the dialog never shows (0.1's README).
 *
 * <p>It decides nothing: which window a workspace opens in is the person's key (Enter, Ctrl+Enter, the row's menu),
 * and what opening does is the frame's. A third {@code @View}, keeping to its own widgets by their address.
 */
@dev.vexelray.framework.api.View
final class OpenView {

    /** The folder picker's question for <i>Open folder…</i>: the framework answers with the same id. */
    static final long PICK = 0x09e_4f01dL;

    private final OpenPage page;
    private final Sender<OpenWorkspace> open;
    private final Sender<OpenFolder> folder;
    private final Sender<PickFile> pick;
    private final Sender<WindowCommand> windows;

    /** The row the list has selected, which Ctrl+Enter opens; null before one is. */
    private OpenPage.Row selected;

    OpenView(OpenPage page, Keymap keys, Sender<OpenWorkspace> open, Sender<OpenFolder> folder, Sender<PickFile> pick,
             Sender<WindowCommand> windows, Sender<OpenPage.Key> dialogKeys) {
        this.page = page;
        this.open = open;
        this.folder = folder;
        this.pick = pick;
        this.windows = windows;
        // Esc is declared by the main view, before the terminal's Esc can be in the way (FN-14); Ctrl+Enter is in nobody's.
        keys.action("open.here", "Open the workspace in this window", Shortcut.of(Key.ENTER, Modifier.CONTROL))
                .sends(dialogKeys, new OpenPage.Key(true)).within(page.gui(), page.listNode(), ClaimScope.FOCUSED);
    }

    @Subscribe
    public void tiles(Tiles m) {
        if (m.workspaces().isEmpty()) {
            pick.send(PickFile.folder(PICK, ""));
            return;
        }
        page.show(m.workspaces());
        windows.send(WindowCommand.show(OpenPage.KEY));
    }

    @Subscribe
    public void selected(ListView.Selected m) {
        if (m.list().equals(page.listAddress())) {
            selected = m.chosen().stream().filter(OpenPage.Row.class::isInstance).map(OpenPage.Row.class::cast)
                    .findFirst().orElse(null);
        }
    }

    /** Enter or a double-click: a workspace in a new window, or the folder picker. */
    @Subscribe
    public void activated(ListView.Activated m) {
        if (m.list().equals(page.listAddress()) && m.item() instanceof OpenPage.Row row) {
            choose(row, false);
        }
    }

    /** The row's menu: Open in this window. */
    @Subscribe
    public void chosen(ListView.Chosen m) {
        if (m.at().equals(page.listAddress()) && m.action().equals(OpenPage.HERE.id())
                && m.item() instanceof OpenPage.Row row) {
            choose(row, true);
        }
    }

    /** Ctrl+Enter opens the selected row here; Esc puts the dialog away. */
    @Subscribe
    public void key(OpenPage.Key m) {
        if (!m.here()) {
            windows.send(WindowCommand.hide(OpenPage.KEY));
        } else if (selected != null) {
            choose(selected, true);
        }
    }

    /** The folder the person picked, for a tab here; nothing when they cancelled. */
    @Subscribe
    public void picked(Picked m) {
        if (m.id() != PICK || m.path().isBlank()) {
            return;
        }
        try {
            folder.send(new OpenFolder(Path.of(m.path()).toAbsolutePath()));
        } catch (InvalidPathException e) {
            // Not a path this platform can hold: nothing to open.
        }
    }

    private void choose(OpenPage.Row row, boolean here) {
        windows.send(WindowCommand.hide(OpenPage.KEY));
        if (row.folder()) {
            pick.send(PickFile.folder(PICK, ""));
        } else {
            open.send(new OpenWorkspace(row.name(), here));
        }
    }
}
