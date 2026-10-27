package dev.mainframe;

import dev.mainframe.core.AppSettings;
import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.Answer;
import dev.mainframe.core.Messages.Ask;
import dev.mainframe.core.Messages.CloseAsked;
import dev.mainframe.core.Messages.CloseTab;
import dev.mainframe.core.Messages.CloseWindow;
import dev.mainframe.core.Messages.EditEnv;
import dev.mainframe.core.Messages.Focus;
import dev.mainframe.core.Messages.Gone;
import dev.mainframe.core.Messages.OpenFolder;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.PickFolder;
import dev.mainframe.core.Messages.Tell;
import dev.mainframe.core.Messages.Tile;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.widget.Modal;
import sibarum.atchung.Atchung;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The main window's view: what the frame decided, shown, and what the person did, told. On a lane of its own.
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision is the {@code Frame}'s, in {@code -core}: which tabs a launch opens, which shell a tab starts and
 * where, whether closing one asks, whether what is opened takes the spare tab's place. This turns the frame's messages
 * into widgets ({@code AddTab} is a tab, {@code Ask} is a dialog, {@code Tile} is a tile of the Open dialog) and the
 * person's gestures into messages ({@code CloseAsked}, {@code OpenFolder}, {@code EditEnv}), adding its word on what
 * only it can see: the title a tab's shell gave it, and whether anybody has typed into the only tab.
 *
 * <h2>Threads</h2>
 *
 * <p>Every {@code @Subscribe} here runs on the {@code view} lane, one at a time. Input arrives elsewhere: a chord on
 * the handler lane, a menu item or a tile on whichever worker delivers it, a picked folder on the dialog's. Those only
 * send an {@link Intent} to this lane, or a message straight to the frame when nothing of the view's is needed to
 * say it. The tabs themselves ({@link Terminals}) are also read by the frame loop, which draws them, so they keep a
 * lock of their own. Writing a widget from here is the framework's idiom: a prop written off the GUI thread is queued
 * and applied by the next drain.
 */
@Component(lane = "view")
@Publishes({Messages.OPEN_FOLDER, Messages.OPEN_WORKSPACE, Messages.CLOSE_ASKED, Messages.GONE, Messages.ANSWER,
        Messages.EDIT_ENV, Intent.TOPIC})
final class View {

    private final Terminals terminals;
    private final OpenDialog open;
    private final Atchung bus;
    private final Dialogs dialogs;
    private final Windows windows;
    /** The Open dialog's tiles as they arrive, for the listing in flight. */
    private long batch = -1;
    private Tile[] tiles = new Tile[0];
    private int arrived;

    View(Terminals terminals, OpenDialog open, Atchung bus, Dialogs dialogs, Windows windows) {
        this.terminals = terminals;
        this.open = open;
        this.bus = bus;
        this.dialogs = dialogs;
        this.windows = windows;
    }

    @Subscribe(topic = Intent.TOPIC)
    public void intent(Intent intent) {
        intent.command().on(this, intent.id(), intent.text());
    }

    // ------------------------------------------------------------------ what the frame says

    /**
     * A tab, started and shown. A shell that cannot be started is said rather than thrown, opens no tab, and the frame
     * hears it has gone, so a launch whose every shell failed still ends. The tab it replaces closes once it is open.
     */
    @Subscribe(topic = Messages.ADD_TAB, capacity = Messages.SET)
    public void addTab(AddTab m) {
        if (!terminals.add(m)) {
            dialogs.info("Could not start the shell", m.command());
            bus.publish(Messages.GONE_TOPIC, new Gone(m.id()));
            return;
        }
        if (m.replaces() != Messages.NO_TAB) {
            terminals.close(m.replaces());
        }
    }

    @Subscribe(topic = Messages.CLOSE_TAB)
    public void closeTab(CloseTab m) {
        terminals.close(m.id());
    }

    @Subscribe(topic = Messages.FOCUS)
    public void focus(Focus m) {
        TerminalView v = m.id() == Messages.NO_TAB ? null : terminals.byId(m.id());
        if (v != null) {
            v.focus();
        } else {
            terminals.focus();
        }
    }

    /** The last tab has gone, and with it the window, which ends MainFrame. */
    @Subscribe(topic = Messages.CLOSE_WINDOW)
    public void closeWindow(CloseWindow m) {
        windows.main().close();
    }

    /** A question, as the framework's dialog: its answer goes back to the frame, from whichever thread presses it. */
    @Subscribe(topic = Messages.ASK)
    public void ask(Ask m) {
        dialogs.ask(Modal.of(m.title(), m.message())
                .defaultButton(m.confirm(), () -> bus.publish(Messages.ANSWER_TOPIC, new Answer(m.question(), true)))
                .cancelButton(m.cancel(), () -> bus.publish(Messages.ANSWER_TOPIC, new Answer(m.question(), false))));
    }

    @Subscribe(topic = Messages.TELL)
    public void tell(Tell m) {
        dialogs.info(m.title(), m.message());
    }

    /** A tile of the Open dialog. The dialog opens when the last of its listing is in, with the workspaces in order. */
    @Subscribe(topic = Messages.TILE, capacity = Messages.SET)
    public void tile(Tile m) {
        if (m.batch() != batch) {
            if (m.batch() < batch) {
                return;
            }
            batch = m.batch();
            tiles = new Tile[m.of()];
            arrived = 0;
        }
        if (m.index() < 0 || m.index() >= tiles.length || tiles[m.index()] != null) {
            return;
        }
        tiles[m.index()] = m;
        if (++arrived < tiles.length) {
            return;
        }
        List<OpenDialog.Choice> spaces = new ArrayList<>();
        for (Tile t : tiles) {
            if (!t.name().isBlank()) {
                spaces.add(new OpenDialog.Choice(t.name(), t.detail()));
            }
        }
        open.show(spaces);
    }

    @Subscribe(topic = Messages.PICK_FOLDER)
    public void pickFolder(PickFolder m) {
        pickFolder();
    }

    // ------------------------------------------------------------------ what the person did, on this lane

    /** Close tab {@code id}, with what its header says now for the question. */
    void close(long id, boolean ask) {
        TerminalView v = terminals.byId(id);
        if (v != null) {
            bus.publish(Messages.CLOSE_ASKED_TOPIC, new CloseAsked(id, v.displayTitle(), ask));
        }
    }

    void editEnv(long id, boolean tab) {
        bus.publish(Messages.EDIT_ENV_TOPIC, new EditEnv(id, tab));
    }

    /** Ask the OS for a folder, starting in the profile; Cancel gives the terminal back the keyboard. */
    void pickFolder() {
        dialogs.openFolder(AppSettings.home(),
                dir -> bus.publish(Intent.INTENTS, new Intent(Intent.Command.OPEN_FOLDER, Messages.NO_TAB, dir.toString())),
                terminals::focus);
    }

    void openFolder(String dir) {
        try {
            bus.publish(Messages.OPEN_FOLDER_TOPIC, new OpenFolder(Path.of(dir), terminals.spare()));
        } catch (InvalidPathException e) {
            terminals.focus();
        }
    }

    void openWorkspace(String name, boolean here) {
        bus.publish(Messages.OPEN_WORKSPACE_TOPIC, new OpenWorkspace(name, here, terminals.spare()));
    }
}
