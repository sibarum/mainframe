package dev.mainframe;

import dev.mainframe.Intent.Command;
import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.AddTab;
import dev.mainframe.core.Messages.Caption;
import dev.mainframe.core.Messages.Close;
import dev.mainframe.core.Messages.CloseTabs;
import dev.mainframe.core.Messages.Edited;
import dev.mainframe.core.Messages.Flush;
import dev.mainframe.core.Messages.FrontTab;
import dev.mainframe.core.Messages.Fronted;
import dev.mainframe.core.Messages.Go;
import dev.mainframe.core.Messages.Listed;
import dev.mainframe.core.Messages.Listing;
import dev.mainframe.core.Messages.Mark;
import dev.mainframe.core.Messages.Marked;
import dev.mainframe.core.Messages.Open;
import dev.mainframe.core.Messages.Retitle;
import dev.mainframe.core.Messages.Save;
import dev.mainframe.core.Messages.SaveFailed;
import dev.mainframe.core.Messages.ShowFolder;
import dev.mainframe.core.Messages.Status;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The view: what the editor decided, shown, and what the person did, told. A {@code @View}, so it runs on the GUI
 * thread, in each frame's widget tick (threading.md, T7.4).
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision is the {@code Editor}'s, in {@code -core}: whether a file is already open, what a typed path names,
 * whether closing needs a question. This turns the editor's messages into widgets ({@code AddTab} is a tab) and the
 * person's gestures into messages ({@code Open}, {@code Close}, {@code Save}). A question is not the view's: the editor
 * sends the framework an {@code Ask}, and the framework shows it.
 *
 * <h2>Everything it hears is a message, in the frame</h2>
 *
 * <p>Every {@code @Subscribe} here is drained on the GUI thread, after input is dispatched and before layout: what the
 * editor and the jobs send, and what the widgets say they did (a tab selected, a tree's row clicked or its menu chosen,
 * a field's text changed). So a click is heard, and what the view writes about it drawn, in the frame the click
 * arrived, and the widgets, the {@link Workspace} and the {@link Navigator} are only ever touched here, with no lock.
 * A widget's messages carry its address, and every widget of a kind shares a topic, so each handler first asks which
 * of the view's widgets a message is about.
 *
 * <h2>Gestures are declared, not handled</h2>
 *
 * <p>A chord, a menu item and a dialog's button each name the message they send ({@link Intent}, a
 * {@code Messages} record) through a {@link Sender}; the dispatcher sends it, and nothing of the application's runs at
 * the moment of the click (T7.5). What needs the view comes back to it as one of these handlers.
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, and nothing else to send with: the wiring finds each one's topic by
 * its payload, beside the record in {@code Messages}, so the processor sees every message the view can send without
 * reading a line of its body (ROADMAP fork 5).
 *
 * <h2>Slow work is a job</h2>
 *
 * <p>Reading a folder for the tree and colouring a Markdown file are the jobs' ({@code ListFolder},
 * {@code MarkText}): the view asks and acts on the answer, because its time is the frame's.
 */
@dev.vexelray.framework.api.View
final class View {

    private final Gui gui;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;

    private final Sender<Open> open;
    private final Sender<Go> go;
    private final Sender<Fronted> fronted;
    private final Sender<Edited> edited;
    private final Sender<Close> close;
    private final Sender<Save> save;
    private final Sender<Listing> listing;
    private final Sender<Mark> mark;
    private final Sender<TreeView.Children> children;
    private final Sender<Intent> intents;

    /** The tab in front, as the editor was last told. */
    private long front = Messages.NO_TAB;

    View(Gui gui, Ui ui, Sender<Open> open, Sender<Go> go, Sender<Fronted> fronted,
         Sender<Edited> edited, Sender<Close> close, Sender<Save> save,
         Sender<Listing> listing, Sender<Mark> mark, Sender<TreeView.Children> children, Sender<Intent> intents) {
        this.gui = gui;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.open = open;
        this.go = go;
        this.fronted = fronted;
        this.edited = edited;
        this.close = close;
        this.save = save;
        this.listing = listing;
        this.mark = mark;
        this.children = children;
        this.intents = intents;
        shortcuts();
    }

    /**
     * Every chord, as a declaration: the chord and the {@link Intent} it sends. Chords a text field leaves alone, so a
     * focused field never needs to outrank them. Zoom is here too: which chord zooms, or whether zooming exists at all,
     * is the application's to say; how far it goes is {@code Appearance.ZoomRange}, the framework's.
     */
    private void shortcuts() {
        chord(Shortcut.of(Key.S, Modifier.CONTROL), Command.SAVE_FRONT);
        chord(Shortcut.of(Key.W, Modifier.CONTROL), Command.CLOSE_FRONT);
        chord(Shortcut.of(Key.L, Modifier.CONTROL), Command.FOCUS_PATH);
        chord(Shortcut.of(Key.E, Modifier.CONTROL, Modifier.SHIFT), Command.FOCUS_TREE);
        chord(Shortcut.of(Key.TAB, Modifier.CONTROL), Command.NEXT_TAB);
        chord(Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), Command.PREVIOUS_TAB);
        chord(Shortcut.of(Key.PAGE_DOWN, Modifier.CONTROL), Command.NEXT_TAB);
        chord(Shortcut.of(Key.PAGE_UP, Modifier.CONTROL), Command.PREVIOUS_TAB);
        chord(Shortcut.of(Key.EQUAL, Modifier.CONTROL), Command.ZOOM_IN);
        chord(Shortcut.of(Key.MINUS, Modifier.CONTROL), Command.ZOOM_OUT);
        chord(Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Command.ZOOM_RESET);
        chord(Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL), Command.ZOOM_IN);
        chord(Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL), Command.ZOOM_OUT);
        chord(Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL), Command.ZOOM_RESET);
    }

    private void chord(Shortcut shortcut, Command command) {
        gui.shortcut(shortcut, intents, new Intent(command));
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    // ------------------------------------------------------------------ what the editor says

    @Subscribe
    public void showFolder(ShowFolder m) {
        nav.show(m.path());
    }

    @Subscribe
    public void addTab(AddTab m) {
        ws.add(m.id(), m.path(), m.text(), m.focus());
    }

    @Subscribe
    public void frontTab(FrontTab m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            ws.show(b, m.focus());
        }
    }

    @Subscribe
    public void closeTabs(CloseTabs m) {
        for (long id : m.tabs()) {
            ws.close(id);
        }
    }

    /** The last title per tab in the frame: a state topic, so a burst of them is never a backlog. */
    @Subscribe
    public void retitle(Retitle m) {
        ws.retitle(m.id(), m.title());
    }

    @Subscribe
    public void caption(Caption m) {
        ui.caption(m.file());
    }

    @Subscribe
    public void status(Status m) {
        ui.status(m.message(), m.alert());
    }

    @Subscribe
    public void flush(Flush m) {
        for (long id : m.tabs()) {
            Buffer b = ws.byId(id);
            if (b == null) {
                save.send(new Save(id, "", false));
            } else {
                save(b);
            }
        }
    }

    @Subscribe
    public void saveFailed(SaveFailed m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.saveFailed();
            reportDirty(b);
        }
    }

    // ------------------------------------------------------------------ what the jobs answer

    @Subscribe
    public void listed(Listed m) {
        if (m.tree().equals(nav.treeAddress())) {
            children.send(nav.children(m));
        }
    }

    @Subscribe
    public void marked(Marked m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.marked(m);
        }
    }

    // ------------------------------------------------------------------ what the widgets say

    /** The bar's selection moved: the editor hears which file is in front, and the status line where its caret is. */
    @Subscribe
    public void tabSelected(Tabs.Selected m) {
        Buffer b = m.tabs().equals(ws.address()) ? ws.at(m.index()) : null;
        if (b != null && b.id != front) {
            front = b.id;
            ui.position(b.position());
            fronted.send(new Fronted(b.id));
        }
    }

    /** One of the tab menu's actions. */
    @Subscribe
    public void tabChosen(Tabs.Chosen m) {
        Buffer b = m.at().equals(ws.address()) && m.item() instanceof Integer i ? ws.at(i) : null;
        if (b == null) {
            return;
        }
        if (m.action().equals(Workspace.SAVE)) {
            save(b);
        } else if (m.action().equals(Workspace.CLOSE)) {
            close(List.of(b));
        } else if (m.action().equals(Workspace.CLOSE_OTHERS)) {
            List<Buffer> rest = new ArrayList<>(ws.all());
            rest.remove(b);
            close(rest);
        } else if (m.action().equals(Workspace.CLOSE_ALL)) {
            close(ws.all());
        } else if (m.action().equals(Workspace.REVEAL) && !nav.reveal(b.path)) {
            ui.status(b.name() + " is not inside the folder shown", false);
        }
    }

    /** A document's text changed: whether it is now unsaved, and its colours. */
    @Subscribe
    public void fieldChanged(TextField.Changed m) {
        Buffer b = ws.byField(m.field());
        if (b == null) {
            return;
        }
        reportDirty(b);
        Mark wanted = b.changed(m.text(), m.version());
        if (wanted != null) {
            mark.send(wanted);
        }
    }

    @Subscribe
    public void fieldCaret(TextField.Caret m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.caret(m.line(), m.column());
            if (b.id == front) {
                ui.position(b.position());
            }
        }
    }

    /** The path bar was submitted: what the text names is the editor's call. */
    @Subscribe
    public void fieldSubmitted(TextField.Submitted m) {
        if (m.field().equals(nav.pathAddress())) {
            go.send(new Go(m.text().strip()));
        }
    }

    /** The tree wants a level: ask {@code ListFolder}, or answer an empty top when no folder is shown yet. */
    @Subscribe
    public void treeWanted(TreeView.ChildrenWanted m) {
        if (!m.tree().equals(nav.treeAddress())) {
            return;
        }
        Listing wanted = nav.wanted(m.parent());
        if (wanted != null) {
            listing.send(wanted);
        } else {
            children.send(nav.nothing());
        }
    }

    /** A file selected in the tree opens, and the keyboard stays in the tree, so walking it keeps walking. */
    @Subscribe
    public void treeSelected(TreeView.Selected m) {
        if (m.tree().equals(nav.treeAddress()) && m.item() instanceof Path p && !nav.isFolder(p)) {
            open.send(new Open(p, false));
        }
    }

    /** Enter on a folder opens it in place. */
    @Subscribe
    public void treeActivated(TreeView.Activated m) {
        if (m.tree().equals(nav.treeAddress()) && m.item() instanceof Path p && nav.isFolder(p)) {
            nav.expand(p);
        }
    }

    /** One of a row's menu actions. */
    @Subscribe
    public void treeChosen(TreeView.Chosen m) {
        if (!m.at().equals(nav.treeAddress()) || !(m.item() instanceof Path p)) {
            return;
        }
        if (m.action().equals(Navigator.OPEN)) {
            open.send(new Open(p, true));
        } else if (m.action().equals(Navigator.SHOW)) {
            go.send(new Go(p.toString()));
        } else if (m.action().equals(Navigator.COPY_PATH)) {
            gui.clipboard().set(p.toString());
        }
    }

    @Subscribe
    public void treeRevealed(TreeView.Revealed m) {
        if (m.tree().equals(nav.treeAddress())) {
            nav.revealed(m.item(), m.reached());
        }
    }

    // ------------------------------------------------------------------ what the person did, by chord

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b);
        }
    }

    /** The text, taken now and marked saved now: what is written is what the document said when Save was asked for. */
    private void save(Buffer b) {
        save.send(new Save(b.id, b.takeForSave(), true));
        reportDirty(b);
    }

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            close(List.of(b));
        }
    }

    /** Ask the editor to close these tabs, in one message: whether any is unsaved is the editor's to know. */
    private void close(List<Buffer> buffers) {
        List<Long> ids = new ArrayList<>(buffers.size());
        for (Buffer b : buffers) {
            ids.add(b.id);
        }
        close.send(new Close(ids));
    }

    void cycle(int delta) {
        ws.cycle(delta);
    }

    void focusPath() {
        nav.focusPath();
    }

    void focusTree() {
        nav.focusTree();
    }

    /** In ({@code 1}), out ({@code -1}), or back to where it started ({@code 0}). */
    void zoom(int direction) {
        if (direction > 0) {
            gui.zoomIn();
        } else if (direction < 0) {
            gui.zoomOut();
        } else {
            gui.resetZoom();
        }
    }

    /** Tell the editor when a document went unsaved, or came back to what is on disk. */
    private void reportDirty(Buffer b) {
        if (b.dirtyChanged()) {
            edited.send(new Edited(b.id, b.dirty()));
        }
    }
}
