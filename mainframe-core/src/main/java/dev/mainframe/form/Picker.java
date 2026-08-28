package dev.mainframe.form;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.mainframe.Session;
import dev.mainframe.form.Form.Pick;
import dev.mainframe.fs.SafeFs;
import dev.mainframe.panel.Editor;
import dev.mainframe.panel.Event;
import dev.mainframe.panel.Screen;
import dev.mainframe.ui.Renderer;

/**
 * Looking for a file, which MainFrame draws itself.
 *
 * <h2>Why this exists at all</h2>
 * A path field can always be typed into, and for a path somebody knows off by
 * heart that is the quickest thing there is. Browsing is for the other case: a
 * JDK three directories down inside a folder with a build number in its name. So
 * a {@code pick} field offers both, and this is the second one.
 *
 * <p>An editor with a chooser of its own says {@code pick} in its capabilities
 * and is left to it -- a native file dialog knows about shortcuts, network places
 * and the last folder somebody used, and nothing here is going to beat that. This
 * is what every other editor gets, and it is drawn out of the parts the protocol
 * already had: text, an entry, and something to click. Nobody has to write a new
 * editor for a form to grow a file chooser, which is the rule the whole protocol
 * turns on.
 *
 * <h2>Two halves, one set of rules</h2>
 * The arrangement is {@link Form}'s. What a folder holds, what a typed line means
 * and what would be chosen if it were settled now all live here, once, and the
 * two halves below only ask. {@link #show} paints a screen for an editor to put
 * up; {@link #ask} prints a numbered listing to a terminal. They navigate the
 * same way and refuse the same things in the same words, so a form filled in on a
 * tube and a form filled in down a pipe cannot disagree about where a file was.
 *
 * <h2>It only ever hands back something real</h2>
 * A chooser offers what is there, so choosing a file that is not there is refused
 * here -- unlike {@link Form.Field#problem}, which tolerates it, because a record
 * that arrived down a pipe may quite reasonably name a folder nobody has unpacked
 * yet. The two are not disagreeing: this is the narrower promise a chooser makes,
 * not a second opinion about what a field will hold. Naming something that is not
 * there yet is still done by typing into the field.
 */
public final class Picker {

    /**
     * How many entries of a folder are shown at once when nothing has said how
     * much room there is -- which on a terminal is the answer, the listing going
     * into the scrollback rather than onto a fixed screen.
     */
    private static final int PAGE = 14;

    /** The fewest entries worth showing, and the most, however much room there is. */
    private static final int LEAST_PAGE = 4;
    private static final int MOST_PAGE = 24;

    /** Rows the chooser takes up around the listing: the frame, the boxes, the buttons. */
    private static final int CHROME = 14;

    /** Wide enough for a path, capped for the reason a form is capped. */
    private static final int MIN_WIDTH = 48;
    private static final int MAX_WIDTH = 100;

    private static final int LEFT = 3;
    private static final int ENTRY_COLUMN = 11;

    /** The two entries on the screen. Unprefixed: this screen is the chooser's own. */
    private static final String WHERE = "where";
    private static final String NAME = "name";

    /** Prefixes for what a click can mean, kept clear of anything a file is called. */
    private static final String AT = "at:";
    private static final String UP = "go:up";
    private static final String DRIVES = "go:drives";
    private static final String BACK = "go:back";
    private static final String MORE = "go:more";
    private static final String CHOOSE = "do:choose";
    private static final String CANCEL = "do:cancel";

    private final Pick what;

    /** The folder being looked at, or null for the list of drives above them all. */
    private Path where;
    /** The file name settled on so far, which survives moving between folders. */
    private String named = "";
    /** How far down a long folder the listing has been scrolled. */
    private int from;
    /**
     * How many entries fit at once.
     *
     * <p>Worked out from the room the editor says it has, and worked out again on
     * every screen, because a window gets dragged. A chooser is the one screen in
     * MainFrame that would rather be shorter than complete: the protocol says an
     * editor with too little room scrolls rather than reflows, and a listing
     * scrolled far enough to hide its own Cancel button is a listing somebody is
     * stuck in.
     */
    private int page = PAGE;
    /** What is wrong, shown once and cleared by the next thing anybody does. */
    private String problem;

    private int nextId = 1;

    private Picker(Pick what, Path start) {
        this.what = what;
        this.where = start;
    }

    /**
     * Where to open, given whatever the field already holds.
     *
     * <p>The folder a file is in rather than the file, because somebody reopening
     * a chooser on an answer wants to see its neighbours -- that is what makes it
     * possible to notice the answer is the wrong one. A path that is not there is
     * walked up until something is, so an answer left over from another machine
     * opens somewhere real instead of nowhere.
     */
    private static Path openAt(Pick what, Path answer, Path fallback) {
        Path start = answer == null ? fallback : answer;
        if (start == null) {
            start = SafeFs.userHome();
        }
        if (!what.isFolder() && !Files.isDirectory(start) && start.getParent() != null) {
            start = start.getParent();
        }
        while (start != null && !Files.isDirectory(start)) {
            start = start.getParent();
        }
        return start == null ? SafeFs.userHome() : start;
    }

    /** The name to start with in the box, when the field already holds a file. */
    private static String nameOf(Pick what, Path answer) {
        if (what.isFolder() || answer == null || Files.isDirectory(answer)) {
            return "";
        }
        Path name = answer.getFileName();
        return name == null ? "" : name.toString();
    }

    // ---- what a folder holds ---------------------------------------------------------------

    /**
     * What is in the folder being looked at: folders first, then files, each by
     * name.
     *
     * <p>Folders first because they are how anybody gets anywhere, and files left
     * out altogether when a folder is what is wanted -- a listing of everything on
     * the disk is a worse answer to "which folder?" than a listing of the folders.
     * Nothing else is hidden: a chooser that quietly drops a dotfile is a chooser
     * somebody will swear at.
     */
    private List<Path> listing() {
        if (where == null) {
            List<Path> roots = new ArrayList<>();
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                roots.add(root);
            }
            return roots;
        }
        List<Path> folders = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (var children = Files.list(where)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    folders.add(child);
                } else if (!what.isFolder()) {
                    files.add(child);
                }
            }
        } catch (IOException | SecurityException e) {
            // Said out loud rather than shown as an empty folder, which would be a
            // lie about what is there.
            problem = "that folder cannot be read";
            return List.of();
        }
        Comparator<Path> byName =
                Comparator.comparing(Picker::shortName, String.CASE_INSENSITIVE_ORDER);
        folders.sort(byName);
        files.sort(byName);
        folders.addAll(files);
        return folders;
    }

    private static String shortName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** How a folder is written where a file is written plainly. */
    private static String rowText(Path path) {
        return Files.isDirectory(path) ? "[" + shortName(path) + "]" : shortName(path);
    }

    private void into(Path folder) {
        where = folder;
        from = 0;
        problem = null;
        // Moving keeps the name when the point is to write a file and drops it
        // when the point is to find one: the same name in a different folder is a
        // different file, and only one of those is worth carrying over.
        if (!what.isNew()) {
            named = "";
        }
    }

    private void up() {
        // Above a root is the list of drives, which is the only way to get from one
        // to another without typing. On a machine with one root it is a listing of
        // one, which is honest rather than useless.
        where = where == null ? null : where.getParent();
        from = 0;
        problem = null;
    }

    /** {@code typed} as a path, relative to where the chooser is now. */
    private Path resolved(String typed) {
        try {
            return SafeFs.resolve(where == null ? SafeFs.userHome() : where, typed);
        } catch (InvalidPathException e) {
            problem = "that is not a path this machine could have";
            return null;
        }
    }

    /**
     * Settle on {@code target}, or say why it will not do.
     *
     * <p>The one place a choice is judged, so the printed listing and the painted
     * one refuse the same things. A folder is stepped into rather than refused:
     * somebody who types the name of a folder wants to go there, and calling that
     * a mistake would be pedantry.
     *
     * @return the path chosen, or null when something else happened -- either a
     *         move, or a problem now sitting in {@link #problem}
     */
    private Path settle(Path target) {
        if (target == null) {
            return null;
        }
        boolean folder = Files.isDirectory(target);
        if (what.isFolder()) {
            if (folder) {
                return target;
            }
            problem = "there is no folder called " + shortName(target) + " here";
            return null;
        }
        if (folder) {
            into(target);
            return null;
        }
        if (what.isNew()) {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                problem = "there is no folder to save into at " + parent;
                return null;
            }
            return target;
        }
        if (!Files.exists(target)) {
            problem = "there is no file called " + shortName(target) + " here";
            return null;
        }
        return target;
    }

    /**
     * A path somebody typed rather than chose: a folder is somewhere to go, and
     * anything else is something to settle on.
     *
     * <p>Navigating rather than choosing even when a folder is what is wanted,
     * which is worth saying because {@link #settle} would happily take it. Typing
     * a folder into a chooser and being thrown straight back out with it would
     * make the deep case -- the one browsing exists for -- impossible: there would
     * be no way to type half a path and then look. So the location goes there and
     * the folder is taken by settling the screen, which is what every folder
     * chooser ever built does and what the line of instructions says.
     *
     * @return the path chosen, or null when it moved or would not do
     */
    private Path typedPath(String text) {
        Path asked = resolved(text);
        if (asked == null) return null;
        if (Files.isDirectory(asked)) {
            into(asked);
            return null;
        }
        return settle(asked);
    }

    /** What choosing the {@code at}th thing in the listing means. */
    private Path picked(List<Path> listing, int at) {
        if (at < 0 || at >= listing.size()) {
            return null;
        }
        Path path = listing.get(at);
        if (Files.isDirectory(path)) {
            into(path);
            return null;
        }
        // A file chosen while saving is a file to overwrite, so its name goes in
        // the box where it can be seen and thought about, rather than being taken
        // as settled the way an ordinary choice is.
        if (what.isNew()) {
            named = shortName(path);
            problem = null;
            return null;
        }
        return path;
    }

    /** Where the chooser is, for the line across the top. */
    private String here() {
        return where == null ? "(the drives on this machine)" : where.toString();
    }

    /** How the count at the foot of the listing reads. */
    private String tally(int total) {
        if (total == 0) {
            return what.isFolder() ? "no folders in here" : "nothing in here";
        }
        if (total <= page) {
            return total + (total == 1 ? " thing" : " things");
        }
        return (from + 1) + "-" + Math.min(total, from + page) + " of " + total;
    }

    // ---- on a screen somebody else is painting ---------------------------------------------

    /**
     * Browse for a path on the editor's screen.
     *
     * @param answer   what the field holds now, which is where to open, or null
     * @param fallback where to open when the field holds nothing
     * @return the path chosen, or null when they backed out -- which leaves the
     *         field exactly as it was, because backing out of a chooser is not an
     *         answer of any kind
     */
    public static Path show(Pick what, Path answer, Path fallback, Editor editor) {
        Picker picker = new Picker(what, openAt(what, answer, fallback));
        picker.named = nameOf(what, answer);
        return picker.browse(editor);
    }

    private Path browse(Editor editor) {
        while (true) {
            List<Path> listing = listing();
            if (from >= listing.size()) {
                from = 0;
            }
            Event event = editor.show(paint(editor, listing));
            if (event.is(Event.CANCEL)) {
                return null;
            }

            // The name comes off every event, because it is being typed and
            // clicking something else should not undo that. The folder does not:
            // moving is asked for, by a button or by settling the screen, and never
            // inferred from a box somebody was halfway through.
            String typed = event.field(NAME);
            if (typed != null) {
                named = typed;
            }

            boolean settling = event.is(Event.SUBMIT);
            if (event.is(Event.CLICK)) {
                String clicked = event.on() == null ? "" : event.on();
                problem = null;
                switch (clicked) {
                    case CANCEL -> {
                        return null;
                    }
                    case CHOOSE -> settling = true;
                    case UP -> up();
                    case DRIVES -> {
                        where = null;
                        from = 0;
                    }
                    case BACK -> from = Math.max(0, from - page);
                    case MORE -> from = onward(listing.size());
                    default -> {
                        if (!clicked.startsWith(AT)) {
                            continue;               // a name nothing here knows about
                        }
                        Path chosen = picked(listing, number(clicked.substring(AT.length())) - 1);
                        if (chosen != null) {
                            return chosen;
                        }
                    }
                }
            }
            if (!settling) {
                continue;                           // a change, a resize, a move
            }

            // Settling reads the folder box first: a path typed there is a place to
            // go, and going there is what somebody who typed it wanted. Only once it
            // agrees with where the chooser already is does the screen stand for a
            // choice.
            problem = null;
            String box = event.field(WHERE);
            if (box != null && !box.isBlank()) {
                Path asked = resolved(box);
                if (asked == null) {
                    continue;
                }
                // Only a folder box that has been changed is a place to go. Left as
                // it was found, it is not an instruction at all, and the screen
                // stands for the choice underneath it.
                if (where == null || !asked.equals(where)) {
                    Path chosen = typedPath(box);
                    if (chosen != null) {
                        return chosen;
                    }
                    continue;
                }
            }
            Path chosen = settle(what.isFolder() || named.isBlank() ? where : resolved(named));
            if (chosen != null) {
                return chosen;
            }
            // Settling something that was never going to do it says why. Nothing
            // said means there was nothing to settle: an empty file name, or the
            // list of drives, which is above every folder rather than being one.
            if (problem == null) {
                problem = where == null
                        ? "open a drive first -- the list of them is not a folder"
                        : what.isNew() ? "name the file to save as" : "name a file, or choose one";
            }
        }
    }

    private Screen paint(Editor editor, List<Path> listing) {
        int width = Math.max(MIN_WIDTH, Math.min(editor.hello().cols(), MAX_WIDTH));
        page = Math.max(LEAST_PAGE, Math.min(editor.hello().rows() - CHROME, MOST_PAGE));
        boolean clickable = editor.hello().can("action");
        String heading = what.heading().toUpperCase(Locale.ROOT);
        Screen screen = new Screen(nextId++).title(heading);
        screen.focus(what.isFolder() ? WHERE : NAME);

        screen.text(1, LEFT, heading, "title");
        screen.text(2, LEFT, "=".repeat(width - LEFT), "frame");

        int row = 4;
        screen.text(row, LEFT, "FOLDER", "label");
        screen.entry(row, ENTRY_COLUMN, WHERE, Math.max(20, width - ENTRY_COLUMN - 4),
                where == null ? "" : where.toString(), "path");
        row = Math.max(row, screen.rows());
        if (clickable) {
            row++;
            screen.action(row, ENTRY_COLUMN, UP, "[ Up ]", null);
            screen.action(row, ENTRY_COLUMN + 8, DRIVES, "[ Drives ]", null);
        } else {
            // Without a button to press, the box is the only way about -- so say so
            // rather than leave somebody looking for the one that is not there.
            row++;
            screen.text(row, ENTRY_COLUMN, "type a folder here to go there", "hint");
        }

        row += 2;
        screen.text(row, LEFT, "-".repeat(width - LEFT), "frame");
        int shown = 0;
        for (int at = from; at < listing.size() && shown < page; at++, shown++) {
            Path path = listing.get(at);
            String text = elided(rowText(path), width - ENTRY_COLUMN - 3);
            row++;
            // Clickable where there is a mouse or a Tab ring to reach it with, and
            // plain text where there is not: an editor that cannot report a click
            // still gets a readable listing, and the folder box is how somebody on
            // one gets about.
            if (clickable) {
                screen.action(row, ENTRY_COLUMN, AT + (at + 1), text, null);
            } else {
                screen.text(row, ENTRY_COLUMN, text, "plain");
            }
        }
        if (shown == 0) {
            row++;
        }
        screen.text(++row, LEFT, "-".repeat(width - LEFT), "frame");
        row++;
        screen.text(row, ENTRY_COLUMN, tally(listing.size()), "hint");
        if (clickable && listing.size() > page) {
            if (from > 0) {
                screen.action(row, width - 22, BACK, "[ Back ]", null);
            }
            if (from + page < listing.size()) {
                screen.action(row, width - 12, MORE, "[ More ]", null);
            }
        }

        if (!what.isFolder()) {
            row += 2;
            screen.text(row, LEFT, "FILE", "label");
            screen.entry(row, ENTRY_COLUMN, NAME, Math.max(20, width - ENTRY_COLUMN - 4),
                    named, "string");
            row = Math.max(row, screen.rows());
            if (what.isNew() && !named.isBlank() && where != null
                    && Files.exists(where.resolve(named))) {
                // Not a problem -- overwriting is somebody else's decision -- but not
                // something to find out about afterwards either.
                screen.text(++row, ENTRY_COLUMN, "there is already a file called that", "hint");
            }
        }
        if (problem != null) {
            screen.text(++row, ENTRY_COLUMN, problem, "error");
        }

        row++;
        screen.text(row, LEFT, "=".repeat(width - LEFT), "frame");
        screen.key("F12", Event.SUBMIT, what.verb());
        screen.key("F3", Event.CANCEL, "Cancel");
        // The same arrangement a form uses, and for the same reason: buttons where
        // there can be buttons, the key line where there cannot, and never both at
        // once because they want the same cells.
        if (clickable) {
            screen.action(row + 1, LEFT, CHOOSE, "[ " + what.verb() + " ]", "F12");
            screen.action(row + 1, LEFT + what.verb().length() + 7, CANCEL, "[ Cancel ]", "F3");
        } else {
            screen.text(row + 1, LEFT, "F12 " + what.verb() + "    F3 Cancel", "status");
        }
        return screen;
    }

    // ---- on a terminal ---------------------------------------------------------------------

    /**
     * Browse for a path by printing a folder and reading a line, which is the
     * offer the printed form makes everywhere else: every step of it stays in the
     * scrollback, so what was looked at is still there afterwards.
     *
     * @return the path chosen, or null when they backed out
     */
    public static Path ask(Pick what, Path answer, Session session) {
        Picker picker = new Picker(what, openAt(what, answer, session.cwd()));
        picker.named = nameOf(what, answer);
        return picker.browse(session, 2);
    }

    private Path browse(Session session, int indent) {
        Renderer out = session.out();
        String pad = " ".repeat(Math.max(0, indent));
        while (true) {
            List<Path> listing = listing();
            if (from >= listing.size()) {
                from = 0;
            }

            out.info("");
            out.info(pad + out.bold(what.heading().toUpperCase(Locale.ROOT))
                    + "  " + out.dim(here()));
            int shown = 0;
            for (int at = from; at < listing.size() && shown < page; at++, shown++) {
                out.info(pad + out.dim(String.format("%4d) ", at + 1)) + rowText(listing.get(at)));
            }
            out.info(pad + out.dim("  " + tally(listing.size())));
            for (String line : how(listing.size())) {
                out.info(pad + out.dim("  " + line));
            }
            if (!what.isFolder() && !named.isBlank()) {
                out.info(pad + out.dim("  the file so far: ") + named);
            }
            if (problem != null) {
                out.info(out.red(pad + "!! ") + problem);
            }
            problem = null;

            out.out().print(pad + out.cyan("pick> "));
            out.out().flush();
            String line = session.readLine();
            // The end of the input is a cancel, for the reason it is one at a field:
            // carrying on would mean inventing an answer.
            if (line == null) {
                return null;
            }
            String typed = line.trim();
            if (typed.equals("!cancel") || typed.equals("!back")) {
                return null;
            }
            if (typed.isEmpty()) {
                if (what.isFolder()) {
                    // The list of drives is above every folder rather than being
                    // one, so there is nothing there to take.
                    if (where != null) {
                        return where;
                    }
                    problem = "open a drive first -- the list of them is not a folder";
                    continue;
                }
                if (!named.isBlank()) {
                    Path chosen = settle(resolved(named));
                    if (chosen != null) {
                        return chosen;
                    }
                }
                continue;
            }
            if (typed.equals("+")) {
                from = onward(listing.size());
                continue;
            }
            if (typed.equals("-")) {
                from = Math.max(0, from - page);
                continue;
            }
            if (typed.equals("..")) {
                up();
                continue;
            }
            if (typed.equals(".")) {
                if (!what.isFolder()) {
                    problem = "a folder is not the answer here -- name a file";
                } else if (where != null) {
                    return where;
                } else {
                    problem = "open a drive first -- the list of them is not a folder";
                }
                continue;
            }
            int at = number(typed);
            if (at >= 1 && at <= listing.size()) {
                Path chosen = picked(listing, at - 1);
                if (chosen != null) {
                    return chosen;
                }
                continue;
            }
            if (at > 0) {
                problem = "there is no " + at + " in this listing";
                continue;
            }
            // A backslash makes the rest of the line data, the same as at a field,
            // because a file really can be called "..".
            if (typed.startsWith("\\")) {
                typed = typed.substring(1);
            }
            Path chosen = typedPath(typed);
            if (chosen != null) {
                return chosen;
            }
        }
    }

    /**
     * What can be typed, which differs by what is being looked for.
     *
     * <p>Two lines rather than one, because one would be a hundred characters
     * across and a printed form is laid out for seventy-two. The form itself does
     * not wrap the prose under a field, and it does not have to -- a help line is
     * as long as somebody wrote it. This one is written here, so it is written to
     * fit.
     */
    private List<String> how(int total) {
        String move = "a number goes there, .. goes up, a path goes straight to it"
                + (total > page ? ", + and - page" : "");
        return List.of(move, switch (what) {
            case FOLDER -> "blank takes this folder, !cancel goes back to the field";
            case FILE -> "a number on a file takes it, !cancel goes back to the field";
            case SAVE -> "type the name to save as, !cancel goes back to the field";
        });
    }

    // ---- odds and ends ---------------------------------------------------------------------

    /**
     * The next page down, or where the listing already is when there is no next
     * page.
     *
     * <p>Held rather than let run on, so paging past the end shows the last page
     * again instead of a screen with one entry marooned on it. A listing that stops
     * moving is a listing that has said it is finished.
     */
    private int onward(int total) {
        return from + page < total ? from + page : from;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code text}, cut to {@code room} with an ellipsis if it will not fit. */
    private static String elided(String text, int room) {
        int most = Math.max(8, room);
        return text.length() <= most ? text : text.substring(0, most - 3) + "...";
    }
}
