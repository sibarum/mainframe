package dev.mainframe.gui.console;

import dev.mainframe.form.Form;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.gui.app.ProjectScope;
import dev.mainframe.value.Value;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MainFrame as a window: a tailing scrollback over a prompt over a message line, on its own {@link Gui}, opened
 * under a name — so "open the console" means <em>the</em> console, whether that has to create one or raise the
 * one already there.
 *
 * <p>This is a <b>shell console, not a terminal emulator</b>: lines in, lines out. There is no character grid, no
 * pseudo-terminal and no escape-sequence state machine here, and MainFrame needs none — it is a Java library that
 * prints, so the window feeds it a line and renders what it prints.
 *
 * <h2>Two ways to be on screen</h2>
 * {@link #show} opens it as a named window <em>beside</em> an application that already exists — an editor's
 * terminal, a calculator's scratchpad. {@link #adopt} makes it the application's <b>main</b> window, which is
 * what {@code dev.mainframe.gui.desktop.Desktop} does and what MainFrame being the program rather than a panel
 * inside one actually looks like. Same tree, same session, same everything else; the difference is which window
 * the title bar commands and what closing it means.
 *
 * <h2>Why it looks like a 5250</h2>
 * MainFrame is a shell whose pipes carry typed records rather than text, which is the one idea it shares with the
 * machine this screen is borrowed from. So the window wears the part: {@link Phosphor} for a green tube,
 * {@code Command ===>} over a boxed entry area, and a message line that turns over into reverse video when
 * something failed.
 *
 * <p><b>What is left is what earns its row.</b> A 5250 spent its top three lines on a screen identifier, a
 * centred title and a "Type command, press Enter." that stopped being news the second time anyone read it, and
 * its bottom line on a function-key legend. Those four rows are scrollback now; the working directory and the
 * clock, the only things up there that ever changed, share one. The shadow mask over the glass went the same
 * way, and for the same reason: this is a window you read through, and the costume was charging rent.
 *
 * <p>The chrome is a 5250; the <em>content</em> is not. MainFrame's output keeps its own case and its own
 * spacing, because a shell that upper-cases your paths is a shell that lies about them.
 *
 * <h2>Typing without aiming</h2>
 * The caret lives in the command field and returns there on any click anywhere in the window, so the field never
 * has to be hit to be typed into — see {@link #focusFollowsWindow}.
 *
 * <p><b>The session outlives the window.</b> The tree belongs to this object, not to the OS window, so closing a
 * console opened with {@link #show} releases a window and leaves MainFrame running: reopen it and the scrollback,
 * the history and the working directory are where you left them. Only {@link #close} stops the shell.
 *
 * <h2>What an application puts in it</h2>
 * Everything application-specific arrives through {@link ConsoleSpec}: the apps plugged into it, the project it
 * is pointed at, what it is called, what it says when it opens, and what its bottom line offers. This class
 * names no application and no command that is not the shell's own.
 *
 * <p>All methods run on the frame loop except {@link ConsoleShell#submit} (a handler thread) and the job thread
 * behind it.
 */
public final class Console implements AutoCloseable, ConsoleContext {

    /** This window's margin, and so its resize grip — the same bargain an editor's gutter makes. */
    private static final Length GUTTER = Length.dp(12);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM/dd/yy  HH:mm:ss");

    /** What the clock reads before a window has ever been opened — see {@link #stamp()}. */
    private static final String UNSET = "--/--/--  --:--:--";

    private final ConsoleSpec spec;
    private final WindowMemory memory;
    private final Gui gui = new Gui();
    private final Ansi ansi;
    private final Node output;
    private final Node location;
    private final Node badge;
    private final Node clock;
    private final Node message;
    private final TextField prompt;
    /** The command line's row, hidden while a screen is up: in panel mode the screen is where typing goes. */
    private final Node commandRow;
    /** This window as a display MainFrame can borrow. See {@link Panel}. */
    private final Panel panel;
    private final Scrollback scrollback;
    private final TitleBar titleBar;
    private final Subscription clicks;
    private final List<String> history = new ArrayList<>();
    /** What a command asked to have done on the frame loop. Drained one per frame by {@link #tick()}. */
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

    private ConsoleShell shell;
    /** The application this console is on, once it is on one. Null while headless. */
    private GuiApp host;
    /** The framework's handle on this window, claimed the first time {@link #show} is called. */
    private AppWindow handle;
    /** Whether this console <em>is</em> the application's main window rather than one beside it. */
    private boolean primary;
    private int recall;
    private String shownLocation = "";
    /** Characters the location field can show on one line, learned from the layout -- see {@link #fitted}. */
    private int locationRoom = Integer.MAX_VALUE;
    /** The width the budget was learned at, so a resize measures again instead of keeping an old answer. */
    private float locationWidth = -1f;
    private String shownBadge = "";
    private String shownClock = "";
    private String shownMessage = "";
    private boolean shownError;

    public Console(ConsoleSpec spec) {
        this.spec = spec;
        this.memory = spec.memory();

        // First, and before a single node exists: a role resolves at the moment a widget writes a prop, so a
        // theme installed after the tree is built reaches nothing that is already painted.
        gui.theme(spec.theme());
        Theme theme = gui.theme();
        this.ansi = Ansi.of(theme);

        // ---- the display -------------------------------------------------------------
        // One line, because one line is all there was worth keeping: where you are, and when it is. The screen
        // identifier, the centred title and the instruction line underneath them were furniture that told you
        // nothing the second time you read them, and the three rows they cost are scrollback now.
        this.location = glyphs("", theme.color(Phosphor.HOT))
                .width(Length.grow(1))
                .scroll(false, false);
        // What a 5250 kept up here was the library list -- which toolchain the next command would find. What
        // stands there now is whatever the host says is that fact for it; see ConsoleSpec.badge.
        this.badge = glyphs("", theme.color(Role.INK))
                .width(Length.AUTO)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        this.clock = glyphs(UNSET, theme.color(Role.DIM))
                .width(Length.AUTO)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        Node header = gui.row().width(Length.FILL).height(Length.rem(1.4f)).gap(Length.em(1.5f))
                .children(location, badge, clock);

        Node rule = gui.box().width(Length.FILL).height(Length.dp(1)).background(theme.color(Role.DIM));
        // A tailing log is clipped at its top edge, so the oldest visible line is usually cut through the
        // middle. That is what a scrolling display does; it only looks like a fault when the cut lands against
        // the rule. This is the clearance that keeps the two apart.
        Node clearance = gui.box().width(Length.FILL).height(Length.rem(0.3f));

        this.scrollback = new Scrollback(gui, ansi);
        // One document rather than a node per line, which is what makes the output selectable and copyable. It
        // tails itself: the scroll lock a container would have carried lives inside it, said in the one term a
        // text node has for its bottom edge. See Scrollback.
        this.output = scrollback.node().width(Length.FILL).height(Length.grow(1));

        // ---- the panel -----------------------------------------------------------------
        // A screen MainFrame describes goes here, in the slot the scrollback stands in, because the two are never
        // both wanted: while a screen is up, a screen is what this window is doing. Swapped by visibility rather
        // than by re-parenting — a hidden child is not placed, not measured and not counted toward the gaps, which
        // makes visibility the correct way to swap two things sharing one grow(1) slot.
        Node glass = gui.column().width(Length.FILL).height(Length.grow(1))
                .gap(Length.ZERO)
                .scroll(true, true)
                .visible(false);
        // The chooser is asked for once, here, rather than left to the panel to find: whether there is a native
        // file dialog on this machine is what the panel says in its capabilities, and a capability worked out
        // later than the first hello is one that was a guess before it. Nothing on a machine without one, and the
        // panel then makes no claim -- so MainFrame draws the chooser instead, which it can.
        this.panel = new Panel(gui, glass, ansi, scrollback, this::curtain, this::busy,
                NativeChooser.here() ? new NativeChooser(this::ownerHandle) : null);

        // ---- the entry field ----------------------------------------------------------
        Node command = glyphs("Command", theme.color(Role.INK)).width(Length.AUTO);
        Node arrow = glyphs("===>", theme.color(Phosphor.HOT)).width(Length.AUTO);
        this.prompt = new TextField(gui, "");
        // Square, with the tube showing through it. The widget paints itself a rounded sunken well, which is
        // right on a page and wrong on glass — but its border it re-paints on every focus change, so that one is
        // not ours to take away. Left alone it becomes the thing a 5250 entry field was always drawn as: a box
        // around the input area, bright while the field holds the caret. Which, in this window, is always.
        prompt.node().width(Length.grow(1)).height(Length.FILL)
                .background(theme.color(Role.NONE))
                .corner(Length.ZERO)
                .font(1).textSize(Length.rem(0.8125f)).textColor(theme.color(Phosphor.HOT));
        this.commandRow = gui.row().width(Length.FILL).height(Length.rem(1.9f)).gap(Length.em(0.6f))
                .children(command, arrow, prompt.node());

        // ---- the message line ------------------------------------------------------------
        this.message = glyphs("", theme.color(Role.DIM))
                .width(Length.FILL).height(Length.rem(1.5f))
                .padding(Length.ZERO, Length.em(0.4f));
        // ---- the tube ------------------------------------------------------------------
        // Rounded because the glass is, lit because it is glass, and elevated because this palette's depth anchor
        // is the phosphor itself — so what would be a drop shadow under any other theme is the halo the screen
        // throws onto the bezel around it. One anchor; no special case anywhere in the renderer.
        Node tube = gui.column().width(Length.FILL).height(Length.grow(1))
                .background(theme.color(Role.PAGE))
                .corner(Length.rem(1.1f))
                .padding(Length.dp(18))
                .gap(Length.rem(0.35f))
                .lit(theme.lit())
                .elevation(Length.rem(1.25f))
                .children(header, rule, clearance, output, glass, commandRow, message);
        Node frame = gui.column().width(Length.FILL).height(Length.grow(1))
                .padding(GUTTER)
                .children(tube);
        // This window draws its own frame too. The bar is bound later: as a named window, in onCreated, because
        // a window does not exist until the frame loop services the request and it is that window the buttons
        // command; as the main window, in adopt().
        this.titleBar = new TitleBar(gui, WindowControls.NONE, spec.title());
        gui.root().background(theme.color(Phosphor.BEZEL)).children(titleBar.node(), frame);
        // One row of chrome more than the plain console had, so barely more than it asked for.
        gui.minSize(Length.em(26), Length.em(15));
        // The margin is the grip: dead space around the tube resizes the window, the bar above it still drags it.
        gui.resizeBorder(GUTTER);
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);

        prompt.onSubmit(this::onLine);
        this.clicks = focusFollowsWindow();
        gui.onContextMenu(frame, this::contextMenu);
        // And over the output, where the framework would otherwise stop at the pane's own Copy.
        scrollback.menu(this::contextMenu);
        claims();
    }

    /**
     * Where a modal file dialog parents: this window, while there is one.
     *
     * <p>Read late rather than held, because a console constructed headless has no window at all and one shown
     * later has a different one. Zero is a real answer and not a failure — a dialog with no parent still opens,
     * and it is the honest thing to say on the frames before this console is on a window.
     */
    private long ownerHandle() {
        AppWindow open = handle;
        if (open != null && open.open()) {
            return open.window().osHandle();
        }
        GuiApp app = host;
        return app == null ? 0L : app.windowHandle();
    }

    /** This window's Gui, so the host can bind its shortcuts and its clipboard here too. */
    public Gui gui() {
        return gui;
    }

    /** The name this window is opened, raised and remembered under. */
    public String windowName() {
        return spec.windowName();
    }

    /**
     * Open the console beside an application that already exists, starting MainFrame in {@code cwd}.
     *
     * <p>Only the first call opens a window. Asking again raises the one that exists and puts the caret back in
     * the prompt — "open the console" has to mean the console, not a second console, and a window that is already
     * open but behind something else has to come forward or the shortcut looks broken.
     *
     * <p>Its position and size come from {@link WindowMemory} when the spec was given one, clamped to a monitor
     * that exists, and go back there as the user moves it.
     */
    public void show(GuiApp app, Path cwd) {
        this.host = app;
        start(cwd);
        // One call for both cases: show() creates the window if it is closed and raises it if it is not.
        if (handle == null) {
            handle = app.window(spec.windowName(), () -> WindowSpec
                    .of(config().decorations(Decorations.CLIENT), gui)
                    .onCreated(this::onCreated)
                    .onClosed(this::onClosed));
        }
        handle.show();
        gui.focus(panel.up() ? panel.node() : prompt.node());
    }

    /**
     * Become {@code app}'s <b>main</b> window, starting MainFrame in {@code cwd}.
     *
     * <p>The other way round from {@link #show}, and the one that says what MainFrame is: the shell is the
     * program, and the windows an application opens are things it opened. The application's own window already
     * exists by the time a {@link GuiApp} is constructed, so there is nothing to create here — only the three
     * facts that {@code onCreated} would otherwise carry: which window the title bar commands, where it should
     * be, and that the caret belongs in the prompt.
     *
     * <p>Closing this window ends the application, because for a main window that is what closing means. The
     * caller drives the loop with {@code app.run(console.gui(), ...)}.
     */
    public void adopt(GuiApp app, Path cwd) {
        this.host = app;
        this.primary = true;
        start(cwd);
        titleBar.controls(app.controls());
        if (memory != null) {
            if (memory.maximized(spec.windowName())) {
                app.window().maximize();
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
                // dragging the window bigger, and losing it on quit is the same loss. The constructor has
                // already set the range the restored factor is clamped into.
                memory.watch(spec.windowName(), app.window(), gui);
        }
        gui.focus(panel.up() ? panel.node() : prompt.node());
    }

    /**
     * The window request this console would like: its remembered place if it has a memory, its default size if
     * not. Public because {@code adopt} needs the same rectangle <em>before</em> there is a {@link GuiApp} to
     * hand it to — a main window is configured at construction, not on creation.
     */
    public WindowConfig config() {
        return memory == null
                ? WindowConfig.of(spec.title(), spec.width(), spec.height())
                : memory.config(spec.windowName(), spec.title(), spec.width(), spec.height());
    }

    /**
     * Start MainFrame in {@code cwd} and greet, without opening a window. Separate from {@link #show} so the
     * headless capture path can render this tree — the window's look is reviewable without a GPU or a keyboard.
     */
    public void start(Path cwd) {
        if (shell != null) {
            return;
        }
        // The panel goes to the session only when there is a window to put a screen on. Both ways in --
        // show() and adopt() -- set the host before starting, so this is the one place that has to know.
        shell = new ConsoleShell(scrollback, cwd, this::dismiss, spec.apps(), this, host == null ? null : panel);
        greet(cwd);
        for (ConsoleApp app : spec.apps()) {
            app.started(this);
        }
    }

    /** Run one line as if it had been typed. The entry point for the capture path and for scripted checks. */
    public void submit(String line) {
        onLine(line);
    }

    /**
     * Render this display to a PNG at its default size — no window, no input backend, no GPU surface beyond the
     * one the capture opens for itself.
     *
     * <p>The clear colour is the bezel, read off this window's own theme rather than repeated as three floats.
     */
    public void capture(String path) throws java.io.IOException {
        tick();
        dev.vexelray.canvas.Color bezel = gui.theme().color(Phosphor.BEZEL);
        GuiApp.capture(gui, spec.width(), spec.height(), bezel.r(), bezel.g(), bezel.b(), path);
    }

    /** True while a command is running — the capture path waits on this. */

    /**
     * Render a screen headlessly: borrow this display, run {@code line} until it asks for something, and write the
     * PNG of whatever it put up.
     *
     * <p>The same argument as {@link #capture}, for the half of the window that argument did not reach. A panel is
     * the one thing here whose look cannot be checked by reading the code — a cell in the wrong column is invisible
     * in a source file and obvious in a picture — and it is also the one thing the ordinary capture cannot show,
     * because a screen only exists while something is waiting for an answer.
     *
     * <p>Nobody answers it. The job thread is left parked on the screen and {@link Panel#close} lets it go on the
     * way out, which is the same cancel a closed window gives; a capture is a look at a display and not a session.
     */
    public void capturePanel(String line, String path) throws java.io.IOException {
        if (shell == null) {
            return;
        }
        // Attached even though there is no window. Everywhere else this console decides for itself whether anybody
        // is there; here the caller is saying so, and a capture that had to open a window would not be headless.
        shell.display(panel);
        submit(line);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!panel.up() && System.nanoTime() < deadline) {
            tick();
            settle();
        }
        // One picture before the one that counts, and it is not waste. Nothing lays this tree out until something
        // draws it, and the panel learns the size of the tube by measuring rows that have been laid out -- so the
        // first render is what tells it how big the window is, and the screen it was holding at the time was laid
        // out for a guess. Drawing once, letting the correction happen, and drawing again is what makes a capture a
        // picture of the same screen a person would be looking at.
        tick();
        capture(path);
        int quiet = 0;
        while (quiet < 4 && System.nanoTime() < deadline) {
            tick();
            quiet = panel.settled() ? quiet + 1 : 0;
            settle();
        }
        tick();
        capture(path);
        panel.close();
    }

    /** A frame's worth of waiting, for the loops that are standing in for a frame loop. */
    private static void settle() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    public boolean busy() {
        return shell != null && shell.busy();
    }

    /**
     * How many lines this console has finished, only ever going up.
     *
     * <p>What {@link #submit} needs to be waitable: take the count, submit, wait for it
     * to change. {@link #busy()} cannot serve, being false both before a queued line
     * starts and after it ends.
     */
    public long finished() {
        return shell == null ? 0 : shell.finished();
    }

    /**
     * How the last command failed, or {@code ""} once one succeeds.
     *
     * <p>The same string the message line shows, {@code error[CODE] what happened}. Public because a host with a
     * status bar of its own may want to say it there too, and because it is the one fact about a finished command
     * that is not already in the scrollback in some other form.
     */
    public String lastError() {
        return shell == null ? "" : shell.lastError();
    }

    /** Whether the window is up right now — polled each frame so it can be reopened next launch. */
    public boolean isOpen() {
        return primary ? host != null : handle != null && handle.open();
    }

    // ---- what an app is handed -------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void run(String line) {
        if (shell == null) {
            return;
        }
        scrollback.tail();
        String label = promptText();
        scrollback.post(label + line, List.of(Span.foreground(0, label.length(), ansi.hot())));
        shell.submit(line);
    }

    @Override
    public void post(String line) {
        scrollback.post(line, List.of());
    }

    @Override
    public Path cwd() {
        return shell == null ? Path.of("").toAbsolutePath() : shell.cwd();
    }

    @Override
    public Value.Rec form(Form definition, Value.Rec starting, String title) {
        return shell == null ? null : shell.form(definition, starting, title);
    }

    @Override
    public void onGuiThread(Runnable task) {
        pending.add(task);
    }

    @Override
    public Optional<GuiApp> host() {
        return Optional.ofNullable(host);
    }

    @Override
    public ProjectScope project() {
        return spec.project();
    }

    /**
     * {@code exit} asked to leave, or Ctrl+D did, or an app did.
     *
     * <p>As a named window it travels the ordinary close route, so the frame loop tears the window down on its
     * own terms and {@link #onClosed} still runs — and MainFrame keeps running behind it, so this is "put the
     * display away", not "throw the session away". As the main window there is nothing behind it to keep, so the
     * same request ends the application. Safe from the job thread: both only enqueue.
     */
    @Override
    public void dismiss() {
        if (primary) {
            GuiApp app = host;
            if (app != null) {
                app.window().requestClose();
            }
            return;
        }
        AppWindow w = handle;
        if (w != null) {
            w.close();
        }
    }

    // ---- the prompt ------------------------------------------------------------------

    /**
     * Handler thread: echo the line, remember it, hand it to the job thread.
     *
     * <p>And send the display back to the tail. Scrolling up through history detaches the scroll lock and hands
     * the reader full control of the view, which is right — output arriving underneath them must not yank the
     * page. But pressing Enter says they are finished reading history: a shell that runs a command and leaves you
     * looking at some older screen has hidden its own answer. So the tail is re-attached here rather than waiting
     * for them to scroll back down, and the jump lands in the same frame as the echo.
     */
    private void onLine(String line) {
        if (shell == null) {
            return;
        }
        prompt.text("");
        scrollback.tail();
        // A line is either a command or an answer to a question the shell is holding open -- a form's field, a
        // yes/no. The shell knows which, because it knows whether it is blocked reading; the window only has to
        // ask. An answer is echoed like a command, since that is what the scrollback of a filled-in form is.
        if (shell.asking()) {
            scrollback.post("> " + line, List.of(Span.foreground(0, 1, ansi.hot())));
            shell.answer(line);
            return;
        }
        String label = promptText();
        scrollback.post(label + line, List.of(Span.foreground(0, label.length(), ansi.hot())));
        if (line.isBlank()) {
            return;
        }
        // Written here on a handler thread, read by the Up/Down claims on the GUI thread.
        synchronized (history) {
            history.remove(line);
            history.add(line);
            recall = history.size();
        }
        shell.submit(line);
    }

    /**
     * Any click anywhere in this window puts the caret back in the command field.
     *
     * <p>The click <em>topic</em> rather than a handler on the root, and that is the point: a handler bubbles to
     * the nearest ancestor that has one, so a click on the scrollback would reach the root but a click on the
     * title bar's maximize button would not — and the one that leaves you unable to type afterwards is the
     * second. The topic publishes every click whatever consumed it. Re-focusing a field that already has focus
     * is a no-op in the dispatcher, so the common case costs a comparison.
     *
     * <p>This is what makes the whole tube the input: there is nothing to aim at, because everything is the
     * same target.
     */
    private Subscription focusFollowsWindow() {
        // A selection dragged across the output is the one gesture a click does not end: press and release land
        // on different nodes, so no click is published and the caret would be left nowhere -- with Ctrl+C, which
        // is claimed on the field, reaching nothing at the moment there is finally something to copy. The pane's
        // own drag stage is the widget's; this is a second, unordered handler on the same node, so both run.
        gui.onDrag(scrollback.node(), event -> {
            if (event.phase() == DragEvent.Phase.END) {
                gui.focus(panel.up() ? panel.node() : prompt.node());
            }
        });
        return gui.bus().subscribe(gui.clicks(),
                event -> gui.focus(panel.up() ? panel.node() : prompt.node()));
    }

    /**
     * The prompt's keys, claimed on the field.
     *
     * <p>A claim is preemption declared in advance: while the field has focus these run and nothing else sees the
     * key, which is what lets Up mean "previous command" here and "previous line" in an editor with no
     * subclassing and no {@code preventDefault}. They use the <em>ordered</em> claim, because each one edits the
     * state typing also edits.
     */
    private void claims() {
        Node node = prompt.node();
        gui.claimUi(node, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> recall(-1));
        gui.claimUi(node, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> recall(+1));
        gui.claimUi(node, Shortcut.of(Key.L, Modifier.CONTROL), ClaimScope.FOCUSED, scrollback::clear);
        // Ctrl+C is the one real conflict: the field handles it as copy in its own key stage, and a FOCUSED claim
        // preempts that. So the claim decides — job running, interrupt it; otherwise copy the selection, which
        // this window can do itself because the document is public and the clipboard is writable.
        gui.claimUi(node, Shortcut.of(Key.C, Modifier.CONTROL), ClaimScope.FOCUSED, this::interruptOrCopy);
        // Ctrl+D on an empty line closes the window, as it does in a shell. On a line with something on it, it
        // does nothing rather than deleting forward: this prompt is one line, so there is no "delete the rest".
        gui.claimUi(node, Shortcut.of(Key.D, Modifier.CONTROL), ClaimScope.FOCUSED, () -> {
            if (prompt.text().isEmpty()) {
                dismiss();
            }
        });
    }

    private void recall(int direction) {
        String line;
        synchronized (history) {
            if (history.isEmpty()) {
                return;
            }
            recall = Math.max(0, Math.min(history.size(), recall + direction));
            line = recall < history.size() ? history.get(recall) : "";
        }
        prompt.text(line);
        prompt.caret(line.length());
    }

    /**
     * What the session says when it opens.
     *
     * <p>The leading word of the first line goes to full beam. That is how the default greeting reads as a
     * heading without anything having to mark it as one, and it holds for a greeting the host wrote instead.
     */
    private void greet(Path cwd) {
        List<String> lines = spec.greeting(cwd);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lead = i == 0 ? leadingWord(line) : 0;
            scrollback.post(line, lead == 0 ? List.of() : List.of(Span.foreground(0, lead, ansi.hot())));
        }
    }

    /** How many characters the first word of {@code line} runs to, or 0 if there is not one. */
    private static int leadingWord(String line) {
        int at = 0;
        while (at < line.length() && !Character.isWhitespace(line.charAt(at))) {
            at++;
        }
        return at;
    }

    private void interruptOrCopy() {
        if (shell != null && shell.busy()) {
            if (shell.interrupt()) {
                scrollback.post("interrupt sent -- it reaches a program being waited on, but MainFrame's own "
                        + "loops run to the end", List.of());
            }
            return;
        }
        String selected = selection();
        if (!selected.isEmpty()) {
            gui.clipboard().set(selected);   // Ctrl+C with nothing selected copies nothing; it does not empty it
        }
    }

    /**
     * What Copy would copy: whatever is selected on this window.
     *
     * <p>Two documents can hold a selection — the command line and the output pane — and only one of them ever
     * holds the caret, so "the focused one" is not the answer. The command line is asked first because it is
     * where the caret is and so where a selection was most recently made; the scrollback answers when it has not
     * been. Which means Ctrl+C copies the lines somebody just dragged across without their having to give the
     * pane focus it is deliberately not allowed to take.
     *
     * @return the selected text, or {@code ""} when nothing is selected anywhere
     */
    private String selection() {
        Document typed = prompt.document().value();
        if (typed.hasSelection()) {
            return typed.selectedText();
        }
        return scrollback.document().selectedText();
    }

    // ---- per frame -------------------------------------------------------------------

    /**
     * Frame loop, once per frame: publish the output that arrived since the last frame, refresh the three fields
     * that track the session, and service one thing a command asked to have done up here.
     *
     * <p>Every field is written only when its text actually changed — a display that rewrites a prop per frame
     * dirties the layout per frame.
     *
     * <p>One queued task per frame, not all of them. A task opens a window, and a window may bring a modal
     * dialog with it; running two in one frame would mean running the second one behind a dialog that has not
     * been answered. At sixty frames a second the wait is not a wait.
     */
    public void tick() {
        if (shell == null) {
            return;
        }
        scrollback.flush();
        panel.flush();
        set(location, fitted(where()), () -> shownLocation, s -> shownLocation = s);
        set(badge, spec.badge(), () -> shownBadge, s -> shownBadge = s);
        set(clock, stamp(), () -> shownClock, s -> shownClock = s);
        messageLine();
        Runnable task = pending.poll();
        if (task != null) {
            task.run();
        }
        // The apps last, and after the queue: a launch serviced above opens its window on this frame, so the
        // app that owns it gets its first tick on the same frame rather than one later.
        for (ConsoleApp app : spec.apps()) {
            app.tick();
        }
    }

    /**
     * The message line, and the one place a hue would have earned its keep. A monochrome tube cannot draw a red
     * error, so this does what the machine it is imitating did: turns the line over — the fill becomes the
     * phosphor and the text becomes unlit glass — which is louder than any colour and needs none.
     *
     * <p>Nothing here picks the two colours. {@code Role.DANGER} is the fill and {@code Role.ON_DANGER} is
     * whichever of the palette's extremes lies further from it, which in a monochrome palette resolves to the
     * page. Reverse video falls out of the role rather than being spelled.
     */
    private void messageLine() {
        String error = shell.lastError();
        boolean failed = !error.isEmpty();
        String text = failed ? error.toUpperCase(Locale.ROOT) : statusText();
        if (text.equals(shownMessage) && failed == shownError) {
            return;
        }
        shownMessage = text;
        shownError = failed;
        Theme theme = gui.theme();
        message.text(text)
                .background(theme.color(failed ? Role.DANGER : Role.NONE))
                .textColor(theme.color(failed ? Role.ON_DANGER : Role.DIM));
    }

    /**
     * This window as MainFrame's display, for the shell to hand to the session.
     *
     * <p>Package-private on purpose: an application plugged into this console gets {@link ConsoleContext#form},
     * which states a {@link Form} and has no screen in it. The panel is how that question gets asked, not
     * something an app is asked to drive.
     */
    Panel panel() {
        return panel;
    }

    /**
     * Raise or lower the curtain on the rest of the window.
     *
     * <p>The scrollback and the command line go away together while a screen is up, because a 3270 screen was the
     * whole display and because a command line under a screen is a second place the caret could be. What is left
     * is the header, the screen, and the message line — which is the arrangement this window already had, with
     * one thing swapped for another in the same slot.
     */
    private void curtain(boolean up) {
        output.visible(!up);
        commandRow.visible(!up);
        if (!up) {
            gui.focus(prompt.node());
        }
    }

    /** Write {@code text} onto {@code node} only if it is not already what the node says. */
    private static void set(Node node, String text, Supplier<String> shown, Consumer<String> remember) {
        if (!text.equals(shown.get())) {
            remember.accept(text);
            node.text(text);
        }
    }

    /**
     * The date and time, or {@link #UNSET} while no window has been opened.
     *
     * <p>Not a flourish: the headless capture renders this tree without ever creating a window, and a capture
     * that differs every run is a capture you cannot diff against the last one to see what a change did. Gating
     * the clock on a window makes the PNG a function of the screen alone — and it is the truth besides, since a
     * display with no session on it has no session time to show.
     */
    private String stamp() {
        return host == null ? UNSET : STAMP.format(LocalDateTime.now());
    }

    // ---- the menu --------------------------------------------------------------------

    /**
     * The context menu: what the apps offer, then what the console itself does.
     *
     * <p>Built at the moment of the click, which is the point of a sink -- it lists what exists right now and
     * greys what does not apply rather than hiding it, so the menu teaches the same shape whatever the state.
     * And every line here runs a command, so nothing in it is a second implementation of anything: the menu is a
     * way of finding {@code launch} and {@code env}, not an alternative to them.
     */
    private void contextMenu(MenuSink menu) {
        if (shell == null) {
            // No session yet, so nothing on this menu would run. Reachable only if a host shows the window
            // before starting it, which is a mistake rather than a state to render for.
            return;
        }
        for (ConsoleApp app : spec.apps()) {
            menu.separator();
            app.menu(menu, this);
        }
        // Whatever has a window gets a line, without any app having to write one: the launcher is a list, so the
        // menu over it is a list too.
        boolean any = false;
        for (ConsoleApp app : spec.apps()) {
            if (app.launchable()) {
                if (!any) {
                    menu.separator();
                    any = true;
                }
                // The shortest line that opens it, so the menu teaches what anyone would actually type.
                String line = shell.launchLine(app);
                menu.item("Open " + app.name(), () -> run(line));
            }
        }
        menu.separator();
        menu.item("List this console's apps", () -> run("apps"));
        menu.item("Show the environment", () -> run("env"));
    }

    // ---- the header ------------------------------------------------------------------

    /**
     * {@code path} shortened to what the header can actually show, keeping the end of it.
     *
     * <p>The header is one row of a fixed height, and a text node that needs two lines does not get clipped to
     * its box -- it draws the second line straight through the rule underneath. So the field is measured rather
     * than guessed at: the layout says where the first visual line ended, which is exactly how many characters
     * fit, and everything before that is dropped behind an ellipsis. The <em>end</em> of a working directory is
     * the part worth keeping, and the whole of it is on every echoed prompt line anyway.
     *
     * <p>The budget is thrown away whenever the field's width changes, so widening the window measures again
     * instead of holding on to an answer from when it was narrow. One frame late, like everything read from the
     * layout, and self-correcting because the next frame measures what this one decided.
     */
    private String fitted(String path) {
        var layout = location.layout();
        float width = layout.rect().w();
        if (width != locationWidth) {
            locationWidth = width;
            locationRoom = Integer.MAX_VALUE;
        }
        var metrics = layout.text();
        if (metrics != null && metrics.lines().size() > 1) {
            locationRoom = Math.max(12, metrics.lines().get(0).end() - 1);
        }
        if (path.length() <= locationRoom) {
            return path;
        }
        return "..." + path.substring(path.length() - Math.max(9, locationRoom - 3));
    }

    /** What an echoed command line is prefixed with — a shell prompt, because the echo is shell output. */
    private String promptText() {
        return where() + " > ";
    }

    /** The working directory as the field above the screen shows it: no arrow, because a field is not a prompt. */
    private String where() {
        if (shell == null) {
            return "";
        }
        Path cwd = shell.cwd();
        Path home = Path.of(System.getProperty("user.home"));
        return cwd.equals(home) ? "~"
                : cwd.startsWith(home) ? "~/" + home.relativize(cwd).toString().replace('\\', '/')
                : cwd.toString();
    }

    private String statusText() {
        ConsoleSpec.Status status = spec.status();
        if (shell.asking()) {
            return status.answering();
        }
        if (shell.busy()) {
            return status.running();
        }
        return status.ready();
    }

    // ---- lifecycle -------------------------------------------------------------------

    /**
     * The window exists, and its input is already attached and pumping — the framework did that from the factory
     * the host supplied. What is left is what only this window knows: which window its own title bar commands,
     * where it should be, and that the caret belongs in the prompt.
     */
    private void onCreated(NativeWindow created) {
        titleBar.controls(WindowControls.of(created));
        if (memory != null) {
            if (memory.maximized(spec.windowName())) {
                created.maximize();
            } else {
                memory.restoreBounds(spec.windowName(), created, spec.width(), spec.height());
            }
            memory.watch(spec.windowName(), created, gui);
        }
        gui.focus(panel.up() ? panel.node() : prompt.node());
    }

    /**
     * The window is gone, and <b>MainFrame is not</b>: the shell keeps running and the scrollback stays, so
     * reopening comes back to the same session — same working directory, same history, same output above the
     * prompt — rather than a fresh greeting.
     *
     * <p>That is the whole reason this is a named window rather than a popup. The tree was never the window's;
     * closing releases an OS window and its input backend, and nothing else.
     */
    private void onClosed() {
        if (memory != null) {
            // Stop reading placement off a window that is being destroyed; what was recorded last stands.
            memory.forget(spec.windowName());
        }
        // The window this bar commanded is gone; the tree outlives it and is shown again the next time the
        // console is asked for, so the buttons go back to commanding nothing until onCreated rebinds them.
        titleBar.controls(WindowControls.NONE);
    }

    /** Application shutdown: this is where MainFrame's job thread actually stops. */
    @Override
    public void close() {
        clicks.close();
        // Before the shell: a job thread parked on a screen has to be told the display went away, or the
        // shutdown waits on a person who is no longer being shown anything.
        panel.close();
        if (shell != null) {
            shell.close();
            shell = null;
        }
        prompt.close();
        scrollback.close();
    }

    /** One line of screen text in the tube's own face and size. Every label on this display goes through here. */
    private Node glyphs(String text, dev.vexelray.canvas.Color ink) {
        return gui.text(text)
                .height(Length.FILL)
                .font(1)
                .textSize(Length.rem(0.8125f))
                .textColor(ink)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }
}
