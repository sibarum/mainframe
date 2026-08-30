package dev.mainframe.assistant;

import dev.mainframe.eval.Signature;

/**
 * How much nerve the assistant has, decided by what a command touches.
 *
 * <p>MainFrame already sorts every command by {@link Signature.Effect}, and that
 * sorting is the whole policy -- there is no separate list of what a model may
 * do, because a second list would be a second thing to keep true. A command that
 * only reads can be got wrong and cost you a wrong answer on screen. A command
 * that writes can be got wrong and cost you the afternoon. The gap between those
 * is exactly the gap the enum already describes.
 *
 * <p>Note what this is not. It is not the last line of defence: a destructive
 * command still goes through {@code plan} and still asks, because it asks for
 * anyone who types it. This is the earlier decision about whether a guess is
 * worth putting in front of a person at all.
 */
public enum Gate {

    /** Run it. Getting this wrong shows the wrong thing and changes nothing. */
    RUN,
    /**
     * Show the line and what it plans to do, and run it only if the person says
     * so. Everything that changes anything lands here, including changes to the
     * session: a model that can quietly move the shell somewhere else has moved
     * the ground under every command that follows.
     */
    ASK,
    /**
     * Never, on a guess. Reserved for what the interceptor catches before the
     * model is ever reached -- see {@link Interceptor}.
     */
    REFUSE;

    public static Gate on(Signature.Effect effect) {
        return switch (effect) {
            case PURE, READS -> RUN;
            case SESSION, WRITES, DESTRUCTIVE -> ASK;
        };
    }

    public static Gate on(Signature signature) { return on(signature.effect()); }
}
