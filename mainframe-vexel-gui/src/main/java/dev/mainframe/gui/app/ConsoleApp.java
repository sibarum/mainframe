package dev.mainframe.gui.app;

import dev.mainframe.eval.Registry;
import dev.vexelray.gui.core.input.MenuSink;

import java.util.function.BiConsumer;

/**
 * Something plugged into a console: its commands, its menu entries, and — when it has one — its own window.
 *
 * <h2>The shape of an application here</h2>
 * MainFrame is the program. An editor, a calculator, a mail reader: those are things it opens, and this is what
 * one of them looks like from the console's side. An app states a name, adds whatever commands it wants the
 * shell to have, and — if it is the kind of app that has a window — says how to open it. The console does the
 * rest: the commands are indistinguishable from built-in ones, with the same argument checking, the same
 * {@code help}, the same {@code --dry-run} and the same errors, because they <em>are</em> built-in ones.
 *
 * <p>Nothing here is required. An app that only adds commands leaves {@link #launch} alone and is never listed
 * as launchable; an app that is only a window leaves {@link #commands} alone. Both are ordinary.
 *
 * <h2>Commands over callbacks</h2>
 * The console deliberately gives an app no way to do something the shell cannot. A menu entry works by
 * <b>submitting a command</b> ({@link ConsoleContext#run}) rather than by calling Java behind the shell's back,
 * and three things fall out of that: what the menu did is in the scrollback, so it can be read back; it is in
 * the history, so Up repeats it; and anything the menu can do can be scripted, piped and put in a file, because
 * it was never anything but a command. A menu is a way of discovering the command surface, not a second
 * implementation of it.
 */
public interface ConsoleApp {

    /**
     * How this app is named: the {@code help} category its commands are filed under, and what {@code launch}
     * takes as an argument.
     *
     * <p>Lower case, one word if it can be — this is typed.
     */
    String name();

    /** One line about it, for the {@code apps} listing. */
    default String summary() {
        return "";
    }

    /**
     * Add this app's commands. Called once, as the console's session is built, before anything is greeted.
     *
     * <p><b>Frame loop</b> — but the bodies of the commands added here run on the job thread, so what they close
     * over has to survive that. {@link ConsoleContext#onGuiThread} is how one gets back.
     */
    default void commands(Registry registry, ConsoleContext console) {
    }

    /**
     * The session has started and greeted, and the first command can be run.
     *
     * <p>Where an app puts the thing it wants done every time — applying a default profile, saying what it found.
     * Through {@link ConsoleContext#run} rather than quietly, so the first lines of the scrollback say what was
     * done rather than leaving a session whose environment is not the one anybody would have guessed.
     */
    default void started(ConsoleContext console) {
    }

    /**
     * Add this app's entries to the console's context menu.
     *
     * <p><b>Frame loop</b>, at the moment of the click — which is the point of a sink: it lists what exists right
     * now and greys what does not apply rather than hiding it, so the menu teaches the same shape whatever the
     * state.
     */
    default void menu(MenuSink menu, ConsoleContext console) {
    }

    /** Whether {@link #launch} would do anything. A greyed line in a launcher beats an absent one. */
    default boolean launchable() {
        return false;
    }

    /**
     * Open this app's own window, or raise it if it is already up.
     *
     * <p><b>Frame loop.</b> What {@code launch <name>} calls, after hopping threads for you. An app that raises
     * rather than duplicating is the one people expect: "open the editor" has to mean <em>the</em> editor.
     */
    default void launch(ConsoleContext console) {
    }

    /**
     * An app that is nothing but a handful of commands.
     *
     * <p>The short form, for the common case: a host that wants {@code edit} and {@code reveal} in its console
     * and has no window to spawn from the shell writes one lambda instead of a class.
     */
    static ConsoleApp of(String name, String summary, BiConsumer<Registry, ConsoleContext> commands) {
        return new ConsoleApp() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String summary() {
                return summary;
            }

            @Override
            public void commands(Registry registry, ConsoleContext console) {
                commands.accept(registry, console);
            }
        };
    }
}
