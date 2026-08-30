package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.mainframe.eval.Signature;
import dev.mainframe.value.ValueType;

/** Turning a command's declaration into something a model can call. */
class ToolSchemaTest {

    private static Signature deploy() {
        return Signature.named("deploy", "my app")
                .summary("push the current build to an environment")
                .required("environment", ValueType.STRING, "where to deploy: staging or live")
                .optional("since", ValueType.DURATION, "only include work from this far back")
                .switchFlag("skip-tests", '\0', "deploy without running the test suite")
                .valueFlag("budget", 'b', ValueType.SIZE, "refuse to upload more than this")
                .effect(Signature.Effect.DESTRUCTIVE)
                .example("deploy staging")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> tool) {
        return (Map<String, Object>) ((Map<String, Object>) tool.get("input_schema")).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> tool, String name) {
        return (Map<String, Object>) properties(tool).get(name);
    }

    @Test
    void namesTheCommandAndItsArguments() {
        Map<String, Object> tool = ToolSchema.of(deploy());
        assertEquals("deploy", tool.get("name"));
        assertEquals(List.of("environment", "since", "--skip-tests", "--budget"),
                List.copyOf(properties(tool).keySet()));
    }

    @Test
    void onlyRequiredArgumentsAreRequired() {
        Map<String, Object> schema = (Map<String, Object>) ToolSchema.of(deploy()).get("input_schema");
        assertEquals(List.of("environment"), schema.get("required"));
        // An invented argument should be refused by the model's own validation
        // rather than by MainFrame after the call has already been made.
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    void flagsKeepTheDashesTheyAreTypedWith() {
        Map<String, Object> tool = ToolSchema.of(deploy());
        assertEquals("boolean", property(tool, "--skip-tests").get("type"));
        assertEquals("string", property(tool, "--budget").get("type"));
    }

    @Test
    void typesWrittenAsTextSayHowTheyWillBeRead() {
        Map<String, Object> tool = ToolSchema.of(deploy());
        // A size is a string on the wire; saying "number" would be tidier and
        // would produce calls MainFrame cannot accept.
        Map<String, Object> budget = property(tool, "--budget");
        assertEquals("string", budget.get("type"));
        assertTrue(budget.get("description").toString().contains("10mb"), budget.toString());
        assertTrue(property(tool, "since").get("description").toString().contains("7d"));
    }

    @Test
    void descriptionCarriesUsageAndExamples() {
        String description = ToolSchema.of(deploy()).get("description").toString();
        assertTrue(description.startsWith("push the current build"), description);
        assertTrue(description.contains("deploy <environment> [since]"), description);
        assertTrue(description.contains("deploy staging"), description);
    }

    @Test
    void theModelIsNeverOfferedTheConfirmationGate() {
        // MainFrame adds --yes and --dry-run to anything destructive. --yes is the
        // switch that decides whether a person is asked before data is lost, so a
        // model that could set it could excuse itself from being checked.
        Map<String, Object> properties = properties(ToolSchema.of(deploy()));
        assertFalse(properties.containsKey("--yes"), properties.keySet().toString());
        assertFalse(properties.containsKey("--dry-run"), properties.keySet().toString());
    }

    @Test
    void aRestArgumentArrivesAsAList() {
        Signature remove = Signature.named("remove", "files")
                .summary("delete files")
                .rest("paths", ValueType.PATH, "the files to delete")
                .effect(Signature.Effect.DESTRUCTIVE)
                .build();
        Map<String, Object> paths = property(ToolSchema.of(remove), "paths");
        assertEquals("array", paths.get("type"));
        assertEquals(Map.of("type", "string", "description",
                "a filesystem path, resolved against the current directory"), paths.get("items"));
    }

    @Test
    void aCommandThatTakesThePipeSaysSo() {
        Signature where = Signature.named("where", "tables")
                .summary("keep the rows that match")
                .input(ValueType.TABLE)
                .build();
        assertTrue(ToolSchema.of(where).get("description").toString()
                .contains("Accepts a table from the pipe"));
        assertFalse(ToolSchema.of(deploy()).get("description").toString().contains("from the pipe"));
    }

    @Test
    void aRosterRendersInOrder() {
        List<Map<String, Object>> tools = ToolSchema.of(List.of(deploy(), deploy()));
        assertEquals(2, tools.size());
        assertEquals("deploy", tools.get(0).get("name"));
    }
}
