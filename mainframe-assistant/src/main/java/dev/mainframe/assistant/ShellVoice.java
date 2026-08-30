package dev.mainframe.assistant;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.Session;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Signature;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * The assistant, plugged into a running shell.
 *
 * <p>Two small jobs. It hands over the roster and runs a line; and it shows the
 * person what is about to happen and asks them when asking is due. Both go
 * through what MainFrame already has -- {@link Parser} and the interpreter for
 * the first, {@link Session#confirm} for the second -- so there is no second way
 * to run a command and no second way to be asked about one.
 */
final class ShellVoice implements Assistant.Runner, Assistant.Voice {

    /**
     * Commands the model is never shown.
     *
     * <p>{@code ask} because a model that can ask itself is a model that can spend
     * an afternoon doing it. {@code key} because the roster is a list of things
     * the model may decide to run, and nothing that touches a stored credential
     * belongs on it -- the person sets a key, never the thing the key is for.
     */
    private static final List<String> WITHHELD = List.of(Ask.NAME, Keys.NAME);

    private final Args args;

    ShellVoice(Args args) { this.args = args; }

    @Override
    public List<Signature> roster() {
        List<Signature> roster = new ArrayList<>();
        for (Builtin builtin : args.registry().all()) {
            Signature signature = builtin.signature();
            if (!WITHHELD.contains(signature.name())) roster.add(signature);
        }
        return roster;
    }

    /**
     * Runs one line exactly as a typed one runs.
     *
     * <p>Through the parser rather than by reaching for the builtin: everything
     * that makes a command safe is on this path, and a shortcut past it would be a
     * second entrance that has to be kept in step with the first for ever.
     */
    @Override
    public String run(String line) {
        Value produced = args.interpreter().run(Parser.parse(line));
        // What a command hands back, not what it printed. Printing has already
        // happened -- it went to the person, which is where it was going anyway.
        return produced == null || produced instanceof Value.Nothing
                ? "" : Values.display(produced);
    }

    @Override
    public void echo(String line) {
        Session session = args.session();
        // The canonical form of what was asked for, in the shell's own language.
        // Somebody who typed "what files are here" gets to see what that was, and
        // can type it themselves next time.
        session.out().info(session.out().cyan("> ") + line);
    }

    @Override
    public boolean ok(String line) {
        return args.session().confirm("run this?", List.of(line));
    }

    @Override
    public void say(String text) {
        args.session().out().info(text);
    }
}
