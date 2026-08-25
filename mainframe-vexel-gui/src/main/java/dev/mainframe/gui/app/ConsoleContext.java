package dev.mainframe.gui.app;

import dev.mainframe.form.Form;
import dev.mainframe.value.Value;
import dev.vexelray.gui.core.app.GuiApp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The console, as the thing plugged into it is allowed to see. Handed to every {@link ConsoleApp} hook.
 *
 * <h2>Why an interface and not the window</h2>
 * An app that held the window could reach into its tree, and then the console could not change shape without
 * breaking it. This is the whole of what an app is owed: a way to run a line, a way to say something, a way to
 * ask a question, a way to get onto the frame loop, and the two facts about the session it might need to decide
 * with. Everything a console can do that is not on here is the console's own business.
 *
 * <h2>Which thread</h2>
 * A command body runs on the shell's <b>job thread</b>; a menu contribution and {@link ConsoleApp#launch} run on
 * the <b>frame loop</b>. The two rules that follow from that are marked on each method, and the one that matters
 * is {@link #onGuiThread}: anything that touches a window has to go through it, because a command that opened a
 * window from the job thread would be building a tree while the loop was drawing one.
 */
public interface ConsoleContext {

    /**
     * Run {@code line} as if it had been typed, and show that it was.
     *
     * <p>It matters that this echoes: a menu that changes the environment silently leaves you guessing at what
     * it did, whereas a menu that puts {@code profile-use jdk-21} in the scrollback has taught you the command.
     * It stays out of the history, though — Up is for things you typed.
     *
     * <p>Any thread. The line is queued behind whatever is already running.
     */
    void run(String line);

    /** Write one line into the scrollback, as the console itself writes them. Any thread; it queues. */
    void post(String line);

    /** Where the shell is standing. {@code cd} moves it. Any thread. */
    Path cwd();

    /**
     * Show a data-entry screen and collect the answers, or {@code null} if the person gave up.
     *
     * <p><b>Job thread only</b>, because it blocks: the questions are printed into the scrollback and the
     * answers are read off the command line, which is what makes a form in this window feel like a form in a
     * terminal — {@code !back}, {@code !cancel}, {@code !clear} and the review step all come free, and an answer
     * that would not survive being used is refused by the same field definition that asked for it.
     *
     * <p>This is the seam for "a special data entry screen": state the {@link Form}, and there is no screen to
     * draw.
     *
     * @param starting what to offer as the answers — the record being edited, or a blank one
     * @param title    what the form calls itself at the top of the screen
     */
    Value.Rec form(Form definition, Value.Rec starting, String title);

    /**
     * Run {@code task} on the frame loop, at the top of the next frame.
     *
     * <p>How a command reaches a window. Any thread, and ordered: two commands that both open something open it
     * in the order they ran.
     */
    void onGuiThread(Runnable task);

    /**
     * The window system this console is on, or empty when there is none — the headless capture path, and tests.
     *
     * <p>An app that spawns a window needs this and should check it: {@link ConsoleApp#launch} is called on the
     * frame loop, but a console being captured to a PNG has no frame loop and no application to attach to.
     */
    Optional<GuiApp> host();

    /** The project the console is pointed at right now, never null. Read on every call, because it changes. */
    ProjectScope project();

    /**
     * Ask the console to put its window away. What {@code exit} and Ctrl+D do.
     *
     * <p>Not a shutdown: the session keeps running behind the closed window, so reopening comes back to the same
     * working directory, the same history and the same scrollback.
     */
    void dismiss();
}
