package dev.mainframe;

import dev.mainframe.core.Messages.ChangePrefs;
import dev.mainframe.core.Messages.Prefs;
import dev.mainframe.core.Messages.PrefsSaved;
import dev.mainframe.core.TabMotion;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import sibarum.atchung.Sender;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The settings window's view: what the frame says is set, shown on the {@link SettingsPage}, and each touch of a
 * control told back as {@link ChangePrefs}, which the frame applies, says to every view and saves (docs/settings.md).
 * A second {@code @View} beside the main window's: each hears every widget message and keeps to its own widgets by
 * their address.
 *
 * <p>It decides nothing: a change is the setting as shown with the one thing touched changed, and the frame's
 * {@link Prefs} is what the page then shows, so a chord in the main window and a click here meet in one place.
 */
@dev.vexelray.framework.api.View
final class SettingsView {

    /** The folder picker's question: the framework answers with the same id. */
    static final long PICK = 0x5e7_7175L;

    private final SettingsPage page;
    private final Sender<ChangePrefs> change;
    private final Sender<PickFile> pick;

    SettingsView(SettingsPage page, Sender<ChangePrefs> change, Sender<PickFile> pick) {
        this.page = page;
        this.change = change;
        this.pick = pick;
    }

    @Subscribe
    public void prefs(Prefs m) {
        page.show(m);
    }

    @Subscribe
    public void saved(PrefsSaved m) {
        page.problem(m.problem());
    }

    /** The text size or the tab motion. */
    @Subscribe
    public void segment(Segment.Changed m) {
        if (m.segment().equals(page.sizeAddress()) && m.value() instanceof Integer px) {
            change.send(new ChangePrefs(page.prefs().withFontPx(px)));
        } else if (m.segment().equals(page.motionAddress()) && m.value() instanceof TabMotion motion) {
            change.send(new ChangePrefs(page.prefs().withMotion(motion)));
        }
    }

    /** The shell new tabs run. */
    @Subscribe
    public void chosen(Select.Committed m) {
        if (m.select().equals(page.shellAddress())) {
            m.chosen().stream().findFirst().flatMap(page::shell)
                    .ifPresent(c -> change.send(new ChangePrefs(page.prefs().withShell(c.id()))));
        }
    }

    /** Choose… asks the picker, starting where new tabs start now; the home button forgets the chosen folder. */
    @Subscribe
    public void pressed(Button.Pressed m) {
        if (m.button().equals(page.chooseAddress())) {
            pick.send(PickFile.folder(PICK, page.prefs().dir().map(Object::toString).orElse("")));
        } else if (m.button().equals(page.homeAddress())) {
            change.send(new ChangePrefs(page.prefs().withDir(Optional.empty())));
        }
    }

    /** The folder the person picked; nothing when they cancelled. */
    @Subscribe
    public void picked(Picked m) {
        if (m.id() != PICK || m.path().isBlank()) {
            return;
        }
        try {
            change.send(new ChangePrefs(page.prefs().withDir(Optional.of(Path.of(m.path()).toAbsolutePath()))));
        } catch (InvalidPathException e) {
            page.problem("Not a folder MainFrame can use: " + m.path());
        }
    }
}
