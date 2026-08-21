package dev.mainframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mainframe.eval.Registry;
import dev.mainframe.value.Value;

/** Piping typed values from one command to the next. */
class PipelineTest {

    @TempDir
    Path here;

    private Mf mf() { return new Mf(here); }

    private void tree() throws IOException {
        Files.writeString(here.resolve("small.txt"), "x");
        Files.writeString(here.resolve("big.txt"), "y".repeat(4096));
        Files.writeString(here.resolve("page.html"), "<html><body>hi</body></html>");
        Files.createDirectories(here.resolve("sub"));
        Files.writeString(here.resolve("sub/note.md"), "# note");
    }

    @Test
    void filteringOnColumnsWithoutQuotingThem() throws IOException {
        tree();
        Value kept = mf().eval("ls | where size > 1kb");
        assertEquals(1, ((Value.ListVal) kept).items().size());
    }

    @Test
    void mediaTypeIsAFirstClassColumn() throws IOException {
        tree();
        Value texts = mf().eval("ls | where kind == \"file\" and mime =~ \"text/\"");
        assertEquals(3, ((Value.ListVal) texts).items().size());

        Value html = mf().eval("ls | where mime == \"text/html\" | get name");
        assertEquals(new Value.Str("page.html"), ((Value.ListVal) html).items().getFirst());

        Value types = mf().eval("ls page.html | get mime | first");
        assertEquals("text", ((Value.Mime) types).type());
    }

    @Test
    void sortingAndSlicing() throws IOException {
        tree();
        Value biggest = mf().eval("ls | sort-by size --reverse | first | get name");
        assertEquals(new Value.Str("big.txt"), ((Value.ListVal) biggest).items().getFirst());
    }

    @Test
    void selectComplainsAboutColumnsThatAreNotThere() throws IOException {
        tree();
        MfError error = mf().error("ls | select nmae");
        assertEquals("E701", error.code());
        assertTrue(error.hints().getFirst().contains("name"), error.hints().toString());
    }

    @Test
    void countingAndAdding() throws IOException {
        tree();
        assertEquals(new Value.Int(4), mf().eval("ls | length"));
        assertEquals(new Value.Size(4096 + 1 + 28), mf().eval("ls | where kind == \"file\" | sum size"));
    }

    @Test
    void jsonGoesOutAndComesBack() throws IOException {
        tree();
        Mf mf = mf();
        Value round = mf.eval("ls | select name | to-json | from-json | length");
        assertEquals(new Value.Int(4), round);
    }

    @Test
    void textCommandsWorkOnFiles() throws IOException {
        Files.writeString(here.resolve("notes.txt"), "one\ntwo\nthree\n");
        assertEquals(new Value.Int(3), mf().eval("cat notes.txt | lines | length"));
    }

    @Test
    void eachRunsABlockPerItem() throws IOException {
        tree();
        Value names = mf().eval("ls | get name | each { echo $it }");
        assertEquals(4, ((Value.ListVal) names).items().size());
    }

    @Test
    void wrongInputTypeIsCaughtBeforeAnythingRuns() {
        MfError error = mf().error("echo \"text\" | select name");
        assertEquals("E307", error.code());
    }

    @Test
    void tooManyArgumentsIsCaughtBeforeAnythingRuns() {
        assertEquals("E304", mf().errorCode("pwd extra"));
    }

    @Test
    void missingArgumentsExplainWhatIsNeeded() {
        MfError error = mf().error("index-build");
        assertEquals("E306", error.code());
        assertTrue(error.getMessage().contains("name"), error.getMessage());
    }

    @Test
    void everyBuiltinIsDocumentedAndUsable() {
        for (var builtin : Registry.standard().all()) {
            var signature = builtin.signature();
            assertTrue(!signature.summary().isBlank(), signature.name() + " has no summary");
            assertTrue(!signature.category().isBlank(), signature.name() + " has no category");
            for (var param : signature.params()) {
                assertTrue(!param.description().isBlank(),
                        signature.name() + " does not describe its " + param.name());
            }
            for (var flag : signature.flags()) {
                assertTrue(!flag.description().isBlank(),
                        signature.name() + " does not describe --" + flag.name());
            }
        }
    }

    @Test
    void helpWorksForEveryCommand() {
        Mf mf = mf();
        for (var builtin : Registry.standard().all()) {
            mf.eval("help " + builtin.signature().name());
        }
        assertTrue(mf.printed().contains("usage"));
    }

    @Test
    void unknownCommandsSuggestSomethingReal() {
        MfError error = mf().error("lenght");
        assertEquals("E301", error.code());
        assertTrue(error.hints().getFirst().contains("length"), error.hints().toString());
    }
}
