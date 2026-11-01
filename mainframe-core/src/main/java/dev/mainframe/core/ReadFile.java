package dev.mainframe.core;

import dev.mainframe.core.Messages.FileRead;
import dev.mainframe.core.Messages.Read;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

/**
 * Read a file and decide whether it can be opened, on the offload lane.
 *
 * <p>A job, not a method on the {@link Editor}: a read can take as long as the disk likes, a network drive included,
 * and on the editor's lane that would be a stalled component, which the watchdog ends the application for. Here it is
 * a slow answer. What can go wrong with a file is part of the answer ({@code problem}), so the editor has to look at
 * it; the job declares no {@code throws}.
 */
@Job
public final class ReadFile {

    private static final Log LOG = Log.of("mainframe.files");

    public FileRead run(Read r) {
        try {
            TextFile.Loaded loaded = TextFile.load(r.path());
            return new FileRead(r.path(), r.why(), r.focus(), loaded.text(), loaded.crlf(),
                    String.join(", ", loaded.notes()), "");
        } catch (TextFile.Unsupported e) {
            return failed(r, e.getMessage());
        } catch (NoSuchFileException e) {
            // A file of the last session that has gone since: an answer, which the reply carries, so a story rather
            // than a warning.
            LOG.info("not read, gone: {}", r.path());
            return failed(r, String.valueOf(e.getMessage()));
        } catch (IOException e) {
            // A line, not a trace: the reply says why, and the disk's trouble is not a bug in this program.
            LOG.warn("could not read {}: {}", r.path(), e.toString());
            return failed(r, String.valueOf(e.getMessage()));
        }
    }

    private static FileRead failed(Read r, String problem) {
        return new FileRead(r.path(), r.why(), r.focus(), "", false, "", problem);
    }
}
