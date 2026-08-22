package dev.mainframe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Embedding MainFrame and hooking your own commands into it. */
class EmbeddingTest {

    @TempDir
    Path here;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private MainFrame.Builder shell() {
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        return MainFrame.builder()
                .directory(here)
                .indexDirectory(here.resolve(".indexes"))
                .output(stream, stream)
                .color(false);
    }

    private String printed() { return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); }

    /** A stand-in for whatever the host program actually has. */
    private static Data customers() {
        List<Map<String, Data>> rows = new ArrayList<>();
        rows.add(row("Ada", 4200, "ada@example.com"));
        rows.add(row("Grace", 150, "grace@example.com"));
        rows.add(row("Alan", 990, "alan@example.com"));
        return Data.table(rows);
    }

    private static Map<String, Data> row(String name, long spend, String email) {
        Map<String, Data> row = new LinkedHashMap<>();
        row.put("name", Data.text(name));
        row.put("spend", Data.number(spend));
        row.put("email", Data.text(email));
        return row;
    }

    private static CommandSpec customersSpec() {
        return CommandSpec.named("customers")
                .category("my app")
                .summary("list customers from the host program")
                .optional("filter", DataType.TEXT, "only names containing this")
                .output(DataType.TABLE)
                .effect(Effect.READS)
                .example("customers | where spend > 1000")
                .build();
    }

    // ---- the basic hook -----------------------------------------------------------------

    @Test
    void aHostedCommandFeedsTheRestOfThePipeline() {
        MainFrame shell = shell().command(customersSpec(), invocation -> customers()).build();

        Data biggest = shell.run("customers | sort-by spend --reverse | first | get name");
        assertEquals("Ada", biggest.items().getFirst().text());

        Data count = shell.run("customers | where spend > 500 | length");
        assertEquals(2, count.number());
    }

    @Test
    void theCallbackSeesItsArguments() {
        MainFrame shell = shell()
                .command(customersSpec(), invocation -> {
                    String filter = invocation.text(0, "");
                    List<Map<String, Data>> kept = new ArrayList<>();
                    for (Data candidate : customers().rows()) {
                        if (candidate.field("name").text().contains(filter)) {
                            kept.add(Map.of("name", candidate.field("name")));
                        }
                    }
                    return Data.table(kept);
                })
                .build();
        assertEquals(2, shell.run("customers A | length").number());
        assertEquals(3, shell.run("customers \"\" | length").number());
    }

    @Test
    void theCallbackSeesThePipedDataWithItsTypesIntact() {
        List<String> seen = new ArrayList<>();
        CommandSpec spec = CommandSpec.named("collect")
                .summary("hand rows to the host program")
                .input(DataType.TABLE)
                .output(DataType.INTEGER)
                .build();

        MainFrame shell = shell()
                .command(customersSpec(), invocation -> customers())
                .command(spec, invocation -> {
                    for (Data row : invocation.input().rows()) {
                        // A number arrives as a number, not as text to be parsed.
                        seen.add(row.field("name").text() + "=" + row.field("spend").number());
                    }
                    return Data.number(seen.size());
                })
                .build();

        assertEquals(1, shell.run("customers | where spend > 1000 | collect").number());
        assertEquals(List.of("Ada=4200"), seen);
    }

    @Test
    void sizesAndTimesSurviveTheRoundTrip() {
        CommandSpec spec = CommandSpec.named("artifact")
                .summary("describe the build the host program knows about")
                .output(DataType.RECORD)
                .build();
        MainFrame shell = shell().command(spec, invocation -> Data.row()
                        .put("name", "build.zip")
                        .size("size", 4_194_304)
                        .put("built", Instant.ofEpochMilli(1_700_000_000_000L))
                        .put("verified", true)
                        .build())
                .build();

        // A size compares against a size literal, which is the point of having the type.
        assertEquals(true, shell.run("artifact | where size > 1mb | length").number() == 1);
        assertEquals("4.0 MB", shell.run("artifact | get size").items().getFirst().text());
    }

    @Test
    void aHostedCommandCanBeGivenTheWholePipeline() {
        MainFrame shell = shell().command(customersSpec(), invocation -> customers()).build();
        Data json = shell.run("customers | select name spend | to-json");
        // A table is written as a table: the column names once, then the rows.
        assertTrue(json.text().contains("[\"name:string\",\"spend:int\"]"), json.text());
        assertTrue(json.text().contains("[\"Ada\", 4200]"), json.text());
    }

    // ---- the unified interface ----------------------------------------------------------

    @Test
    void hostedCommandsAreCheckedLikeBuiltInOnes() {
        CommandSpec needsRows = CommandSpec.named("collect")
                .summary("hand rows to the host program")
                .input(DataType.TABLE)
                .build();
        MainFrame shell = shell()
                .command(customersSpec(), invocation -> customers())
                .command(needsRows, invocation -> Data.number(invocation.input().rows().size()))
                .build();

        // Too many arguments, wrong flag, wrong input shape: all caught before the callback.
        assertEquals("E304", codeOf(shell, "customers a b"));
        assertEquals("E311", codeOf(shell, "customers --nope"));
        assertEquals("E307", codeOf(shell, "echo \"text\" | collect"));
        assertEquals(3, shell.run("customers | collect").number());
    }

    @Test
    void theCallbackNeverRunsWhenTheArgumentsAreWrong() {
        AtomicInteger calls = new AtomicInteger();
        MainFrame shell = shell()
                .command(customersSpec(), invocation -> {
                    calls.incrementAndGet();
                    return customers();
                })
                .build();
        assertThrows(ShellError.class, () -> shell.run("customers one two three"));
        assertEquals(0, calls.get(), "the callback should not have been reached");
    }

    @Test
    void aTypoSuggestsTheHostedCommand() {
        MainFrame shell = shell().command(customersSpec(), invocation -> customers()).build();
        ShellError error = assertThrows(ShellError.class, () -> shell.run("customer"));
        assertEquals("E301", error.code());
        assertTrue(error.hints().getFirst().contains("customers"), error.hints().toString());
    }

    @Test
    void helpIsGeneratedForHostedCommandsToo() {
        MainFrame shell = shell().command(customersSpec(), invocation -> customers()).build();
        shell.run("help customers");
        assertTrue(printed().contains("customers [filter]"), printed());
        assertTrue(printed().contains("only names containing this"), printed());
        assertTrue(printed().contains("customers | where spend > 1000"), printed());

        out.reset();
        shell.run("customers --help");
        assertTrue(printed().contains("list customers from the host program"), printed());

        out.reset();
        shell.run("help");
        assertTrue(printed().contains("my app"), "the host's category should be listed");
    }

    @Test
    void hostedCommandsFailTheWayBuiltInOnesDo() {
        CommandSpec spec = CommandSpec.named("connect")
                .summary("connect to the host program's database")
                .build();
        MainFrame shell = shell()
                .command(spec, invocation -> {
                    throw invocation.fail("no database is configured",
                            "set one with: env-set DB_URL \"...\"");
                })
                .build();
        ShellError error = assertThrows(ShellError.class, () -> shell.run("connect"));
        assertEquals("E1002", error.code());
        assertEquals("no database is configured", error.getMessage());
        assertTrue(error.hints().getFirst().contains("env-set"), error.hints().toString());
    }

    @Test
    void anUnexpectedExceptionIsReportedAsTheHostsFault() {
        CommandSpec spec = CommandSpec.named("boom").summary("misbehave on purpose").build();
        MainFrame shell = shell()
                .command(spec, invocation -> { throw new IllegalStateException("pool exhausted"); })
                .build();
        ShellError error = assertThrows(ShellError.class, () -> shell.run("boom"));
        assertEquals("E1001", error.code());
        assertTrue(error.getMessage().contains("pool exhausted"), error.getMessage());
        assertTrue(error.hints().getFirst().contains("hosting MainFrame"), error.hints().toString());
    }

    // ---- guardrails for hosted commands --------------------------------------------------

    @Test
    void aDestructiveHostedCommandRefusesToRunUnattended() {
        AtomicInteger deletes = new AtomicInteger();
        MainFrame shell = shell().command(dropSpec(), plan(deletes)).build();

        ShellError error = assertThrows(ShellError.class, () -> shell.run("drop customers"));
        assertEquals("E302", error.code());
        assertEquals(0, deletes.get(), "nothing should have been dropped");
    }

    @Test
    void dryRunShowsAHostedPlanWithoutRunningIt() {
        AtomicInteger deletes = new AtomicInteger();
        MainFrame shell = shell().command(dropSpec(), plan(deletes)).build();

        shell.run("drop customers --dry-run");
        assertTrue(printed().contains("dry run"), printed());
        assertTrue(printed().contains("drop the table customers (3 rows)"), printed());
        assertEquals(0, deletes.get());
    }

    @Test
    void withYesTheHostedPlanRuns() {
        AtomicInteger deletes = new AtomicInteger();
        MainFrame shell = shell().command(dropSpec(), plan(deletes)).build();
        shell.run("drop customers --yes");
        assertEquals(1, deletes.get());
    }

    @Test
    void aHostedCommandThatChangesThingsMustPlan() {
        CommandSpec spec = CommandSpec.named("wipe")
                .summary("claims to be destructive without planning")
                .effect(Effect.DESTRUCTIVE)
                .build();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> shell().command(spec, invocation -> Data.nothing()));
        assertTrue(refused.getMessage().contains("PlannedCommand"), refused.getMessage());
    }

    private static CommandSpec dropSpec() {
        return CommandSpec.named("drop")
                .category("my app")
                .summary("drop a table from the host program's database")
                .argument("table", DataType.TEXT, "the table to drop")
                .effect(Effect.DESTRUCTIVE)
                .example("drop customers --dry-run")
                .build();
    }

    private static PlannedCommand plan(AtomicInteger deletes) {
        return (invocation, steps) -> steps.step(
                "drop the table " + invocation.text(0) + " (3 rows)",
                deletes::incrementAndGet);
    }

    // ---- shaping the shell ---------------------------------------------------------------

    @Test
    void commandsCanBeLeftOut() {
        MainFrame shell = shell().without("rm", "save").build();
        assertFalse(shell.has("rm"));
        assertTrue(shell.has("ls"));
        assertEquals("E301", codeOf(shell, "rm anything"));
    }

    @Test
    void leavingOutSomethingThatIsNotThereIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> shell().without("nonexistent").build());
    }

    @Test
    void aBuiltInNameIsNotTakenBySurprise() {
        CommandSpec spec = CommandSpec.named("ls").summary("my own listing").build();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> shell().command(spec, invocation -> Data.nothing()).build());
        assertTrue(refused.getMessage().contains("replacing"), refused.getMessage());
    }

    @Test
    void aBuiltInCanBeReplacedOnPurpose() {
        CommandSpec spec = CommandSpec.named("ls").summary("my own listing").output(DataType.TEXT).build();
        MainFrame shell = shell().replacing(spec, invocation -> Data.text("mine")).build();
        assertEquals("mine", shell.run("ls").text());
    }

    @Test
    void registeringTheSameNameTwiceIsRefused() {
        CommandSpec spec = CommandSpec.named("twice").summary("once is enough").build();
        MainFrame.Builder builder = shell().command(spec, invocation -> Data.nothing());
        assertThrows(IllegalArgumentException.class, () -> builder.command(spec, invocation -> Data.nothing()));
    }

    // ---- the specification itself ---------------------------------------------------------

    @Test
    void aSpecMustDescribeItself() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("bare").build());
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("x")
                .summary("fine")
                .argument("thing", DataType.TEXT, "  ")
                .build());
    }

    @Test
    void aNameTheShellCouldNotReadIsRefusedAtRegistration() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("has space"));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("2fast"));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("trailing-"));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("we$t"));
        // These are all fine, and all lex as one word.
        CommandSpec.named("db-query").summary("ok").build();
        CommandSpec.named("deploy_now").summary("ok").build();
    }

    @Test
    void requiredArgumentsHaveToComeFirst() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("x")
                .summary("ok")
                .optional("maybe", DataType.TEXT, "optional one")
                .argument("must", DataType.TEXT, "required one")
                .build());
    }

    @Test
    void aHostCannotClaimMainFramesOwnFlags() {
        for (String reserved : List.of("help", "dry-run", "yes")) {
            assertThrows(IllegalArgumentException.class, () -> CommandSpec.named("x")
                    .summary("ok")
                    .switchFlag(reserved, '\0', "clashes with the shell"));
        }
    }

    // ---- the session ------------------------------------------------------------------------

    @Test
    void theHostCanSeeAndSetTheEnvironment() {
        MainFrame shell = shell().env("APP_MODE", "test").build();
        assertEquals("test", shell.env("APP_MODE"));
        assertEquals("test", shell.run("env APP_MODE").text());
        shell.env("APP_MODE", "live");
        assertEquals("live", shell.run("env APP_MODE").text());
    }

    @Test
    void aHostedCommandCanReadTheSessionItRunsIn() {
        CommandSpec spec = CommandSpec.named("context")
                .summary("report what the shell is pointed at")
                .output(DataType.RECORD)
                .build();
        MainFrame shell = shell().env("APP_MODE", "test")
                .command(spec, invocation -> Data.row()
                        .put("directory", invocation.directory())
                        .put("mode", invocation.env("APP_MODE"))
                        .put("dry-run", invocation.dryRun())
                        .build())
                .build();
        assertEquals(here.toString(), shell.run("context | get directory").items().getFirst().text());
        assertEquals("test", shell.run("context | get mode").items().getFirst().text());
    }

    @Test
    void dryRunModeAppliesToHostedCommandsToo() {
        AtomicInteger deletes = new AtomicInteger();
        MainFrame shell = shell().dryRun(true).command(dropSpec(), plan(deletes)).build();
        shell.run("drop customers");
        assertEquals(0, deletes.get());
        assertTrue(printed().contains("nothing was changed"), printed());
    }

    @Test
    void exitIsReportedRatherThanEndingTheProgram() {
        MainFrame shell = shell().build();
        shell.run("exit 3");
        assertEquals(3, shell.exitRequest().orElse(-1));
    }

    @Test
    void theHostCanListWhatTheShellOffers() {
        MainFrame shell = shell().command(customersSpec(), invocation -> customers()).build();
        assertTrue(shell.commands().contains("customers"));
        assertEquals("customers [filter]", shell.usage("customers"));
        assertNull(shell.usage("not-a-command"));
    }

    private static String codeOf(MainFrame shell, String source) {
        try {
            shell.run(source);
            return null;
        } catch (ShellError e) {
            return e.code();
        }
    }
}
