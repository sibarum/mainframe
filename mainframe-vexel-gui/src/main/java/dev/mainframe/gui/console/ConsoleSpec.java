package dev.mainframe.gui.console;

import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ProjectScope;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.style.Theme;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What one console is: its name, its size, its look, and what is plugged into it.
 *
 * <p>Everything on here has a default that works, so the smallest useful console is
 * {@code new Console(ConsoleSpec.builder().build())} — a MainFrame session in a window, with nothing application
 * specific in it. Every field exists because it is the kind of thing two consoles in two applications would
 * reasonably disagree about; anything they would not disagree about is not on here.
 *
 * <pre>{@code
 * Console console = new Console(ConsoleSpec.builder()
 *         .windowName("terminal").title("Terminal")
 *         .memory(memory)
 *         .project(() -> ProjectScope.at(folder, ".vtext"))
 *         .app(editorCommands)
 *         .build());
 * }</pre>
 */
public final class ConsoleSpec {

    /** The title bar's own height, in dp — {@code TitleBar}'s, which is the Windows caption metric. */
    public static final int BAR_H = 32;

    /**
     * The default size, used the first time — after that, whatever the user left it at. The height carries the
     * title bar the console draws itself, plus the three rows around the scrollback: the header, the command
     * line and the message line.
     */
    public static final int DEFAULT_WIDTH = 760;
    public static final int DEFAULT_HEIGHT = 520 + BAR_H;

    /**
     * The bottom line's three states, said in the host's own words.
     *
     * <p>It is the one row on screen whose whole job is telling you what to do next, so an application that adds
     * a key the console has never heard of has to be able to mention it. The default says what the console
     * itself provides and nothing else.
     */
    public interface Status {

        /** Nothing running: what can be done from here. */
        String ready();

        /** A command is running: how to stop it, and what else still works. */
        String running();

        /** A form or a confirmation is waiting on a line: the three words that are instructions, not answers. */
        String answering();

        Status DEFAULT = new Status() {
            @Override
            public String ready() {
                return "Ready.  help lists every command   apps lists what can be opened"
                        + "   Ctrl+D closes this display";
            }

            @Override
            public String running() {
                return "Running.  Ctrl+C interrupt   Ctrl+L clear   Up/Down history";
            }

            @Override
            public String answering() {
                return "Answering.  !back a field   !clear empties it   !cancel abandons the form";
            }
        };
    }

    private final String windowName;
    private final String title;
    private final int width;
    private final int height;
    private final Theme theme;
    private final WindowMemory memory;
    private final Supplier<ProjectScope> project;
    private final List<ConsoleApp> apps;
    private final Function<Path, List<String>> greeting;
    private final Status status;
    private final Supplier<String> badge;

    private ConsoleSpec(Builder b) {
        this.windowName = b.windowName;
        this.title = b.title;
        this.width = b.width;
        this.height = b.height;
        this.theme = b.theme;
        this.memory = b.memory;
        this.project = b.project;
        this.apps = List.copyOf(b.apps);
        this.greeting = b.greeting;
        this.status = b.status;
        this.badge = b.badge;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The key this window is remembered and raised under. */
    public String windowName() {
        return windowName;
    }

    public String title() {
        return title;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Theme theme() {
        return theme;
    }

    /** Where the window's placement is kept, or {@code null} — a console that is never asked to remember. */
    public WindowMemory memory() {
        return memory;
    }

    public ProjectScope project() {
        return project.get();
    }

    public List<ConsoleApp> apps() {
        return apps;
    }

    /** The lines said when the session opens, given the directory it opened in. */
    public List<String> greeting(Path cwd) {
        return greeting.apply(cwd);
    }

    public Status status() {
        return status;
    }

    /** The short fact shown in the header, or {@code ""}. Read every frame; see the note on the builder. */
    public String badge() {
        String text = badge.get();
        return text == null ? "" : text;
    }

    /** The default greeting: where the shell is, and one line of what to try. */
    public static List<String> defaultGreeting(Path cwd) {
        return List.of(
                "MainFrame in " + cwd,
                "type help to see every command, or try: ls | where kind == \"file\" | sort-by size",
                "");
    }

    public static final class Builder {

        private String windowName = "console";
        private String title = "MainFrame";
        private int width = DEFAULT_WIDTH;
        private int height = DEFAULT_HEIGHT;
        private Theme theme = Phosphor.THEME;
        private WindowMemory memory;
        private Supplier<ProjectScope> project = ProjectScope::none;
        private final List<ConsoleApp> apps = new ArrayList<>();
        private Function<Path, List<String>> greeting = ConsoleSpec::defaultGreeting;
        private Status status = Status.DEFAULT;
        private Supplier<String> badge = () -> "";

        private Builder() {
        }

        /**
         * The name this window is opened, raised and remembered under.
         *
         * <p>It is why "open the console" means <em>the</em> console rather than a second one, so two consoles
         * in one application need two names — and an application that already calls this window something else
         * should keep saying so, because the name is also the settings key its placement is already stored under.
         */
        public Builder windowName(String windowName) {
            this.windowName = windowName;
            return this;
        }

        /** What the title bar and the taskbar call it. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** The size the first time it is ever opened. After that the user's own size wins. */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * The look. {@link Phosphor#THEME} unless told otherwise — and whatever replaces it still resolves
         * {@link Phosphor#HOT} and {@link Phosphor#BEZEL}, because those are roles rather than colours.
         */
        public Builder theme(Theme theme) {
            this.theme = theme;
            return this;
        }

        /** Where to keep this window's position and size. Without one it opens at its default place each time. */
        public Builder memory(WindowMemory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * The project the console is pointed at.
         *
         * <p>A supplier, because it changes under a running shell: a console told the answer at startup would go
         * on writing the old project's file after the host had moved on to another one.
         */
        public Builder project(Supplier<ProjectScope> project) {
            this.project = project;
            return this;
        }

        /** Plug something in. Order is kept: commands, menu entries and start-up hooks run in this order. */
        public Builder app(ConsoleApp app) {
            apps.add(app);
            return this;
        }

        public Builder apps(List<ConsoleApp> more) {
            apps.addAll(more);
            return this;
        }

        /**
         * What the session says when it opens. The first word of the first line is drawn at full beam, which is
         * how the default line reads as a heading without anything having to mark it as one.
         */
        public Builder greeting(Function<Path, List<String>> greeting) {
            this.greeting = greeting;
            return this;
        }

        /** The bottom line's three states. */
        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        /**
         * The short fact shown up in the header, where a 5250 kept its library list — which toolchain the next
         * command will find, or whatever this application's equivalent of that is.
         *
         * <p>Read every frame and written only when it changes, so it is free to be computed.
         */
        public Builder badge(Supplier<String> badge) {
            this.badge = badge;
            return this;
        }

        public ConsoleSpec build() {
            return new ConsoleSpec(this);
        }
    }
}
