package dev.mainframe.template;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A template plus a set of answers, worked out into files.
 *
 * <p>The whole of the engine's public shape is here and it is one method: hand it
 * a template, what somebody said, and where the files come from, and it hands
 * back every byte it would write. Nothing in this package touches a disk except
 * {@link Blueprint.Writing}, and nothing decides to.
 *
 * <p>That split is what lets the shell put a plan on the screen and lets a test
 * assert on the contents of a generated pom without a temporary directory.
 */
public final class Scaffold {

    /**
     * Files that are copied rather than filled in.
     *
     * <p>A template made of text is the ordinary case, and substituting a
     * {@code ${...}} into a PNG would be a way of quietly corrupting it. Rather
     * than keeping a list of extensions -- which is a list that is always one
     * entry out of date -- a file that is not valid UTF-8 is copied through
     * untouched. Anything a person could have typed a placeholder into decodes;
     * anything that does not decode had no placeholder in it.
     */
    private Scaffold() {}

    /**
     * Everything the template would write, with every placeholder resolved.
     *
     * @param answers what somebody said, after {@link Answers#filled} -- callers
     *                that skip that step will be told about the placeholders it
     *                would have supplied, which is the right failure but a
     *                confusing one
     */
    public static Blueprint of(Template template, Answers answers, Catalogue catalogue) {
        List<Blueprint.Entry> entries = new ArrayList<>(template.items().size());
        List<String> notes = new ArrayList<>();
        for (Template.Item item : template.items()) {
            if (item.conditional()) {
                boolean on = answers.flag(item.when());
                if (item.unless() == on) {
                    notes.add(resolvePath(item.path(), answers, template)
                            + " left out (" + item.when() + " is " + (on ? "yes" : "no") + ")");
                    continue;
                }
            }
            String path = resolvePath(item.path(), answers, template);
            byte[] source = catalogue.read(template.id(), item.source());
            entries.add(new Blueprint.Entry(path, render(source, answers, item.source())));
        }
        return new Blueprint(List.copyOf(entries), List.copyOf(notes));
    }

    /** Where the project folder itself goes, under the folder somebody chose. */
    public static Path folder(Template template, Answers answers, Path where) {
        String name = answers.resolve(template.folder()).strip();
        if (name.isEmpty()) {
            throw TemplateError.of("the new project has no name")
                    .hint("the template says its folder is " + template.folder())
                    .build();
        }
        // One segment, always. A template whose folder resolved to a path would be
        // a template that could put files two directories up from where it was
        // pointed, which is not something a folder chooser can be asked about.
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw TemplateError.of("the new project's name is not a plain name: " + name)
                    .hint("it becomes one folder inside the one you chose")
                    .build();
        }
        return where.resolve(name);
    }

    // ---- rendering -----------------------------------------------------------------------

    private static String resolvePath(String path, Answers answers, Template template) {
        String resolved = answers.resolve(path);
        if (resolved.contains("//") || resolved.startsWith("/") || resolved.contains("..")) {
            throw TemplateError.of(template.id() + " would write to " + resolved)
                    .hint("that came from: " + path)
                    .hint("a template writes underneath the project folder and nowhere else")
                    .build();
        }
        return resolved;
    }

    /**
     * One file's bytes, with its placeholders filled in -- or exactly as they were,
     * when the file is not text.
     */
    private static byte[] render(byte[] source, Answers answers, String name) {
        String text = decode(source);
        if (text == null) return source;
        try {
            return answers.resolve(text).getBytes(StandardCharsets.UTF_8);
        } catch (TemplateError e) {
            // The message from Answers says which placeholder; only this level
            // knows which file it was in, and that is the half that saves the time.
            throw TemplateError.of(e.getMessage() + ", and " + name + " uses it")
                    .hint(hints(e))
                    .because(e)
                    .build();
        }
    }

    private static String hints(TemplateError e) {
        return e.hints().isEmpty() ? null : String.join("; ", e.hints());
    }

    /** The text of a file, or null when it is not UTF-8 and so is not text. */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
