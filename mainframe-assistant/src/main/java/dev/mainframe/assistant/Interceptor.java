package dev.mainframe.assistant;

import java.util.List;
import java.util.Locale;

/**
 * What never leaves the process, even when nothing understood it.
 *
 * <p>The assistant is reached only when MainFrame could not read a line, which
 * is what makes it cheap: the cost lands on input that was going to be an error
 * anyway. But that ordering is also the danger. A correctly typed line that sets
 * a credential parses, runs locally, and no model ever sees it. The line that
 * reaches the model is the one that was typed <em>wrong</em> -- and a mistyped
 * secret is still a secret, now in a request body on somebody else's machine.
 *
 * <p>So this runs first, before the parser, and matches more loosely than the
 * parser does. It has to: it exists to cover the path taken when the strict
 * reading already failed. Matching loosely means it will sometimes stop a line
 * that was harmless. That is the correct way round -- the cost of a false catch
 * is retyping a sentence, and the cost of a miss cannot be taken back.
 */
public final class Interceptor {

    /**
     * Words that suggest the line carries something that should not be sent
     * anywhere. Deliberately broad, and matched against the raw line rather than
     * against any parse of it, since there is no parse.
     */
    private static final List<String> SECRETS = List.of(
            "password", "passwd", "secret", "credential", "api key", "apikey",
            "api-key", "token", "private key", "passphrase", "auth");

    private Interceptor() {}

    /** True when this line must be answered locally or refused, never forwarded. */
    public static boolean holds(String line) {
        if (line == null) return false;
        String text = line.toLowerCase(Locale.ROOT);
        for (String word : SECRETS) if (text.contains(word)) return true;
        // A long unbroken run of key-ish characters is what a pasted key looks
        // like, whatever it was typed after.
        return looksPasted(line);
    }

    /**
     * What to say instead. Not an error: the person did nothing wrong, and the
     * reason they are being turned away is worth telling them, because otherwise
     * a shell that answers everything else has just gone quiet for no visible
     * reason.
     */
    public static String reply() {
        return """
                That line looks like it carries a secret, so it was not sent anywhere.
                Say it again as a real command and it will run locally, as it always has.""";
    }

    private static boolean looksPasted(String line) {
        int run = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean keyish = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            run = keyish ? run + 1 : 0;
            if (run >= 32) return true;
        }
        return false;
    }
}
