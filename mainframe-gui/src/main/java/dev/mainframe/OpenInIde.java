package dev.mainframe;

import dev.mainframe.core.Messages;
import dev.mainframe.core.Messages.IdeOpened;
import dev.vexelray.framework.api.Job;

import java.io.IOException;

/**
 * A file opened in VexelRay IDE, a process of its own, on the offload lane ({@link Vex}). Here rather than in
 * {@code -core} because finding the editor is the suite's install record, which is the framework shell's to read.
 * Whatever stops it is answered as a dialog would say it, with the file's path, so it can still be edited some other
 * way.
 */
@Job(topic = Messages.OPEN_IN_IDE, reply = Messages.IDE_OPENED)
public final class OpenInIde {

    public IdeOpened run(Messages.OpenInIde m) {
        try {
            if (!Vex.edit(m.file())) {
                return new IdeOpened(m.file(), "VexelRay IDE is not installed", "MainFrame opens environment scripts "
                        + "in VexelRay IDE, which is not installed. The script is " + m.file() + ".");
            }
            return new IdeOpened(m.file(), "", "");
        } catch (IOException | RuntimeException e) {
            return new IdeOpened(m.file(), "Could not start VexelRay IDE",
                    e.getMessage() + "\nThe script is " + m.file() + ".");
        }
    }
}
