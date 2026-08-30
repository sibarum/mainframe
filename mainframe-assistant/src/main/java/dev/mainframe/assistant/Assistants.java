package dev.mainframe.assistant;

import dev.mainframe.eval.Registry;

/**
 * Putting the assistant into a shell.
 *
 * <p>One call, because a capability that takes six lines to install is a
 * capability people install differently in six places. MainFrame's own
 * {@code Main} does not do this: core cannot see this module, and that is the
 * right way round -- the shell does not depend on there being an assistant, and
 * this module is kept out of {@code mainframe-dist} until the native-image build
 * is known to survive OkHttp.
 *
 * <pre>{@code
 * Registry registry = Registry.standard();
 * Assistants.install(registry);
 * }</pre>
 */
public final class Assistants {

    private Assistants() {}

    /** Adds {@code ask} and {@code key}, talking to Anthropic. */
    public static void install(Registry registry) {
        registry.add(new Ask());
        registry.add(new Keys());
    }

    /**
     * The same, with a model of your own.
     *
     * <p>What the tests use, and what anything embedding MainFrame would use to
     * point the assistant somewhere else. {@code key} still goes in: whoever
     * supplies the model may still want somewhere to keep its credential.
     */
    public static void install(Registry registry, Model model) {
        registry.add(new Ask(model));
        registry.add(new Keys());
    }
}
