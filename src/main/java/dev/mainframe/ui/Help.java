package dev.mainframe.ui;

import java.util.List;

import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;

/**
 * Help text, generated from signatures rather than written by hand -- so it
 * cannot drift out of date, and every command is documented by construction.
 */
public final class Help {

    private Help() {}

    /** The command list, grouped the way people look for things. */
    public static void overview(Renderer out, Registry registry) {
        out.info(out.bold("MainFrame") + " -- type a command, or " + out.cyan("help <command>")
                + " to see how one works.");
        out.info("");
        for (String category : registry.categories()) {
            out.info(out.bold(category));
            List<Builtin> builtins = registry.inCategory(category);
            int width = 0;
            for (Builtin b : builtins) width = Math.max(width, b.signature().name().length());
            for (Builtin b : builtins) {
                Signature s = b.signature();
                out.info("  " + out.cyan(pad(s.name(), width)) + "  " + s.summary() + effectTag(out, s));
            }
            out.info("");
        }
        out.info(out.dim("run an external program with a caret, e.g. ") + out.cyan("^git status"));
        out.info(out.dim("add ") + out.cyan("--dry-run") + out.dim(" to any command that changes files"));
    }

    /** Everything about one command. */
    public static void command(Renderer out, Builtin builtin) {
        Signature s = builtin.signature();
        out.info(out.bold(s.name()) + " -- " + s.summary());
        out.info("");
        out.info(out.bold("usage") + "  " + s.usage());

        if (s.input() != dev.mainframe.value.ValueType.NOTHING) {
            out.info(out.bold("in") + "     " + s.input().display() + out.dim(" (from the pipe)"));
        }
        out.info(out.bold("out") + "    " + s.output().display());
        out.info(out.bold("does") + "   " + explainEffect(s));

        if (!s.params().isEmpty()) {
            out.info("");
            out.info(out.bold("arguments"));
            int width = 0;
            for (Signature.Param p : s.params()) width = Math.max(width, p.name().length());
            for (Signature.Param p : s.params()) {
                String kind = p.required() ? "required" : "optional";
                out.info("  " + out.cyan(pad(p.name(), width)) + "  " + p.description()
                        + out.dim("  [" + p.type().display() + ", " + kind + (p.rest() ? ", repeatable" : "") + "]"));
            }
        }

        if (!s.flags().isEmpty()) {
            out.info("");
            out.info(out.bold("flags"));
            int width = 0;
            for (Signature.Flag f : s.flags()) width = Math.max(width, label(f).length());
            for (Signature.Flag f : s.flags()) {
                out.info("  " + out.cyan(pad(label(f), width)) + "  " + f.description());
            }
        }

        if (!s.examples().isEmpty()) {
            out.info("");
            out.info(out.bold("examples"));
            for (String example : s.examples()) out.info("  " + out.green(example));
        }
    }

    private static String label(Signature.Flag f) {
        String name = "--" + f.name() + (f.isSwitch() ? "" : "=" + f.type().display());
        return f.shortName() == '\0' ? name : "-" + f.shortName() + ", " + name;
    }

    private static String explainEffect(Signature s) {
        return switch (s.effect()) {
            case PURE -> "nothing outside this pipeline";
            case READS -> "reads from disk, changes nothing";
            case SESSION -> "changes this session only -- your files are untouched";
            case WRITES -> "creates or updates files (supports --dry-run)";
            case DESTRUCTIVE -> "can lose data, so it always asks first (supports --dry-run and --yes)";
        };
    }

    private static String effectTag(Renderer out, Signature s) {
        return switch (s.effect()) {
            case DESTRUCTIVE -> " " + out.yellow("(asks first)");
            case WRITES -> " " + out.dim("(changes files)");
            case SESSION -> " " + out.dim("(changes this session)");
            default -> "";
        };
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
