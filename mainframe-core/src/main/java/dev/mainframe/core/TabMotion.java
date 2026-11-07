package dev.mainframe.core;

/**
 * How the tab bar changes tabs, as Settings says: slid, faded, or at once. The view gives each its {@code Tabs}
 * transition; what is saved is the name ({@code tabs.motion}, 0.1's key).
 */
public enum TabMotion {
    SLIDE, FADE, NONE;

    /** The motion a saved name stands for; anything unknown is the default, {@link #SLIDE}. */
    static TabMotion named(String name) {
        for (TabMotion m : values()) {
            if (m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return SLIDE;
    }
}
