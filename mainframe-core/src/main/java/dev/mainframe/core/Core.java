package dev.mainframe.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module is made of: nothing yet. The template's editor and its jobs are gone (docs/rebuild.md, M0), and
 * MainFrame's {@code Frame} and its jobs come in M0's second step.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code MainFrameApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new component or job in this module goes on
 * this list, or nothing builds it.
 */
@Configuration
public final class Core {
}
