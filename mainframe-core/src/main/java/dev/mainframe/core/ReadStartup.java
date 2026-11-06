package dev.mainframe.core;

import dev.mainframe.core.Messages.Startup;
import dev.mainframe.core.Messages.StartupWanted;
import dev.sibarum.suite.tty.PtyPlatform;
import dev.vexelray.framework.api.Job;

import java.nio.file.Path;
import java.util.List;

/**
 * What the launch asked for: the command line every tab runs, and where. A job, because it reads the process's own
 * command line and working directory, which a step may not.
 *
 * <p>A tab per folder {@code --dir} and {@code --dirs} name ({@link Folders}), or one where MainFrame was started when
 * they name none, each running the command line given after MainFrame's options, or else this platform's first
 * shell. M3 adds the shell and the folder chosen in Settings; M4 workspaces.
 *
 * <p>The command line reaches this job through a static, {@link Command#launched()}, set once by {@code main}: the
 * framework's {@code Launch} cannot carry MainFrame's grammar, and a value parsed before the framework runs has no
 * other way into the wiring (docs/framework-notes.md, FN-1). A job may read it; a step may not.
 */
@Job
public final class ReadStartup {

    public Startup run(StartupWanted w) {
        Command c = Command.launched();
        String line = c.shellLine() != null ? c.shellLine() : PtyPlatform.current().shells().getFirst().commandLine();
        Path here = Path.of("").toAbsolutePath();
        List<Path> dirs = Folders.of(c.sources(), here);
        return new Startup(dirs.isEmpty() ? List.of(here) : dirs, line);
    }
}
