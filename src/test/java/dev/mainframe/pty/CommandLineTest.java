package dev.mainframe.pty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandLineTest {

    @Test
    void charactersOutsideTheAnsiPageComeThroughWhole() {
        String line = "mainframe.exe --dir=C:\\x \"--insert-path=C:\\x\\a\u2019; calc; \u2019b \u7aaf.txt\"";
        assertArrayEquals(new String[] {"mainframe.exe", "--dir=C:\\x", "--insert-path=C:\\x\\a\u2019; calc; \u2019b \u7aaf.txt"},
                CommandLine.split(line));
    }

    @Test
    void quotingIsTheCRuntimesQuoting() {
        // What ProcessBuilder writes for an argument with a space, and a backslash run before a quote.
        assertArrayEquals(new String[] {"p.exe", "a b", "c\\\"d", "e\\f"},
                CommandLine.split("p.exe \"a b\" c\\\\\\\"d e\\f"));
    }

    @Test
    void underTheJvmTheArgumentsAreLeftAlone() {
        String[] args = {"--dir=C:\\x"};
        assertSame(args, CommandLine.unicode(args));
    }
}
