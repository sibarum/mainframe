package dev.mainframe.term;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalTest {

    private final StringBuilder replies = new StringBuilder();
    private final Terminal t = new Terminal(10, 4, new Terminal.Host() {
        public void reply(String s) { replies.append(s); }
        public void title(String s) { replies.append("T:").append(s); }
    });

    private String row(int r) {
        return t.text(t.scrollbackSize() + r, 0, t.scrollbackSize() + r, t.cols() - 1);
    }

    @Test
    void printsAndWraps() {
        t.feed("0123456789AB");
        assertEquals("0123456789", row(0));
        assertEquals("AB", row(1));
    }

    @Test
    void cursorPositioningAndErase() {
        t.feed("hello\u001b[1;3H\u001b[K");
        assertEquals("he", row(0));
        t.feed("\u001b[2J");
        assertEquals("", row(0));
    }

    @Test
    void scrollsIntoHistory() {
        t.feed("a\r\nb\r\nc\r\nd\r\ne");
        assertEquals(1, t.scrollbackSize());
        assertEquals("a", t.text(0, 0, 0, 9));
        assertEquals("e", row(3));
    }

    @Test
    void sgrColours() {
        t.feed("\u001b[31mR\u001b[0mN\u001b[38;2;1;2;3mX");
        assertEquals(Terminal.Palette.xterm(1), t.lineAt(0).fg[0]);
        assertEquals(Terminal.DEFAULT, t.lineAt(0).fg[1]);
        assertEquals(0x010203, t.lineAt(0).fg[2]);
    }

    @Test
    void altScreenRestoresMain() {
        t.feed("main\u001b[?1049h\u001b[2J\u001b[Halt\u001b[?1049l");
        assertEquals("main", row(0));
    }

    @Test
    void answersCursorPositionAndSetsTitle() {
        t.feed("ab\u001b[6n\u001b]0;hi\u0007");
        assertEquals("\u001b[1;3RT:hi", replies.toString());
    }

    @Test
    void resizeKeepsContent() {
        t.feed("abc");
        t.resize(20, 6);
        assertEquals("abc", row(0));
        assertEquals(20, t.cols());
    }
}
