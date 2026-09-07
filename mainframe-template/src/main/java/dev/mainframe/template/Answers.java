package dev.mainframe.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What somebody said, and what follows from it.
 *
 * <p>Everything is text here, including the yes-or-nos, and that is deliberate:
 * the same answers have to arrive from a form somebody filled in, from a record
 * that came down a pipe, and from a line of {@code --set}, and the one
 * shape all three can agree on is a name and a string. Reading them as anything
 * richer is the shell's business, and the shell has already done it by the time
 * they get here.
 *
 * <p>Substitution is deliberately dumb. {@code ${name}} and nothing else -- no
 * expressions, no conditionals, no loops. A template is a folder somebody may
 * have been handed, and a template language that could compute is a template
 * language that can be made to do something other than what it looks like it
 * does. What it will not do quietly is leave a {@code ${placeholder}} in a
 * generated file: an unknown name is refused, with the list of ones that exist.
 */
public final class Answers {


    private final Map<String, String> values;

    private Answers(Map<String, String> values) {
        this.values = values;
    }

    public static Answers of(Map<String, String> values) {
        return new Answers(new LinkedHashMap<>(values));
    }

    /** What is known, in the order it became known. */
    public Map<String, String> values() { return Map.copyOf(values); }

    public String get(String name) { return values.get(name); }

    public boolean has(String name) {
        String value = values.get(name);
        return value != null && !value.isBlank();
    }

    /** A yes-or-no, as the file list reads one. */
    public boolean flag(String name) {
        String value = values.get(name);
        if (value == null) return false;
        String lower = value.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.equals("true") || lower.equals("yes") || lower.equals("y") || lower.equals("1");
    }

    /**
     * The answers a template starts a form with: every slot's {@code preset}, with
     * the ones before it already substituted in.
     *
     * <p>Presets are resolved in slot order rather than all at once, so a preset
     * may name a slot above it and cannot name one below. That ordering is the
     * whole of the rule, and it is worth stating because the alternative -- a
     * preset that reaches forward -- would have to guess at an answer nobody has
     * given yet and would put that guess on the screen as though it were one.
     */
    public static Answers presets(Template template) {
        Map<String, String> values = new LinkedHashMap<>();
        Answers running = new Answers(values);
        for (Template.Slot slot : template.slots()) {
            if (slot.preset() == null) continue;
            // Leniently: a preset that names a slot nobody has answered yet keeps
            // its ${...} rather than throwing, because a preset is a suggestion and
            // an unanswered one is the ordinary case on the way down the list.
            values.put(slot.name(), running.substitute(slot.preset(), true));
        }
        return new Answers(values);
    }

    /**
     * Everything the template works out for itself, applied in order.
     *
     * <p>Two kinds, and they are different questions. {@code always} is a value
     * nobody is asked about -- a package as a path, a name as a type -- and asking
     * would only be inviting two answers that disagree. {@code blank} is a slot
     * somebody was offered and left empty, which is how "leave it blank for the
     * obvious thing" becomes a real offer instead of a note in a README.
     */
    public Answers filled(Template template) {
        Map<String, String> next = new LinkedHashMap<>(values);
        Answers running = new Answers(next);
        for (Template.Fill fill : template.fills()) {
            boolean blank = !running.has(fill.name());
            if (fill.when() == Template.Fill.When.BLANK && !blank) continue;
            next.put(fill.name(), fill.how().apply(running.resolve(fill.from())));
        }
        return new Answers(next);
    }

    // ---- substitution ----------------------------------------------------------------

    /**
     * {@code text} with every {@code ${name}} replaced.
     *
     * @throws TemplateError when a name is not one of the answers -- which in a
     *                       template file means a placeholder nobody filled in, and
     *                       is the one failure that would otherwise reach disk
     */
    public String resolve(String text) { return substitute(text, false); }

    /**
     * One pass, and only one.
     *
     * <p>Deliberately not repeated until nothing changes. A value somebody typed
     * into a form is text, not a template, so a project genuinely called
     * {@code ${home}} gets that name rather than a surprise -- and a fill is
     * already fully resolved at the moment it is worked out, so there is nothing
     * a second pass could find. It also makes the escape below mean what it says:
     * something restored to {@code ${...}} stays that way, because nothing looks
     * at it again.
     */
    private String substitute(String text, boolean lenient) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("${", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            // $${...} is a literal ${...}, and the generated file keeps it. A pom
            // is the reason: it is full of Maven's own ${} and every one of them
            // has to survive, which makes an escape the one thing this needs
            // beyond replacing a name.
            if (open > 0 && text.charAt(open - 1) == '$') {
                out.append(text, i, open - 1).append("${");
                i = open + 2;
                continue;
            }
            int close = text.indexOf('}', open);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            String name = text.substring(open + 2, close);
            out.append(text, i, open);
            if (values.containsKey(name)) {
                out.append(values.get(name));
            } else if (lenient) {
                out.append(text, open, close + 1);
            } else {
                throw unknown(name);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private TemplateError unknown(String name) {
        TemplateError.Builder error = TemplateError.of(
                "nothing was given for ${" + name + "}");
        String closest = closest(name, values.keySet());
        if (closest != null) error.hint("did you mean ${" + closest + "}?");
        error.hint(values.isEmpty()
                ? "no answers were given at all"
                : "what is known: " + String.join(", ", values.keySet()));
        return error.build();
    }

    /** Every {@code ${name}} in a piece of text, in the order it appears. */
    public static Set<String> namesIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        int i = 0;
        while (true) {
            int open = text.indexOf("${", i);
            if (open < 0) return names;
            int close = text.indexOf('}', open);
            if (close < 0) return names;
            names.add(text.substring(open + 2, close));
            i = close + 1;
        }
    }

    /**
     * The nearest of {@code candidates} to {@code word}, or null when none is near.
     *
     * <p>MainFrame has one of these and this is not it, for the reason the manifest
     * reader has its own parser: reaching into the shell for a spelling hint is
     * what a module that cannot stand on its own does. Twenty lines is the price
     * of the boundary, and it is the whole price.
     */
    static String closest(String word, java.util.Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(word, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int allowed = Math.max(1, word.length() / 3);
        return bestDistance <= allowed ? best : null;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Which of a template's placeholders nothing has answered. */
    public List<String> missing(Template template) {
        List<String> missing = new ArrayList<>();
        for (Template.Slot slot : template.slots()) {
            if (slot.required() && !has(slot.name())) missing.add(slot.name());
        }
        return List.copyOf(missing);
    }
}
