package dev.mainframe.core;

import dev.mainframe.core.Messages.Create;
import dev.mainframe.core.Messages.Created;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;

/** Make an empty file, which the path bar named into being. */
@Job
public final class CreateFile {

    private static final Log LOG = Log.of("mainframe.files");

    /** A new file gets this platform's line endings: asked here, so the editor never reads the platform. */
    private static final boolean CRLF = System.lineSeparator().equals("\r\n");

    public Created run(Create c) {
        try {
            Files.createFile(c.path());
            return new Created(c.path(), CRLF, "");
        } catch (FileAlreadyExistsException e) {
            // Somebody named a file that is there: an answer, which the reply carries, so a story rather than a warning.
            LOG.info("not created, already there: {}", c.path());
            return new Created(c.path(), CRLF, String.valueOf(e.getMessage()));
        } catch (IOException e) {
            // A line, not a trace: the reply says why, and the disk's trouble is not a bug in this program.
            LOG.warn("could not create {}: {}", c.path(), e.toString());
            return new Created(c.path(), CRLF, String.valueOf(e.getMessage()));
        }
    }
}
