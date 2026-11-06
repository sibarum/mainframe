package dev.mainframe;

import sibarum.atchung.Topic;

/**
 * What a key chord asks of the {@link View}: the message the gesture was declared to send.
 *
 * <p>A chord is a declaration (a keymap action's {@code sends(sender, message)}, threading.md T7.5): the dispatcher
 * sends this when one of the action's chords is pressed and runs nothing of the application's, and the view hears it
 * in the frame, on the GUI thread, like any other message. Which chords those are is the person's to change. A chord
 * the key map holds outranks the terminal's typing, so taking a chord away from here gives it back to the program.
 */
record Intent(Command command) {

    static final Topic<Intent> INTENTS = Topic.edge(Intent.class);

    /** Each command is a method on the view, so there is no switch to keep in step with this list. */
    enum Command {
        COPY {
            @Override
            void on(View view) {
                view.copyOrInterrupt();
            }
        },
        PASTE {
            @Override
            void on(View view) {
                view.paste();
            }
        },
        INTERRUPT {
            @Override
            void on(View view) {
                view.interrupt();
            }
        },
        PAGE_BACK {
            @Override
            void on(View view) {
                view.page(-1);
            }
        },
        PAGE_ON {
            @Override
            void on(View view) {
                view.page(1);
            }
        },
        LARGER {
            @Override
            void on(View view) {
                view.fontSize(1);
            }
        },
        SMALLER {
            @Override
            void on(View view) {
                view.fontSize(-1);
            }
        },
        NORMAL {
            @Override
            void on(View view) {
                view.fontSize(0);
            }
        };

        abstract void on(View view);
    }
}
