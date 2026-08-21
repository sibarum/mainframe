package dev.mainframe.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;

import dev.mainframe.builtins.ConvertBuiltins;
import dev.mainframe.builtins.EnvBuiltins;
import dev.mainframe.builtins.CoreBuiltins;
import dev.mainframe.builtins.FsBuiltins;
import dev.mainframe.builtins.IndexBuiltins;
import dev.mainframe.builtins.TableBuiltins;

/** Every command MainFrame knows, in the order they should be listed. */
public final class Registry {

    private final SequencedMap<String, Builtin> builtins = new LinkedHashMap<>();

    /** The full command set. */
    public static Registry standard() {
        Registry r = new Registry();
        CoreBuiltins.register(r);
        FsBuiltins.register(r);
        TableBuiltins.register(r);
        ConvertBuiltins.register(r);
        IndexBuiltins.register(r);
        EnvBuiltins.register(r);
        return r;
    }

    public void add(Builtin builtin) {
        String name = builtin.signature().name();
        if (builtins.containsKey(name)) {
            throw new IllegalStateException("two builtins are called " + name);
        }
        builtins.put(name, builtin);
    }

    /** Adds a command, taking the place of any existing one with the same name. */
    public void replace(Builtin builtin) {
        builtins.put(builtin.signature().name(), builtin);
    }

    /** Removes a command, so a host can offer a shell without it. */
    public boolean remove(String name) { return builtins.remove(name) != null; }

    public Builtin get(String name) { return builtins.get(name); }

    public boolean has(String name) { return builtins.containsKey(name); }

    public SequencedSet<String> names() { return new LinkedHashSet<>(builtins.keySet()); }

    public List<Builtin> all() { return List.copyOf(builtins.values()); }

    public List<String> categories() {
        List<String> categories = new ArrayList<>();
        for (Builtin b : builtins.values()) {
            String category = b.signature().category();
            if (!categories.contains(category)) categories.add(category);
        }
        return categories;
    }

    public List<Builtin> inCategory(String category) {
        List<Builtin> out = new ArrayList<>();
        for (Builtin b : builtins.values()) {
            if (b.signature().category().equals(category)) out.add(b);
        }
        return out;
    }
}
