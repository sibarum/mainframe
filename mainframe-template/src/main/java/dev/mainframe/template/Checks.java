package dev.mainframe.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;
import java.util.Set;

/**
 * The checks a pattern cannot make.
 *
 * <p>A slot's {@code match} catches the shape of an answer and is where most of
 * the work is done, because a rule written as data is a rule the form and a
 * script both get. What is here is the rest: the things that need a word list
 * (a package segment called {@code new} matches every sensible pattern and will
 * not compile), the things that need the disk (is there anything in that folder
 * already?), and the one thing that needs the machine (is the library this
 * project is about to depend on actually installed?).
 *
 * <h2>Two kinds, and the difference matters</h2>
 *
 * <p>A <b>refusal</b> stops the whole thing before a byte is written. A
 * <b>caution</b> is said out loud and carried on from. The line between them is
 * not severity, it is certainty: a package name that will not compile is a
 * refusal because it is not a matter of opinion, and a missing artifact in the
 * local repository is a caution because the person may be about to install it, or
 * may be building against something this code cannot see.
 *
 * <p>The exception is the destination, where everything is a refusal. Nothing here
 * ever writes over a file that is already there, and no flag turns that off --
 * see {@link #collisions}.
 */
public final class Checks {

    /**
     * Java's reserved words, plus the two literals and {@code var}, {@code record}
     * and friends where they cannot be a package segment or a type name. Kept here
     * rather than derived, because {@code SourceVersion.isKeyword} is in
     * {@code java.compiler}, which is a module a shell should not have to have.
     */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    /** The rules a slot can name, and what each one holds its answer to. */
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    /** One extra check on one answer: null when it is fine, the problem when it is not. */
    public interface Rule {
        String problem(String label, String value);
    }

    static {
        RULES.put("java-package", Checks::packageProblem);
        RULES.put("java-type", Checks::typeProblem);
        RULES.put("maven-id", Checks::mavenIdProblem);
    }

    private Checks() {}

    /** The rule written like this, or null -- which is a misspelling, not a crash. */
    public static Rule rule(String name) { return RULES.get(name); }

    public static boolean knows(String name) { return RULES.containsKey(name); }

    /** The words, for a listing and for a "did you mean". */
    public static List<String> ruleNames() { return List.copyOf(RULES.keySet()); }

    /**
     * Every problem with a set of answers, in slot order.
     *
     * <p>All of them, not the first: somebody fixing a form wants the whole list,
     * not one round trip per mistake. The same reason MainFrame's own form check
     * gives, and the same behaviour, arrived at separately because this package
     * cannot see that one.
     */
    public static List<String> problems(Template template, Answers answers) {
        List<String> problems = new ArrayList<>();
        for (Template.Slot slot : template.slots()) {
            String value = answers.get(slot.name());
            if (value == null || value.isBlank()) {
                if (slot.required()) problems.add(slot.label() + " is required");
                continue;
            }
            for (String name : slot.rules()) {
                Rule rule = RULES.get(name);
                if (rule == null) continue;   // the manifest reader already refused these
                String problem = rule.problem(slot.label(), value.strip());
                if (problem != null) problems.add(problem);
            }
        }
        return List.copyOf(problems);
    }

    // ---- the name rules ------------------------------------------------------------------

    /** A dotted name every segment of which javac would accept. */
    public static String packageProblem(String label, String value) {
        if (value.isBlank()) return label + " is required";
        for (String segment : value.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return label + " has an empty part: " + value;
            }
            if (RESERVED.contains(segment)) {
                return label + " cannot contain " + segment + " -- it is a Java keyword, so "
                        + value + " would not compile";
            }
            if (!Character.isJavaIdentifierStart(segment.charAt(0))) {
                return label + " has a part starting with " + segment.charAt(0) + ", and a package part "
                        + "starts with a letter";
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    return label + " has a " + quoted(segment.charAt(i)) + " in it, and a package part "
                            + "is letters, digits and underscores";
                }
            }
        }
        return null;
    }

    /** A name javac would accept for a class. */
    public static String typeProblem(String label, String value) {
        if (value.isBlank()) return label + " is required";
        if (RESERVED.contains(value)) {
            return label + " cannot be " + value + " -- it is a Java keyword";
        }
        if (!Character.isJavaIdentifierStart(value.charAt(0))) {
            return label + " starts with " + quoted(value.charAt(0)) + ", and a class name starts "
                    + "with a letter";
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return label + " has a " + quoted(value.charAt(i)) + " in it, and a class name is "
                        + "letters, digits and underscores";
            }
        }
        if (!Character.isUpperCase(value.charAt(0))) {
            return label + " should start with a capital: " + Transform.TYPE.apply(value);
        }
        return null;
    }

    /** A groupId or artifactId Maven would accept, and a filesystem would take as a folder. */
    public static String mavenIdProblem(String label, String value) {
        if (value.isBlank()) return label + " is required";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_';
            if (!ok) {
                return label + " has a " + quoted(c) + " in it, and Maven takes lower-case letters, "
                        + "digits, and - . _";
            }
        }
        if (value.startsWith("-") || value.startsWith(".") || value.endsWith(".")) {
            return label + " cannot start or end like that: " + value;
        }
        return null;
    }

    // ---- the destination -----------------------------------------------------------------

    /**
     * What is wrong with writing a new project at {@code target}, or null.
     *
     * <p>Everything here is a refusal, and the reasoning is the same in each case:
     * this command's whole job is to put a tree of files somewhere, so the only
     * mistake it can make that is not recoverable by deleting the result is
     * putting them somewhere that already meant something.
     *
     * @param inside true when the caller has said the folder may already exist and
     *               have things in it. Even then, no file that is there is ever
     *               written over -- see {@link #collisions}.
     */
    public static String destinationProblem(Path target, boolean inside, Path stateDir) {
        Path parent = target.getParent();
        if (parent == null) {
            return target + " is the root of a drive, which is not somewhere a project goes";
        }
        if (!Files.exists(parent)) {
            return SafePath.display(parent) + " does not exist, so there is nowhere to put "
                    + target.getFileName();
        }
        if (!Files.isDirectory(parent)) {
            return SafePath.display(parent) + " is a file, not a folder";
        }
        if (!Files.isWritable(parent)) {
            return SafePath.display(parent) + " cannot be written to";
        }
        if (stateDir != null && target.startsWith(stateDir)) {
            return SafePath.display(target) + " is inside MainFrame's own state folder";
        }
        Path home = home();
        if (home != null && target.equals(home)) {
            return "that is your home folder itself, not a folder in it";
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target)) {
                return SafePath.display(target) + " is already a file";
            }
            if (!inside && !isEmpty(target)) {
                return SafePath.display(target) + " already has things in it";
            }
        }
        return null;
    }

    /**
     * The files a blueprint would land on top of.
     *
     * <p>The one promise this whole module makes that has no flag to turn it off:
     * a file that is already on disk is never written over. Not with
     * {@code --force}, not with {@code --yes}. Everything else about a generated
     * project can be fixed by deleting the folder and running it again; a file
     * somebody wrote and this replaced cannot.
     *
     * <p>Returned rather than thrown, and returned as a list rather than a first
     * offender, because the caller is going to show them all at once.
     */
    public static List<String> collisions(Path target, Blueprint blueprint) {
        List<String> hit = new ArrayList<>();
        for (Blueprint.Entry entry : blueprint.entries()) {
            if (Files.exists(target.resolve(entry.path()), LinkOption.NOFOLLOW_LINKS)) hit.add(entry.path());
        }
        return List.copyOf(hit);
    }

    private static boolean isEmpty(Path directory) {
        try (var children = Files.list(directory)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            // Unreadable is not empty, and guessing that it is would be the one
            // guess that ends in somebody's files being sat on top of.
            return false;
        }
    }

    // ---- will it actually build --------------------------------------------------------

    /**
     * The artifacts a generated project depends on that are not in the local Maven
     * repository.
     *
     * <p>This is the question "will it actually run", asked of the machine rather
     * than hoped about. The stack a VexelRay project sits on is installed locally
     * rather than downloaded, so a version nobody has run {@code mvn install} for
     * is the single most likely reason a freshly generated project does not build
     * -- and it is a reason that shows up as a wall of Maven output twenty seconds
     * later rather than as a sentence.
     *
     * <p>A caution rather than a refusal, deliberately. The person may be about to
     * install it, may have a repository somewhere this cannot see, or may be
     * generating a project on a machine that is not the one that will build it.
     * Being wrong about that should cost them a line of text, not their command.
     *
     * @return the coordinates that are missing, in the order the template declared
     *         them; empty when everything is there, and empty when there is no
     *         local repository at all, because then nothing has been established
     */
    public static List<String> missingArtifacts(Template template, Answers answers, Path repository) {
        if (repository == null || !Files.isDirectory(repository)) return List.of();
        List<String> missing = new ArrayList<>();
        for (Template.Need need : template.needs()) {
            String group = answers.resolve(need.group());
            String artifact = answers.resolve(need.artifact());
            String version = answers.resolve(need.version());
            Template.Need resolved = new Template.Need(group, artifact, version);
            Path folder;
            try {
                folder = repository.resolve(resolved.repoPath());
            } catch (InvalidPathException e) {
                missing.add(resolved.coordinate());
                continue;
            }
            if (!Files.isDirectory(folder)) {
                missing.add(resolved.coordinate());
                continue;
            }
            // A folder with a pom and no jar is a half-installed artifact, which
            // fails at compile time rather than at resolve time -- a worse failure
            // to have to diagnose, so it counts as missing here.
            if (!Files.isRegularFile(folder.resolve(resolved.jarName()))
                    && !hasJar(folder, artifact)) {
                missing.add(resolved.coordinate());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * A snapshot installed by a remote build carries a timestamped jar name rather
     * than the {@code -SNAPSHOT} one, so the plain name missing does not settle it.
     */
    private static boolean hasJar(Path folder, String artifact) {
        try (var children = Files.list(folder)) {
            return children.anyMatch(child -> {
                String name = child.getFileName().toString();
                return name.startsWith(artifact + "-") && name.endsWith(".jar")
                        && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every generated file that says it is XML and is not, with the parser's own
     * complaint.
     *
     * <p>Narrow, and worth its keep for a reason that is not obvious until it
     * happens: a template's files are not compiled by anything, so a mistake in one
     * of them is found by whoever generates a project rather than by whoever wrote
     * the template. A pom is the file that matters most and the one most likely to
     * go wrong, because it is mostly prose in XML comments -- and XML comments may
     * not contain two hyphens in a row, which is a rule nobody remembers while
     * writing an em dash into a sentence about Maven.
     *
     * <p>Checked over the rendered bytes rather than over the template, so it also
     * catches an answer that broke the file on its way in -- a project summary with
     * a stray {@code </description>} in it lands here rather than in a wall of
     * Maven output twenty seconds later.
     *
     * <p>Only well-formedness. Whether a pom is a <em>valid</em> pom is Maven's
     * question and this has no business having an opinion about it.
     */
    public static List<String> malformedXml(Blueprint blueprint) {
        List<String> problems = new ArrayList<>();
        for (Blueprint.Entry entry : blueprint.entries()) {
            if (!entry.path().endsWith(".xml")) continue;
            String problem = xmlProblem(entry.bytes());
            if (problem != null) problems.add(entry.path() + ": " + problem);
        }
        return List.copyOf(problems);
    }

    /** What a parser makes of these bytes, or null when it is happy. */
    static String xmlProblem(byte[] bytes) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            // Nothing here is trusted enough to be allowed to fetch a DTD, and a
            // template that could would be a template that could make a request.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.parse(new java.io.ByteArrayInputStream(bytes));
            return null;
        } catch (org.xml.sax.SAXException e) {
            return firstLine(e.getMessage());
        } catch (IOException | javax.xml.parsers.ParserConfigurationException e) {
            return firstLine(e.getMessage());
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "it is not well-formed XML";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** Where Maven keeps what it has installed, honouring the usual override. */
    public static Path localRepository() {
        String property = System.getProperty("maven.repo.local");
        if (property != null && !property.isBlank()) return Path.of(property);
        Path home = home();
        return home == null ? null : home.resolve(".m2").resolve("repository");
    }

    private static Path home() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }

    private static String quoted(char c) { return "\"" + c + "\""; }

    /** Naming a path in a message without a wall of drive letters where one is not wanted. */
    static final class SafePath {

        private SafePath() {}

        static String display(Path path) {
            Path home = home();
            if (home != null && path.startsWith(home) && !path.equals(home)) {
                return "~/" + home.relativize(path).toString().replace('\\', '/');
            }
            return path.toString();
        }

    }
}
