package dev.mainframe;

import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * What comes back next time: the folder the navigator showed, the files that were open, and which was in front.
 *
 * <p>Kept in the framework's one {@link Settings} — the same store that remembers where the window was — under
 * keys of this application's own. Written whenever that set changes, not on every keystroke, since unsaved work
 * and the status line are not part of it; read once, after the window exists.
 *
 * <p>A remembered file that has gone is skipped, and a folder that has gone is forgotten. Neither is an error: the
 * user moved them, and an editor should not complain about it.
 */
final class Session {

    static final String FOLDER = "session.folder";
    static final String FILES = "session.files";
    static final String FRONT = "session.front";

    /** What is remembered, as a value, so "did it change" is an equals. */
    record Saved(String folder, List<String> files, String front) {

        static Saved of(Doc doc) {
            Doc.Entry f = doc.front();
            return new Saved(doc.folder() == null ? "" : doc.folder().toString(),
                    doc.files().stream().map(Path::toString).toList(),
                    f == null ? "" : f.path().toString());
        }
    }

    private final Settings settings;
    private final Model model;
    private volatile Saved last;
    /** Off until {@link #restore} has finished: before that, a write would overwrite what is being restored. */
    private volatile boolean armed;

    Session(Settings settings, Model model) {
        this.settings = settings;
        this.model = model;
    }

    /**
     * Write the session if it differs from what was last written. Runs on the model's listener, and once at the end
     * of {@link #restore}.
     *
     * <p>It reads {@link Model#doc()} rather than taking the listener's snapshot. The listener's order is among its
     * own deliveries only, so an older snapshot still being delivered could land after the direct call and be the
     * last thing written — which is how an earlier version of this lost a file from the session.
     */
    synchronized void remember() {
        if (!armed) {
            return;
        }
        Saved now = Saved.of(model.doc());
        if (Objects.equals(now, last)) {
            return;
        }
        last = now;
        settings.putString(FOLDER, now.folder())
                .putList(FILES, now.files())
                .putString(FRONT, now.front())
                .save();
    }

    /**
     * Bring back what was open, then {@code extra} — the command line's paths — on {@code io}, one after another so
     * the tabs come back in the order they were in. With no folder remembered and none given, the navigator shows
     * the working directory.
     */
    void restore(Actions actions, List<Path> extra, Executor io) {
        String folder = settings.getString(FOLDER, "");
        List<String> files = settings.getList(FILES);
        String front = settings.getString(FRONT, "");
        io.execute(() -> {
            Path shown = !folder.isEmpty() && Files.isDirectory(Path.of(folder)) ? Path.of(folder) : Path.of("");
            actions.showFolder(shown.toAbsolutePath());
            for (String f : files) {
                Path p = Path.of(f);
                if (Files.isRegularFile(p)) {
                    actions.load(p, true);
                }
            }
            for (Path p : extra) {
                if (Files.isDirectory(p)) {
                    actions.showFolder(p.toAbsolutePath());
                } else {
                    actions.load(p, true);
                }
            }
            if (extra.isEmpty() && !front.isEmpty()) {
                Path p = Path.of(front);
                if (Files.isRegularFile(p)) {
                    actions.open(p, true);
                }
            }
            armed = true;
            remember();
        });
    }
}
