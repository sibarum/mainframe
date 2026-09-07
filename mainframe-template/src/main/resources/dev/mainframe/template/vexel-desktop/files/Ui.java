package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The tree. Builds it, holds the handles the application needs afterwards, and owns nothing else.
 *
 * <h2>It holds no state</h2>
 *
 * <p>{@link #show} takes a whole {@link Doc} and writes everything derived from it, which is the rule worth
 * keeping as this grows: a view that remembers a value can disagree with the document, and a label that
 * disagrees with the state is not a cosmetic bug -- it is the application lying about what it is doing.
 *
 * <p>{@code show} is called from the committing thread, which is a worker. That is correct and is the
 * framework's own idiom: a prop written off the GUI thread is queued and applied by the next drain, so the
 * frame that presents a value is the frame that reconciled it. What is <em>not</em> allowed is the other
 * direction -- reading the model from inside the frame loop.
 */
final class Ui {

    private final Gui gui;
    private final TitleBar titleBar;
    private final Node count;
    private final Node note;

    Ui(Gui gui, Model model) {
        this.gui = gui;

        // The window's own chrome. Handed no controls yet -- the window does not exist until GuiApp is
        // constructed, and a bar bound to a window that is not there would be a set of buttons that do nothing.
        titleBar = new TitleBar(gui, WindowControls.NONE, ${className}.TITLE);

        Node heading = gui.text(${className}.TITLE)
                .font(Type.UI)
                .textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));

        count = gui.text("0")
                .font(Type.MONO)
                .textSize(Type.FIGURE)
                .textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.COUNT, count);

        note = gui.text("")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        gui.landmark(Landmarks.NOTE, note);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count", model::bump),
                button(Landmarks.RESET_BUTTON, "Reset", model::reset));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, buttons);
        gui.landmark(Landmarks.CARD, card);

        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.PAGE))
                .children(card);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body);
    }

    /**
     * An outline button.
     *
     * <p>There is no button component in {@code vexelray-gui-widget} yet, so this is the hand-rolled shape the
     * reference implementation uses: a text node made focusable, given a pointer cursor, a click handler and a
     * hover wash. Four ordinary calls rather than a widget -- and worth noting in {@code docs/framework-notes.md}
     * as an opportunity, because every project on this framework writes these four calls again.
     */
    private Node button(String landmark, String label, Runnable action) {
        Node button = gui.text(label)
                .font(Type.UI)
                .textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.INK))
                .padding(Type.TIGHT, Type.WIDE)
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Role.LINE))
                .role("button");
        gui.focusable(button, true);
        gui.cursor(button, CursorShape.POINTER);
        // The handler runs on a worker, not on the GUI thread. Everything it touches goes through Model.
        gui.onClick(button, action);
        gui.onState(button, state -> button.background(state == InteractionState.HOVER
                ? gui.theme().color(Role.ACCENT, InteractionState.HOVER)
                : gui.theme().color(Role.NONE)));
        gui.landmark(landmark, button);
        return button;
    }

    /**
     * Write everything derived from the document.
     *
     * <p>One method taking the whole value, rather than a setter per field: two setters can be called with
     * values from two different versions, and this cannot.
     */
    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
        note.text(doc.note());
    }

    TitleBar titleBar() {
        return titleBar;
    }
}
