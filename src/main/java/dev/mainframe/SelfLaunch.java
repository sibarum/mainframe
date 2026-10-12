package dev.mainframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts another MainFrame, a process of its own, as the one running was started: the executable itself when this is
 * a native image, or the same Java and class path when it is not.
 *
 * <p>The new one is not this one's child in any way that matters: it outlives this process, and shares nothing with
 * it but the settings and workspaces on disk. The settings directory goes with it when this run was pointed at one
 * ({@code -Dmainframe.home}), so a run against a test home opens its workspaces from the same place.
 */
final class SelfLaunch {

    private SelfLaunch() {
    }

    /** MainFrame with {@code args}, or an exception saying why it could not be started. */
    static Process start(List<String> args) throws IOException {
        return new ProcessBuilder(command(args)).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    /** The command line that starts MainFrame with {@code args}. */
    static List<String> command(List<String> args) {
        List<String> cmd = new ArrayList<>();
        String home = System.getProperty("mainframe.home");
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            cmd.add(ProcessHandle.current().info().command()
                    .orElseThrow(() -> new IllegalStateException("cannot tell which executable this is")));
            if (home != null) cmd.add("-Dmainframe.home=" + home);   // a native image reads -D at run time too
        } else {
            cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("--enable-native-access=ALL-UNNAMED");
            if (home != null) cmd.add("-Dmainframe.home=" + home);
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(Main.class.getName());
        }
        cmd.addAll(args);
        return cmd;
    }
}
