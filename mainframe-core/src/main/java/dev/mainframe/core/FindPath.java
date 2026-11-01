package dev.mainframe.core;

import dev.mainframe.core.Messages.Find;
import dev.mainframe.core.Messages.Found;
import dev.mainframe.core.Messages.Kind;
import dev.vexelray.framework.api.Job;

import java.nio.file.Files;
import java.nio.file.Path;

/** What is at a path, as far as opening it goes: a folder, a file, a name a file could be made under, or nothing. */
@Job
public final class FindPath {

    public Found run(Find f) {
        Path p = f.path();
        Path parent = p.getParent();
        Kind kind = Files.isDirectory(p) ? Kind.FOLDER
                : Files.isRegularFile(p) ? Kind.FILE
                : parent != null && Files.isDirectory(parent) ? Kind.NEW
                : Kind.NOWHERE;
        return new Found(p, f.why(), kind);
    }
}
