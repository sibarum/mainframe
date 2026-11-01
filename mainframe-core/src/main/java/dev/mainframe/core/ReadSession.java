package dev.mainframe.core;

import dev.mainframe.core.Messages.Recall;
import dev.mainframe.core.Messages.Recalled;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read back what the last session remembered, and what the command line named, on the offload lane.
 *
 * <p>A job because the settings store is a file, and the {@link Editor}'s steps make no effects. It is also where a
 * remembered string becomes a path: one that is not a path any more is left out, because the person moved it and an
 * editor should not complain about it, and every path is made absolute here, so the editor never asks where the
 * application started. That is in the reply too ({@code started}), for a session that showed no folder.
 */
@Job
public final class ReadSession {

    static final String FOLDER = "session.folder";
    static final String FILES = "session.files";
    static final String FRONT = "session.front";

    private final Settings settings;
    private final Launch launch;

    public ReadSession(Settings settings, Launch launch) {
        this.settings = settings;
        this.launch = launch;
    }

    public Recalled run(Recall r) {
        return new Recalled(path(settings.getString(FOLDER, "")), paths(settings.getList(FILES)),
                path(settings.getString(FRONT, "")), paths(launch.rest()), Path.of("").toAbsolutePath());
    }

    private static List<Path> paths(List<String> texts) {
        List<Path> out = new ArrayList<>();
        for (String t : texts) {
            path(t).ifPresent(out::add);
        }
        return out;
    }

    /** A path from a remembered or typed string, absolute and normalised; none for blank or one that is not a path. */
    static Optional<Path> path(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(text).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
