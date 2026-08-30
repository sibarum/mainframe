package dev.mainframe.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.mainframe.eval.Signature;

/** What the assistant may do on its own, and what it must ask about. */
class GateTest {

    @Test
    void readingIsAllowedOutright() {
        assertEquals(Gate.RUN, Gate.on(Signature.Effect.PURE));
        assertEquals(Gate.RUN, Gate.on(Signature.Effect.READS));
    }

    @Test
    void anythingThatChangesAnythingIsAskedAbout() {
        assertEquals(Gate.ASK, Gate.on(Signature.Effect.WRITES));
        assertEquals(Gate.ASK, Gate.on(Signature.Effect.DESTRUCTIVE));
    }

    @Test
    void changingTheSessionCountsAsChangingSomething() {
        // Moving the shell somewhere else loses no data, and doing it quietly
        // changes what every command after this one means.
        assertEquals(Gate.ASK, Gate.on(Signature.Effect.SESSION));
    }

    @Test
    void everyEffectIsAccountedFor() {
        for (Signature.Effect effect : Signature.Effect.values()) {
            assertTrue(Gate.on(effect) == Gate.RUN || Gate.on(effect) == Gate.ASK,
                    "no gate decided for " + effect);
        }
    }
}
