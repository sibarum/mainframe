package dev.mainframe.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module is made of: the {@link Frame} and the jobs that touch the disk and start processes.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code MainFrameApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new component or job in this module goes on
 * this list, or nothing builds it.
 *
 * <p>The services both modules read ({@link AppSettings}, and {@link WorkspaceStore}, the jobs' one field) are built by
 * {@code -gui}'s {@code Recipes}: a recipe here returning one of this module's own classes would have to return an
 * interface instead, and to {@code -gui} they are somebody else's classes, which may be returned as they are.
 */
@Configuration(components = Frame.class,
        jobs = {ResolveTabs.class, PrepareShell.class, CheckInsert.class, LaunchWindow.class, MakeScript.class,
                ListWorkspaces.class, LoadWorkspace.class, SaveWorkspace.class, RenameWorkspace.class,
                DeleteWorkspace.class, RestoreWorkspace.class, CreateWorkspace.class, FindWorkspace.class,
                PruneDeleted.class})
public final class Core {
}
