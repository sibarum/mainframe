package dev.mainframe;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;

/** Runs MainFrame source in a throwaway session, for tests. */
final class Mf {

    private final Session session;
    private final Interpreter interpreter;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    Mf(Path cwd) {
        this(cwd, cwd.resolve(".indexes"));
    }

    Mf(Path cwd, Path indexDirectory) {
        this(cwd, indexDirectory, "");
    }

    private Mf(Path cwd, Path indexDirectory, String typed) {
        // Keep the trash, history and indexes inside the test's own directory.
        System.setProperty("mainframe.home", cwd.resolve(".mainframe").toString());
        Renderer renderer = new Renderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                false);
        this.session = new Session(renderer, new IndexStore(indexDirectory),
                new BufferedReader(new StringReader(typed)), cwd);
        this.interpreter = new Interpreter(session, Registry.standard());
    }

    /**
     * A session with somebody at the keyboard, answering prompts with these
     * lines. The input runs out at the end, which is what Ctrl-D looks like.
     */
    static Mf typing(Path cwd, String... lines) {
        StringBuilder typed = new StringBuilder();
        for (String line : lines) typed.append(line).append('\n');
        Mf mf = new Mf(cwd, cwd.resolve(".indexes"), typed.toString());
        mf.session.interactive(true);
        return mf;
    }

    Session session() { return session; }

    /** Runs source and returns the last value; errors surface as {@link MfError}. */
    Value eval(String source) {
        session.source(source);
        return interpreter.run(Parser.parse(source));
    }

    /** Runs source and returns the error code, or null when it succeeded. */
    String errorCode(String source) {
        try {
            eval(source);
            return null;
        } catch (MfError e) {
            return e.code();
        }
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

    String warnings() { return err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }
}
