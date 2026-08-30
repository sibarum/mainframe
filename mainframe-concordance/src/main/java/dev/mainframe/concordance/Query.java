package dev.mainframe.concordance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import dev.mainframe.api.Program;
import dev.mainframe.api.ProgramCall;
import dev.mainframe.api.ProgramSpec;

import sibarum.concordance.cli.Concordance;

/**
 * {@code ^concordance [<project>] <query> [args]} -- ask Concordance about Java source.
 *
 * <p>Everything this class does that {@code Concordance.main} does not is about
 * standing somewhere. A CLI starts in the directory the process started in; a
 * shell has moved since then, and a path typed at a MainFrame prompt means what it
 * means <em>there</em>. So the project argument is resolved against
 * {@link ProgramCall#directory()}, and a line with no project at all takes that
 * directory as the project -- which is what makes {@code ^concordance summary} the
 * short way to ask about where you are standing.
 */
final class Query implements Program {

    /**
     * The queries Concordance answers, so a line can be told from a path.
     *
     * <p>A copy of a list that lives in the CLI, and copying it is the price of the
     * boundary rather than an oversight: reaching into Concordance for its verb
     * table would make this module a part of Concordance instead of an adapter to
     * it. It fails safely in one direction only -- a verb added over there is not
     * recognised here, and the line still works with the project spelled out,
     * which is the form Concordance documents anyway.
     */
    private static final Set<String> QUERIES = Set.of("summary", "names", "usages", "impls");

    static final ProgramSpec SPEC = ProgramSpec.named("concordance")
            .summary("what Java source declares, and where a name is used")
            .usage("concordance [<project>] <summary|names|usages|impls> [args]")
            .example("^concordance summary")
            .example("^concordance usages area")
            .example("^concordance ../vexelray impls Gui")
            .example("^concordance names Store | lines | first 20")
            .build();

    @Override
    public int run(ProgramCall call) {
        List<String> arguments = call.arguments();
        if (arguments.isEmpty()) {
            // Concordance prints its own usage on a short line, and it is the usage
            // worth printing -- but only once it has been given the two arguments it
            // needs to get that far. An empty line here would be a bare "" project.
            return delegate(call, new String[0]);
        }

        // Either the project was named or it was not, and the first word says which.
        boolean named = !QUERIES.contains(arguments.getFirst());
        Path project = named
                ? call.directory().resolve(arguments.getFirst()).normalize()   // ./inner is inner
                : call.directory();
        List<String> rest = named ? arguments.subList(1, arguments.size()) : arguments;

        String[] argv = new String[rest.size() + 1];
        argv[0] = project.toString();
        for (int i = 0; i < rest.size(); i++) argv[i + 1] = rest.get(i);
        return delegate(call, argv);
    }

    /**
     * Run Concordance with MainFrame's streams standing in for the process's.
     *
     * <p>Buffered rather than streamed, because a hosted program's standard output
     * <em>is</em> the value of this pipeline stage: a query that fails halfway
     * should hand on nothing rather than half a table. Concordance indexes before
     * it prints anything anyway, so nothing is being made less responsive than it
     * was.
     */
    private static int delegate(ProgramCall call, String[] argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream toOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream toErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Concordance.run(argv, toOut, toErr);
        }
        String printed = out.toString(StandardCharsets.UTF_8);
        String complained = err.toString(StandardCharsets.UTF_8);
        if (!printed.isEmpty()) call.write(printed);
        if (!complained.isEmpty()) call.writeError(complained.stripTrailing());
        return code;
    }
}
