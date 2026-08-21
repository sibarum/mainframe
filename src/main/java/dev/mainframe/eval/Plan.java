package dev.mainframe.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a command intends to do, worked out before it does any of it.
 *
 * <p>Anything that changes the filesystem builds a plan first. That is what
 * makes {@code --dry-run} honest for every command, lets confirmation prompts
 * say exactly what is about to happen, and keeps a half-finished pipeline from
 * leaving a mess: if planning fails, nothing has run yet.
 */
public final class Plan {

    /** A single file operation, already validated and ready to go. */
    public interface Action {
        void run() throws IOException;
    }

    public record Step(String description, Action action) {}

    private final String verb;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public Plan(String verb) { this.verb = verb; }

    public Plan step(String description, Action action) {
        steps.add(new Step(description, action));
        return this;
    }

    /** A remark to show the user alongside the plan, e.g. what was skipped. */
    public Plan note(String note) {
        notes.add(note);
        return this;
    }

    public String verb() { return verb; }
    public List<Step> steps() { return List.copyOf(steps); }
    public List<String> notes() { return List.copyOf(notes); }
    public boolean isEmpty() { return steps.isEmpty(); }
    public int size() { return steps.size(); }

    /** The one-line question a confirmation prompt asks. */
    public String summary() {
        String count = steps.size() + (steps.size() == 1 ? " item" : " items");
        return verb + " " + count;
    }
}
