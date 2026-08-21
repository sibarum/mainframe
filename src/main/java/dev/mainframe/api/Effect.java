package dev.mainframe.api;

import dev.mainframe.eval.Signature;

/**
 * What a hosted command does to the world. This is not paperwork: it decides
 * which guardrails MainFrame puts in front of the callback.
 */
public enum Effect {

    /** Works only on what it is given. */
    PURE,
    /** Reads from disk or elsewhere, changes nothing. */
    READS,
    /** Changes the session -- a setting, a connection -- but nothing on disk. */
    SESSION,
    /**
     * Creates or updates things. MainFrame adds --dry-run, and the command must
     * be a {@link PlannedCommand} so there is something to show.
     */
    WRITES,
    /**
     * Can lose data. MainFrame adds --dry-run and --yes, asks before running,
     * and refuses to run unattended without --yes.
     */
    DESTRUCTIVE;

    Signature.Effect internal() {
        return switch (this) {
            case PURE -> Signature.Effect.PURE;
            case READS -> Signature.Effect.READS;
            case SESSION -> Signature.Effect.SESSION;
            case WRITES -> Signature.Effect.WRITES;
            case DESTRUCTIVE -> Signature.Effect.DESTRUCTIVE;
        };
    }

    boolean needsAPlan() { return this == WRITES || this == DESTRUCTIVE; }
}
