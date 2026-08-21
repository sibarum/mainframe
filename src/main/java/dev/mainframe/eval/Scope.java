package dev.mainframe.eval;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SequencedSet;

import dev.mainframe.value.Value;

/** A set of named values, with a parent to fall back on. */
public final class Scope {

    private final Scope parent;
    private final Map<String, Value> values = new LinkedHashMap<>();

    public Scope(Scope parent) { this.parent = parent; }

    public Scope child() { return new Scope(this); }

    public void define(String name, Value value) { values.put(name, value); }

    public boolean has(String name) {
        return values.containsKey(name) || (parent != null && parent.has(name));
    }

    /** Returns null when the name is unknown; callers turn that into an error with suggestions. */
    public Value get(String name) {
        Value v = values.get(name);
        if (v != null) return v;
        return parent == null ? null : parent.get(name);
    }

    /** Every visible name, innermost first, for suggestions and completion. */
    public SequencedSet<String> names() {
        SequencedSet<String> all = new LinkedHashSet<>(values.keySet());
        if (parent != null) all.addAll(parent.names());
        return all;
    }
}
