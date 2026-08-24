package dev.mainframe;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.mainframe.eval.Scope;
import dev.mainframe.form.FormStore;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.fs.SafeFs;
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

    /** The text currently being run, so errors can point at the right line. */
    public String source() { return source; }
    public void source(String value) { this.source = value == null ? "" : value; }

    /**
     * Asks a yes/no question. When there is nobody to ask -- a script, a pipe, a
     * CI job -- the answer is no, and the caller turns that into an error telling
     * the user to pass --yes.
     */
    public boolean confirm(String question) {
        if (assumeYes) return true;
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
