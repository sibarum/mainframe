package dev.mainframe;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.SequencedSet;

import dev.mainframe.eval.Scope;
import dev.mainframe.form.FormStore;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.panel.Editor;
import dev.mainframe.ui.Renderer;

/** Everything a running MainFrame knows: where it is, what it has been told, and how it talks. */
public final class Session {

    private final Renderer renderer;
    private final IndexStore indexes;
    private final Scope globals = new Scope(null);
    private final BufferedReader input;
    private final Environment environment = Environment.fromProcess();
    private final Programs programs = new Programs();

    private FormStore forms = FormStore.inState();
    private Editor editor;
    private Path cwd;
    private boolean dryRun;
    private boolean assumeYes;
    private boolean interactive;
    private String source = "";

    public Session(Renderer renderer, IndexStore indexes, BufferedReader input, Path cwd) {
        this.renderer = renderer;
        this.indexes = indexes;
        this.input = input;
        this.cwd = cwd;
    }

    public Renderer out() { return renderer; }
    public IndexStore indexes() { return indexes; }
    public Scope globals() { return globals; }
    public Path cwd() { return cwd; }

    /** Form data saved by name, for pre-filling a form with what was entered last time. */
    public FormStore forms() { return forms; }

    public void forms(FormStore store) { this.forms = store; }

    /**
     * Whatever is painting screens for this session, or null when nobody is.
     *
     * <p>An editor is a display MainFrame borrows, not a mode it runs in: with one
     * attached a form is a panel, and without one it is printed downwards. Nothing
     * else about the session changes.
     */
    public Editor editor() { return editor; }

    public void editor(Editor value) { this.editor = value; }

    /**
     * The environment handed to external programs, editable between commands.
     * MAINFRAME_HOME is the one exception: it is read once at startup, because
     * moving the trash or the indexes mid-session would be worse than useless.
     */
    public Environment env() { return environment; }

    /**
     * The programs the surrounding application provides in-process. They are
     * looked up before the PATH, so a hosted program takes the place of a real
     * one with the same name.
     */
    public Programs programs() { return programs; }

    public void cd(Path directory) { this.cwd = directory; }

    public Path resolve(String raw) { return SafeFs.resolve(cwd, raw); }

    public boolean dryRun() { return dryRun; }
    public void dryRun(boolean value) { this.dryRun = value; }

    public boolean assumeYes() { return assumeYes; }
    public void assumeYes(boolean value) { this.assumeYes = value; }

    public boolean interactive() { return interactive; }
    public void interactive(boolean value) { this.interactive = value; }

    /**
     * Whether there is anybody to ask a question of.
     *
     * <p>There are two ways there can be, and they are not the same way twice.
     * {@link #interactive} says this process's own input belongs to a person,
     * which is what a terminal means; an attached {@link #editor} says somebody
     * lent MainFrame a display, which is what a window means. A window is not a
     * terminal -- handing an external program the process's stdio in a window
     * sends it somewhere nobody can see, which is why a windowed session is
     * deliberately not interactive -- so a session can have nobody at its input
     * and still have somebody in front of it.
     *
     * <p>Everything that stops when nobody is there asks this rather than either
     * one on its own: a form, a confirmation, anything that cannot be answered by
     * guessing.
     */
    public boolean somebodyToAsk() { return interactive || editor != null; }

    /** The text currently being run, so errors can point at the right line. */
    public String source() { return source; }
    public void source(String value) { this.source = value == null ? "" : value; }

    /**
     * Asks a yes/no question. When there is nobody to ask -- a script, a pipe, a
     * CI job -- the answer is no, and the caller turns that into an error telling
     * the user to pass --yes.
     */
    public boolean confirm(String question) {
        return confirm(question, java.util.List.of());
    }

    /**
     * The same question, with the lines that say what is about to happen.
     *
     * <p>{@code detail} is for the display and not for the transcript: a printed
     * confirmation has those lines above it already, because the caller wrote them
     * out before asking, whereas a screen replaces what was on the glass and has to
     * carry them itself. So the printed path ignores them and the panel shows them,
     * and neither one says anything twice.
     */
    public boolean confirm(String question, java.util.List<String> detail) {
        if (assumeYes) return true;
        // The editor before the terminal, and before the question of whether there
        // is one: a window is somebody to ask even though its session is not
        // interactive. With an editor attached standard input is the protocol, so
        // reading a line from it would eat the editor's next message.
        if (editor != null) return dev.mainframe.panel.Panels.confirm(editor, question, detail);
        if (!interactive) return false;
        renderer.out().print(question + " " + renderer.dim("[y/N]") + " ");
        renderer.out().flush();
        String answer = readLine();
        if (answer == null) return false;
        answer = answer.trim().toLowerCase();
        return answer.equals("y") || answer.equals("yes");
    }

    /**
     * Reads one line from wherever this session takes its input, or null when
     * there is no more of it.
     *
     * <p>Running out of input is an answer, not a failure: at a confirmation it
     * means no, and in a form it means the person gave up. Either way the caller
     * stops rather than inventing something.
     */
    public String readLine() {
        try {
            return input.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** Creates {@code ~/.mainframe} on first use. */
    public Path stateDir() {
        Path dir = SafeFs.stateDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // If we cannot create it, the commands that need it will say so.
        }
        return dir;
    }
}
