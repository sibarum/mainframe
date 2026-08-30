package dev.mainframe.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mainframe.MfError;
import dev.mainframe.Span;
import dev.mainframe.panel.Screen;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * A field that holds a key, and the one rule it is allowed to break.
 */
class SecretFieldTest {

    private static Value spec(Value.Rec... fields) {
        return new Value.ListVal(List.of(fields));
    }

    private static Value.Rec field(String name, Object... pairs) {
        Value.Rec rec = Value.Rec.of().with("name", new Value.Str(name));
        for (int i = 0; i < pairs.length; i += 2) {
            Object raw = pairs[i + 1];
            Value value = raw instanceof Boolean b ? new Value.Bool(b)
                    : raw instanceof Value v ? v
                    : new Value.Str(String.valueOf(raw));
            rec = rec.with((String) pairs[i], value);
        }
        return rec;
    }

    private static Form read(Value.Rec... fields) {
        return Form.read(spec(fields), Span.NONE);
    }

    // ---- declaring one -------------------------------------------------------------------

    @Test
    void anOrdinaryFieldIsNotSecret() {
        assertFalse(read(field("email")).field("email").isSecret());
    }

    @Test
    void aFieldCanSayItHoldsASecret() {
        Form.Field key = read(field("key", "secret", true, "label", "api key")).field("key");
        assertTrue(key.isSecret());
        assertEquals("api key", key.label());
    }

    // ---- what it may not also be ---------------------------------------------------------

    @Test
    void aSecretCannotCarryADefault() {
        // A default exists to be sent down and shown, which is the one thing a
        // secret never does.
        MfError thrown = assertThrows(MfError.class,
                () -> read(field("key", "secret", true, "default", "sk-ant-example")));
        assertTrue(thrown.getMessage().contains("default"), thrown.getMessage());
    }

    @Test
    void aSecretIsText() {
        assertThrows(MfError.class, () -> read(field("key", "secret", true, "type", "int")));
    }

    @Test
    void aSecretCannotBeChosenFromAList() {
        // The list would have to travel, and the real answer would be in it.
        assertThrows(MfError.class, () -> read(field("key", "secret", true,
                "choose", new Value.ListVal(List.of(new Value.Str("a"), new Value.Str("b"))))));
    }

    @Test
    void aSecretCannotBeAGroup() {
        assertThrows(MfError.class, () -> read(field("keys", "secret", true,
                "fields", spec(field("one")))));
    }

    @Test
    void refusalHappensWhenTheFormIsRead() {
        // Not when it is drawn. A form that claims secret and hands the value out
        // anyway is worse than one that never claimed it.
        assertThrows(MfError.class, () -> read(field("key", "secret", true, "default", "x")));
    }

    // ---- how it travels ------------------------------------------------------------------

    @Test
    void aSecretEntryCarriesNoValueAtAll() {
        Value.Rec message = new Screen(1).secret(0, 0, "key", 40).message();
        Value.Rec entry = onlyEntry(message);
        // Not an empty string standing in for the real one -- no value key.
        assertNull(entry.get("value"));
        assertEquals("true", Values.display(entry.get("secret")));
    }

    @Test
    void anOrdinaryEntryStillCarriesItsValue() {
        Value.Rec entry = onlyEntry(new Screen(1).entry(0, 0, "email", 40, "a@b.c", "string").message());
        assertEquals("a@b.c", Values.display(entry.get("value")));
        assertNull(entry.get("secret"));
    }

    @Test
    void maskingIsACapabilityRatherThanAnOfferToIgnore() {
        // pick degrades safely: an editor that never heard of it lets the path be
        // typed, and the answer is identical. secret does not degrade -- ignoring
        // it shows the key on screen and echoes it in every event -- so it is
        // negotiated instead of assumed.
        assertFalse(dev.mainframe.panel.Editor.Hello.silent().can().contains(Screen.SECRET));
    }

    private static Value.Rec onlyEntry(Value.Rec message) {
        Value screen = message.get("screen");
        Value.Rec body = (Value.Rec) screen;
        for (Value part : ((Value.ListVal) body.get("parts")).items()) {
            Value.Rec rec = (Value.Rec) part;
            if (rec.get("entry") != null) return rec;
        }
        throw new AssertionError("no entry in " + Values.display(message));
    }
}
