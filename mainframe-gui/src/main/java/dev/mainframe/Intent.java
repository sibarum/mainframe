package dev.mainframe;

import sibarum.atchung.Topic;

/**
 * What a key chord asks of the {@link View}: the message the gesture was declared to send.
 *
 * <p>A chord is a declaration ({@code gui.shortcut(chord, sender, message)}, threading.md T7.5): the dispatcher sends
 * this when the chord is pressed and runs nothing of the application's, and the view hears it in the frame, on the GUI
 * thread, like any other message.
 */
record Intent(Command command) {

    static final Topic<Intent> INTENTS = Topic.edge(Intent.class);

    /** Each command is a method on the view, so there is no switch to keep in step with this list. */
    enum Command {
        SAVE_FRONT {
            @Override
            void on(View view) {
                view.saveFront();
            }
        },
        CLOSE_FRONT {
            @Override
            void on(View view) {
                view.closeFront();
            }
        },
        NEXT_TAB {
            @Override
            void on(View view) {
                view.cycle(1);
            }
        },
        PREVIOUS_TAB {
            @Override
            void on(View view) {
                view.cycle(-1);
            }
        },
        FOCUS_PATH {
            @Override
            void on(View view) {
                view.focusPath();
            }
        },
        FOCUS_TREE {
            @Override
            void on(View view) {
                view.focusTree();
            }
        },
        ZOOM_IN {
            @Override
            void on(View view) {
                view.zoom(1);
            }
        },
        ZOOM_OUT {
            @Override
            void on(View view) {
                view.zoom(-1);
            }
        },
        ZOOM_RESET {
            @Override
            void on(View view) {
                view.zoom(0);
            }
        };

        abstract void on(View view);
    }
}
