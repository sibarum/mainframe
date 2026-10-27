package dev.mainframe;

import sibarum.atchung.Topic;

/**
 * Something the person did that the {@link View} has to act on, sent from the thread it happened on to the view's own
 * lane.
 *
 * <p>A key chord, a tab's menu, a tile of the Open dialog and a folder picked in the OS's dialog all arrive somewhere
 * other than the view's lane, and what they ask often needs the view's word on its tabs: which tab is in front, what
 * its header says, whether the only tab is still unused. So the handler only says what happened, and the view does
 * it, in order, on its own thread. {@code id} is the tab a gesture is about ({@code NO_TAB} where none is), and
 * {@code text} the folder or workspace it names (blank where none is).
 */
record Intent(Command command, long id, String text) {

    static final String TOPIC = "view.intent";
    static final Topic<Intent> INTENTS = Topic.of(TOPIC, Intent.class);

    /** Each command is a method on the view, so there is no switch to keep in step with this list. */
    enum Command {
        /** Ctrl+W: close the tab, after asking. */
        CLOSE {
            @Override
            void on(View view, long id, String text) {
                view.close(id, true);
            }
        },
        /** Ctrl+Shift+W: close the tab without asking. */
        CLOSE_NOW {
            @Override
            void on(View view, long id, String text) {
                view.close(id, false);
            }
        },
        /** A tab's menu: its own environment script. */
        EDIT_TAB_ENV {
            @Override
            void on(View view, long id, String text) {
                view.editEnv(id, true);
            }
        },
        /** A tab's menu: its workspace's environment script. */
        EDIT_WORKSPACE_ENV {
            @Override
            void on(View view, long id, String text) {
                view.editEnv(id, false);
            }
        },
        /** The Open dialog's first tile: ask the OS for a folder. */
        PICK_FOLDER {
            @Override
            void on(View view, long id, String text) {
                view.pickFolder();
            }
        },
        /** A folder was picked, {@code text}, to open as a tab here. */
        OPEN_FOLDER {
            @Override
            void on(View view, long id, String text) {
                view.openFolder(text);
            }
        },
        /** Workspace {@code text}, in a MainFrame of its own. */
        OPEN_WINDOW {
            @Override
            void on(View view, long id, String text) {
                view.openWorkspace(text, false);
            }
        },
        /** Workspace {@code text}'s tabs, here. */
        OPEN_HERE {
            @Override
            void on(View view, long id, String text) {
                view.openWorkspace(text, true);
            }
        };

        abstract void on(View view, long id, String text);
    }
}
