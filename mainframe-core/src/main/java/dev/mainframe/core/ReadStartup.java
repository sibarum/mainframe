package dev.mainframe.core;

import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.mainframe.core.Messages.ShellChoice;
import dev.mainframe.core.Messages.Preferences;
import dev.mainframe.core.Messages.TabSpec;
import dev.vexelray.gui.core.app.Settings;
import dev.sibarum.suite.tty.PtyPlatform;
import dev.vexelray.framework.api.Job;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the launch asked for: the command line every tab runs, and where. A job, because it reads the process's own
 * command line and working directory, which a step may not.
 *
 * <p>The folders {@code --dir} and {@code --dirs} name ({@link Folders}), the command line given after MainFrame's
 * options, the settings ({@link PrefsStore}), the workspaces and this platform's shells. Which folder and shell a tab gets when the
 * launch names none is the frame's to decide from these (docs/settings.md, docs/workspaces.md).
 *
 * <p>The command line reaches this job through a static, {@link Command#launched()}, set once by {@code main}: the
 * framework's {@code Launch} cannot carry MainFrame's grammar, and a value parsed before the framework runs has no
 * other way into the wiring (docs/framework-notes.md, FN-1). A job may read it; a step may not.
 */
@Job
public final class ReadStartup {

    private final Settings settings;

    public ReadStartup(Settings settings) {
        this.settings = settings;
    }

    public Startup run(StartupWanted w) {
        Command c = Command.launched();
        List<ShellChoice> shells = new ArrayList<>();
        for (PtyPlatform.Shell s : PtyPlatform.current().shells()) {
            shells.add(new ShellChoice(s.id(), s.label(), s.commandLine()));
        }
        String line = c.shellLine() == null ? "" : c.shellLine();
        Path here = Path.of("").toAbsolutePath();
        Preferences prefs = PrefsStore.read(settings);
        WorkspaceStore workspaces = WorkspaceStore.of(settings);
        // A launch that names no folder and no workspace opens the one Settings says, if any (0.1's README).
        List<Command.Source> sources = c.sources().isEmpty() && !prefs.startWorkspace().isBlank()
                ? List.of(new Command.Source(Command.Kind.WORKSPACE, prefs.startWorkspace()))
                : c.sources();
        Folders.Opened opened = Folders.of(sources, here, workspaces);
        Path home = Path.of(System.getProperty("user.home"));
        InsertPath.Outcome insert = c.insertPath() == null ? InsertPath.Outcome.typed("", "")
                : insertion(c.insertPath(), opened.tabs(), line, prefs, shells, home);
        return new Startup(opened.tabs(), line, prefs, shells, home, opened.workspace(), workspaces.names(),
                insert.typesSomething() ? insert.text() : "", insert.notice() == null ? "" : insert.notice());
    }

    /**
     * What {@code --insert-path} types on the first tab: in the folder that tab starts in, and quoted for the shell it
     * runs, which is the chosen one unless a command line was given, which MainFrame cannot quote for. The frame makes
     * the same choices for the tab; the file has to be looked at, which a step may not, so it is checked here.
     */
    private static InsertPath.Outcome insertion(String spec, List<TabSpec> tabs, String line, Preferences prefs,
                                                List<ShellChoice> shells, Path home) {
        Path dir = tabs.isEmpty() ? prefs.dir().orElse(home) : tabs.getFirst().dir();
        String shell = shells.stream().map(ShellChoice::id).filter(prefs.shell()::equals).findFirst()
                .orElse(shells.isEmpty() ? "" : shells.getFirst().id());
        InsertPath.Dialect dialect = line.isBlank() ? InsertPath.Dialect.of(shell).orElse(null) : null;
        return InsertPath.check(spec, dir.toAbsolutePath().normalize(), dialect);
    }
}
