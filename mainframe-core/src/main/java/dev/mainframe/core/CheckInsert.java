package dev.mainframe.core;

import dev.mainframe.core.Messages.Insert;
import dev.mainframe.core.Messages.InsertChecked;
import dev.vexelray.framework.api.Job;

/**
 * What {@code --insert-path} may type into the first tab, on the offload lane: the file is looked at, and on Windows
 * its short name may be asked for ({@link Startup#insertion}). Whatever stops it is part of the answer, as the notice
 * the tab shows.
 */
@Job(topic = Messages.CHECK_INSERT, reply = Messages.INSERT_CHECKED)
public final class CheckInsert {

    public InsertChecked run(Insert i) {
        InsertPath.Outcome o = Startup.insertion(i.spec(), i.dir(), i.dialect());
        return new InsertChecked(o.typesSomething() ? o.text() : "", o.notice() == null ? "" : o.notice());
    }
}
