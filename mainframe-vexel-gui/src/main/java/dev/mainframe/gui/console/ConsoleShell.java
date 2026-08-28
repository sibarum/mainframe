package dev.mainframe.gui.console;

import dev.mainframe.ExitRequest;
import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.form.Form;
import dev.mainframe.form.FormPanel;
import dev.mainframe.form.FormScreen;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.lang.Parser;
import dev.mainframe.panel.Editor;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainFrame, embedded. The shell is a library — a {@link Session} that talks through a {@link Renderer} over two
 * streams, and an {@link Interpreter} that runs a parsed program — so the console neither drives a subprocess nor
 * scrapes a terminal: it hands MainFrame two {@link LineSink}s and gets lines back.
 *
 * <p>Everything here runs on one job thread, in submission order, one line at a time. Nothing about running a
 * command touches the GUI thread; the only crossing is {@link Scrollback#post}, which queues.
 *
 * <p>The session is deliberately <b>not</b> interactive, and that is a decision rather than an omission.
 * MainFrame hands an external program the process's own stdio when the program is the last stage of an
 * <em>interactive</em> line — in a GUI that would send {@code ^git log} to whatever launched the JVM instead of
 * to this pane. And a command that can lose data would otherwise stop on a question this pane has no way to
 * answer. Non-interactive, MainFrame captures the program's output and answers the destructive command with
 * {@code add --yes once you are sure}: both of those are visible here, which is the point.
 *
 * <p>This class knows nothing about any particular application. What is in the registry beyond the standard
 * commands is whatever the {@link ConsoleApp}s put there.
 */
final class ConsoleShell implements AutoCloseable {

    /** One command's output ceiling. Past it the rest is dropped, with a line saying so. */
    private static final int MAX_LINES = 20_000;
    private static final long MAX_CHARS = 4L * 1024 * 1024;

    private final Scrollback scrollback;
    /** The command line, as something MainFrame's forms and confirmations can read a line from. */
    private final PromptPipe pipe = new PromptPipe();
    private final Session session;
    private final LaunchCommands launcher;
    private final Interpreter interpreter;
    private final ExecutorService jobs;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong finished = new java.util.concurrent.atomic.AtomicLong();
    private final Runnable onExit;

    private volatile Thread worker;
    private volatile String lastError = "";
    private int lines;
    private long chars;
    private boolean truncated;

    /**
     * @param onExit  called when the {@code exit} builtin runs, to put the window away
     * @param apps    what is plugged into this console; their commands are added to the registry here, in order
     * @param context what those apps are handed — the console, as they are allowed to see it
     * @param display the screen MainFrame may borrow, or null when this console has no window to put one on
     */
    ConsoleShell(Scrollback scrollback, Path cwd, Runnable onExit, List<ConsoleApp> apps,
                 ConsoleContext context, Editor display) {
        this.scrollback = scrollback;
        this.onExit = onExit;

        PrintStream out = new PrintStream(new LineSink(this::emit), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new LineSink(this::emitError), true, StandardCharsets.UTF_8);
        // The session reads from the command line now. That is what lets a form ask a question in the scrollback
        // and get an answer back -- see PromptPipe. It stays *non*-interactive all the same: interactive means
        // "hand an external program the process's own stdio", which in a GUI sends it somewhere nobody can see.
        this.session = new Session(new Renderer(out, err, true), IndexStore.inState(),
                new BufferedReader(pipe), cwd);
        session.interactive(false);
        // The display MainFrame borrows, when this console has one. A window that has never been opened has
        // nobody at it, so it attaches none -- and the printed form and the ordinary refusals stand, which is
        // what the headless capture path and the tests want.
        session.editor(display);

        Registry registry = Registry.standard();
        this.launcher = new LaunchCommands(apps, context);
        // Three passes, and the order is the whole of the naming policy. apps and launch go on first, so an app
        // cannot quietly take the name of the command that opens it. The apps go on next, so they can claim
        // anything else that is still free. The name-shaped shortcuts go on last, so they fill only what is left
        // -- which is what lets an app call its own command by its own name, as calc does.
        launcher.core(registry);
        for (ConsoleApp app : apps) {
            app.commands(registry, context);
        }
        launcher.shortcuts(registry);
        this.interpreter = new Interpreter(session, registry);

        this.jobs = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mainframe-job");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- what the window asks --------------------------------------------------------

    /** Where MainFrame is standing — {@code cd} moves it, and the prompt reads it each frame. */
    Path cwd() {
        return session.cwd();
    }

    boolean busy() {
        return busy.get();
    }

    /** The last error, for the status line. Empty once a command succeeds. */
    String lastError() {
        return lastError;
    }

    /** Queue a line. Called from a handler thread; a line typed while a command runs waits its turn. */
    void submit(String source) {
        jobs.execute(() -> run(source));
    }

    /** Lend MainFrame a display after the fact — what the panel capture uses, having no window to infer it from. */
    void display(Editor editor) {
        session.editor(editor);
    }

    /**
     * Whether the shell is waiting to be told something rather than to be given a command -- a form asking for a
     * field, or a destructive command asking for a yes.
     *
     * <p>This is what the window routes on, and it is a fact about the shell rather than a mode the window is put
     * into: it is true exactly while the job thread is blocked reading. A form that finishes, cancels or fails
     * stops reading, so the next line is a command again without anything having to say so.
     */
    boolean asking() {
        return pipe.waiting();
    }

    /** Hand a typed line to whatever is asking. */
    void answer(String line) {
        pipe.offer(line);
    }

    /**
     * The shortest line that opens {@code app} in this console -- its own name where that reached the registry,
     * and the explicit {@code launch} otherwise. What the context menu shows.
     */
    String launchLine(ConsoleApp app) {
        return launcher.lineFor(app);
    }

    /**
     * Show a form and collect the answers. Job thread only, because it blocks either way.
     *
     * <p>Two ways of asking, one {@link Form}. With a display attached the whole form goes up at once and the
     * person moves about it; without one the questions are printed downwards and the answers read off the command
     * line. Same fields, same rules, same record at the end — {@link Form} says what a good answer is and
     * {@code Field.read} says what typed text means, whichever way the asking went.
     *
     * <p>Straight to the two screens rather than through the {@code form} builtin, which wants a signature and an
     * {@code Args}. The builtin's own refusal — "form has nobody to ask" — is right for the general command and
     * wrong here, because this window <em>is</em> who is there.
     */
    Value.Rec form(Form definition, Value.Rec starting, String title) {
        Editor editor = session.editor();
        return editor != null
                ? FormPanel.show(definition, starting, title, editor, session.cwd())
                : FormScreen.show(definition, starting, title, true, session);
    }

    /**
     * Ask the running command to stop.
     *
     * <p>Interrupting reaches what blocks — a child process being waited on, a read — but MainFrame's own loops
     * do not poll for it, so a long {@code find} or {@code index-build} runs to the end regardless. The status
     * line says so rather than implying the command died.
     */
    boolean interrupt() {
        Thread t = worker;
        if (t == null || !busy.get()) {
            return false;
        }
        t.interrupt();
        return true;
    }

    // ---- running ---------------------------------------------------------------------

    /** Job thread. Every way a command can end is handled here, so nothing reaches the frame loop. */
    private void run(String source) {
        worker = Thread.currentThread();
        busy.set(true);
        lines = 0;
        chars = 0;
        truncated = false;
        session.source(source);
        try {
            interpreter.run(Parser.parse(source));
            lastError = "";
        } catch (ExitRequest e) {
            onExit.run();
        } catch (MfError e) {
            session.out().error(e, source);
            lastError = "error[" + e.code() + "] " + e.getMessage();
        } catch (StackOverflowError e) {
            report("E901", "that nested too deeply for me to follow", source,
                    "check for a block that runs itself");
        } catch (RuntimeException e) {
            // A bug in MainFrame or in an app plugged into it, not in what was typed. Say which.
            report("E902", "MainFrame hit an internal problem: " + e, source,
                    "this is a bug in MainFrame, not in what you typed");
        } finally {
            if (truncated) {
                scrollback.post("... output truncated at " + MAX_LINES
                        + " lines -- send the rest to a file with: ... | save ./out.txt");
            }
            // Clear the interrupt so the next command on this thread does not inherit it.
            Thread.interrupted();
            busy.set(false);
            // Last, and after lastError is settled: something waiting on this count is
            // waiting to read the result, and busy() alone cannot be waited on -- it is
            // false both before a queued line starts and after it finishes.
            finished.incrementAndGet();
        }
    }

    /**
     * How many lines have run to completion, only ever going up.
     *
     * <p>For anything driving the console without a keyboard. Take the count, submit,
     * and wait for it to change: the job thread runs one line at a time in the order
     * they arrived, so a changed count means that line is done and what it left behind
     * -- {@link #lastError}, the scrollback, whatever it wrote -- can be read.
     */
    long finished() { return finished.get(); }

    private void report(String code, String message, String source, String hint) {
        session.out().error(MfError.of(code, message).hint(hint).build(), source);
        lastError = "error[" + code + "] " + message;
    }

    private void emit(String line) {
        if (lines >= MAX_LINES || chars >= MAX_CHARS) {
            truncated = true;
            return;
        }
        lines++;
        chars += line.length() + 1;
        scrollback.post(line);
    }

    /** Warnings and error reports arrive here, already coloured by the renderer. Never capped. */
    private void emitError(String line) {
        scrollback.post(line);
    }

    @Override
    public void close() {
        // Before the interrupt: a form parked on a read has to be told the input ended, or the job thread never
        // leaves it and the shutdown waits the full two seconds for nothing.
        pipe.close();
        interrupt();
        jobs.shutdownNow();
        try {
            jobs.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
