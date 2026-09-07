package dev.mainframe.template.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import dev.mainframe.template.Answers;
import dev.mainframe.template.Template;
import dev.mainframe.value.Value;
import dev.mainframe.value.Values;

/**
 * A template's slots, as MainFrame's data entry framework sees them.
 *
 * <p>This is the whole of the join between the two, and it is deliberately a
 * translation rather than a second implementation. A slot does not know what a
 * form is and a form does not know what a template is; each has one vocabulary
 * for describing a thing to ask a person for, and these are the same idea written
 * twice by two packages that must not depend on each other. So the translation
 * lives here, alone, and both sides stay ignorant.
 *
 * <p>What it buys is everything the form already does: the whole screen goes up at
 * once on a display and prints downwards on a terminal, a path field grows a
 * folder chooser, an answer is read and refused as it is typed, and the rules
 * that stop a typo at the keyboard are the same rules that stop it in a record
 * that arrived down a pipe. None of that is written twice either.
 */
final class Slots {

    private Slots() {}

    /**
     * The template's slots as a form definition -- a list of field records, which
     * is what {@code form} itself takes.
     *
     * <p>Presets are resolved before they go in, so the form comes up with
     * {@code my-app} in the name field rather than with {@code ${artifactId}}.
     */
    static Value fields(Template template) {
        Answers presets = Answers.presets(template);
        List<Value> fields = new ArrayList<>(template.slots().size());
        for (Template.Slot slot : template.slots()) fields.add(field(slot, presets));
        return new Value.ListVal(List.copyOf(fields));
    }

    private static Value.Rec field(Template.Slot slot, Answers presets) {
        SequencedMap<String, Value> field = new LinkedHashMap<>();
        field.put("name", new Value.Str(slot.name()));
        field.put("label", new Value.Str(slot.label()));
        field.put("type", new Value.Str(slot.kind().written()));
        field.put("required", new Value.Bool(slot.required()));
        if (slot.help() != null) field.put("help", new Value.Str(slot.help()));
        if (slot.match() != null) field.put("match", new Value.Str(slot.match()));
        if (slot.least() != null) field.put("min", new Value.Int(slot.least()));
        if (slot.most() != null) field.put("max", new Value.Int(slot.most()));
        if (slot.choices() != null) {
            List<Value> choices = new ArrayList<>(slot.choices().size());
            for (String choice : slot.choices()) choices.add(new Value.Str(choice));
            field.put("choose", new Value.ListVal(List.copyOf(choices)));
        }
        // The one place a slot's kind is not the whole story: a folder is a path,
        // and "which folder?" is a different question from "which file?" -- so the
        // field says which chooser it wants. An editor without one still lets the
        // path be typed, and the answer is the same either way.
        if (slot.kind() == Template.Kind.FOLDER) field.put("pick", new Value.Str("folder"));

        String preset = presets.get(slot.name());
        if (preset != null && !preset.isBlank()) field.put("default", preset(slot, preset));
        return new Value.Rec(field);
    }

    /** A preset, as the type its field holds -- a form refuses a default that is not one. */
    private static Value preset(Template.Slot slot, String preset) {
        return switch (slot.kind()) {
            case FLAG -> new Value.Bool(truthy(preset));
            case NUMBER -> {
                try {
                    yield new Value.Int(Long.parseLong(preset.strip()));
                } catch (NumberFormatException e) {
                    // A template with a nonsense preset should say so when the
                    // template is read, not silently come up with an empty field.
                    yield new Value.Str(preset);
                }
            }
            case FOLDER -> new Value.PathVal(java.nio.file.Path.of(preset));
            case TEXT -> new Value.Str(preset);
        };
    }

    /**
     * What came back from the form, as the engine takes it.
     *
     * <p>Everything becomes text, including the yes-or-nos. That looks like a loss
     * and is not: the engine has to accept answers from a form, from a piped
     * record and from a {@code --set} on the command line, and text is the one
     * shape all three arrive in. It is read back as its type on the way in -- which
     * is what has already happened by the time this runs.
     */
    /**
     * The template's presets as a record, ready to sit underneath whatever else
     * arrives.
     *
     * <p>The same values the form would have shown, in the same types, so a project
     * made without a form is the project a person would have got by accepting every
     * default. Anything else would make the unattended path a different feature.
     */
    static Value.Rec presets(Template template) {
        Answers presets = Answers.presets(template);
        SequencedMap<String, Value> fields = new LinkedHashMap<>();
        for (Template.Slot slot : template.slots()) {
            String value = presets.get(slot.name());
            if (value == null || value.isBlank()) continue;
            fields.put(slot.name(), preset(slot, value));
        }
        return new Value.Rec(fields);
    }

    static Map<String, String> answers(Template template, Value.Rec record) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (Template.Slot slot : template.slots()) {
            Value value = record.get(slot.name());
            if (value == null || value instanceof Value.Nothing) continue;
            answers.put(slot.name(), text(value));
        }
        // Anything else the record carried is kept too, so a template can be given
        // a value for a fill directly -- which is how a script skips a question a
        // person would have been asked.
        for (Map.Entry<String, Value> entry : record.fields().entrySet()) {
            if (answers.containsKey(entry.getKey())) continue;
            if (entry.getValue() instanceof Value.Nothing) continue;
            answers.put(entry.getKey(), text(entry.getValue()));
        }
        return answers;
    }

    private static String text(Value value) {
        return switch (value) {
            case Value.Str s -> s.value();
            case Value.Bool b -> String.valueOf(b.value());
            case Value.PathVal p -> p.path().toString();
            default -> Values.display(value);
        };
    }

    static boolean truthy(String text) {
        String lower = text.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.equals("true") || lower.equals("yes") || lower.equals("y") || lower.equals("1");
    }
}
