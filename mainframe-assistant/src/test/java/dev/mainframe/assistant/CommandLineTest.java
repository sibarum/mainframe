package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.mainframe.eval.Signature;
import dev.mainframe.lang.Parser;
import dev.mainframe.value.ValueType;

/** Writing a model's answer back out as a line MainFrame can read. */
class CommandLineTest {

    private static Signature deploy() {
        return Signature.named("deploy", "my app")
                .summary("push the current build to an environment")
                .required("environment", ValueType.STRING, "where to deploy")
                .optional("since", ValueType.DURATION, "how far back")
                .switchFlag("skip-tests", '\0', "skip the test suite")
                .valueFlag("budget", 'b', ValueType.SIZE, "upload ceiling")
                .build();
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    @Test
    void writesArgumentsInTheOrderTheCommandDeclaresThem() {
        // The model sent them the other way round; the line still reads correctly,
        // because position is the command's business and not the caller's.
        assertEquals("deploy \"staging\" \"7d\"",
                CommandLine.render(deploy(), args("since", "7d", "environment", "staging")));
    }

    @Test
    void leavesOutWhatWasNotSent() {
        assertEquals("deploy \"live\"", CommandLine.render(deploy(), args("environment", "live")));
    }

    @Test
    void aSwitchIsWrittenOnlyWhenItIsOn() {
        assertEquals("deploy \"live\" --skip-tests",
                CommandLine.render(deploy(), args("environment", "live", "--skip-tests", true)));
        assertEquals("deploy \"live\"",
                CommandLine.render(deploy(), args("environment", "live", "--skip-tests", false)));
    }

    @Test
    void aValueFlagKeepsItsEqualsSign() {
        assertEquals("deploy \"live\" --budget=\"10mb\"",
                CommandLine.render(deploy(), args("environment", "live", "--budget", "10mb")));
    }

    @Test
    void aRestArgumentSpreadsBackOut() {
        Signature remove = Signature.named("remove", "files")
                .summary("delete files")
                .rest("paths", ValueType.PATH, "what to delete")
                .build();
        assertEquals("remove \"a.txt\" \"b.txt\"",
                CommandLine.render(remove, args("paths", List.of("a.txt", "b.txt"))));
    }

    @Test
    void quotingSurvivesTheThingsThatBreakShells() {
        Signature echo = Signature.named("echo", "text")
                .summary("say something")
                .required("text", ValueType.STRING, "what to say")
                .build();
        // A space, a quote, a backslash and a newline: each of these has broken a
        // real shell by being passed along unquoted.
        String awkward = "a \"b\" c\\d\ne";
        String line = CommandLine.render(echo, args("text", awkward));
        assertEquals("echo \"a \\\"b\\\" c\\\\d\\ne\"", line);
        // The real check is not the string: it is that MainFrame can read it back.
        Parser.parse(line);
    }

    @Test
    void numbersAndBooleansGoBare() {
        Signature take = Signature.named("take", "tables")
                .summary("keep the first rows")
                .required("count", ValueType.INT, "how many")
                .build();
        assertEquals("take 5", CommandLine.render(take, args("count", 5)));
    }

    @Test
    void anInventedArgumentIsRefusedBeforeALineIsBuilt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CommandLine.render(deploy(), args("environment", "live", "region", "eu-west")));
        assertTrue(thrown.getMessage().contains("region"), thrown.getMessage());
    }

    @Test
    void whatIsShownIsWhatIsRun() {
        // Every line this produces has to be readable by the same parser that
        // reads what a person types -- otherwise the echo is a description of the
        // command rather than the command itself.
        for (Map<String, Object> call : List.of(
                args("environment", "staging"),
                args("environment", "sta ging", "--skip-tests", true),
                args("environment", "live", "since", "90m", "--budget", "512kb"))) {
            Parser.parse(CommandLine.render(deploy(), call));
        }
    }
}
