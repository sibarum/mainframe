package dev.mainframe.core;

import dev.mainframe.core.Messages.Mark;
import dev.mainframe.core.Messages.Marked;
import dev.vexelray.framework.api.Job;

/**
 * A Markdown document's colours, worked out on the offload lane: {@link Markdown#spans} over the text it was sent.
 *
 * <p>A job because it is work over the whole document after every edit, and the view's time is the frame's. The reply
 * names the version of the text it was computed from, and the view applies it only if the field still says that, so a
 * slow answer about old text is dropped rather than drawn over new text.
 */
@Job
public final class MarkText {

    public Marked run(Mark m) {
        return new Marked(m.field(), m.version(), Markdown.spans(m.text(), m.style()));
    }
}
