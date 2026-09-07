package dev.mainframe.template.shell;

import java.nio.file.Path;

import dev.mainframe.Session;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.template.Catalogue;

/**
 * The templates this session can see: the ones that ship, plus anybody's own.
 *
 * <p>{@code ~/.mainframe/templates/<id>/} is the second half, and it is what stops
 * this being a feature only its author can extend. A template there is a folder
 * with a {@code template.manifest} and a {@code files/} beside it -- the same two
 * things a bundled one has, read by the same reader -- so writing one is copying
 * one out and editing it, and there is nothing to register.
 *
 * <p>Under {@code SETTINGS} rather than {@code STATE} in spirit, though it predates
 * nothing and could move: a template somebody wrote is a decision, and losing it
 * loses work. Kept at a plain path rather than through {@code Store} because a
 * template is a directory of files, and {@code Store} keeps documents under names.
 */
final class Catalogues {

    private Catalogues() {}

    /** Where a person's own templates go. */
    static Path directory() { return SafeFs.stateDir().resolve("templates"); }

    static Catalogue forSession(Session session) {
        // Asked for through the session so a test pointing MAINFRAME_HOME somewhere
        // else gets that somewhere else, which is the whole reason stateDir is not
        // read from user.home directly anywhere in MainFrame.
        return Catalogue.including(session.stateDir().resolve("templates"));
    }
}
