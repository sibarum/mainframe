package dev.mainframe;

import dev.mainframe.core.Messages.Redo;
import dev.mainframe.core.Messages.Undo;
import dev.vexelray.gui.core.edit.Change;
import sibarum.atchung.Sender;

/**
 * An entry of the {@code Workspaces} component's journal, in the Workspaces window's history: applied, it asks the
 * component to take the entry back ({@link Undo}) or do it again ({@link Redo}), and returns the change that asks the
 * other. Vexplore's, and the history's ruled pattern: the change holds only the entry's number, so what the entry did,
 * and whether it can still be taken back, stays the component's.
 *
 * @param entry the journal entry
 * @param undo  whether applying it takes the entry back, rather than doing it again
 */
record Journaled(long entry, boolean undo, Sender<Undo> undos, Sender<Redo> redos) implements Change {

    @Override
    public Change apply() {
        if (undo) {
            undos.send(new Undo(entry));
        } else {
            redos.send(new Redo(entry));
        }
        return new Journaled(entry, !undo, undos, redos);
    }
}
