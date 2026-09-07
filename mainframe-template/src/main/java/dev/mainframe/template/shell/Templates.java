package dev.mainframe.template.shell;

import dev.mainframe.eval.Registry;

/**
 * Putting project templates into a shell.
 *
 * <p>One call, because a capability that takes six lines to install is a
 * capability people install differently in six places. MainFrame's own
 * {@code Main} does not do this and should not: core cannot see this module, and
 * that is the right way round -- the shell does not depend on there being
 * templates.
 *
 * <pre>{@code
 * Registry registry = Registry.standard();
 * Templates.install(registry);
 *
 * // templates
 * // new vexel-desktop --in=~/Documents/GitHub
 * }</pre>
 */
public final class Templates {

    private Templates() {}

    /** Adds {@code new} and {@code templates}. */
    public static void install(Registry registry) {
        registry.add(new NewProject());
        registry.add(new TemplateList());
    }
}
