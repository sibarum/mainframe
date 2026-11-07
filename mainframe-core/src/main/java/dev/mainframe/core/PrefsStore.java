package dev.mainframe.core;

import dev.mainframe.core.Messages.Preferences;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The settings as the store keeps them, under 0.1's keys so a 0.1 user's file carries over (docs/settings.md, ruled):
 * read by {@link ReadStartup} and written by {@link SaveSettings}, the two jobs that touch the file, and nothing else.
 *
 * <p>Nothing read here stops a launch: a value that is missing or malformed is the first run's.
 */
final class PrefsStore {

    static final String FONT = "font.px";
    static final String MOTION = "tabs.motion";
    static final String SHELL = "shell.id";
    static final String DIR = "tabs.dir";

    private PrefsStore() {
    }

    static Preferences read(Settings s) {
        // 0.1 wrote the size as a float ("18.0"); the store reads either.
        int px = Math.round(s.getFloat(FONT, Preferences.DEFAULT.fontPx()));
        Preferences p = new Preferences(Preferences.SIZES.contains(px) ? px : Preferences.DEFAULT.fontPx(),
                TabMotion.named(s.getString(MOTION, "")), s.getString(SHELL, ""), dir(s.getString(DIR, "")));
        return p;
    }

    /** Every setting into the store, and saved. A folder of none (home) is the key's absence, as 0.1 kept it. */
    static void write(Settings s, Preferences p) {
        s.putInt(FONT, p.fontPx());
        s.putString(MOTION, p.motion().name());
        if (p.shell().isBlank()) {
            s.remove(SHELL);
        } else {
            s.putString(SHELL, p.shell());
        }
        if (p.dir().isPresent()) {
            s.putString(DIR, p.dir().get().toString());
        } else {
            s.remove(DIR);
        }
        s.save();
    }

    private static Optional<Path> dir(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(text).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
