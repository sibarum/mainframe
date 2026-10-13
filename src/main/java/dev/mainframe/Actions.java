package dev.mainframe;

import dev.mainframe.text.TextFile;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.framework.shell.Dialogs;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Every command the editor has, and the questions some of them ask first.
 *
 * <h2>Threads</h2>
 *
 * <p>Commands arrive on workers — a shortcut, a menu item, a submitted path. Reading and writing files goes to the
 * offload lane ({@link Gui#offload}), never to the frame loop, so a slow disk is a slow command rather than a
 * frozen window.
 *
 * <h2>Nothing is lost without being asked</h2>
 *
 * <p>Closing a tab with unsaved work, closing several, and closing the window all go through one question — save,
 * discard, or cancel — and a save that fails cancels whatever was waiting on it, quitting included.
 *
 * <h2>Questions go through one seam</h2>
 *
 * <p>Every question here is asked of the {@link Dialogs} this was handed: the framework's, which holds a question
 * until there is a window to ask it in, or a test's, which answers it — {@code EditorTest} hands back its own. The
 * close gate is the same shape: {@link #guardClose} is the main window's {@code CloseGate}, called by the framework
 * when the window is asked to close and by a test with answers of its own.
 */
final class Actions {

    private static final Log LOG = Log.of("mainframe.files");

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;
    private final Executor io;
    private final Dialogs dialogs;

    Actions(Gui gui, Model model, Ui ui, Dialogs dialogs) {
        this.gui = gui;
        this.dialogs = dialogs;
        this.model = model;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.io = gui.offload();
        // From the tree the keyboard stays in the tree, so walking it with the arrow keys keeps walking.
        nav.onOpenFile(file -> open(file, false));
        nav.onGo(this::go);
        ws.tabs().onContextMenu((index, menu) -> {
            List<Buffer> all = ws.all();
            if (index < 0 || index >= all.size()) {
                return;
            }
            Buffer b = all.get(index);
            menu.item("Save", () -> save(b, () -> { }, () -> { }))
                    .separator()
                    .item("Close", () -> close(b))
                    .item("Close others", all.size() > 1, () -> closeAll(others(b)))
                    .item("Close all", () -> closeAll(ws.all()))
                    .separator()
                    .item("Reveal in navigator", () -> reveal(b));
        });
    }

    /**
     * Every chord, as a {@code GLOBAL} claim. Claims rather than key handlers, which is how this framework does
     * preemption: a focused field outranks a global claim by claiming the same chord itself, so the chords chosen
     * here are ones a text field leaves alone.
     */
    void shortcuts() {
        gui.shortcut(Key.S, this::saveFront, Modifier.CONTROL);
        gui.shortcut(Key.W, this::closeFront, Modifier.CONTROL);
        gui.shortcut(Key.L, nav::focusPath, Modifier.CONTROL);
        gui.shortcut(Key.E, nav::focusTree, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.TAB, () -> ws.cycle(1), Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> ws.cycle(-1), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.PAGE_DOWN, () -> ws.cycle(1), Modifier.CONTROL);
        gui.shortcut(Key.PAGE_UP, () -> ws.cycle(-1), Modifier.CONTROL);
    }

    // ------------------------------------------------------------------ opening

    /**
     * What the path bar was given: a folder to show, a file to open, or a name that is not there yet, to create. A
     * relative path is relative to the folder shown.
     */
    void go(String text) {
        if (text.isEmpty()) {
            return;
        }
        Path typed;
        try {
            typed = Path.of(text);
        } catch (InvalidPathException e) {
            refuse("Not a path: " + text);
            return;
        }
        Path base = nav.folder();
        Path target = (typed.isAbsolute() || base == null ? typed : base.resolve(typed)).toAbsolutePath().normalize();
        io.execute(() -> {
            if (Files.isDirectory(target)) {
                showFolder(target);
            } else if (Files.isRegularFile(target)) {
                open(target, true);
            } else if (target.getParent() != null && Files.isDirectory(target.getParent())) {
                create(target);
            } else {
                refuse("No such folder: " + target.getParent());
            }
        });
    }

    /** Make an empty file and open it. On the offload lane. */
    private void create(Path file) {
        try {
            Files.createFile(file);
        } catch (IOException e) {
            LOG.warn("could not create {}", file, e);
            refuse("Could not create " + file.getFileName() + ": " + e.getMessage());
            return;
        }
        nav.refresh();
        ws.add(file, TextFile.Loaded.empty(), true);
        model.say("Created " + file.getFileName());
    }

    /** Open {@code file} in a tab, or bring forward the tab it is already in. */
    void open(Path file, boolean focus) {
        Buffer already = ws.find(file);
        if (already != null) {
            ws.show(already, focus);
            return;
        }
        io.execute(() -> load(file, focus));
    }

    /**
     * Read {@code file} and open it, on this thread — the offload lane, since it is a disk read. The session's
     * restore calls it in sequence, so the tabs come back in the order they were in.
     */
    void load(Path file, boolean focus) {
        Path at = file.toAbsolutePath().normalize();
        if (ws.find(at) != null) {
            return;
        }
        TextFile.Loaded loaded;
        try {
            loaded = TextFile.load(at);
        } catch (TextFile.Unsupported e) {
            refuse("Could not open " + at.getFileName() + ": " + e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read {}", at, e);
            refuse("Could not open " + at.getFileName() + ": " + e.getMessage());
            return;
        }
        ws.add(at, loaded, focus);
        model.say(loaded.notes().isEmpty() ? "Opened " + at.getFileName()
                : "Opened " + at.getFileName() + " (" + String.join(", ", loaded.notes()) + ")");
    }

    /** Point the navigator at {@code folder}. */
    void showFolder(Path folder) {
        nav.show(folder, () -> model.folder(nav.folder()));
    }

    void reveal(Buffer b) {
        if (!nav.reveal(b.path)) {
            model.say(b.name() + " is not inside the folder shown");
        }
    }

    /** Say why on the status line, and draw the eye to it. A refusal is not worth a dialog. */
    private void refuse(String why) {
        model.say(why);
        ui.alert();
    }

    // ------------------------------------------------------------------ saving

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b, () -> { }, () -> { });
        }
    }

    /**
     * Write {@code b} on the offload lane; exactly one of {@code saved} and {@code failed} runs afterwards. The
     * bytes are taken here, on the calling thread, so what is written is what the document said when Save was
     * asked for.
     */
    private void save(Buffer b, Runnable saved, Runnable failed) {
        byte[] bytes = b.takeForSave();
        io.execute(() -> {
            try {
                TextFile.write(b.path, bytes);
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not save {}", b.path, e);
                b.saveFailed();
                refuse("Could not save " + b.name() + ": " + e.getMessage());
                failed.run();
                return;
            }
            model.say("Saved " + b.name());
            saved.run();
        });
    }

    /** Save each of {@code buffers} in turn: {@code done} once all have, {@code failed} on the first that does not. */
    private void saveAll(List<Buffer> buffers, Runnable done, Runnable failed) {
        if (buffers.isEmpty()) {
            done.run();
            return;
        }
        save(buffers.getFirst(), () -> saveAll(buffers.subList(1, buffers.size()), done, failed), failed);
    }

    // ------------------------------------------------------------------ closing

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            close(b);
        }
    }

    /** Close {@code b}, asking first if it has unsaved work. */
    void close(Buffer b) {
        if (!b.dirty()) {
            ws.close(b.id);
            return;
        }
        dialogs.ask(Modal.of("Unsaved changes", "Save the changes to " + b.name() + " before closing it?")
                .defaultButton("Save", () -> save(b, () -> ws.close(b.id), () -> { }))
                .button("Don't save", () -> ws.close(b.id))
                .cancelButton("Cancel", () -> { }));
    }

    /** Close every one of {@code buffers}, asking once about all the unsaved ones together. */
    void closeAll(List<Buffer> buffers) {
        List<Buffer> unsaved = buffers.stream().filter(Buffer::dirty).toList();
        Runnable closeThem = () -> buffers.forEach(b -> ws.close(b.id));
        if (unsaved.isEmpty()) {
            closeThem.run();
            return;
        }
        dialogs.ask(Modal.of("Unsaved changes", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, closeThem, () -> { }))
                .button("Discard", closeThem)
                .cancelButton("Cancel", () -> { }));
    }

    private List<Buffer> others(Buffer keep) {
        List<Buffer> rest = new ArrayList<>(ws.all());
        rest.remove(keep);
        return rest;
    }

    /**
     * The close gate, a {@code CloseGate}: the framework calls it and so can a test. The window is still open and
     * drawing while it is unanswered, so the question is an ordinary dialog, and every path through it answers exactly
     * once.
     */
    void guardClose(Runnable proceed, Runnable cancel) {
        List<Buffer> unsaved = ws.all().stream().filter(Buffer::dirty).toList();
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        dialogs.ask(Modal.of("Quit with unsaved changes?", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, proceed, cancel))
                .button("Discard", proceed)
                .cancelButton("Cancel", cancel));
    }

    private static String unsavedMessage(List<Buffer> unsaved) {
        String names = unsaved.stream().map(Buffer::name).collect(Collectors.joining(", "));
        return unsaved.size() == 1
                ? names + " has unsaved changes."
                : unsaved.size() + " files have unsaved changes: " + names + ".";
    }
}
