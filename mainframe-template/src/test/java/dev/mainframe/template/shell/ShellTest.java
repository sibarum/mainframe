package dev.mainframe.template.shell;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;

/**
 * A throwaway MainFrame with templates installed.
 *
 * <p>Core has one of these in its own tests and it is not on this module's classpath, so this is the small
 * duplication the module boundary costs -- forty lines, paid once, against a test-jar dependency that would
 * make this module need core's tests to compile.
 */
final class ShellTest {

    private final Session session;
    private final Interpreter interpreter;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ShellTest(Path cwd, String typed) {
        // Keep the trash, the indexes and anybody's own templates inside the test's own directory.
        System.setProperty("mainframe.home", cwd.resolve(".mainframe").toString());
        Renderer renderer = new Renderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                false);
        this.session = new Session(renderer, new IndexStore(cwd.resolve(".indexes")),
                new BufferedReader(new StringReader(typed)), cwd);
        Registry registry = Registry.standard();
        Templates.install(registry);
        this.interpreter = new Interpreter(session, registry);
    }

    /** Nobody at the keyboard: the case a script is in, and the one `new` has to refuse to guess in. */
    static ShellTest unattended(Path cwd) { return new ShellTest(cwd, ""); }

    /** Somebody at the keyboard, answering prompts with these lines. */
    static ShellTest typing(Path cwd, String... lines) {
        StringBuilder typed = new StringBuilder();
        for (String line : lines) typed.append(line).append('\n');
        ShellTest shell = new ShellTest(cwd, typed.toString());
        shell.session.interactive(true);
        return shell;
    }

    Session session() { return session; }

    Value eval(String source) {
        session.source(source);
        return interpreter.run(Parser.parse(source));
    }

    MfError error(String source) {
        try {
            eval(source);
            throw new AssertionError("expected " + source + " to fail, but it did not");
        } catch (MfError e) {
            return e;
        }
    }

    String printed() { return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }
}
