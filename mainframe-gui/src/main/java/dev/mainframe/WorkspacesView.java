package dev.mainframe;

import dev.mainframe.core.Messages.AddRow;
import dev.mainframe.core.Messages.DeleteWorkspace;
import dev.mainframe.core.Messages.MoveRow;
import dev.mainframe.core.Messages.NameWanted;
import dev.mainframe.core.Messages.NewWorkspace;
import dev.mainframe.core.Messages.OpenWorkspace;
import dev.mainframe.core.Messages.Recorded;
import dev.mainframe.core.Messages.Redo;
import dev.mainframe.core.Messages.RemoveRow;
import dev.mainframe.core.Messages.RenameRow;
import dev.mainframe.core.Messages.RenameWorkspace;
import dev.mainframe.core.Messages.RepointRow;
import dev.mainframe.core.Messages.Select;
import dev.mainframe.core.Messages.Showing;
import dev.mainframe.core.Messages.Undo;
import dev.mainframe.core.Workspace;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Deck;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Workspaces window's view (docs/workspaces-page.md): the component's {@link Showing} put on the page, and what
 * the person does told back: a workspace picked, made, renamed, opened or deleted, and a card added, moved, renamed,
 * given another folder or removed. Each step the component records goes into the window's history as a
 * {@link Journaled}, so Ctrl+Z and Ctrl+Y ask the component, which knows what the step was.
 *
 * <p>It decides nothing: which rows a gesture makes, whether a name is taken, and what an undo does are the
 * component's. A fourth {@code @View}, keeping to its own widgets by their address.
 */
@dev.vexelray.framework.api.View
final class WorkspacesView {

    /** The folder picker's questions: a folder for a new card, and another folder for a card. */
    static final long ADD_PICK = 0x5ace_add1L;
    static final long REPOINT_PICK = 0x5ace_4e9L;

    private final WorkspacesPage page;
    private final Sender<Select> select;
    private final Sender<NewWorkspace> make;
    private final Sender<RenameWorkspace> rename;
    private final Sender<DeleteWorkspace> delete;
    private final Sender<AddRow> add;
    private final Sender<RemoveRow> remove;
    private final Sender<MoveRow> move;
    private final Sender<RenameRow> retitle;
    private final Sender<RepointRow> repoint;
    private final Sender<Undo> undo;
    private final Sender<Redo> redo;
    private final Sender<OpenWorkspace> open;
    private final Sender<PickFile> pick;
    private final Sender<WorkspacesPage.CardAsk> asks;

    /** What the component last said. */
    private Showing showing = new Showing(List.of(), "", "", "", "");
    /** The card whose tab is being renamed, or given another folder; null for none. */
    private String card;
    /** Selections the view made in the list, which the list has yet to say back: not the person's, so not told. */
    private final java.util.ArrayDeque<String> echoes = new java.util.ArrayDeque<>();

    WorkspacesView(WorkspacesPage page, Keymap keys, Sender<Select> select, Sender<NewWorkspace> make,
                   Sender<RenameWorkspace> rename, Sender<DeleteWorkspace> delete, Sender<AddRow> add,
                   Sender<RemoveRow> remove, Sender<MoveRow> move, Sender<RenameRow> retitle,
                   Sender<RepointRow> repoint, Sender<Undo> undo, Sender<Redo> redo, Sender<OpenWorkspace> open,
                   Sender<PickFile> pick, Sender<WorkspacesPage.CardAsk> asks) {
        this.page = page;
        this.select = select;
        this.make = make;
        this.rename = rename;
        this.delete = delete;
        this.add = add;
        this.remove = remove;
        this.move = move;
        this.retitle = retitle;
        this.repoint = repoint;
        this.undo = undo;
        this.redo = redo;
        this.open = open;
        this.pick = pick;
        this.asks = asks;
        // Esc and Ctrl+Shift+R are declared by the main view, before the terminal's and the tab bar's (FN-14).
        keys.action("workspaces.delete", "Delete the workspace", Shortcut.of(Key.DELETE), Shortcut.of(Key.BACKSPACE))
                .sends(asks, new WorkspacesPage.CardAsk("", false))
                .within(page.gui(), page.listNode(), ClaimScope.FOCUSED);
    }

    // ------------------------------------------------------------------ what the component says

    @Subscribe
    public void showing(Showing m) {
        showing = m;
        String echo = page.show(m.all(), m.selected(), m.launch(), m.problem(), m.said(), !m.problem().isBlank(),
                remove, asks);
        if (echo != null) {
            echoes.add(echo);
        }
    }

    /** A workspace just made: the keyboard in its name. */
    @Subscribe
    public void nameWanted(NameWanted m) {
        page.name(m.workspace());
        page.focusName();
    }

    /** A step landed: one entry in the window's history, which asks the component to take it back. */
    @Subscribe
    public void recorded(Recorded m) {
        History history = page.gui().history();
        if (history != null) {
            history.record(new Journaled(m.entry(), true, undo, redo));
        }
    }

    // ------------------------------------------------------------------ what the person does

    @Subscribe
    public void selected(ListView.Selected m) {
        if (!m.list().equals(page.listAddress())) return;
        m.chosen().stream().filter(WorkspacesPage.Row.class::isInstance).map(WorkspacesPage.Row.class::cast)
                .findFirst().ifPresent(r -> {
                    if (r.name().equals(echoes.peek())) {
                        echoes.poll();   // the list saying back what this view selected
                    } else if (!r.name().equals(showing.selected())) {
                        echoes.clear();
                        select.send(new Select(r.name()));
                    }
                });
    }

    /** A workspace row's menu. */
    @Subscribe
    public void chosen(ListView.Chosen m) {
        if (!m.at().equals(page.listAddress()) || !(m.item() instanceof WorkspacesPage.Row row)) return;
        String a = m.action();
        if (a.equals(WorkspacesPage.OPEN.id()) || a.equals(WorkspacesPage.HERE.id())) {
            open.send(new OpenWorkspace(row.name(), a.equals(WorkspacesPage.HERE.id())));
        } else if (a.equals(WorkspacesPage.DELETE.id())) {
            delete.send(new DeleteWorkspace(row.name()));
        } else if (a.equals(WorkspacesPage.RENAME.id())) {
            if (!row.name().equals(showing.selected())) select.send(new Select(row.name()));
            page.name(row.name());
            page.focusName();
        }
    }

    @Subscribe
    public void pressed(Button.Pressed m) {
        if (m.button().equals(page.newAddress())) {
            make.send(new NewWorkspace());
        } else if (m.button().equals(page.addAddress()) && !showing.selected().isBlank()) {
            pick.send(PickFile.folder(ADD_PICK, ""));
        }
    }

    /** Enter in the name, or in a card's tab name. */
    @Subscribe
    public void submitted(TextField.Submitted m) {
        if (m.field().equals(page.nameAddress()) && !showing.selected().isBlank()) {
            rename.send(new RenameWorkspace(showing.selected(), m.text()));
        } else if (m.field().equals(page.retitleAddress()) && card != null) {
            at(card).ifPresent(i -> retitle.send(new RenameRow(showing.selected(), i, m.text())));
            endRetitle();
        }
    }

    /** A card dropped, or moved by Alt+Up and Alt+Down: from its place to where it landed. */
    @Subscribe
    public void placed(Deck.Placed m) {
        if (!m.at().equals(page.deckAddress()) || !(m.item() instanceof String key)) return;
        Optional<Workspace> w = selected();
        if (w.isEmpty()) return;
        List<String> keys = keys(w.get());
        int from = keys.indexOf(key);
        if (from < 0) return;
        keys.remove(from);
        int to = keys.size();
        Placement<?> where = m.where();
        if (where.reference() != null) {
            int ref = keys.indexOf(where.reference());
            if (ref < 0) return;
            to = where.relation() == Placement.Relation.AFTER ? ref + 1 : ref;
        }
        if (to != from) {
            move.send(new MoveRow(w.get().name(), from, to));
        } else {
            page.show(showing.all(), showing.selected(), showing.launch(), showing.problem(), showing.said(),
                    !showing.problem().isBlank(), remove, asks);   // back where it was: the deck shows the list again
        }
    }

    /** A card's menu, or the list's Delete key (a blank key). */
    @Subscribe
    public void ask(WorkspacesPage.CardAsk m) {
        if (m.key().isEmpty()) {
            if (!showing.selected().isBlank()) delete.send(new DeleteWorkspace(showing.selected()));
            return;
        }
        Optional<Workspace> w = selected();
        Optional<Integer> i = at(m.key());
        if (w.isEmpty() || i.isEmpty()) return;
        card = m.key();
        Workspace.Entry e = w.get().entries().get(i.get());
        if (m.folder()) {
            pick.send(PickFile.folder(REPOINT_PICK, e.path()));
        } else {
            page.retitling(true, e.title(), e.name().isBlank() ? e.title() : e.name());
        }
    }

    /** Esc puts a field back as it was; Ctrl+Shift+R goes to the name. */
    @Subscribe
    public void key(WorkspacesPage.Key m) {
        if (!m.cancel()) {
            page.focusName();
        } else if (card != null) {
            endRetitle();
        } else if (!showing.selected().isBlank()) {
            page.name(showing.selected());
            if (!showing.problem().isBlank()) {
                rename.send(new RenameWorkspace(showing.selected(), showing.selected()));   // clears what was refused
            }
        }
    }

    /** A folder picked: a new card, or a card's new folder; nothing when the person cancelled. */
    @Subscribe
    public void picked(Picked m) {
        if (m.id() != ADD_PICK && m.id() != REPOINT_PICK) return;
        String ws = showing.selected();
        String key = card;
        card = null;
        if (m.path().isBlank() || ws.isBlank()) return;
        Path dir;
        try {
            dir = Path.of(m.path()).toAbsolutePath();
        } catch (InvalidPathException e) {
            return;   // not a path this platform can hold
        }
        if (m.id() == ADD_PICK) {
            add.send(new AddRow(ws, dir));
        } else if (key != null) {
            at(key).ifPresent(i -> repoint.send(new RepointRow(ws, i, dir)));
        }
    }

    // ------------------------------------------------------------------

    private void endRetitle() {
        card = null;
        page.retitling(false, "", "");
    }

    private Optional<Workspace> selected() {
        return showing.all().stream().filter(w -> w.name().equals(showing.selected())).findFirst();
    }

    private static List<String> keys(Workspace w) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < w.entries().size(); i++) keys.add(WorkspacesPage.key(w.entries().get(i), i));
        return keys;
    }

    private Optional<Integer> at(String key) {
        return selected().map(w -> keys(w).indexOf(key)).filter(i -> i >= 0);
    }

    /** The card being renamed or repointed: for a test. */
    String card() {
        return card;
    }
}
