package dev.mainframe;

import dev.mainframe.AppSettings.Motion;
import dev.mainframe.AppSettings.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Toggle;
import dev.vexelray.text.TextLayout;

/**
 * The settings menu: a panel docked to the right of the tabs, shown and hidden by the button in the tab bar.
 *
 * <p>Every control applies the moment it is touched and is saved by {@link AppSettings}, so there is nothing to
 * confirm. It is docked rather than floated so it takes real space, and the terminal beside it refits like any
 * other resize.
 */
final class SettingsPanel {

    private final Node panel;
    private final Runnable afterChange;

    SettingsPanel(Gui gui, AppSettings settings, Runnable afterChange) {
        this.afterChange = afterChange;

        Segment<Float> font = new Segment<>(gui);
        for (float px : AppSettings.FONT_SIZES) font.option(String.valueOf((int) px), px);
        font.show(nearest(settings.fontPx())).onChange(px -> {
            settings.fontPx(px);
            done();
        });

        Segment<Motion> motion = new Segment<Motion>(gui)
                .option("Slide", Motion.SLIDE).option("Fade", Motion.FADE).option("None", Motion.NONE);
        motion.show(settings.motion()).onChange(m -> {
            settings.motion(m);
            done();
        });

        Segment<Shell> shell = new Segment<>(gui);
        for (Shell s : settings.shells()) shell.option(s.label(), s);
        shell.show(settings.shell()).onChange(s -> {
            settings.shell(s);
            done();
        });

        Toggle remap = new Toggle(gui, settings.remapKeys());
        remap.onChange(on -> {
            settings.remapKeys(on);
            done();
        });

        var theme = gui.theme();
        this.panel = gui.column().width(Length.rem(19)).height(Length.FILL)
                .background(theme.color(Role.CHROME)).padding(Length.rem(1)).gap(Length.rem(0.5f))
                .children(
                        heading(gui, "Settings"),
                        label(gui, "Font size"), font.node(),
                        label(gui, "Tab animation"), motion.node(),
                        label(gui, "Shell for new tabs"), shell.node(),
                        label(gui, "Remap keybindings"), remap.node(),
                        note(gui, "On: Ctrl+C copies, Ctrl+V pastes, and Esc sends Ctrl+C (interrupt). "
                                + "Off: every key goes to the shell as is. Ctrl+Shift+C and Ctrl+Shift+V always copy and paste."),
                        note(gui, "Font size and animation apply at once; a new shell applies to tabs opened from now on."))
                .visible(false);
    }

    Node node() {
        return panel;
    }

    void toggle() {
        panel.visible(!open);
        open = !open;
        afterChange.run();
    }

    private boolean open;

    private void done() {
        afterChange.run();   // hands the keyboard back to the terminal
    }

    private static float nearest(float px) {
        float best = AppSettings.FONT_SIZES[0];
        for (float f : AppSettings.FONT_SIZES) {
            if (Math.abs(f - px) < Math.abs(best - px)) best = f;
        }
        return best;
    }

    private static Node heading(Gui gui, String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(2)).textSize(Length.rem(1.25f))
                .textColor(gui.theme().color(Role.INK)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    private static Node label(Gui gui, String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(1.5f)).textSize(Length.rem(0.875f))
                .textColor(gui.theme().color(Role.DIM)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.BOTTOM);
    }

    private static Node note(Gui gui, String text) {
        return gui.text(text).width(Length.FILL).textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }
}
