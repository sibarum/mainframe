package dev.mainframe.core;

import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.mainframe.core.Messages.ShellChoice;
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
 * options, the settings ({@link PrefsStore}) and this platform's shells. Which folder and shell a tab gets when the
 * launch names none is the frame's to decide from these (docs/settings.md); M4 adds workspaces.
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
        return new Startup(Folders.of(c.sources(), here), line, PrefsStore.read(settings), shells,
                Path.of(System.getProperty("user.home")));
    }
}
