package dev.mainframe.gui.console;

import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.nfd.Nfd;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * The operating system's own file chooser, which is what {@link Panel} promises when it claims {@code pick}.
 *
 * <h2>Why a native dialog rather than a drawn one</h2>
 * MainFrame can draw a chooser, and does — a folder listing made of text and things to click, which every
 * editor gets whether or not it has ever heard of {@code pick}. This is the other branch of that offer, and it
 * is worth taking where it exists: the OS dialog knows about the sidebar somebody has arranged, the network
 * places, the drives that were plugged in a minute ago and the folder they were last in. None of that is
 * reachable from a screen of cells, and none of it is worth reimplementing badly.
 *
 * <h2>Asking before claiming</h2>
 * The binding ships a library for Windows and macOS and nothing else, so on any other machine loading it fails
 * — as a linkage error out of a static initialiser, which is why {@link #here} catches {@link Throwable} rather
 * than an exception. Somewhere without one, {@link Console} hands {@link Panel} no chooser at all, the editor
 * does not claim {@code pick}, and MainFrame draws its own. That is rule five doing exactly what it is for: an
 * editor must never claim what it cannot do, and the fallback for not claiming is already written.
 *
 * <h2>Which thread, and what it does to the frame</h2>
 * NFD is modal and wants the thread that owns the window, which is the frame loop — so this is called from
 * there, and the frame loop stops until somebody answers the dialog. That is what a modal dialog <em>is</em>;
 * the OS window on top is drawing itself, and the console underneath is a still picture until it goes away.
 */
final class NativeChooser implements Panel.Chooser {

    /**
     * Whether the library loaded, asked once and remembered.
     *
     * <p>Eager, on the class being touched at all, so the answer is known before the first {@code hello} an
     * editor sends rather than at the first form somebody fills in — a capability worked out halfway through a
     * session is a capability that was a guess on every screen before it.
     */
    private static final boolean HERE = probe();

    /** Where a modal dialog parents: the console's window while there is one, and nothing before that. */
    private final LongSupplier owner;

    NativeChooser(LongSupplier owner) {
        this.owner = owner;
    }

    /** Whether this machine has a native file dialog to offer. See the note above about the catch. */
    static boolean here() {
        return HERE;
    }

    private static boolean probe() {
        try {
            Nfd.ensureInit();
            return true;
        } catch (Throwable t) {
            // A platform with no bundled library, which is not a fault and not worth a word on the way past:
            // the drawn chooser is a working answer rather than a degraded one.
            return false;
        }
    }

    /**
     * Put the dialog up and wait, which is what a modal dialog means everywhere.
     *
     * @return the path chosen, or null when they backed out — which the editor reads as "leave the field alone"
     */
    @Override
    public Path choose(String pick, String answer) {
        long parent = owner.getAsLong();
        Path folder = folderOf(answer);
        return switch (pick) {
            case "folder" -> FileDialog.pickFolder(parent, folder).orElse(null);
            // No filters on either of the file dialogs. MainFrame says a field holds a path and says nothing
            // about what is meant to be in the file, so a filter here would be this editor inventing a rule --
            // and hiding the file somebody was looking for is the expensive way to get that wrong.
            case "save" -> FileDialog.save(parent, null, folder, nameOf(answer)).orElse(null);
            default -> FileDialog.open(parent, null, folder).orElse(null);
        };
    }

    /**
     * Where to open, given whatever is in the field.
     *
     * <p>The folder a path is in rather than the path, because that is the folder a dialog can show. A path
     * that is not there is walked up until something is, so a half-typed path opens at the deepest folder that
     * does exist and an answer left over from another machine opens somewhere real rather than nowhere. Null
     * when nothing survives that, which NFD reads as "wherever you were last", and that is the right default.
     */
    private static Path folderOf(String answer) {
        Path path = pathOf(answer);
        Path at = path != null && Files.isDirectory(path) ? path : path == null ? null : path.getParent();
        while (at != null && !Files.isDirectory(at)) {
            at = at.getParent();
        }
        return at;
    }

    /** The name to offer in a save dialog: what the field already names, when it names a file. */
    private static String nameOf(String answer) {
        Path path = pathOf(answer);
        if (path == null || Files.isDirectory(path)) {
            return null;
        }
        Path name = path.getFileName();
        return name == null ? null : name.toString();
    }

    private static Path pathOf(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        try {
            return Path.of(answer.trim());
        } catch (InvalidPathException e) {
            // Somebody is halfway through typing something this machine could not hold. Not an error here --
            // the field is still theirs to fix -- just nowhere to open.
            return null;
        }
    }
}
