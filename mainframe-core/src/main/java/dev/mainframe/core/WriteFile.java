package dev.mainframe.core;

import dev.mainframe.core.Messages.Write;
import dev.mainframe.core.Messages.Written;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * Write a file with its own line endings, on the offload lane. A write that does not land is an answer
 * ({@code problem}), and the {@link Editor} cancels whatever was waiting on it.
 */
@Job
public final class WriteFile {

    private static final Log LOG = Log.of("mainframe.files");

    public Written run(Write w) {
        try {
            TextFile.write(w.path(), TextFile.encode(w.text(), w.crlf()));
            return new Written(w.id(), w.path(), "");
        } catch (IOException e) {
            // A line, not a trace: the reply says why, and the disk's trouble is not a bug in this program.
            LOG.warn("could not save {}: {}", w.path(), e.toString());
            return new Written(w.id(), w.path(), String.valueOf(e.getMessage()));
        }
    }
}
