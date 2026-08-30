package dev.mainframe.assistant;

import java.io.IOException;
import java.nio.file.Path;

import dev.mainframe.fs.Store;

/**
 * The one key MainFrame keeps, and what it is honest about.
 *
 * <p>All the keeping is {@link Store}'s: a secrets area, owned by this program,
 * written owner-only with the permissions set before the bytes. What is left here
 * is the part that is about a key rather than about a file -- that a blank one is
 * not a key, that a pasted one usually arrives with a newline on the end, and
 * that a person about to type one deserves to be told where it is going.
 *
 * <p>What this does not do is protect the key from you, or from anything running
 * as you. It is a file on your disk. Owner-only permissions stop the other
 * accounts on a shared machine and stop a careless backup; they do not stop a
 * program you ran. Storing it at all is a trade -- the alternative is typing it
 * at every launch, which people avoid by putting it in a shell script, which is a
 * plainer copy in a place with worse permissions. That trade is the user's to
 * make knowingly, which is what {@link #risk()} is for.
 */
public final class Secrets {

    /** Named as a warning, so it reads as one in a directory listing. */
    private static final String ENTRY = "credentials";

    private final Store store;

    public Secrets(Store store) { this.store = store; }

    /** Alongside every other program's, under this program's name. */
    public static Secrets inState() {
        return new Secrets(Store.owned(Store.Area.SECRETS, "assistant"));
    }

    public Path file() { return store.file(ENTRY); }

    public boolean isSet() { return store.has(ENTRY); }

    /** The key, or null when none has been stored. */
    public String read() throws IOException {
        String text = store.read(ENTRY);
        if (text == null) return null;
        text = text.strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Stores the key, readable by this account and no other.
     *
     * @throws IOException if it could not be written, or could be written but not
     *                     restricted. Writing a secret the machine could not be
     *                     told to protect, and saying nothing, is the worst of the
     *                     outcomes available.
     */
    public void write(String key) throws IOException {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a key cannot be blank");
        // Pasted keys arrive with a trailing newline, which is the commonest way
        // for one to stop working with nothing on screen to explain it.
        store.write(ENTRY, key.strip());
    }

    /** Forgets the key. Quiet when there was nothing to forget. */
    public void clear() throws IOException { store.drop(ENTRY); }

    /**
     * What to tell someone before they type a key in, in the words they would
     * want afterwards. Shown at the point of entry, not buried in help: a warning
     * that arrives after the decision is a warning that was not given.
     */
    public String risk() {
        return """
                This key will be saved to %s, readable only by your account.

                It is stored as plain text. Anything you run as yourself can read it,
                and anything that copies your home directory copies it too. Remove it
                at any time with `key forget`."""
                .formatted(file());
    }
}
