package dev.mainframe;

import dev.mainframe.InsertPath.Dialect;
import dev.mainframe.InsertPath.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What {@code --insert-path} types. Names that are hostile to a shell, each against each shell; and then random names,
 * where anything typed at all is held to a description of a safe token written independently of the code under test.
 */
class InsertPathTest {

    private static final Supplier<Optional<String>> NO_SHORT_NAME = Optional::empty;

    private static Outcome ps(String name) {
        return InsertPath.decide(Dialect.POWERSHELL, name, NO_SHORT_NAME);
    }

    private static Outcome cmd(String name) {
        return InsertPath.decide(Dialect.CMD, name, NO_SHORT_NAME);
    }

    @Test
    void anOrdinaryNameIsTypedQuotedForEachShell() {
        assertEquals("'glaze recipes (v2).pdf'", ps("glaze recipes (v2).pdf").text());
        assertEquals("\"glaze recipes (v2).pdf\"", cmd("glaze recipes (v2).pdf").text());
        assertNull(ps("glaze recipes (v2).pdf").notice());
    }

    @Test
    void anApostropheIsDoubledForPowerShellAndLiteralForCmd() {
        assertEquals("'John''s notes.txt'", ps("John's notes.txt").text());
        assertEquals("\"John's notes.txt\"", cmd("John's notes.txt").text());
    }

    @Test
    void whatPowerShellWouldExpandOutsideQuotesIsInertInsideThem() {
        assertEquals("'$(calc).txt'", ps("$(calc).txt").text());
        assertEquals("'`calc`.txt'", ps("`calc`.txt").text());
        assertEquals("'a & calc; b.txt'", ps("a & calc; b.txt").text());
        assertEquals("'%PATH%.txt'", ps("%PATH%.txt").text());
    }

    @Test
    void curlyQuotesAreQuotesToPowerShellSoTheyAreNotTyped() {
        for (String q : new String[] {"‘", "’", "‚", "‛", "“", "”", "„"}) {
            assertFalse(ps("a" + q + "; calc; " + q + "b.txt").typesSomething(), "U+" + Integer.toHexString(q.charAt(0)));
        }
    }

    @Test
    void aModifierLetterApostropheIsNotTakenForALetter() {
        assertFalse(ps("donʼt.txt").typesSomething());
    }

    @Test
    void cmdExpandsPercentAndBangInsideQuotesSoThoseAreNotTyped() {
        assertFalse(cmd("%PATH%.txt").typesSomething());
        assertFalse(cmd("a!b!.txt").typesSomething());
        assertEquals("\"a & calc.txt\"", cmd("a & calc.txt").text());
        assertFalse(cmd("a^b.txt").typesSomething());
    }

    @Test
    void controlCharactersAreRefusedBeforeAnyFallback() {
        Supplier<Optional<String>> shortName = () -> fail("the short name is not asked for a name with a control in it");
        for (String name : new String[] {"a\nb.txt", "a\rcalc.txt", "a\u001b[2Jb.txt", "a\u0085b.txt", "a\u007fb.txt"}) {
            Outcome o = InsertPath.decide(Dialect.POWERSHELL, name, shortName);
            assertFalse(o.typesSomething());
            assertTrue(o.notice().contains("control character"));
            assertFalse(InsertPath.hasControl(o.notice()), "the notice draws the control rather than carrying it");
        }
    }

    @Test
    void aNameThatFailsFallsBackToItsShortName() {
        Outcome o = InsertPath.decide(Dialect.CMD, "glaze%recipes!.pdf", () -> Optional.of("GLAZE~1.PDF"));
        assertEquals("\"GLAZE~1.PDF\"", o.text());
        assertTrue(o.notice().contains("short name"));
    }

    @Test
    void aShortNameIsCheckedLikeAnyOther() {
        Outcome o = InsertPath.decide(Dialect.CMD, "a%b.pdf", () -> Optional.of("A%B~1.PDF"));
        assertFalse(o.typesSomething());
        assertTrue(o.notice().contains("cannot be typed safely"));
    }

    @Test
    void aShellMainFrameCannotNameIsRefused() {
        Outcome o = InsertPath.decide(null, "plain.txt", NO_SHORT_NAME);
        assertFalse(o.typesSomething());
        assertTrue(o.notice().contains("cannot tell how it quotes"));
    }

    @Test
    void nonLatinLettersAreTyped() {
        assertEquals("'窯の記録 2024.txt'", ps("窯の記録 2024.txt").text());
        assertEquals("\"Глазурь.txt\"", cmd("Глазурь.txt").text());
    }

    @Test
    void combiningMarksAreNotOnTheList() {
        assertFalse(ps("café.txt").typesSomething());
    }

    /**
     * Random names, many of them hostile: whenever anything is typed, it must be one single-quoted (PowerShell) or
     * double-quoted (cmd) string, by rules stated here rather than borrowed from the code.
     */
    @Test
    void whateverIsTypedIsOneInertQuotedString() {
        Random r = new Random(20261010);
        String pool = "aZ09 ._-'\"`$%!^&|;<>(){}[]@#~,+=*?\\/:\t\n\r\u001b\u0000\u007f\u0085"
                + "‘’‚‛“”„ʼ́＂é窯Ж";
        int typed = 0;
        for (int i = 0; i < 50_000; i++) {
            StringBuilder name = new StringBuilder();
            for (int n = 1 + r.nextInt(12); n > 0; n--) name.append(pool.charAt(r.nextInt(pool.length())));
            for (Dialect d : Dialect.values()) {
                Outcome o = InsertPath.decide(d, name.toString(), NO_SHORT_NAME);
                if (!o.typesSomething()) continue;
                typed++;
                String t = o.text();
                if (d == Dialect.POWERSHELL) assertInertForPowerShell(t, name.toString());
                else assertInertForCmd(t, name.toString());
            }
        }
        assertTrue(typed > 1000, "the generator reaches names that are typed, not only refusals: " + typed);
    }

    private static void assertInertForPowerShell(String t, String name) {
        assertTrue(t.length() >= 2 && t.startsWith("'") && t.endsWith("'"), t);
        String inside = t.substring(1, t.length() - 1);
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            assertFalse(c < 0x20 || (c >= 0x7f && c <= 0x9f), t);
            assertFalse("‘’‚‛“”„\"".indexOf(c) >= 0, t);
            if (c == '\'') {
                assertTrue(i + 1 < inside.length() && inside.charAt(i + 1) == '\'', "a lone quote ends the string: " + t);
                i++;
            }
        }
        assertEquals(name, inside.replace("''", "'"), "what PowerShell reads back is the name");
    }

    private static void assertInertForCmd(String t, String name) {
        assertTrue(t.length() >= 2 && t.startsWith("\"") && t.endsWith("\""), t);
        String inside = t.substring(1, t.length() - 1);
        for (char c : inside.toCharArray()) {
            assertFalse(c < 0x20 || (c >= 0x7f && c <= 0x9f), t);
            assertFalse("\"%!^".indexOf(c) >= 0, t);
        }
        assertEquals(name, inside);
    }
}
