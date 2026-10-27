package dev.mainframe.core;

import dev.sibarum.suite.tty.PtyPlatform;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the user can change, persisted in the framework's one settings store (the same one that remembers where the
 * window was) and told to whoever is listening the moment it changes. Setting a value saves it, so there is no
 * separate "apply".
 */
public final class AppSettings {

    /** How a tab change moves. */
    public enum TabMotion { SLIDE, FADE, NONE }

    /** A shell a new tab can start: what the menu calls it, and the command line that starts it. */
    public record ShellOption(String id, String label, String command) { }

    public static final float[] FONT_SIZES = {14f, 16f, 18f, 20f, 24f};
    public static final float DEFAULT_FONT = 18f;

    private final Settings store;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<ShellOption> shells = detectShells();
    private volatile String shellOverride;

    public AppSettings(Settings store) {
        this.store = store;
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    // -- font -------------------------------------------------------------------------------------------------

    public float fontPx() {
        return store.getFloat("font.px", DEFAULT_FONT);
    }

    public void fontPx(float px) {
        store.putFloat("font.px", px);
        changed();
    }

    // -- tab motion -------------------------------------------------------------------------------------------

    public TabMotion motion() {
        try {
            return TabMotion.valueOf(store.getString("tabs.motion", TabMotion.SLIDE.name()));
        } catch (IllegalArgumentException e) {
            return TabMotion.SLIDE;
        }
    }

    public void motion(TabMotion motion) {
        store.putString("tabs.motion", motion.name());
        changed();
    }

    // -- keys -------------------------------------------------------------------------------------------------

    /**
     * Whether the user had turned off the old <i>Remap keybindings</i> switch, which handed the shell Ctrl+T, Ctrl+W,
     * Ctrl+O, Ctrl+C, Ctrl+V and Esc. The key map took its place, so this is asked once: the answer is forgotten
     * here, and the caller unbinds those chords ((Terminals.handShellKeysBack, in -gui)) where it is true.
     */
    public boolean takeRemapOff() {
        if (!store.has("keys.remap")) {
            return false;
        }
        boolean off = !store.getBoolean("keys.remap", true);
        store.remove("keys.remap");
        store.save();
        return off;
    }

    // -- starting ---------------------------------------------------------------------------------------------

    /** The workspace a launch that names no tabs opens, or null for one plain tab. */
    public String startWorkspace() {
        String name = store.getString("start.workspace", "");
        return name.isEmpty() ? null : name;
    }

    public void startWorkspace(String name) {
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
    public Path tabDir() {
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

    /**
     * The folder chosen for plain new tabs, as it was chosen, without looking whether it is there; null when none
     * was. For the controller, which looks at nothing on disk: {@link PrepareShell} looks, and falls back to the
     * profile as {@link #tabDir} does.
     */
    public Path tabDirChosen() {
        String chosen = store.getString("tabs.dir", "");
        try {
            return chosen.isEmpty() ? null : Path.of(chosen);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Whether {@link #tabDir} is the profile because nothing else was chosen. */
    public boolean tabDirIsDefault() {
        return store.getString("tabs.dir", "").isEmpty();
    }

    /** A folder for new tabs; null goes back to the profile. */
    public void tabDir(Path dir) {
        if (dir == null) {
            store.remove("tabs.dir");
        } else {
            store.putString("tabs.dir", dir.toString());
        }
        changed();
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    // -- shell ------------------------------------------------------------------------------------------------

    /** The shells installed here, in the order the menu offers them. */
    public List<ShellOption> shells() {
        return shells;
    }

    /** The shell new tabs start: a command line given at launch wins, then the saved choice, then the first. */
    public String shellCommand() {
        String o = shellOverride;
        return o != null ? o : shell().command();
    }

    public ShellOption shell() {
        String id = store.getString("shell.id", shells.get(0).id());
        return shells.stream().filter(s -> s.id().equals(id)).findFirst().orElse(shells.get(0));
    }

    public void shell(ShellOption shell) {
        store.putString("shell.id", shell.id());
        changed();
    }

    /** A command line from the launch arguments, for this run only: not saved, and not shown as a choice. */
    public void overrideShell(String commandLine) {
        shellOverride = commandLine;
    }

    // ---------------------------------------------------------------------------------------------------------

    private void changed() {
        store.save();
        listeners.forEach(Runnable::run);
    }

    /** The shells this machine has, the platform's default first: the pty's OS module knows which those are. */
    private static List<ShellOption> detectShells() {
        return PtyPlatform.current().shells().stream()
                .map(s -> new ShellOption(s.id(), s.label(), s.commandLine()))
                .toList();
    }
}
