package dev.mainframe.core;

import dev.mainframe.core.Messages.Make;
import dev.mainframe.core.Messages.Move;
import dev.mainframe.core.Messages.Restore;
import dev.mainframe.core.Messages.Restored;
import dev.mainframe.core.Messages.Stash;
import dev.mainframe.core.Messages.Stashed;
import dev.mainframe.core.Messages.Turn;
import dev.mainframe.core.Messages.Write;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Workspaces window's jobs, on a temporary home: what each does to the files, and what it answers. */
class WorkspaceJobsTest {

    @TempDir
    Path home;

    private Settings settings() {
        return Settings.at(home.resolve("settings.properties"));
    }

    private WorkspaceStore store() {
        return WorkspaceStore.of(settings());
    }

    @Test
    void makeWritesAnEmptyWorkspaceAndRefusesOneThere() {
        MakeWorkspace job = new MakeWorkspace(settings());
        assertEquals("", job.run(new Make("day", Messages.NO_ENTRY, Turn.DO)).problem());
        assertTrue(store().exists("day"));
        assertFalse(job.run(new Make("day", Messages.NO_ENTRY, Turn.DO)).problem().isBlank());
    }

    @Test
    void writeMoveStashAndRestoreCarryTheScriptsAlong() throws IOException {
        Workspace day = new Workspace("day", List.of(new Workspace.Entry("", "C:/work/api")));
        assertEquals("", new WriteWorkspace(settings()).run(new Write(day)).problem());
        Files.writeString(store().envFile("day", EnvScripts.Dialect.values()[0]), "# day");

        assertEquals("", new MoveWorkspace(settings()).run(new Move("day", "morning", 3, Turn.DO)).problem());
        assertTrue(store().exists("morning"));
        assertTrue(Files.exists(store().envFile("morning", EnvScripts.Dialect.values()[0])));
        assertFalse(new MoveWorkspace(settings()).run(new Move("nothing", "morning", 3, Turn.DO)).problem().isBlank(),
                "another workspace is never overwritten");

        Stashed s = new StashWorkspace(settings()).run(new Stash("morning", 4, Turn.DO));
        assertEquals("", s.problem());
        assertFalse(store().exists("morning"));
        assertFalse(Files.exists(store().envFile("morning", EnvScripts.Dialect.values()[0])));

        Restored r = new RestoreWorkspace(settings()).run(new Restore(s.stash(), "morning", 4, Turn.UNDO));
        assertEquals("", r.problem());
        assertEquals(1, r.workspace().orElseThrow().entries().size());
        assertTrue(WorkspaceStore.validKey(r.workspace().get().entries().getFirst().env()), "keyed as it came back");
        assertTrue(Files.exists(store().envFile("morning", EnvScripts.Dialect.values()[0])));
    }
}
