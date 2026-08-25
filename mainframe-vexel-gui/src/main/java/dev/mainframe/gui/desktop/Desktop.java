package dev.mainframe.gui.desktop;

import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ProjectScope;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.mainframe.gui.profile.ProfileApp;
import dev.mainframe.gui.profile.ProfileStore;
import dev.mainframe.value.Values;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.os.Decorations;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

import java.nio.file.Path;
import java.util.List;

/**
 * MainFrame with nothing hosting it: the console <em>is</em> the application's main window.
 *
 * <h2>What this is for</h2>
 * The console can be opened beside an editor, and for now that is where most of its hours are spent. But a
 * component that can only exist inside somebody else's application is a panel, not a program — so this is the
 * proof that it is a program: {@code Desktop} is a hundred lines of platform plumbing and one
 * {@link Console#adopt} call, and what comes up is MainFrame, in a window, on its own.
 *
 * <h2>Booting one with your own apps in it</h2>
 * {@link #run} is the same boot with a list of {@link ConsoleApp}s handed in, which is what an application looks
 * like when MainFrame is the program rather than a panel inside one. A whole application is then this:
 *
 * <pre>{@code
 * public static void main(String[] args) throws Exception {
 *     Desktop.run("calculator", "MainFrame",
 *             (settings, memory) -> List.of(new Calculator(memory)), args);
 * }
 * }</pre>
 *
 * MainFrame comes up, {@code apps} lists what is in it, and {@code launch "calculator"} opens the calculator in
 * a window of its own. Nothing in that application owns a frame loop, a window memory or an input backend —
 * those are here, once.
 *
 * <pre>{@code
 * mvn -pl mainframe-vexel-gui compile exec:exec
 * mvn -pl mainframe-vexel-gui compile exec:exec "-Dapp.args=--capture" "-Dapp.args2=console.png"
 * mvn -pl mainframe-vexel-gui compile exec:exec "-Dapp.args=--capture-panel" "-Dapp.args2=panel.png"
 * }</pre>
 *
 * Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Desktop {

    /** Where a plain MainFrame's own settings live: profiles, and where the window was left. */
    private static final String APP = "mainframe";

    /**
     * What to plug into a booting console, given the two things an app usually needs and cannot make for
     * itself: the application's settings file, and the window memory every window on this desk shares.
     *
     * <p>A function rather than a plain list because both of those are made by {@link #run} — one
     * {@link Settings} for the whole application, because two instances over the same file each hold their own
     * copy of it and the second one to save would drop whatever the first had added.
     */
    @FunctionalInterface
    public interface Apps {
        List<ConsoleApp> of(Settings settings, WindowMemory memory);
    }

    private Desktop() {
    }

    /** MainFrame on its own, with nothing but the shell and its profiles in it. */
    public static void main(String[] args) throws Exception {
        run(APP, "MainFrame", (settings, memory) -> List.of(), args);
    }

    /**
     * Boot MainFrame as the main window of an application called {@code appName}, with {@code apps} plugged in.
     *
     * <p>Profiles come as standard and do not have to be asked for: a shell that starts programs wants to be
     * able to say which toolchain it starts them with, wherever it is running.
     *
     * @param appName where this application's settings live — its profiles, and where its windows were left
     * @param title   what the console's title bar and the taskbar call it
     * @param args    {@code --capture [out.png]} or {@code --capture-panel [out.png]} for a headless still;
     *                {@code --launch <app>} to come up with
     *                one already open; else an optional frame cap
     */
    public static void run(String appName, String title, Apps apps, String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        if (args.length >= 1 && args[0].equals("--capture")) {
            capture(appName, apps, args.length >= 2 ? args[1] : "console.png");
            return;
        }
        // --capture-panel: the same still, of a data entry screen. Its own argument rather than a second PNG out
        // of --capture, because a screen only exists while something is waiting for an answer, so capturing one
        // means running a line that asks and stopping while it is asking.
        if (args.length >= 1 && args[0].equals("--capture-panel")) {
            capturePanel(appName, apps, args.length >= 2 ? args[1] : "panel.png");
            return;
        }

        // --launch <app>: come up with that app's window already open, as a shortcut on a desk would. It runs
        // the ordinary command rather than calling launch() behind the shell's back, so it is echoed into the
        // scrollback like any other line and a name nothing answers to is refused the way it always is.
        String launch = "";
        if (args.length >= 2 && args[0].equals("--launch")) {
            launch = args[1];
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        Path cwd = Path.of("").toAbsolutePath();

        Settings settings = Settings.open(appName);
        WindowMemory memory = new WindowMemory(settings);
        ProfileApp profiles = new ProfileApp(new ProfileStore(settings));

        Console console = new Console(ConsoleSpec.builder()
                .title(title)
                .memory(memory)
                .app(profiles)
                // The header says which toolchain the next command will find, where a 5250 kept its library list.
                .badge(profiles::badge)
                .apps(apps.of(settings, memory))
                // Nothing is hosting this, so there is no project — and saying so is better than inventing one.
                // A project is chosen deliberately; the directory a process happened to start in is not a
                // choice, and writing a project file into it because someone typed --project would be a
                // surprise. An application that embeds the console supplies a real one.
                .project(ProjectScope::none)
                .build());
        console.gui().zoomRange(0.5f, 3f, 1.25f);

        try (Tactroller input = openInput();
             // Placement is read before the window exists, so it comes up where it was left rather than being
             // moved there after appearing — and clamped on the way, because the desk may have changed shape.
             GuiApp app = new GuiApp(console.config().decorations(Decorations.CLIENT));
             Clipboard clipboard = openClipboard()) {
            attachInput(input, app);
            // Every window this application opens gets its own backend from here — dialogs included.
            app.input(Desktop::windowInput);
            if (clipboard != null) {
                bindClipboard(console.gui(), clipboard);
            }
            // The console takes the main window: its title bar commands it, its placement is remembered under
            // its own name, and closing it is quitting.
            console.adopt(app, cwd);
            if (!launch.isEmpty()) {
                console.run("launch " + Values.quoted(launch));
            }

            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input,
                    console.gui().bus());
            try {
                app.run(console.gui(), maxFrames, () -> {
                    pump(bridge);
                    console.tick();
                    memory.poll();
                });
            } finally {
                console.close();
                memory.save();
            }
        }
        console.gui().close();
        System.out.println("clean shutdown");
    }

    /**
     * Render a data entry screen headlessly, and stop while it is still asking.
     *
     * <p>The form is stated here rather than read from anywhere because this is a look at the display and not a
     * test of a form: what it has to have is one of everything the protocol carries — a required field, a plain
     * one, a list to choose from — so that a cell in the wrong column shows up in the picture.
     */
    private static void capturePanel(String appName, Apps apps, String path) throws Exception {
        Settings settings = Settings.open(appName);
        WindowMemory unused = new WindowMemory(settings);
        try (Console console = new Console(ConsoleSpec.builder()
                .app(new ProfileApp(new ProfileStore(settings)))
                .apps(apps.of(settings, unused))
                .build())) {
            console.start(Path.of("").toAbsolutePath());
            console.capturePanel("form [{name: \"name\", label: \"Full name\", required: true}, "
                    + "{name: \"email\", help: \"where the receipt goes\"}, "
                    + "{name: \"tier\", choose: [\"free\", \"team\", \"enterprise\"]}] "
                    + "--title=\"New customer\"", path);
        }
        System.out.println("captured " + path);
    }

    /**
     * Render the console headlessly: start MainFrame, run a few real lines against the real filesystem, let the
     * per-frame flush publish them, and write the PNG.
     *
     * <p>A window whose look cannot be looked at without a GPU and a keyboard is a window whose look nobody
     * checks. This doubles as a smoke test of the whole path — session, job thread, line sink, scrollback,
     * layout — with no window at all.
     */
    private static void capture(String appName, Apps apps, String path) throws Exception {
        Settings settings = Settings.open(appName);
        WindowMemory unused = new WindowMemory(settings);
        try (Console console = new Console(ConsoleSpec.builder()
                .app(new ProfileApp(new ProfileStore(settings)))
                .apps(apps.of(settings, unused))
                .build())) {
            console.start(Path.of("").toAbsolutePath());
            for (String line : List.of("version", "ls | where kind == \"file\" | select name size ext",
                    "ls | where nmae == \"x\"")) {
                console.submit(line);
                long deadline = System.nanoTime() + 10_000_000_000L;
                Thread.sleep(50);
                while (console.busy() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                Thread.sleep(50);
                console.tick();
            }
            console.tick();
            // The display knows its own size and its own bezel colour, so the capture is its call, not this one.
            console.capture(path);
        }
        System.out.println("captured " + path);
    }

    // ---- the platform edge -----------------------------------------------------------

    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    /** CLIENT space, density left at 1.0 — the engine's canvas is logical. */
    private static void attachInput(Tactroller input, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /**
     * How input reaches every window the framework opens for us. One backend per window, attached at creation,
     * pumped by the frame loop, released with the window.
     *
     * <p>The framework cannot do this alone: it speaks {@code tactroller-api} and Atchung topics, but the bridge
     * between them is chosen here, at the application edge.
     */
    private static WindowInput windowInput(dev.vexelray.os.NativeWindow window, Gui gui) {
        try {
            Tactroller backend = Tactroller.open();
            backend.attach(NativeWindow.ofHwnd(window.osHandle()));
            backend.setCoordinateSpace(CoordinateSpace.CLIENT);
            TactrollerInputBridge bridge = new TactrollerInputBridge(backend, gui.bus());
            return new WindowInput() {
                @Override
                public void pump() {
                    try {
                        bridge.pump();
                    } catch (BackendException e) {
                        // Transient poll failure — drop this frame's input rather than tear down the loop.
                    }
                }

                @Override
                public void close() {
                    try {
                        backend.close();
                    } catch (Exception e) {
                        // best effort — the backend is going away regardless
                    }
                }
            };
        } catch (BackendException e) {
            System.out.println("window input unavailable (" + e.getMessage() + "); that window takes no input");
            return WindowInput.NONE;
        }
    }

    /** OS clipboard for cut/copy/paste; falls back to the in-memory default when no backend is present. */
    private static Clipboard openClipboard() {
        try {
            return Clipboard.open();
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage()
                    + "); cut/copy/paste use in-memory buffer");
            return null;
        }
    }

    /** Point one window's clipboard at the OS one. Each Gui carries its own, so each window is bound. */
    private static void bindClipboard(Gui gui, Clipboard clip) {
        gui.clipboard(new TextClipboard() {
            @Override
            public String get() {
                try {
                    return clip.getText().orElse("");
                } catch (ClipboardException e) {
                    return "";
                }
            }

            @Override
            public void set(String text) {
                try {
                    clip.setText(text);
                } catch (ClipboardException e) {
                    // best effort — a transient clipboard failure just drops the copy
                }
            }
        });
    }

    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure — drop this frame's input rather than tear down the loop.
        }
    }
}
