package dev.mainframe;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Tabs;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.ArrayList;
import java.util.List;

/**
 * The window's tabs: one {@link TerminalView} per tab, the bar that selects between them, and the shortcuts that
 * open, close and cycle.
 *
 * <p>Each tab owns its own shell. A tab whose shell exits closes itself, and closing the last tab closes the
 * window. Only the selected tab draws (see {@link TerminalView#active}); the others keep reading their shells.
 *
 * <p>All structure changes go through {@link Tabs}, which tells us about them in {@code onRemove}, so the list of
 * views here is edited in exactly one place whoever asked for the removal: the header menu's Close, a shortcut, or
 * a shell that exited.
 */
final class TerminalTabs implements TerminalView.Hooks {

    private static final String PLUS = " + ";
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;

    private final Gui gui;
    private final AppSettings settings;
    private final Runnable openSettings;
    private final KronoGui krono;
    private final Runnable onEmpty;
    private final Tabs tabs;
    private final List<TerminalView> views = new ArrayList<>();
    private Node plus;
    private Node gear;
    private Rename renaming;

    /** The header being edited: the field standing in for it, and what to undo when editing ends. */
    private record Rename(TerminalView view, TextField field, Node header, Subscription blur) { }

    TerminalTabs(Gui gui, KronoGui krono, AppSettings settings, Runnable openSettings, Runnable onEmpty) {
        this.gui = gui;
        this.settings = settings;
        this.openSettings = openSettings;
        this.krono = krono;
        this.onEmpty = onEmpty;
        this.tabs = new Tabs(gui).onRemove(this::removed).onSelect(this::selected)
                .onContextMenu((index, menu) -> {
                    TerminalView v = at(index);
                    if (v != null) menu.item("Rename", () -> rename(v));
                });
        applyMotion();
        settings.onChange(this::settingsChanged);
        newTab();
    }

    Node node() {
        return tabs.node();
    }

    /** The terminal on show, or null when there is none. */
    synchronized TerminalView current() {
        int i = tabs.selected();
        return i >= 0 && i < views.size() ? views.get(i) : null;
    }

    void focus() {
        TerminalView v = current();
        if (v != null) v.focus();
    }

    /** On the GUI thread, once a frame: draw, retitle, and reap shells that have exited. */
    void tick() {
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        for (TerminalView v : snapshot) {
            v.tick();
            String title = v.takeTitle();
            if (title != null) {
                v.shellTitle(shorten(title));
                retitle(v);
            }
            if (v.exited()) closeTab(v);
        }
    }

    void close() {
        List<TerminalView> all;
        synchronized (this) {
            all = List.copyOf(views);
            views.clear();
        }
        all.forEach(TerminalView::close);
    }

    // -- settings --------------------------------------------------------------------------------------------

    private void settingsChanged() {
        applyMotion();
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        snapshot.forEach(v -> v.fontPx(settings.fontPx()));
    }

    /**
     * Slide: the arriving tab travels a short way into place while the old one dissolves. Fade: the dissolve alone.
     * Linear, because a fade has no place to arrive at (the slide eases itself), and short, because a terminal is
     * something you type into the moment it appears.
     */
    private void applyMotion() {
        Tabs.TabTransition t = switch (settings.motion()) {
            case SLIDE -> Tabs.slide((progress, done) -> krono.ramp(Dur.ms(160), Ease.LINEAR, progress, done));
            case FADE -> Tabs.crossfade((progress, done) -> krono.ramp(Dur.ms(160), Ease.LINEAR, progress, done));
            case NONE -> Tabs.TabTransition.NONE;
        };
        tabs.transition(t);
    }

    // -- Hooks ------------------------------------------------------------------------------------------------

    @Override
    public void newTab() {
        // The lock is never held while calling into Tabs: its header menu reaches us from the other side, through
        // onRemove, while it holds its own.
        TerminalView cur = current();
        TerminalView v = new TerminalView(gui, settings.shellCommand(), cur == null ? 100 : cur.cols(), cur == null ? 30 : cur.rows(),
                settings.fontPx(), settings::remapKeys, this);
        int index;
        synchronized (this) {
            views.add(v);
            index = views.size() - 1;
        }
        tabs.add(v.displayTitle(), v.node());
        long[] lastClick = {0};
        gui.onClick(tabs.header(index), () -> {   // a second click within the interval is a double-click
            long now = System.nanoTime();
            if (now - lastClick[0] < DOUBLE_CLICK_NANOS) rename(v);
            lastClick[0] = now;
        });
        tabs.select(index);
        movePlus();
        v.focus();
    }

    @Override
    public void closeTab(TerminalView view) {
        int i;
        synchronized (this) {
            i = views.indexOf(view);
        }
        if (i >= 0) tabs.remove(i);
    }

    @Override
    public void cycle(int by) {
        int n;
        synchronized (this) {
            n = views.size();
        }
        if (n > 1) tabs.select(Math.floorMod(tabs.selected() + by, n));
    }

    // -- Tabs callbacks ---------------------------------------------------------------------------------------

    /** Runs inline inside {@link Tabs#remove}, with the bar already short one tab. */
    private void removed(int index) {
        finishRename(false);
        TerminalView gone;
        boolean empty;
        synchronized (this) {
            gone = index < views.size() ? views.remove(index) : null;
            empty = views.isEmpty();
        }
        if (gone != null) gone.close();
        if (empty) onEmpty.run();
    }

    private void selected(int index) {
        List<TerminalView> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(views);
        }
        for (int i = 0; i < snapshot.size(); i++) snapshot.get(i).active(i == index);
        if (index >= 0 && index < snapshot.size()) snapshot.get(index).focus();
    }

    private void retitle(TerminalView v) {
        int i;
        synchronized (this) {
            i = views.indexOf(v);
        }
        if (i >= 0) tabs.title(i, v.displayTitle());
    }

    private synchronized TerminalView at(int index) {
        return index >= 0 && index < views.size() ? views.get(index) : null;
    }

    // -- renaming ---------------------------------------------------------------------------------------------

    /**
     * Edit a tab's name in place: a text field takes the header's seat in the bar, and the header is hidden (not
     * removed, which would release its handlers) until editing ends. Enter or clicking away keeps the new name,
     * Escape keeps the old one, and a blank name gives the header back to the shell's own title.
     */
    @Override
    public void rename(TerminalView view) {
        finishRename(true);
        int i;
        synchronized (this) {
            i = views.indexOf(view);
        }
        if (i < 0) return;
        Node header = tabs.header(i);
        TextField field = new TextField(gui, view.displayTitle());
        field.node().width(Length.rem(12)).height(Length.FILL);
        Subscription blur = gui.bus().subscribe(gui.focusEvents(), e -> {
            if (!e.gained() && e.nodeId() == field.node().id()) finishRename(true);
        });
        synchronized (this) {
            renaming = new Rename(view, field, header, blur);
        }
        field.onSubmit(text -> finishRename(true));
        gui.claimUi(field.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> finishRename(false));
        header.visible(false);
        tabs.bar().insert(field.node(), i);
        gui.focus(field.node());
        field.select(0, field.text().length());
    }

    private void finishRename(boolean keep) {
        Rename r;
        synchronized (this) {
            r = renaming;
            renaming = null;   // first, so the focus change below is not mistaken for another way out
        }
        if (r == null) return;
        String text = r.field().text();
        r.blur().close();
        r.field().node().remove();
        r.field().close();
        r.header().visible(true);
        if (keep) r.view().customTitle(text);
        retitle(r.view());
        r.view().focus();
    }

    /** A shell reports its title as a path to its executable; a tab wants the name. */
    private static String shorten(String title) {
        String t = title.strip();
        int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        if (slash >= 0 && slash < t.length() - 1) t = t.substring(slash + 1);
        if (t.toLowerCase().endsWith(".exe")) t = t.substring(0, t.length() - 4);
        return t.isEmpty() ? "Shell" : t;
    }

    /**
     * The new-tab button lives in the bar after the headers. Headers are appended, so each new tab lands behind
     * the button; removing the button releases its handlers, so it is rebuilt rather than moved.
     */
    private void movePlus() {
        if (plus != null) plus.remove();
        plus = gui.text(PLUS).height(Length.FILL);
        gui.onClick(plus, this::newTab);
        tabs.bar().append(plus);
        if (gear != null) gear.remove();
        gear = gui.text(" Settings ").height(Length.FILL);
        gui.onClick(gear, openSettings);
        tabs.bar().append(gear);
    }
}
