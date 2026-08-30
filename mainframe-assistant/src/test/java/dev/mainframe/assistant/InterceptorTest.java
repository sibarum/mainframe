package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What must never be forwarded, however badly it was typed. */
class InterceptorTest {

    @Test
    void catchesCredentialsHoweverTheyAreWorded() {
        // None of these parse. All of them would otherwise reach a model.
        for (String line : new String[] {
                "set my api key to sk-abc123",
                "remember the password hunter2",
                "store this token pls",
                "save my API-KEY",
                "whats my passphrase again" }) {
            assertTrue(Interceptor.holds(line), line);
        }
    }

    @Test
    void catchesSomethingPastedThatLooksLikeAKey() {
        // Built rather than written out, and that is not squeamishness. A literal shaped like a real
        // vendor key is refused by GitHub's push protection on the shape alone, whether or not it was
        // ever a key — and the test suite for a secret detector is the one place that collision is
        // guaranteed to happen. It costs nothing here, because holds() has no notion of a vendor: what
        // it looks for is an unbroken run of key-ish characters, and 44 of them is 44 of them.
        String pasted = "k3yM4t3r14l".repeat(4);
        assertTrue(Interceptor.holds("put this somewhere " + pasted));
    }

    @Test
    void ordinaryConfusionIsLetThrough() {
        for (String line : new String[] {
                "what files are here",
                "Get-ChildItem",
                "show me the biggest thing in this folder",
                "delete the staging deploys from last month" }) {
            assertFalse(Interceptor.holds(line), line);
        }
    }

    @Test
    void sayingWhyIsPartOfRefusing() {
        // A shell that answers everything else and then goes quiet has taught the
        // person nothing except that it is unreliable.
        assertTrue(Interceptor.reply().toLowerCase().contains("not sent"));
    }
}
