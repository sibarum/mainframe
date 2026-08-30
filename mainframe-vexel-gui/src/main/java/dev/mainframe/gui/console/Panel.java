package dev.mainframe.gui.console;

import dev.mainframe.panel.Editor;
import dev.mainframe.panel.Event;
import dev.mainframe.panel.Screen;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The tube, as something MainFrame can put a screen on.
 *
 * <p>This is the other half of {@code PROTOCOL.md} — the display MainFrame borrows because it cannot read an
 * arrow key. MainFrame describes a screen in character cells and waits; this paints it, lets somebody type, tab
 * and click about inside it, and sends back one event when something happens MainFrame has to decide about. What
 * a form is, what a valid answer is, what an error says: none of that is here, and none of it is allowed to be.
 * Adding a feature to MainFrame must never mean editing this file.
 *
 * <h2>Why it reads the wire form</h2>
 * {@link #show} is handed a {@link Screen} and immediately asks it for {@link Screen#message()}, then works from
 * the record. Reaching into the object would be easier and would prove nothing: an editor is on the far side of a
 * protocol, and one that reads a private API is one the protocol was never tested against. Everything below
 * parses cells, styles and part kinds out of a record, the same as an editor on the end of a pipe — so a screen
 * that renders here renders there, and an unknown part is skipped here for the same reason and in the same place.
 *
 * <h2>One text node per row</h2>
 * A character grid built out of widgets has to know how wide a character is, and the framework does not owe
 * anybody that. A grid built out of <em>rows of text in a monospaced face</em> needs nothing: a row is one
 * string, so its cells line up by construction, and colour is a {@link Span} over it rather than a node of its
 * own. Two dozen text nodes for a whole screen, which also keeps a screen well clear of the per-window vertex
 * budget.
 *
 * <p>The one thing that arrangement looks like it costs — knowing which cell was clicked — the framework already
 * pays for: a laid-out text node publishes the x of every character boundary, so a click resolves to a column by
 * lookup rather than by arithmetic. See {@link #clicked}.
 *
 * <h2>Which thread</h2>
 * {@link #show} runs on the shell's <b>job thread</b> and blocks there, which is the whole shape of the protocol
 * — MainFrame sends a screen and waits. Everything that touches the tree runs on the <b>frame loop</b>: the
 * screen crosses over as a record on a field, and {@link #flush} paints it at the top of the next frame. Typing
 * and the editing keys arrive through the ordered {@code *Ui} seams, so a burst of characters applies in the
 * order it was typed; a click arrives on a worker thread and is queued to the loop rather than acted on where it
 * lands.
 */
final class Panel implements Editor {

    /**
     * The three questions a chooser can be asked, as PROTOCOL.md writes them.
     *
     * <p>Checked rather than passed on, because what comes back off the wire decides which dialog opens: a word
     * this editor does not know would be a field offering a chooser that then had to guess which one. An offer
     * it cannot honour is no offer, and the field is still a field somebody can type a path into.
     */
    private static final Set<String> PICKS = Set.of("file", "folder", "save");

    /** What an empty cell of an entry shows, and what makes a field's extent visible when it holds nothing. */
    private static final char FILL = '_';

    /** What stands in a secret entry for each character typed. One per character, so the length still shows. */
    private static final char MASK = '*';

    /** How long a blocked {@link #show} waits before checking whether the window went away under it. */
    private static final long POLL_MS = 100;

    /**
     * How long the caret stays lit, and then unlit. Half a second each way, which is roughly what every terminal
     * has blinked at since they were made of glass.
     *
     * <p>It blinks because a block of reversed phosphor that never moves is indistinguishable from a full cell,
     * and because on a screen where several fields are all on view at once, "where does typing go" has to be
     * answerable from across the room. The blink is reset by typing, so it is solid while somebody is actually
     * using it and flashing only while they are not.
     */
    private static final long BLINK_NANOS = 500_000_000L;

    /**
     * What to assume the tube holds before anything on it has been measured.
     *
     * <p>Only the very first screen of a session sees this. A form asks {@link #hello} while being laid out, and
     * the first one asks before a single row has ever been laid out here — so this is the one answer that is a
     * guess, and a guess too narrow leaves a margin while one too wide runs off the glass. It is corrected within
     * a frame or two by the resize this editor then reports, and every screen after it is measured. See
     * {@link #measure} and {@link #resized}.
     */
    private static final int GUESS_COLS = 72;
    private static final int GUESS_ROWS = 24;

    // The styles, as codes into one switch. A name this list does not have is PLAIN -- rule three.
    private static final byte PLAIN = 0;
    private static final byte TITLE = 1;
    private static final byte LABEL = 2;
    private static final byte HINT = 3;
    private static final byte FRAME = 4;
    private static final byte STATUS = 5;
    private static final byte ERROR = 6;
    private static final byte ENTRY = 7;
    private static final byte ENTRY_FOCUS = 8;
    private static final byte ENTRY_LOCKED = 9;
    private static final byte ACTION = 10;
    /** A button with the caret on it. Not a style MainFrame sends — where focus is, is the editor's business. */
    private static final byte ACTION_FOCUS = 11;

    /**
     * What the pointer is over, which is the same beam a little brighter.
     *
     * <p>A role rather than a colour, so it resolves against whatever palette the console was themed with, and
     * a <em>surface</em> step rather than an ink one, because this is the ground brightening and not the text.
     *
     * <p>Four steps, which is further than it looks as though it should be. This palette's surface step is
     * deliberately short — a CRT has no cards or panels to separate, so the ladder it was built for is a shallow
     * one — and two steps of it, tried first, could not be seen at all in a capture. A highlight nobody can see is
     * not a highlight, and the number that makes it visible is the right number.
     */
    private static final Role GLOW = palette -> palette.surface(4);

    /** What the console does with the rest of its window while a screen is up. */
    interface Curtain {
        void panel(boolean up);
    }

    /**
     * A file chooser, as the one thing this editor cannot draw for itself.
     *
     * <p>An interface rather than a call, for two reasons. It keeps the native binding out of this file, which
     * is a display and should stay one; and it is what a machine with no native dialog says <em>nothing</em>
     * through — {@link Console} hands over null there, {@link #hello} does not claim {@code pick}, and MainFrame
     * sends the chooser it draws itself. See {@link NativeChooser}.
     */
    interface Chooser {

        /**
         * Put a chooser up and wait for it.
         *
         * @param pick   the word MainFrame sent on the entry: {@code file}, {@code folder} or {@code save}
         * @param answer what the field holds now, which is where to open — never a rule about what may be chosen
         * @return the path chosen, or null when they backed out
         */
        Path choose(String pick, String answer);
    }

    /** An entry, a choice or an action: the parts somebody can reach. Everything else is paint. */
    private record Spot(String kind, String name, int row, int col, int width, boolean locked,
                        List<String> of, String pick, boolean secret) {

        /**
         * Whether this is a field MainFrame offered a chooser for.
         *
         * <p>Locked as well as unclaimed: a field nobody may type into is not one a chooser may write into
         * either, the protocol being explicit that a locked entry is shown and not edited.
         */
        boolean browsable() {
            return typable() && pick != null;
        }

        boolean typable() {
            return kind.equals("entry") && !locked;
        }

        boolean pressable() {
            return kind.equals("action");
        }

        /**
         * Whether Tab stops here.
         *
         * <p>Buttons included, which is the whole of "everything can be done without a mouse": a screen whose
         * only way to submit is a button nobody can reach by keyboard is a screen that needs a mouse. Tab
         * <em>lands</em> on one and nothing more — pressing is Enter's job, and a Tab that pressed what it landed
         * on would make walking a screen dangerous.
         */
        boolean reachable() {
            return !locked;
        }

        boolean covers(int r, int c) {
            return r == row && c >= col && c < col + width;
        }
    }

    /** One screen as it stands on the glass, with whatever has been typed into it. */
    private static final class Live {

        final int id;
        final int rows;
        final int cols;
        final char[][] cell;
        final byte[][] style;
        /** Screen order, which is also the tab order. */
        final List<Spot> spots = new ArrayList<>();
        final SequencedMap<String, String> values = new LinkedHashMap<>();
        /** What the screen said each key does, so the editor never decides what a key means. */
        final SequencedMap<String, String> keys = new LinkedHashMap<>();

        String focus;
        int caret;

        Live(int id, int rows, int cols) {
            this.id = id;
            this.rows = rows;
            this.cols = cols;
            this.cell = new char[rows][cols];
            this.style = new byte[rows][cols];
            for (char[] row : cell) {
                Arrays.fill(row, ' ');
            }
        }

        Spot spot(String name) {
            for (Spot spot : spots) {
                if (spot.name().equals(name)) {
                    return spot;
                }
            }
            return null;
        }

        Spot at(int row, int col) {
            for (Spot spot : spots) {
                if (spot.covers(row, col)) {
                    return spot;
                }
            }
            return null;
        }

        String value(String name) {
            return values.getOrDefault(name, "");
        }
    }

    private final Gui gui;
    private final Node host;
    private final Ansi ansi;
    private final Scrollback scrollback;
    private final Curtain curtain;
    /** Whether the shell is still running the command that put a screen up. See {@link #flush}. */
    private final BooleanSupplier busy;
    /**
     * What opens when somebody asks to browse, or null on a machine that has no file dialog to open.
     *
     * <p>Null is not a missing part: it is the answer {@link #hello} gives, and MainFrame draws its own chooser
     * on hearing it. See {@link Chooser}.
     */
    private final Chooser chooser;

    /** Work from threads that are not the frame loop -- clicks, so far. Drained once per frame. */
    private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
    /** Where the job thread waits. One outstanding screen at a time, because the protocol is turn by turn. */
    private final ArrayBlockingQueue<Event> answers = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Subscription clicks;
    private final Subscription pointer;
    /** Where time comes from, so the caret's blink can be driven by a test rather than waited out. */
    private final LongSupplier clock;

    /** A screen the job thread has put up and the frame loop has not painted yet. */
    private volatile Value.Rec arriving;
    private volatile boolean waiting;
    private volatile boolean up;
    /** What the tube measured, once it has been. Read by {@link #hello} from whatever thread asks. */
    private volatile int rows = GUESS_ROWS;
    private volatile int cols = GUESS_COLS;

    // ---- frame loop only -----------------------------------------------------------------

    private Live live;
    private final List<Node> pool = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private boolean dirty;
    /** What the pointer is over, by name, or null. Never touches focus — see {@link #hoverAt}. */
    private String hover;
    /** Whether the caret is lit this half-second. */
    private boolean lit = true;
    /** Screens put up, ever. See {@link #painted}. */
    private int painted;
    /** The size the screen on the glass was laid out for, so a change of room can be reported once. */
    private int laidOut = GUESS_COLS;
    private int laidOutRows = GUESS_ROWS;
    private long litAt;

    Panel(Gui gui, Node host, Ansi ansi, Scrollback scrollback, Curtain curtain, BooleanSupplier busy,
          Chooser chooser) {
        this(gui, host, ansi, scrollback, curtain, busy, chooser, System::nanoTime);
    }

    Panel(Gui gui, Node host, Ansi ansi, Scrollback scrollback, Curtain curtain, BooleanSupplier busy,
          Chooser chooser, LongSupplier clock) {
        this.gui = gui;
        this.host = host;
        this.ansi = ansi;
        this.scrollback = scrollback;
        this.curtain = curtain;
        this.busy = busy;
        this.chooser = chooser;
        this.clock = clock;
        this.litAt = clock.getAsLong();

        // Typing and the editing keys on the ordered seams: each one is a state transition that depends on the
        // order the events arrived in, which is the case the *Ui lane exists for.
        gui.onCharUi(host, this::typed);
        gui.onKeyUi(host, this::pressed);
        gui.focusable(host, true);
        claims();
        // The click topic rather than a handler, for the reason the console's own focus-follows-click uses it: a
        // handler reaches the nearest ancestor that has one, and the topic sees every click whatever consumed it.
        this.clicks = gui.bus().subscribe(gui.clicks(),
                event -> queued.add(() -> clicked(event.x(), event.y())));
        // And the pointer itself, for the highlight. The raw input topic because there is no node-level seam that
        // would do: onState says a whole node is hovered, and every part of a screen shares its row with others.
        // These are the same coordinates the dispatcher hit-tests with, which are the ones a click reports.
        this.pointer = gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.PointerMoved moved) {
                queued.add(() -> hovered(moved.x(), moved.y()));
            }
        });
    }

    /**
     * The keys claimed for as long as the screen holds focus: the ones that can end a transaction, Ctrl+V and
     * Ctrl+O.
     *
     * <p>Claimed rather than handled: a claim is preemption declared in advance, so Tab moves between this
     * screen's fields instead of walking the window's focus ring, and Enter submits instead of reaching the
     * command line hidden behind the screen. What each key <em>does</em> is not decided here — the screen says,
     * in its own key list, and {@link #ended} looks it up.
     *
     * <p>The two control keys are the odd ones out, and are here because a claim is also how this editor gets a
     * key nothing else on the window would take: a field on a screen is painted characters rather than a widget,
     * so there is no text box underneath for either of them to fall through to.
     */
    private void claims() {
        gui.claimUi(host, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> move(+1));
        gui.claimUi(host, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> move(-1));
        gui.claimUi(host, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, this::entered);
        gui.claimUi(host, Shortcut.of(Key.SPACE), ClaimScope.FOCUSED, this::spaced);
        // Paste, which is not an ending at all and is claimed for the other reason: the fields on a screen here
        // are painted characters rather than widgets, so there is nothing underneath this that would know what
        // Ctrl+V meant. See pasted().
        gui.claimUi(host, Shortcut.of(Key.V, Modifier.CONTROL), ClaimScope.FOCUSED, this::pasted);
        // Browse, on the editor with a chooser and nowhere else. MainFrame stops drawing its Browse button the
        // moment this editor claims `pick`, so the way to the dialog is this editor's to provide -- and the key
        // line under the screen says so whenever the caret is on a field that has one.
        gui.claimUi(host, Shortcut.of(Key.O, Modifier.CONTROL), ClaimScope.FOCUSED, this::browsed);
        gui.claimUi(host, Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> ended("Esc"));
        for (Key key : List.of(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
                Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)) {
            gui.claimUi(host, Shortcut.of(key), ClaimScope.FOCUSED, () -> ended(key.name()));
        }
    }

    // ---- what MainFrame asks of an editor -------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>The size is measured rather than assumed, and asked for again on every screen — so a form gets laid out
     * for the window somebody actually has. {@code resize} is claimed because this editor really does report one:
     * see {@link #resized}. Between reporting it and the new screen arriving there is a frame or two where the old
     * screen is the wrong size for its room, which is what the horizontal scroll is for; the protocol is explicit
     * that an editor with too little room scrolls rather than reflowing, MainFrame having placed those cells
     * deliberately.
     *
     * <p><b>{@code choice} is not offered either, and that one is a judgement.</b> This editor can render a
     * choice — {@link #cell} does, and a screen that sends one anyway comes out right — but it cannot do it
     * <em>well</em>, because a dropdown is a thing that opens and a tube has nowhere for it to open into. All the
     * cells MainFrame reserves for a choice are the width of its longest option, which is room for the value and
     * not for the list, so an unanswered one would be an empty field with two marks beside it and no way to find
     * out what belongs in it. Left unclaimed, MainFrame writes the options above it as text and sends a plain
     * entry, which is both readable and what a 5250 actually did with a choice. Rule four earning its keep: the
     * better screen here is the one this editor asked for less of.
     *
     * <p><b>{@code pick} is claimed only where there is a dialog to open</b> — which is a decision made per
     * machine rather than per editor. The binding ships a native library for Windows and macOS; anywhere else
     * {@link Console} hands over no {@link Chooser}, this says nothing about {@code pick}, and MainFrame draws
     * the chooser itself out of text, an entry and things to click. The same form works both ways round, and
     * the machine without a dialog is not missing a feature — it is getting the other implementation of one.
     * That is the whole promise: a form grew a file chooser, and the fallback was written before the dialog was.
     */
    @Override
    public Hello hello() {
        LinkedHashSet<String> can =
                new LinkedHashSet<>(List.of("text", "entry", "action", "box", "click", "resize"));
        if (chooser != null) {
            can.add("pick");
        }
        // Unconditional, unlike pick: masking needs nothing from the host, only that this editor promises to
        // show dots and to hold the value back until submit. Both are here, so the claim is honest.
        can.add(Screen.SECRET);
        return new Hello("mainframe-vexel-gui", 1, rows, cols, can);
    }

    /**
     * Paint a screen and wait for somebody to do something to it. <b>Job thread only</b> — it blocks, and the
     * frame loop is what unblocks it.
     *
     * <p>A window that has gone away answers {@link Event#gone()}, which MainFrame reads as a cancel and never as
     * an error. So does an interrupt: Ctrl+C at a screen is somebody giving up on it.
     */
    @Override
    public Event show(Screen screen) {
        return show(screen.message());
    }

    /**
     * The same, from the message itself: the record is what an editor is actually handed, and going through it
     * here is what makes the claim above structural rather than a comment. Also the seam a test uses to hand this
     * editor a part no editor has ever heard of.
     */
    Event show(Value.Rec message) {
        if (closed.get()) {
            return Event.gone();
        }
        answers.clear();
        waiting = true;
        arriving = message;
        try {
            while (!closed.get()) {
                Event answer = answers.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    return answer;
                }
            }
            return Event.gone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Event.gone();
        } finally {
            waiting = false;
        }
    }

    /** Ordinary output while a session is borrowing this display: the scrollback, where output already goes. */
    @Override
    public void print(String text, String style) {
        scrollback.post(text == null ? "" : text);
    }

    // ---- what the console asks of it ------------------------------------------------------

    /** Whether a screen is on the glass right now. The console routes its clicks and its curtain on this. */
    boolean up() {
        return up;
    }

    /**
     * Whether the screen on the glass is the screen this room deserves.
     *
     * <p>False for a frame or two after one goes up, because the first screen of a session is laid out for a
     * guess at the size and corrected once there is something to measure. Anything that wants to look at a
     * finished screen rather than at a screen mid-correction waits for this.
     */
    boolean settled() {
        return up && arriving == null && cols == laidOut && rows == laidOutRows;
    }

    /** The node a screen is painted into, so the console can put the caret back on it. */
    Node node() {
        return host;
    }

    /**
     * The id of the screen on the glass, or -1 when there is none.
     *
     * <p>MainFrame stamps every screen with one so a late event can be discarded. Minus one for "none" because
     * <em>zero is a real id</em> — a confirmation is screen 0, being the only screen in its conversation — and a
     * reader that took 0 for absence would wait forever for one to arrive.
     */
    int showing() {
        Live current = live;
        return current == null ? -1 : current.id;
    }

    /**
     * How many screens this display has put up since it was made.
     *
     * <p>What tells one screen from the next when their ids cannot: two confirmations in a row are both screen 0,
     * and a form's second screen is a new id only because that form happens to number them. This counts paints,
     * which is the question actually being asked — has the next one arrived yet.
     */
    int painted() {
        return painted;
    }

    /**
     * What is on the glass, a row per line, exactly as it was painted.
     *
     * <p>A screen is a record and its rendering is text, which is what makes a panel testable at all: the
     * question "did the error land on the screen" has an answer that is a string comparison rather than a
     * screenshot.
     */
    List<String> lines() {
        return List.copyOf(shown);
    }

    /** Which part has the caret, by name, or null. */
    String focused() {
        Live current = live;
        return current == null ? null : current.focus;
    }

    /** Which part the pointer is over, by name, or null. Never the same question as {@link #focused}. */
    String hovered() {
        return hover;
    }

    /** Whether the caret is lit this frame — the other half of a second it is not. */
    boolean caret() {
        Live current = live;
        Spot spot = current == null ? null : current.spot(current.focus);
        return lit && spot != null && spot.typable();
    }

    /**
     * Frame loop, once per frame: run the queued work, put up a screen that has arrived, and repaint if anything
     * moved.
     *
     * <p>The screen comes <em>down</em> when the command that was asking finishes, not when an event is sent. A
     * click on a screen is answered and then followed by another screen a moment later — a form redrawing itself
     * around an entry that was just added — and taking the panel down in between would flick the scrollback into
     * view for two frames. So the rule is the one fact that actually says the asking is over: the job thread has
     * stopped running the command.
     */
    void flush() {
        Runnable task;
        while ((task = queued.poll()) != null) {
            task.run();
        }
        Value.Rec message = arriving;
        if (message != null) {
            arriving = null;
            raise(message);
        }
        if (up && !waiting && arriving == null && !busy.getAsBoolean()) {
            lower();
        }
        measure();
        resized();
        blink();
        if (dirty) {
            repaint();
        }
    }

    /**
     * Tell MainFrame the room changed, so the next screen is laid out for the room there is.
     *
     * <p>This is the whole answer to a window being dragged, and it is the protocol's answer rather than one this
     * editor invented: a screen's cells were placed deliberately, so an editor must never reflow one — it reports
     * that the size changed and is sent another. What comes back is laid out for the width just reported, and
     * because a resize event carries every entry on the screen exactly as any other event does, nothing that had
     * been typed is lost in the exchange.
     *
     * <p>Only while something is waiting to be told. A resize nobody asked about would be an event with no screen
     * to be about, and the first thing MainFrame does with one is ask which screen it belongs to.
     */
    private void resized() {
        if (!up || !waiting || live == null || (cols == laidOut && rows == laidOutRows)) {
            return;
        }
        laidOut = cols;
        laidOutRows = rows;
        answer(Event.RESIZE, null, null);
    }

    /**
     * Flip the caret if its half-second is up.
     *
     * <p>Only while there is a caret to flip, so a screen of nothing but buttons costs no repaints at all — and
     * the flip is what sets {@code dirty}, so the cost of blinking is two repaints a second rather than one a
     * frame. A repaint writes text only where the text changed, so what a flip actually moves is a span.
     */
    private void blink() {
        if (!up || live == null || !hasCaret()) {
            return;
        }
        long now = clock.getAsLong();
        if (now - litAt >= BLINK_NANOS) {
            lit = !lit;
            litAt = now;
            dirty = true;
        }
    }

    /** Show the caret solid from now: what typing, moving and clicking all do, so it is never mid-blink in use. */
    private void relight() {
        lit = true;
        litAt = clock.getAsLong();
    }

    private boolean hasCaret() {
        Live current = live;
        Spot spot = current == null ? null : current.spot(current.focus);
        return spot != null && spot.typable();
    }

    /** Unblock whoever is waiting on a screen, because the window is going away. A closed panel cancels. */
    void close() {
        if (closed.compareAndSet(false, true)) {
            clicks.close();
            pointer.close();
            answers.offer(Event.gone());
        }
    }

    // ---- putting a screen up and taking it down -------------------------------------------

    private void raise(Value.Rec message) {
        Live parsed = parse(message);
        if (parsed == null) {
            // Not a screen. An editor that cannot make sense of a message says nothing about it and answers all
            // the same, so MainFrame is never left waiting on a display that has given up.
            waiting = false;
            answers.offer(Event.gone());
            return;
        }
        live = parsed;
        painted++;
        laidOut = cols;
        laidOutRows = rows;
        dirty = true;
        hover = null;                                   // a new screen is not hovered until the pointer says so
        relight();
        if (!up) {
            up = true;
            host.visible(true);
            curtain.panel(true);
        }
        gui.focus(host);
    }

    private void lower() {
        up = false;
        live = null;
        hover = null;
        host.visible(false);
        curtain.panel(false);
    }

    /** Send one event, and stop waiting for anything more about this screen. */
    private void answer(String did, String key, String on) {
        Live current = live;
        if (current == null) {
            return;
        }
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        for (Spot spot : current.spots) {
            if (spot.kind().equals("action")) {
                continue;
            }
            // The one exception to "fields carries every entry", and the reason this editor claims `secret`: a
            // key goes up once, on the submit that ends the screen, and is absent from every change, click and
            // resize before it. Absent rather than blanked -- an empty string here would read as "they cleared
            // it", which is a different thing from "this is not that kind of event".
            if (spot.secret() && !Event.SUBMIT.equals(did)) {
                continue;
            }
            fields.put(spot.name(), new Value.Str(current.value(spot.name())));
        }
        SequencedMap<String, Value> event = new LinkedHashMap<>();
        event.put("screen", new Value.Int(current.id));
        event.put("did", new Value.Str(did));
        if (key != null) {
            event.put("key", new Value.Str(key));
        }
        if (on != null) {
            event.put("on", new Value.Str(on));
        }
        if (current.focus != null) {
            event.put("focus", new Value.Str(current.focus));
        }
        event.put("fields", new Value.Rec(fields));
        // Through the reader rather than straight to the constructor: this is the message this editor would put
        // on a wire, and reading its own message back is what keeps it a message rather than a shortcut.
        waiting = false;
        answers.offer(Event.read(Value.Rec.of("event", new Value.Rec(event))));
    }

    // ---- reading a screen -----------------------------------------------------------------

    /**
     * A screen record, as cells and reachable spots. Anything unrecognised is skipped and its cells left blank,
     * which is rule two: a screen with one part this editor has never heard of is still mostly a screen.
     */
    private Live parse(Value.Rec message) {
        if (!(message.get("screen") instanceof Value.Rec screen)) {
            return null;
        }
        Value.Rec size = screen.get("size") instanceof Value.Rec given ? given : Value.Rec.of();
        List<Value> parts = screen.get("parts") instanceof Value.ListVal list ? list.items() : List.of();
        // What the screen asked for, or what its parts need -- whichever is larger. A screen is never clipped by
        // its own declared size, because that size is what MainFrame laid out for rather than a promise.
        int high = (int) whole(size, "rows", 0);
        int wide = (int) whole(size, "cols", 0);
        for (Value part : parts) {
            if (part instanceof Value.Rec p) {
                int[] at = at(p);
                if (at != null) {
                    high = Math.max(high, at[0] + span(p, 0));
                    wide = Math.max(wide, at[1] + span(p, 1) + text(p, "text").length() + width(p));
                }
            }
        }
        Live parsed = new Live(screen.get("id") instanceof Value.Int id ? (int) id.value() : 0,
                Math.max(1, high), Math.max(1, wide));
        if (screen.get("focus") instanceof Value.Str focus) {
            parsed.focus = focus.value();
        }
        if (screen.get("keys") instanceof Value.ListVal keys) {
            for (Value key : keys.items()) {
                if (key instanceof Value.Rec k && k.get("key") instanceof Value.Str name) {
                    parsed.keys.put(name.value(), text(k, "does"));
                }
            }
        }
        for (Value part : parts) {
            if (part instanceof Value.Rec p) {
                paint(parsed, p);
            }
        }
        // A focus the screen did not name, or named at a part that cannot hold it, lands on the first thing there
        // is to type in — and only on a button if there is nothing to type in at all. A screen opening with its
        // caret on a button is a screen one keystroke away from having pressed it, which is the opposite of what a
        // form wants; a screen that is only buttons had better say which one it means, and a confirmation does.
        Spot asked = parsed.spot(parsed.focus);
        if (asked == null || !asked.reachable()) {
            parsed.focus = null;
            for (Spot spot : parsed.spots) {
                if (spot.reachable() && !spot.pressable()) {
                    parsed.focus = spot.name();
                    break;
                }
            }
            for (Spot spot : parsed.spots) {
                if (parsed.focus == null && spot.reachable()) {
                    parsed.focus = spot.name();
                }
            }
        }
        parsed.caret = parsed.focus == null ? 0 : parsed.value(parsed.focus).length();
        return parsed;
    }

    /** One part onto the cells. The kind key names the part, so it is read before anything else on it. */
    private void paint(Live screen, Value.Rec part) {
        int[] at = at(part);
        if (at == null) {
            return;
        }
        int row = at[0] - 1;
        int col = at[1] - 1;
        byte style = style(text(part, "style"));
        if (part.get("entry") instanceof Value.Str name) {
            boolean locked = part.get("locked") instanceof Value.Bool flag && flag.value();
            boolean secret = part.get("secret") instanceof Value.Bool masked && masked.value();
            // A secret arrives with no value key at all, so this starts empty and stays that way until somebody
            // types into it. Nothing to unpack, which is the point of MainFrame not sending one.
            screen.values.put(name.value(), secret ? "" : text(part, "value"));
            // pick is only ever sent to an editor that claimed it, so an entry carrying one is an entry this
            // editor said it would offer a chooser for. A word nobody here knows is no offer rather than an
            // error -- rule three, the same as an unknown style or an unknown part.
            String pick = text(part, "pick");
            screen.spots.add(new Spot("entry", name.value(), row, col, Math.max(1, width(part)),
                    locked, List.of(), PICKS.contains(pick) ? pick : null, secret));
            return;                                     // its cells are written at repaint, from the value
        }
        if (part.get("choice") instanceof Value.Str name) {
            List<String> of = new ArrayList<>();
            if (part.get("of") instanceof Value.ListVal options) {
                for (Value option : options.items()) {
                    of.add(Values.display(option));
                }
            }
            // The value, a space, and the two marks that say it cycles.
            screen.values.put(name.value(), text(part, "value"));
            screen.spots.add(new Spot("choice", name.value(), row, col, widest(of) + 3, false,
                    List.copyOf(of), null, false));
            return;
        }
        if (part.get("action") instanceof Value.Str name) {
            String label = text(part, "text");
            write(screen, row, col, label, style == PLAIN ? ACTION : style);
            screen.spots.add(new Spot("action", name.value(), row, col, Math.max(1, label.length()),
                    false, List.of(), null, false));
            return;
        }
        if (part.get("box") instanceof Value.ListVal dims && dims.items().size() >= 2) {
            box(screen, row, col, (int) whole(dims.items().get(0)), (int) whole(dims.items().get(1)),
                    text(part, "text"), style == PLAIN ? FRAME : style);
            return;
        }
        if (part.get("text") instanceof Value.Str body) {
            write(screen, row, col, body.value(), style);
        }
        // Anything else: skipped, its cells left blank. Never an error.
    }

    /**
     * A frame, in the characters a monochrome face is certain to have.
     *
     * <p>ASCII rather than the box-drawing block, and not out of nostalgia: this window's face is an atlas built
     * for text, and a glyph it does not carry comes out as nothing at all. A frame is too load-bearing to lose to
     * a missing glyph, and {@code +---+} is what the machines being imitated actually drew.
     */
    private void box(Live screen, int row, int col, int high, int wide, String caption, byte style) {
        if (high < 2 || wide < 2) {
            return;
        }
        String edge = "+" + "-".repeat(wide - 2) + "+";
        write(screen, row, col, edge, style);
        write(screen, row + high - 1, col, edge, style);
        for (int r = row + 1; r < row + high - 1; r++) {
            write(screen, r, col, "|", style);
            write(screen, r, col + wide - 1, "|", style);
        }
        if (!caption.isEmpty()) {
            write(screen, row, col + 2, " " + caption + " ", style);
        }
    }

    private void write(Live screen, int row, int col, String text, byte style) {
        if (row < 0 || row >= screen.rows) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            int c = col + i;
            if (c < 0 || c >= screen.cols) {
                continue;
            }
            screen.cell[row][c] = text.charAt(i);
            screen.style[row][c] = style;
        }
    }

    // ---- painting it ----------------------------------------------------------------------

    private void repaint() {
        Live current = live;
        dirty = false;
        if (current == null) {
            return;
        }
        gui.batch(() -> {
            for (int r = 0; r < current.rows; r++) {
                String text = line(current, r);
                Node node = row(r);
                if (!text.equals(shown.get(r))) {
                    node.text(text);
                    shown.set(r, text);
                }
                node.spans(spans(current, r, text.length()));
            }
            // The editor's own row, under the screen and one clear row away from it. Not part of the screen and
            // not MainFrame's to place: what the keys do here is this editor's arrangement, so saying so is its
            // job. The room is its own — an editor with more rows than the screen asked for may do as it likes
            // with the rest, and this is what the rest is for.
            int hint = current.rows + 1;
            write(hint, keyHint(current), STATUS);
            for (int r = current.rows; r < pool.size(); r++) {
                // Rows nothing uses: emptied rather than hidden, because a text node with nothing in it measures
                // to no height and so takes none.
                if (r != hint && !shown.get(r).isEmpty()) {
                    pool.get(r).text("").spans(List.of());
                    shown.set(r, "");
                }
            }
        });
    }

    /** One row of the editor's own text, at whatever styling it asked for, outside the screen's cells. */
    private void write(int index, String text, byte style) {
        Node node = row(index);
        if (!text.equals(shown.get(index))) {
            node.text(text);
            shown.set(index, text);
        }
        node.spans(text.isEmpty() ? List.of() : List.of(span(style, 0, text.length())));
    }

    /**
     * What the keys do, right now, on this screen.
     *
     * <p>It follows the caret rather than standing still, because the honest answer changes: Enter on a field
     * goes to the next one and Enter on a button presses it, and a line that said both at once would be teaching
     * the reader to work out which applies. What a screen is for should not have to be inferred, and the cheapest
     * way to make an interface self-explanatory is to have it say what it will do next.
     *
     * <p>Which is also where Ctrl+O gets said. A field MainFrame offered a chooser for looks like every other
     * field on the glass, and the button that used to say otherwise is not sent to an editor that claimed
     * {@code pick} -- so without this line there would be a dialog behind a key nobody had been told about.
     */
    private String keyHint(Live screen) {
        Spot focused = screen.spot(screen.focus);
        String enter = focused != null && focused.pressable()
                ? "press " + label(screen, focused)
                : next(screen) == null ? "submit" : "next field";
        String line = "Tab  move    Enter  " + enter + "    Esc  cancel";
        return focused != null && focused.browsable() && chooser != null
                ? line + "    Ctrl+O  choose" : line;
    }

    /** What a button says on it, for talking about it in the hint line. */
    private String label(Live screen, Spot spot) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < spot.width(); i++) {
            int c = spot.col() + i;
            if (spot.row() >= 0 && spot.row() < screen.rows && c >= 0 && c < screen.cols) {
                text.append(screen.cell[spot.row()][c]);
            }
        }
        return text.toString().trim();
    }

    /**
     * One row as a string: the cells, with whatever has been typed into a field on this row written over them.
     *
     * <p>Trailing blanks come off. Nothing that means anything is a trailing blank — an entry pads with
     * {@link #FILL} exactly so its extent survives this — and a row that keeps them is a row whose measured
     * width depends on how a text layout treats space at the end of a line.
     */
    private String line(Live screen, int row) {
        char[] cells = screen.cell[row].clone();
        for (Spot spot : screen.spots) {
            if (spot.row() != row || spot.kind().equals("action")) {
                continue;
            }
            String value = screen.value(spot.name());
            for (int i = 0; i < spot.width(); i++) {
                int c = spot.col() + i;
                if (c < 0 || c >= screen.cols) {
                    continue;
                }
                cells[c] = cell(spot, value, i);
            }
        }
        int end = cells.length;
        while (end > 0 && cells[end - 1] == ' ') {
            end--;
        }
        return new String(cells, 0, end);
    }

    /**
     * One cell of a field: what has been typed, and what stands where nothing has been.
     *
     * <p>An entry pads with {@link #FILL}, which is what a 5250 drew and what keeps an empty field visible. A
     * choice cannot pad the same way — underscores would say "type here" about a field that only cycles — so it
     * pads with blanks and puts its two marks at the far edge, where they say where the field ends.
     */
    private static char cell(Spot spot, String value, int i) {
        if (i < value.length()) {
            // A secret is the one field whose characters are not its own. Masked here rather than at the value,
            // because the value is what gets sent on submit and what the caret moves through -- a field that
            // stored dots would submit dots.
            return spot.secret() ? MASK : value.charAt(i);
        }
        if (spot.kind().equals("choice")) {
            return i == spot.width() - 2 ? '<' : i == spot.width() - 1 ? '>' : ' ';
        }
        return spot.locked() ? ' ' : FILL;
    }

    /**
     * One row's colour, as spans over its characters.
     *
     * <p>Three things layer here and the order they are added is the order they outrank each other, because the
     * renderer takes the last span covering a character: the pointer's highlight goes on first, then what each
     * part is wearing, then the caret. So a button that is both focused and hovered shows the stronger of the two
     * rather than the more recent, which is the same priority the framework's own interaction states use.
     */
    private List<Span> spans(Live screen, int row, int length) {
        List<Span> spans = new ArrayList<>();
        // What the pointer is over. A background, so it says what a click would land on without saying anything
        // about where the caret is -- which is the whole distinction: hovering is a question, clicking is an
        // answer. A button is underlined as well, because a lit rectangle says "something is here" and an
        // underline says "this can be pressed"; a field needs no such help, being drawn out of underscores
        // already, and an underline under those would be an underline nobody could see.
        Spot under = screen.spot(hover);
        if (under != null && under.row() == row) {
            int from = Math.max(0, under.col());
            int to = Math.min(length, under.col() + under.width());
            if (to > from) {
                spans.add(new Span(from, to, null, gui.theme().color(GLOW), under.pressable()));
            }
        }
        byte[] styles = screen.style[row].clone();
        for (Spot spot : screen.spots) {
            if (spot.row() != row) {
                continue;
            }
            byte style;
            if (spot.pressable()) {
                if (!spot.name().equals(screen.focus)) {
                    continue;                           // an unfocused button keeps the style the screen gave it
                }
                style = ACTION_FOCUS;
            } else {
                style = spot.locked() ? ENTRY_LOCKED
                        : spot.name().equals(screen.focus) ? ENTRY_FOCUS : ENTRY;
            }
            for (int i = 0; i < spot.width(); i++) {
                int c = spot.col() + i;
                if (c >= 0 && c < screen.cols) {
                    styles[c] = style;
                }
            }
        }
        int at = 0;
        while (at < length) {
            byte style = styles[at];
            int end = at;
            while (end < length && styles[end] == style) {
                end++;
            }
            if (style != PLAIN) {
                spans.add(span(style, at, end));
            }
            at = end;
        }
        // The caret last, so it lies over whatever the field is wearing -- and only while it is lit, which is what
        // makes it blink. Reverse video rather than a bar: this is one colour of phosphor, and turning a cell over
        // is the only emphasis a tube has.
        Spot focused = screen.spot(screen.focus);
        if (lit && focused != null && focused.row() == row && focused.typable()) {
            int c = focused.col() + Math.min(screen.caret, focused.width() - 1);
            if (c < length) {
                spans.add(new Span(c, c + 1, gui.theme().color(Role.PAGE), ansi.hot(), false));
            }
        }
        return spans;
    }

    /** A style is a name, and this is the only place that turns one into a colour. */
    private Span span(byte style, int start, int end) {
        Theme theme = gui.theme();
        return switch (style) {
            // An error is the one thing on the glass that has to be louder than bright, and a monochrome tube
            // cannot draw a red one. So it does what the message line does: turns the cells over.
            case ERROR -> new Span(start, end, theme.color(Role.ON_DANGER), theme.color(Role.DANGER), false);
            // A button with the caret on it is turned over, for the same reason and by the same means as the caret
            // in a field: on one colour of phosphor, reversal is what "this one" looks like. It also makes the
            // button and the caret the same idea, which is true -- both are where the next keystroke goes.
            case ACTION_FOCUS -> new Span(start, end, theme.color(Role.PAGE), ansi.hot(), false);
            case TITLE, ACTION, ENTRY_FOCUS -> Span.foreground(start, end, ansi.hot());
            case HINT, FRAME, STATUS, ENTRY_LOCKED -> Span.foreground(start, end, theme.color(Role.DIM));
            default -> Span.foreground(start, end, ansi.normal());
        };
    }

    private Node row(int index) {
        while (pool.size() <= index) {
            Node node = gui.text("")
                    .width(Length.AUTO)
                    .font(1)
                    .textSize(Length.rem(0.8125f))
                    .textColor(ansi.normal())
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            host.append(node);
            pool.add(node);
            shown.add("");
        }
        return pool.get(index);
    }

    /**
     * What the tube actually holds, learned from the rows that have been laid out.
     *
     * <p>A row is auto-width over a monospaced face, so its box divided by its characters is the cell — no
     * estimate, and no assumption about the face. What it is for is {@link #hello}, which is asked afresh for
     * every screen: this is how a form comes to be laid out for the window somebody has rather than for the
     * window this file guessed at.
     *
     * <p>The <b>longest</b> row rather than the first one long enough, because the answer feeds a comparison. Any
     * row gives the same cell width to within float rounding, and the longest gives it with the least of that —
     * which matters because a measurement that wobbles by one column between frames would report a resize on every
     * frame, and each one costs a screen.
     *
     * <p>Called every frame rather than after a repaint. A repaint only happens when something moved, and the
     * window being dragged is precisely the case where nothing on the screen has.
     */
    private void measure() {
        NodeLayout frame = host.layout();
        if (!frame.present() || frame.viewW() <= 0 || frame.rect().h() <= 0) {
            return;
        }
        int longest = -1;
        for (int r = 0; r < pool.size(); r++) {
            if (shown.get(r).length() >= 4 && pool.get(r).layout().present()
                    && (longest < 0 || shown.get(r).length() > shown.get(longest).length())) {
                longest = r;
            }
        }
        NodeLayout row = longest < 0 ? null : pool.get(longest).layout();
        if (row == null || row.text() == null || row.text().lines().isEmpty()) {
            return;
        }
        // The caret positions, not the node's box. A row is a text node and its box is as wide as the column it
        // sits in, so box-divided-by-characters measures the container rather than the face -- which was this
        // file's first mistake at it, and a circular one, because it made the width of the tube a function of the
        // width of the screen already on it. What a laid-out text node does publish is the x of every character
        // boundary, and the distance from the first to the last of those, over the characters between, is the cell.
        TextMetrics.VisualLine line = row.text().lines().getFirst();
        float[] xs = line.xs();
        if (xs.length < 2 || line.height() <= 0) {
            return;
        }
        float cell = (xs[xs.length - 1] - xs[0]) / (xs.length - 1);
        if (cell > 0) {
            cols = Math.max(20, (int) (frame.viewW() / cell));
            rows = Math.max(4, (int) (frame.rect().h() / line.height()));
        }
    }

    // ---- what a person does to it ---------------------------------------------------------

    /** A typed character goes into the focused field, or nowhere. The editor validates nothing — rule six. */
    void typed(int codepoint) {
        Live current = live;
        if (current == null || codepoint < 0x20 || codepoint == 0x7F) {
            return;
        }
        Spot spot = current.spot(current.focus);
        if (spot == null || !spot.typable()) {
            return;
        }
        String value = current.value(spot.name());
        if (value.length() >= spot.width()) {
            return;                                     // the field is full; a 3270 beeped, this declines
        }
        int caret = Math.min(current.caret, value.length());
        current.values.put(spot.name(),
                value.substring(0, caret) + Character.toString(codepoint) + value.substring(caret));
        current.caret = caret + 1;
        relight();
        dirty = true;
    }

    /**
     * Ctrl+V: whatever is on the clipboard, into the focused field at the caret.
     *
     * <p>Written here rather than got for free, because the fields on this screen are not widgets — a row is a
     * string of characters and an entry is a stretch of it, so there is nothing holding text that a framework
     * could paste into. It goes in as if it had been typed: same fields refuse it, same width stops it, and a
     * field it will not all fit into takes as much of it as there is room for rather than nothing at all, which
     * is what a long path into a short field wants.
     *
     * <p>What arrives is cleaned to what could have been typed. A field is one line, so a paste stops at the
     * first line break rather than running the lines together — two lines squashed into one would put something
     * in the field that is not on the clipboard and is not what anybody typed either, and it would be past the
     * caret before it could be looked at. Everything up to that break is kept, minus the control characters
     * {@link #typed} would have refused one at a time.
     *
     * <p>The clipboard is the window's, which the host points at the OS one. On a desk where it did not, this
     * pastes what was copied inside MainFrame and nothing from outside — which is the arrangement failing
     * quietly rather than this doing something different.
     */
    void pasted() {
        Live current = live;
        if (current == null) {
            return;
        }
        Spot spot = current.spot(current.focus);
        if (spot == null || !spot.typable()) {
            return;
        }
        String value = current.value(spot.name());
        int room = spot.width() - value.length();
        if (room <= 0) {
            return;                                     // the field is full, as it is for a typed character
        }
        String text = fit(pastable(gui.clipboard().get()), room);
        if (text.isEmpty()) {
            return;
        }
        int caret = Math.min(current.caret, value.length());
        current.values.put(spot.name(), value.substring(0, caret) + text + value.substring(caret));
        current.caret = caret + text.length();
        relight();
        dirty = true;
    }

    /**
     * Ctrl+O: the file dialog this editor claimed, over the window it is in.
     *
     * <p>The way to it, on this display, and the only one — MainFrame stops drawing its own Browse button the
     * moment an editor claims {@code pick}, because two ways to browse one field is one too many. So the key
     * line under the screen names this key whenever the caret is on a field that has an offer, which is the same
     * rule the rest of that line follows: it says what can be done <em>here</em>, and never what cannot.
     *
     * <p>It blocks the frame loop, which is what a modal dialog is. The OS window on top draws itself and the
     * console underneath is a still picture until it is answered. Nothing else in MainFrame notices: the job
     * thread is already waiting on this screen, as it waits on every screen.
     *
     * <p>Backing out of a dialog leaves the field exactly as it was — it is somebody deciding to type the path
     * after all, and not an answer of any kind. What comes back goes in whole, however long it is: the cells
     * MainFrame reserved show the front of it, which is a truth about the room and not about the answer, and
     * cutting a path to fit the field would hand back a path to somewhere else.
     */
    void browsed() {
        Live current = live;
        if (current == null || chooser == null) {
            return;
        }
        Spot spot = current.spot(current.focus);
        if (spot == null || !spot.browsable()) {
            return;
        }
        Path chosen;
        try {
            chosen = chooser.choose(spot.pick(), current.value(spot.name()));
        } catch (RuntimeException | LinkageError e) {
            // Said out loud rather than swallowed, and then out of the way: a dialog that will not open is not a
            // reason to lose the screen, and the field can still be typed into. It lands in the scrollback,
            // which is behind the curtain until the form is finished -- so it is a record of what happened
            // rather than a message anybody reads at the time, and the key line is the wrong place for it.
            scrollback.post("the file chooser would not open (" + e + ") -- the path can still be typed");
            return;
        }
        if (chosen == null) {
            return;
        }
        String text = chosen.toString();
        current.values.put(spot.name(), text);
        current.caret = text.length();
        relight();
        dirty = true;
    }

    /** As much of the clipboard as is one line of typing: up to the first break, control characters dropped. */
    private static String pastable(String clipboard) {
        if (clipboard == null || clipboard.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        clipboard.codePoints()
                .takeWhile(c -> c != '\n' && c != '\r')
                .filter(c -> c >= 0x20 && c != 0x7F)
                .forEach(kept::appendCodePoint);
        return kept.toString();
    }

    /** {@code text} cut to {@code room} characters, never through the middle of a character. */
    private static String fit(String text, int room) {
        if (text.length() <= room) {
            return text;
        }
        int cut = Character.isHighSurrogate(text.charAt(room - 1)) ? room - 1 : room;
        return text.substring(0, cut);
    }

    /** The keys that edit a field or move about one. What ends the transaction is claimed, not handled here. */
    void pressed(KeyEvent event) {
        Live current = live;
        if (current == null) {
            return;
        }
        Spot spot = current.spot(current.focus);
        if (spot == null) {
            return;
        }
        String value = current.value(spot.name());
        int caret = Math.min(current.caret, value.length());
        switch (event.key()) {
            case LEFT -> {
                if (spot.kind().equals("choice")) {
                    cycle(current, spot, -1);
                } else {
                    current.caret = Math.max(0, caret - 1);
                }
            }
            case RIGHT -> {
                if (spot.kind().equals("choice")) {
                    cycle(current, spot, +1);
                } else {
                    current.caret = Math.min(value.length(), caret + 1);
                }
            }
            case UP -> move(-1);
            case DOWN -> move(+1);
            case HOME -> current.caret = 0;
            case END -> current.caret = value.length();
            case BACKSPACE -> {
                if (spot.typable() && caret > 0) {
                    current.values.put(spot.name(), value.substring(0, caret - 1) + value.substring(caret));
                    current.caret = caret - 1;
                }
            }
            case DELETE -> {
                if (spot.typable() && caret < value.length()) {
                    current.values.put(spot.name(), value.substring(0, caret) + value.substring(caret + 1));
                }
            }
            default -> {
                return;
            }
        }
        relight();
        dirty = true;
    }

    /**
     * Enter: on to the next thing, or press the thing.
     *
     * <p>Not submit, which is what it was and what a screen full of fields makes dangerous — one field, one
     * stray Enter, and a half-filled form has been sent. What it does instead is the thing every form on every
     * machine has done since forms: it moves on. And because the buttons are in the ring, moving on far enough
     * arrives at Submit and one more Enter presses it, so Enter still submits a form eventually and cannot do it
     * by accident. Nothing about that had to be explained to anybody, which is the point.
     *
     * <p>A screen with no buttons at all keeps the old meaning on its last field, because on such a screen there
     * is nothing to walk to and a key list is all it has.
     */
    void entered() {
        Live current = live;
        if (current == null) {
            return;
        }
        Spot spot = current.spot(current.focus);
        if (spot != null && spot.pressable()) {
            answer(Event.CLICK, "Enter", spot.name());
            return;
        }
        if (next(current) == null) {
            ended("Enter");
            return;
        }
        move(+1);
    }

    /**
     * Space: presses a focused button, and is otherwise a space.
     *
     * <p>Not asked for and included anyway, because a focused button that ignores Space is the one thing on this
     * screen that would behave unlike every button anywhere else. It is safe because the two channels are
     * separate: this claim sees the <em>key</em>, while the space <em>character</em> reaches a field by its own
     * route, so claiming it here costs typing nothing.
     */
    private void spaced() {
        Live current = live;
        Spot spot = current == null ? null : current.spot(current.focus);
        if (spot != null && spot.pressable()) {
            answer(Event.CLICK, "Space", spot.name());
        }
    }

    /** The next thing Tab would land on, or null when this screen has only the one. */
    private Spot next(Live screen) {
        List<Spot> ring = ring(screen);
        return ring.size() < 2 ? null : ring.get((index(ring, screen.focus) + 1) % ring.size());
    }

    /** Focus the next thing there is, wrapping, because a screen's parts are a ring rather than a list. */
    private void move(int direction) {
        Live current = live;
        if (current == null) {
            return;
        }
        List<Spot> ring = ring(current);
        if (ring.isEmpty()) {
            return;
        }
        Spot next = ring.get((index(ring, current.focus) + direction + ring.size()) % ring.size());
        current.focus = next.name();
        current.caret = current.value(next.name()).length();
        relight();
        dirty = true;
    }

    /** Everything Tab stops at, in screen order — fields and buttons alike. */
    private static List<Spot> ring(Live screen) {
        List<Spot> ring = new ArrayList<>();
        for (Spot spot : screen.spots) {
            if (spot.reachable()) {
                ring.add(spot);
            }
        }
        return ring;
    }

    private static int index(List<Spot> ring, String name) {
        for (int i = 0; i < ring.size(); i++) {
            if (ring.get(i).name().equals(name)) {
                return i;
            }
        }
        return 0;
    }

    private void cycle(Live screen, Spot spot, int direction) {
        List<String> of = spot.of();
        if (of.isEmpty()) {
            return;
        }
        int at = of.indexOf(screen.value(spot.name()));
        screen.values.put(spot.name(), of.get(((at < 0 ? 0 : at) + direction + of.size()) % of.size()));
    }

    /**
     * A key that ends the transaction, if this screen said it does. An unlisted key does nothing at all — the
     * screen decides what a key means, and an editor inventing one is an editor holding a rule.
     *
     * <p>Escape is the exception, and it is the exception in the protocol too: cancel is one of the two things a
     * conforming editor reports, so it works on a screen that never listed a key. Enter reaches here only from
     * {@link #entered}, on a screen with nothing to walk to.
     *
     * <p>The function keys still work and are still what the screen lists, but nothing depends on them any more —
     * a keyboard without an F12 was the ordinary case long before this window existed, and every screen worth
     * submitting now carries a button that says so.
     */
    void ended(String key) {
        Live current = live;
        if (current == null) {
            return;
        }
        String does = current.keys.get(key);
        if (does == null || does.isEmpty()) {
            does = key.equals("Enter") ? Event.SUBMIT : key.equals("Esc") ? Event.CANCEL : null;
        }
        if (does == null) {
            return;
        }
        answer(does, key, null);
    }

    /**
     * A click, at a pixel.
     *
     * <p>The row is the row node the pointer is inside; the column is a lookup rather than a division — a
     * laid-out text node publishes the x of every character boundary, so this asks it which one the pointer was
     * nearest. That is also why clicking inside a field puts the caret where the eye was rather than at the end of
     * the text.
     *
     * <p>Split from {@link #clickAt} so that what a click <em>means</em> can be exercised without a window: the
     * part below this line is two framework lookups, and the part below that is every rule about what clicking
     * something does.
     */
    void clicked(float x, float y) {
        int[] cell = cellAt(x, y);
        if (cell != null) {
            clickAt(cell[0], cell[1]);
        }
    }

    /** The pointer moved. Same lookup, and then nothing but a highlight. */
    void hovered(float x, float y) {
        int[] cell = cellAt(x, y);
        hoverAt(cell == null ? -1 : cell[0], cell == null ? -1 : cell[1]);
    }

    /** Which cell of the screen a point is in, or null when it is not in one. */
    private int[] cellAt(float x, float y) {
        Live current = live;
        if (current == null) {
            return null;
        }
        for (int r = 0; r < current.rows && r < pool.size(); r++) {
            NodeLayout layout = pool.get(r).layout();
            if (layout.present() && layout.rect().contains(x, y) && layout.text() != null) {
                return new int[]{r, layout.text().offsetAt(x, y)};
            }
        }
        return null;
    }

    /**
     * A click on a cell: focus what is there, and press it if it is a button.
     *
     * <p>Clicking is the answer that hovering was asking, so this is the one pointer gesture that moves the
     * caret. It lands where the pointer was — inside a field, on the character clicked — because a click that
     * jumped the caret to the end of the text would be a click that lost you your place.
     */
    void clickAt(int row, int col) {
        Live current = live;
        if (current == null) {
            return;
        }
        Spot spot = current.at(row, col);
        if (spot == null || spot.locked()) {
            return;
        }
        current.focus = spot.name();
        if (spot.pressable()) {
            // Focused first, then pressed, so a screen that comes back still shows where the hand was.
            answer(Event.CLICK, null, spot.name());
            return;
        }
        if (spot.kind().equals("choice")) {
            cycle(current, spot, +1);
        } else {
            current.caret = Math.min(Math.max(0, col - spot.col()), current.value(spot.name()).length());
        }
        relight();
        dirty = true;
    }

    /**
     * The pointer is over a cell, or over none.
     *
     * <p>It highlights and does nothing else. Hovering must not move the caret: a pointer crossing a screen on
     * its way somewhere else would otherwise leave the keyboard typing into whatever it passed over last, and a
     * highlight that follows the pointer while the caret stays put is the thing that makes the two legible as
     * different. What can be clicked is what lights up, so the screen documents its own reach.
     */
    void hoverAt(int row, int col) {
        Live current = live;
        Spot spot = current == null ? null : current.at(row, col);
        String name = spot == null || spot.locked() ? null : spot.name();
        if (!java.util.Objects.equals(name, hover)) {
            hover = name;
            dirty = true;
        }
    }

    // ---- reading a record ------------------------------------------------------------------

    private static int[] at(Value.Rec part) {
        if (!(part.get("at") instanceof Value.ListVal list) || list.items().size() < 2) {
            return null;
        }
        return new int[]{(int) whole(list.items().get(0)), (int) whole(list.items().get(1))};
    }

    /** How far a box reaches on one axis, and zero for everything that is not one. */
    private static int span(Value.Rec part, int axis) {
        if (part.get("box") instanceof Value.ListVal dims && dims.items().size() > axis) {
            return (int) whole(dims.items().get(axis));
        }
        return 0;
    }

    private static int width(Value.Rec part) {
        return (int) whole(part, "width", 0);
    }

    private static String text(Value.Rec part, String name) {
        return part.get(name) instanceof Value.Str value ? value.value() : "";
    }

    private static long whole(Value value) {
        return value instanceof Value.Int number ? number.value() : 0;
    }

    private static long whole(Value.Rec record, String name, long fallback) {
        return record.get(name) instanceof Value.Int number ? number.value() : fallback;
    }

    private static int widest(List<String> of) {
        int widest = 0;
        for (String option : of) {
            widest = Math.max(widest, option.length());
        }
        return widest;
    }

    private static byte style(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "title" -> TITLE;
            case "label" -> LABEL;
            case "hint" -> HINT;
            case "frame" -> FRAME;
            case "status" -> STATUS;
            case "error" -> ERROR;
            case "entry" -> ENTRY;
            case "entry-focus" -> ENTRY_FOCUS;
            case "entry-locked" -> ENTRY_LOCKED;
            case "action" -> ACTION;
            default -> PLAIN;                           // rule three, and the only place it is decided
        };
    }
}
