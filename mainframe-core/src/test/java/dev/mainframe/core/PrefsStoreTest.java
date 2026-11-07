package dev.mainframe.core;

import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.PrefsSaved;
import dev.mainframe.core.Messages.SavePrefs;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings on a store in a temporary folder: written, read back, and 0.1's file read as it was. */
class PrefsStoreTest {

    @TempDir
    Path home;

    @Test
    void whatIsSavedIsReadBack() {
        Path file = home.resolve("settings.properties");
        Preferences p = new Preferences(24, TabMotion.FADE, "cmd", Optional.of(home));
        assertEquals(new PrefsSaved(3, ""), new SaveSettings(Settings.at(file)).run(new SavePrefs(3, p)));
        assertEquals(p, PrefsStore.read(Settings.at(file)));
    }

    @Test
    void zeroOnesFileIsReadAsItWas() throws Exception {
        Path file = home.resolve("settings.properties");
        Files.writeString(file, "font.px=16.0\ntabs.motion=NONE\nshell.id=cmd\ntabs.dir="
                + home.toString().replace("\\", "\\\\") + "\nwindow.main.x=40\n");
        assertEquals(new Preferences(16, TabMotion.NONE, "cmd", Optional.of(home)), PrefsStore.read(Settings.at(file)));
    }

    @Test
    void homeIsTheFolderKeysAbsenceAndNonsenseIsTheFirstRuns() throws Exception {
        Path file = home.resolve("settings.properties");
        Files.writeString(file, "font.px=17\ntabs.motion=sideways\ntabs.dir=C\\:\\\\somewhere\n");
        Settings s = Settings.at(file);
        assertEquals(new Preferences(18, TabMotion.SLIDE, "", Optional.of(Path.of("C:\\somewhere"))),
                PrefsStore.read(s));
        PrefsStore.write(s, Preferences.DEFAULT);
        assertFalse(Settings.at(file).has(PrefsStore.DIR), "no folder chosen is no key, as 0.1 kept it");
        assertTrue(Settings.at(file).has(PrefsStore.FONT));
    }
}
