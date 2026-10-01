package dev.mainframe;

import dev.vexelray.gui.core.app.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the user can change, persisted in vexelray's per-application settings file and told to whoever is
 * listening the moment it changes. Setting a value saves it, so there is no separate "apply".
 */
final class AppSettings {

    /** How a tab change moves. */
    enum Motion { SLIDE, FADE, NONE }

    /** A shell a new tab can start: what the menu calls it, and the command line that starts it. */
    record Shell(String id, String label, String command) { }

    static final float[] FONT_SIZES = {14f, 16f, 18f, 20f, 24f};
    static final float DEFAULT_FONT = 18f;

    private final Settings store;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Shell> shells = detectShells();
    private volatile String shellOverride;

    AppSettings(Settings store) {
        this.store = store;
    }

    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    // -- font -------------------------------------------------------------------------------------------------

    float fontPx() {
        return store.getFloat("font.px", DEFAULT_FONT);
    }

    void fontPx(float px) {
        store.putFloat("font.px", px);
        changed();
    }

    // -- tab motion -------------------------------------------------------------------------------------------

    Motion motion() {
        try {
            return Motion.valueOf(store.getString("tabs.motion", Motion.SLIDE.name()));
        } catch (IllegalArgumentException e) {
            return Motion.SLIDE;
        }
    }

    void motion(Motion motion) {
        store.putString("tabs.motion", motion.name());
        changed();
    }

    // -- keys -------------------------------------------------------------------------------------------------

    /**
     * Whether Ctrl+C and Ctrl+V are copy and paste, with Esc standing in for the interrupt Ctrl+C used to send.
     * On by default; off hands every key to the shell untouched.
     */
    boolean remapKeys() {
        return store.getBoolean("keys.remap", true);
    }

    void remapKeys(boolean on) {
        store.putBoolean("keys.remap", on);
        changed();
    }

    // -- shell ------------------------------------------------------------------------------------------------

    /** The shells installed here, in the order the menu offers them. */
    List<Shell> shells() {
        return shells;
    }

    /** The shell new tabs start: a command line given at launch wins, then the saved choice, then the first. */
    String shellCommand() {
        String o = shellOverride;
        return o != null ? o : shell().command();
    }

    Shell shell() {
        String id = store.getString("shell.id", shells.get(0).id());
        return shells.stream().filter(s -> s.id().equals(id)).findFirst().orElse(shells.get(0));
    }

    void shell(Shell shell) {
        store.putString("shell.id", shell.id());
        changed();
    }

    /** A command line from the launch arguments, for this run only: not saved, and not shown as a choice. */
    void overrideShell(String commandLine) {
        shellOverride = commandLine;
    }

    // ---------------------------------------------------------------------------------------------------------

    private void changed() {
        store.save();
        listeners.forEach(Runnable::run);
    }

    /** Windows PowerShell is always there; the others are offered only when they can be found. */
    private static List<Shell> detectShells() {
        List<Shell> found = new ArrayList<>();
        found.add(new Shell("powershell", "Windows PowerShell", "powershell.exe -NoLogo"));
        if (onPath("pwsh.exe")) found.add(new Shell("pwsh", "PowerShell 7", "pwsh.exe -NoLogo"));
        found.add(new Shell("cmd", "Command Prompt", "cmd.exe"));
        return List.copyOf(found);
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && new File(dir, exe).isFile()) return true;
        }
        return false;
    }
}
