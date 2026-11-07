package dev.mainframe.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module is made of: the {@link Frame}, the job that reads what the launch asked for, and the {@link Shell}
 * each tab runs, which is both a source and a destination.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code MainFrameApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new part in this module goes on this list,
 * or nothing builds it.
 */
@Configuration(components = Frame.class, jobs = {ReadStartup.class, SaveSettings.class}, sources = Shell.class, destinations = Shell.class)
public final class Core {
}
