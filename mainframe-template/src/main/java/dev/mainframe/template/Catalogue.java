package dev.mainframe.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where templates come from.
 *
 * <p>Two answers, and the second one exists because of the first. Templates that
 * ship with MainFrame are resources on the classpath, which is what makes
 * {@code new vexel-desktop} work out of a single executable with nothing
 * installed beside it. Templates somebody wrote are a folder, because that is
 * what writing one looks like.
 *
 * <h2>The index is not optional</h2>
 *
 * <p>A bundled template lists its files in its manifest, and the reason is worth
 * writing down because the alternative looks so much easier: a classpath
 * directory cannot be reliably enumerated at runtime, and cannot be enumerated at
 * all in a native image. So the manifest's {@code file} lines are the only record
 * of what is in a template, and a file in the folder that no line names is a file
 * that does not exist as far as this is concerned. There is a test that holds the
 * two together, because the failure mode otherwise is a template that quietly
 * stops shipping one of its files.
 */
public final class Catalogue {

    /** Where a bundled template's files sit, relative to this class. */
    private static final String BUNDLED = "/dev/mainframe/template/";

    /** The manifest's name, inside a template's folder. */
    public static final String MANIFEST = "template.manifest";

    /** Where a template's files sit, inside its folder. */
    private static final String FILES = "files/";

    /**
     * The templates that ship with MainFrame.
     *
     * <p>A list rather than a scan, for the reason above. Adding one is a line
     * here, a folder beside it, and nothing else.
     */
    private static final List<String> SHIPPED = List.of("vexel-desktop");

    private final Map<String, Source> sources;

    private Catalogue(Map<String, Source> sources) { this.sources = sources; }

    /** Where one template's bytes come from. */
    private interface Source {

        String manifest();

        byte[] file(String name);

        String describe();
    }

    /** Just what ships with MainFrame. */
    public static Catalogue bundled() {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (String id : SHIPPED) sources.put(id, new Bundled(id));
        return new Catalogue(sources);
    }

    /**
     * What ships, plus every template folder under {@code directory}.
     *
     * <p>A folder that is not a template is passed over rather than complained
     * about -- somebody's templates directory is allowed to have a README in it.
     * A folder with a manifest that will not read is a different matter, and is
     * reported when that template is asked for, not when the list is built: one
     * broken template should not stop the other five being listed.
     */
    public static Catalogue including(Path directory) {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (String id : SHIPPED) sources.put(id, new Bundled(id));
        if (directory != null && Files.isDirectory(directory)) {
            try (var children = Files.list(directory)) {
                for (Path child : children.sorted().toList()) {
                    if (!Files.isRegularFile(child.resolve(MANIFEST))) continue;
                    // A folder of somebody's own wins: that is what putting one
                    // there with the same name is for.
                    sources.put(child.getFileName().toString(), new OnDisk(child));
                }
            } catch (IOException ignored) {
                // An unreadable templates folder is the same as not having one.
            }
        }
        return new Catalogue(sources);
    }

    /** The ids, in the order they should be listed. */
    public List<String> ids() { return List.copyOf(sources.keySet()); }

    public boolean has(String id) { return sources.containsKey(id); }

    /** Where a template came from, for a listing. */
    public String origin(String id) {
        Source source = sources.get(id);
        return source == null ? null : source.describe();
    }

    /**
     * Reads a template.
     *
     * @throws TemplateError when there is no such template, or its manifest will
     *                       not read
     */
    public Template get(String id) {
        Source source = sources.get(id);
        if (source == null) {
            TemplateError.Builder error = TemplateError.of("there is no " + id + " template");
            String closest = Answers.closest(id, sources.keySet());
            if (closest != null) error.hint("did you mean " + closest + "?");
            error.hint(sources.isEmpty()
                    ? "none are installed"
                    : "there is: " + String.join(", ", sources.keySet()));
            error.hint("see them with: templates");
            return throwing(error.build());
        }
        return Manifest.read(source.manifest(), source.describe() + "/" + MANIFEST);
    }

    /** One of a template's files, as it is on disk before anything is substituted. */
    public byte[] read(String id, String name) {
        Source source = sources.get(id);
        if (source == null) return throwing(TemplateError.of("there is no " + id + " template").build());
        return source.file(name);
    }

    /** Every template that reads, and the id of every one that does not. */
    public List<Template> readable() {
        List<Template> templates = new ArrayList<>(sources.size());
        for (String id : sources.keySet()) {
            try {
                templates.add(get(id));
            } catch (TemplateError e) {
                // Listed as broken by the caller if it wants to; a listing that
                // died on one bad folder would be worse than one that is short.
            }
        }
        return List.copyOf(templates);
    }

    private static <T> T throwing(TemplateError error) { throw error; }

    // ---- the two sources -----------------------------------------------------------------

    private record Bundled(String id) implements Source {

        @Override
        public String manifest() {
            return new String(resource(MANIFEST), StandardCharsets.UTF_8);
        }

        @Override
        public byte[] file(String name) { return resource(FILES + name); }

        @Override
        public String describe() { return id; }

        private byte[] resource(String name) {
            String path = BUNDLED + id + "/" + name;
            try (InputStream in = Catalogue.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw TemplateError.of(id + " is missing " + name)
                            .hint("it should be on the classpath at " + path)
                            .hint("a bundled template's files are listed in its manifest, and every "
                                    + "listed file has to be there")
                            .build();
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw TemplateError.of("could not read " + name + " from " + id + ": " + e.getMessage())
                        .because(e)
                        .build();
            }
        }
    }

    private record OnDisk(Path folder) implements Source {

        @Override
        public String manifest() { return text(folder.resolve(MANIFEST), MANIFEST); }

        @Override
        public byte[] file(String name) {
            Path file = folder.resolve(FILES).resolve(name);
            // A template folder is not a place to reach out of. A file line saying
            // ../../.ssh/id_rsa would otherwise be a way to read one.
            if (!file.normalize().startsWith(folder.resolve(FILES).normalize())) {
                throw TemplateError.of(name + " reaches outside " + folder.getFileName())
                        .hint("a template's files all live under its own files/ folder")
                        .build();
            }
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw TemplateError.of("could not read " + name + " from "
                                + folder.getFileName() + ": " + e.getMessage())
                        .hint("the manifest lists it, so it has to be at " + file)
                        .because(e)
                        .build();
            }
        }

        @Override
        public String describe() { return folder.toString(); }

        private static String text(Path file, String what) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw TemplateError.of("could not read " + what + ": " + e.getMessage())
                        .because(e)
                        .build();
            }
        }
    }
}
