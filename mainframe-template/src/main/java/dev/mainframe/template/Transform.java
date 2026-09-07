package dev.mainframe.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The handful of ways one answer becomes another.
 *
 * <p>A closed set, and small on purpose. A template that could run arbitrary code
 * to work out a placeholder would be a program, and the thing that makes a
 * template safe to read from a folder somebody else wrote is that it is not one.
 * So this is the whole vocabulary: enough to turn {@code my-app} into
 * {@code MyApp} and a package into a path, and not enough to do anything else.
 */
public enum Transform {

    /** As typed. */
    TEXT {
        @Override public String apply(String text) { return text; }
    },

    /** Dots to slashes: a package as the folder it lives in. */
    PATH {
        @Override public String apply(String text) { return text.replace('.', '/'); }
    },

    /** {@code my-app} to {@code MyApp}: a name as a Java type. */
    TYPE {
        @Override public String apply(String text) {
            StringBuilder out = new StringBuilder(text.length());
            boolean up = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '-' || c == '_' || c == ' ' || c == '.') {
                    up = true;
                } else if (up) {
                    out.append(Character.toUpperCase(c));
                    up = false;
                } else {
                    out.append(c);
                }
            }
            // A type has to start with a letter, and a project called "3d-plot" is
            // a perfectly good project. Prefixed rather than refused, because this
            // is a suggestion in a field somebody can still edit.
            if (!out.isEmpty() && !Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, 'A');
            return out.toString();
        }
    },

    /** {@code My App} to {@code my-app}: a name as an artifactId or a folder. */
    SLUG {
        @Override public String apply(String text) {
            StringBuilder out = new StringBuilder(text.length());
            boolean dash = false;
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toLowerCase(text.charAt(i));
                if (Character.isLetterOrDigit(c)) {
                    out.append(c);
                    dash = false;
                } else if (!dash && !out.isEmpty()) {
                    out.append('-');
                    dash = true;
                }
            }
            while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') out.setLength(out.length() - 1);
            return out.toString();
        }
    },

    /** {@code My App} to {@code myapp}: a name as one package segment. */
    SEGMENT {
        @Override public String apply(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toLowerCase(text.charAt(i));
                if (Character.isLetterOrDigit(c)) out.append(c);
            }
            if (!out.isEmpty() && Character.isDigit(out.charAt(0))) out.insert(0, 'p');
            return out.toString();
        }
    },

    /** Every segment of a dotted name made safe to be one. */
    PACKAGE {
        @Override public String apply(String text) {
            List<String> parts = new ArrayList<>();
            for (String part : text.split("\\.")) {
                String segment = SEGMENT.apply(part);
                if (!segment.isEmpty()) parts.add(segment);
            }
            return String.join(".", parts);
        }
    },

    /** Shouted, for a constant. */
    UPPER {
        @Override public String apply(String text) { return text.toUpperCase(Locale.ROOT); }
    },

    /** Quietened. */
    LOWER {
        @Override public String apply(String text) { return text.toLowerCase(Locale.ROOT); }
    },

    /** {@code my-app} to {@code My app}: a name as a window title. */
    TITLE {
        @Override public String apply(String text) {
            String spaced = text.replace('-', ' ').replace('_', ' ').trim();
            if (spaced.isEmpty()) return spaced;
            return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        }
    };

    public abstract String apply(String text);

    /** The one written like this, or null -- which is a misspelling, not a crash. */
    public static Transform of(String written) {
        for (Transform transform : values()) {
            if (transform.name().toLowerCase(Locale.ROOT).equals(written)) return transform;
        }
        return null;
    }

    /** The words, for a listing and for a "did you mean". */
    public static List<String> names() {
        List<String> names = new ArrayList<>(values().length);
        for (Transform transform : values()) names.add(transform.name().toLowerCase(Locale.ROOT));
        return List.copyOf(names);
    }
}
