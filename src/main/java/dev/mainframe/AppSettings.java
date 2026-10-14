package dev.mainframe;

import dev.vexelray.gui.core.app.Settings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the user can change, persisted in the framework's one settings store (the same one that remembers where the
 * window was) and told to whoever is listening the moment it changes. Setting a value saves it, so there is no
 * separate "apply".
 */
final class AppSettings {

    /** How a tab change moves. */
    enum TabMotion { SLIDE, FADE, NONE }

    /** A shell a new tab can start: what the menu calls it, and the command line that starts it. */
    record ShellOption(String id, String label, String command) { }

    static final float[] FONT_SIZES = {14f, 16f, 18f, 20f, 24f};
    static final float DEFAULT_FONT = 18f;

    private final Settings store;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<ShellOption> shells = detectShells();
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

    TabMotion motion() {
        try {
            return TabMotion.valueOf(store.getString("tabs.motion", TabMotion.SLIDE.name()));
        } catch (IllegalArgumentException e) {
            return TabMotion.SLIDE;
        }
    }

    void motion(TabMotion motion) {
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

    // -- starting ---------------------------------------------------------------------------------------------

    /** The workspace a launch that names no tabs opens, or null for one plain tab. */
    String startWorkspace() {
        String name = store.getString("start.workspace", "");
        return name.isEmpty() ? null : name;
    }

    void startWorkspace(String name) {
        if (name == null || name.isEmpty()) {
            store.remove("start.workspace");
        } else {
            store.putString("start.workspace", name);
        }
        changed();
    }

    /**
     * The folder a plain new tab's shell starts in: the one chosen, while it is there, or else the user's profile.
     * Not MainFrame's own working directory, which for an installed copy is wherever its shortcut said.
     */
    Path tabDir() {
        String chosen = store.getString("tabs.dir", "");
        if (!chosen.isEmpty()) {
            try {
                Path p = Path.of(chosen);
                if (Files.isDirectory(p)) return p;
            } catch (InvalidPathException e) {
                // fall through to the profile
            }
        }
        return home();
    }

    /** Whether {@link #tabDir} is the profile because nothing else was chosen. */
    boolean tabDirIsDefault() {
        return store.getString("tabs.dir", "").isEmpty();
    }

    /** A folder for new tabs; null goes back to the profile. */
    void tabDir(Path dir) {
        if (dir == null) {
            store.remove("tabs.dir");
        } else {
            store.putString("tabs.dir", dir.toString());
        }
        changed();
    }

    static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    // -- shell ------------------------------------------------------------------------------------------------

    /** The shells installed here, in the order the menu offers them. */
    List<ShellOption> shells() {
        return shells;
    }

    /** The shell new tabs start: a command line given at launch wins, then the saved choice, then the first. */
    String shellCommand() {
        String o = shellOverride;
        return o != null ? o : shell().command();
    }

    ShellOption shell() {
        String id = store.getString("shell.id", shells.get(0).id());
        return shells.stream().filter(s -> s.id().equals(id)).findFirst().orElse(shells.get(0));
    }

    void shell(ShellOption shell) {
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
    private static List<ShellOption> detectShells() {
        List<ShellOption> found = new ArrayList<>();
        found.add(new ShellOption("powershell", "Windows PowerShell", "powershell.exe -NoLogo"));
        if (onPath("pwsh.exe")) found.add(new ShellOption("pwsh", "PowerShell 7", "pwsh.exe -NoLogo"));
        found.add(new ShellOption("cmd", "Command Prompt", "cmd.exe"));
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
