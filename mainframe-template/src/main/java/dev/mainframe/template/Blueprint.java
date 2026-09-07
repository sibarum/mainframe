package dev.mainframe.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Every file a template would write, worked out before any of them is written.
 *
 * <p>The same idea as MainFrame's own plan, and for the same reason: a command
 * that can fail on its last file should fail before its first. Here it buys one
 * more thing -- the collision check is a comparison of two lists rather than a
 * question asked halfway through, so "this would land on a file you already have"
 * is something the person is told while nothing has happened yet.
 *
 * <p>Rendering happens here too, which is to say that by the time this exists,
 * every {@code ${placeholder}} has already been either substituted or refused.
 * A blueprint holds bytes.
 */
public record Blueprint(List<Entry> entries, List<String> notes) {

    /** One file: where it goes under the project folder, and what is in it. */
    public record Entry(String path, byte[] bytes) {

        /** How big it is, for a listing. */
        public int size() { return bytes.length; }
    }

    /** The paths, in the order they will be written. */
    public List<String> paths() {
        List<String> paths = new ArrayList<>(entries.size());
        for (Entry entry : entries) paths.add(entry.path());
        return List.copyOf(paths);
    }

    public Entry entry(String path) {
        for (Entry entry : entries) if (entry.path().equals(path)) return entry;
        return null;
    }

    /** What is in a file, as text -- what a test asserts against. */
    public String text(String path) {
        Entry entry = entry(path);
        return entry == null ? null : new String(entry.bytes(), StandardCharsets.UTF_8);
    }

    public int size() { return entries.size(); }

    /**
     * A write in progress, which knows how to take itself back.
     *
     * <p>The shell runs a plan one step at a time and a step that throws stops the
     * rest, which is right -- but for a project it leaves half a tree on disk, and
     * half a tree is worse than none. It looks like a directory somebody could
     * build. So each step writes through one of these, and the step that fails
     * removes what the earlier ones made on its way out.
     *
     * <p>It only ever removes what it made. A folder that was already there when
     * this started is left exactly as it was found, empty or not.
     */
    public static final class Writing {

        private final Path target;
        private final Deque<Path> created = new ArrayDeque<>();
        private final List<Path> files = new ArrayList<>();

        public Writing(Path target) { this.target = target; }

        /** The project folder and everything above it that is missing. */
        public void begin() throws IOException {
            makeDirectories(target);
        }

        /**
         * One file, atomically: written beside itself and renamed into place, so a
         * failure halfway through a big file cannot leave a truncated one that
         * looks complete.
         */
        public void write(Entry entry) throws IOException {
            Path file = target.resolve(entry.path());
            makeDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".part");
            try {
                Files.write(temp, entry.bytes());
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // Some filesystems will not move atomically. A plain move is
                    // still better than writing in place: the file appears whole.
                    Files.move(temp, file);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            files.add(file);
        }

        /**
         * Take back everything this write made, deepest first.
         *
         * <p>Best effort by construction: this runs while something has already
         * gone wrong, and a second failure here must not replace the first one,
         * which is the one that says what actually happened.
         */
        public void undo() {
            for (int i = files.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (IOException ignored) {
                    // Leaving one file behind is not worth losing the real error over.
                }
            }
            files.clear();
            while (!created.isEmpty()) {
                try {
                    Files.deleteIfExists(created.pop());
                } catch (IOException ignored) {
                    // A folder somebody has open in a window will not go; the rest still do.
                }
            }
        }

        /** What was written, for the record the command hands back. */
        public List<Path> written() { return List.copyOf(files); }

        private void makeDirectories(Path directory) throws IOException {
            if (directory == null || Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
            makeDirectories(directory.getParent());
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
            Files.createDirectory(directory);
            // Pushed after it is made, so undo pops the deepest first -- and only
            // ever pops ones this actually created.
            created.push(directory);
        }
    }
}
