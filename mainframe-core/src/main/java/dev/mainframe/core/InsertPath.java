package dev.mainframe.core;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What goes on a new tab's input line for {@code --insert-path}: a file's name, quoted for the tab's shell, or nothing.
 *
 * <p><b>The rule is that only what can be proved safe is typed.</b> A name is quoted and then every character of it
 * is checked against what that shell's quoting leaves inert, and a name with anything else is not typed at all. It
 * is an allowlist and not a list of dangerous characters on purpose: a denylist is only as good as its author's
 * memory, and PowerShell alone takes four characters besides {@code '} as a single quote. A partial or cleaned-up name
 * is never typed either; it would be a different file, or a name the user has not seen.
 *
 * <p>When the name fails, the file's 8.3 short name (when the volume has one) gets the same check, since it is drawn
 * from a narrow alphabet; failing that, nothing is typed and the notice says why. A name with a control character in
 * it is refused before either, so no fallback is ever reached on its behalf.
 *
 * <p>Nothing here touches a shell or the disk, so the whole decision is unit-tested; the short name is asked for only
 * when it is needed.
 */
public final class InsertPath {

    private InsertPath() {
    }

    /** How a shell quotes, as far as this needs to know. */
    public enum Dialect {
        /**
         * Windows PowerShell and pwsh: single quotes, inside which nothing is expanded and the only special
         * character is the quote itself, written twice. The curly single quotes count as quotes too, so they are not
         * on the list.
         */
        POWERSHELL("PowerShell", " ._-()[]{},+=@#~&;$`%'"),
        /**
         * cmd: double quotes, which make {@code & | < > ^ ( )} literal but not {@code %} (a variable) or {@code !}
         * (delayed expansion), so those two are not on the list. Nor is {@code "}, which Windows does not allow in a
         * name, but the check does not take that on trust.
         */
        CMD("Command Prompt", " ._-()[]{},+=@#~&;$'`");

        private final String label;
        private final String punctuation;

        Dialect(String label, String punctuation) {
            this.label = label;
            this.punctuation = punctuation;
        }

        /** The dialect of a shell MainFrame knows by its settings id; empty for anything else. */
        public static Optional<Dialect> of(String shellId) {
            return switch (shellId) {
                case "powershell", "pwsh" -> Optional.of(POWERSHELL);
                case "cmd" -> Optional.of(CMD);
                default -> Optional.empty();
            };
        }

        /** {@code name} quoted for this shell, or empty if some character of it is not provably inert. */
        Optional<String> quote(String name) {
            if (name.isEmpty()) {
                return Optional.empty();
            }
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                if (!letterOrDigit(cp) && (cp > 0x7e || punctuation.indexOf(cp) < 0)) {
                    return Optional.empty();
                }
                i += Character.charCount(cp);
            }
            return Optional.of(switch (this) {
                case POWERSHELL -> "'" + name.replace("'", "''") + "'";
                case CMD -> "\"" + name + "\"";
            });
        }
    }

    /** What to do: type {@code text} (and say {@code notice}, if not null), or type nothing and say {@code notice}. */
    record Outcome(String text, String notice) {
        static Outcome typed(String text, String notice) {
            return new Outcome(text, notice);
        }

        static Outcome refused(String notice) {
            return new Outcome(null, notice);
        }

        boolean typesSomething() {
            return text != null;
        }
    }

    /**
     * Decide what to type for the file called {@code name}, in a shell of {@code dialect} (null when MainFrame
     * cannot tell which shell the tab runs). {@code shortName} is the file's 8.3 name, asked for only if the name
     * itself fails.
     */
    static Outcome decide(Dialect dialect, String name, Supplier<Optional<String>> shortName) {
        String shown = printable(name);
        if (dialect == null) {
            return Outcome.refused("This tab runs a shell given on the command line, and MainFrame cannot tell how it"
                    + " quotes, so " + shown + " was not typed.");
        }
        if (hasControl(name)) {
            return Outcome.refused(shown + " has a control character in its name, so it was not typed.");
        }
        Optional<String> token = dialect.quote(name);
        if (token.isPresent()) {
            return Outcome.typed(token.get(), null);
        }
        Optional<String> alias = shortName.get().filter(s -> !s.equals(name)).flatMap(dialect::quote);
        if (alias.isPresent()) {
            return Outcome.typed(alias.get(), "Typed the short name " + alias.get() + " for " + shown
                    + ": the full name cannot be typed safely into " + dialect.label + ".");
        }
        return Outcome.refused(shown + " cannot be typed safely into " + dialect.label + ", so it was not typed.");
    }

    /** Letters of a script and decimal digits. Not modifier letters, some of which look like apostrophes. */
    private static boolean letterOrDigit(int cp) {
        return switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                 Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER -> true;
            default -> false;
        };
    }

    /** C0, DEL and C1: CR, LF and ESC among them. */
    public static boolean hasControl(String s) {
        return s.codePoints().anyMatch(cp -> cp < 0x20 || (cp >= 0x7f && cp <= 0x9f));
    }

    /** The name as a notice may show it: quoted, with control characters drawn as a replacement mark. */
    private static String printable(String name) {
        StringBuilder out = new StringBuilder("“");
        name.codePoints().forEach(cp -> out.appendCodePoint(cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) ? 0xfffd : cp));
        return out.append('”').toString();
    }
}
