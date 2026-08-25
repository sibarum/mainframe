package dev.mainframe;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import dev.mainframe.eval.Interpreter;
import dev.mainframe.lang.Lexer;
import dev.mainframe.lang.Parser;
import dev.mainframe.lang.Token;
import dev.mainframe.lang.TokenType;
import dev.mainframe.ui.Renderer;

/** The read-eval-print loop. */
public final class Shell {

    private final Session session;
    private final Interpreter interpreter;
    private final BufferedReader input;

    public Shell(Session session, Interpreter interpreter, BufferedReader input) {
        this.session = session;
        this.interpreter = interpreter;
        this.input = input;
    }

    /** Runs until the user leaves. Returns the exit code. */
    public int loop() {
        Renderer out = session.out();
        out.info(out.bold("MainFrame " + dev.mainframe.builtins.CoreBuiltins.VERSION)
                + out.dim(" -- type ") + out.cyan("help") + out.dim(" to see what you can do, ")
                + out.cyan("exit") + out.dim(" to leave"));
        if (session.dryRun()) out.info(out.yellow("dry run mode: nothing will be changed"));

        StringBuilder pending = new StringBuilder();
        while (true) {
            out.out().print(prompt(pending.length() > 0));
            out.out().flush();
            String line;
            try {
                line = input.readLine();
            } catch (IOException e) {
                out.warn("could not read input: " + e.getMessage());
                return 1;
            }
            if (line == null) {
                out.info("");
                return 0;
            }
            pending.append(line).append('\n');
            String source = pending.toString();
            if (incomplete(source)) continue;
            pending.setLength(0);
            if (source.isBlank()) continue;

            remember(source.strip());
            Integer exit = runOnce(source);
            if (exit != null) return exit;
        }
    }

    /** Runs one piece of source. Returns an exit code when the shell should stop. */
    private Integer runOnce(String source) {
        session.source(source);
        try {
            interpreter.run(Parser.parse(source));
            return null;
        } catch (ExitRequest e) {
            return e.code();
        } catch (MfError e) {
            session.out().error(e, source);
            return null;
        } catch (StackOverflowError e) {
            session.out().error(MfError.of("E901", "that nested too deeply for me to follow")
                    .hint("check for a block that runs itself").build(), source);
            return null;
        } catch (RuntimeException e) {
            // A bug in MainFrame, not in what the user typed. Say so plainly.
            session.out().error(MfError.of("E902", "MainFrame hit an internal problem: " + e)
                    .hint("this is a bug in MainFrame, not in what you typed")
                    .build(), source);
            return null;
        }
    }

    private String prompt(boolean continuation) {
        Renderer out = session.out();
        if (continuation) return out.dim("... ");
        Path cwd = session.cwd();
        Path home = dev.mainframe.fs.SafeFs.userHome();
        String where = cwd.startsWith(home) && !cwd.equals(home)
                ? "~/" + home.relativize(cwd).toString().replace('\\', '/')
                : cwd.equals(home) ? "~" : cwd.toString();
        String marker = session.dryRun() ? out.yellow(" [dry run]") : "";
        return out.cyan(where) + marker + out.bold(" > ");
    }

    /** True when the source cannot be complete yet, so the shell should read more. */
    static boolean incomplete(String source) {
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source);
        } catch (MfError e) {
            return false;  // a real problem; let the parser report it properly
        }
        int depth = 0;
        TokenType previous = TokenType.NEWLINE;
        for (Token token : tokens) {
            switch (token.type()) {
                case LBRACE, LBRACKET, LPAREN -> depth++;
                case RBRACE, RBRACKET, RPAREN -> depth--;
                default -> { }
            }
            if (token.type() != TokenType.NEWLINE && token.type() != TokenType.EOF) previous = token.type();
        }
        return depth > 0 || previous == TokenType.PIPE;
    }

    /** Appends to the history file, best effort. */
    private void remember(String source) {
        try {
            Path history = session.stateDir().resolve("history");
            Files.writeString(history, source.replace('\n', ' ') + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // History is a convenience, never a reason to interrupt someone's work.
        }
    }
}
