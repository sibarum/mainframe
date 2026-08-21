package dev.mainframe.builtins;

import java.util.ArrayList;
import java.util.List;

import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.value.Json;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

/** Turning values into text and back again. */
public final class ConvertBuiltins {

    private static final String CATEGORY = "converting";

    private ConvertBuiltins() {}

    public static void register(Registry registry) {
        registry.add(toJson());
        registry.add(fromJson());
        registry.add(lines());
        registry.add(toText());
    }

    private static Builtin toJson() {
        Signature signature = Signature.named("to-json", CATEGORY)
                .summary("write what came down the pipe as JSON")
                .switchFlag("compact", 'c', "leave out the indentation")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("ls | select name size | to-json")
                .build();
        return Cmd.of(signature, args ->
                new Value.Str(Values.toJson(args.input(), args.flag("compact") ? 0 : 2)));
    }

    private static Builtin fromJson() {
        Signature signature = Signature.named("from-json", CATEGORY)
                .summary("read JSON text into real values")
                .input(ValueType.STRING)
                .output(ValueType.ANY)
                .example("cat package.json | from-json | get name")
                .example("^curl -s https://example.com/data.json | from-json")
                .build();
        return Cmd.of(signature, args ->
                Json.parse(Values.asString(args.input(), args.span()), args.span()));
    }

    private static Builtin lines() {
        Signature signature = Signature.named("lines", CATEGORY)
                .summary("split text into a list of lines")
                .switchFlag("keep-empty", '\0', "keep blank lines instead of dropping them")
                .input(ValueType.STRING)
                .output(ValueType.LIST)
                .example("cat notes.txt | lines | length")
                .build();
        return Cmd.of(signature, args -> {
            String text = Values.asString(args.input(), args.span());
            List<Value> out = new ArrayList<>();
            text.lines().forEach(line -> {
                if (args.flag("keep-empty") || !line.isBlank()) out.add(new Value.Str(line));
            });
            return new Value.ListVal(List.copyOf(out));
        });
    }

    private static Builtin toText() {
        Signature signature = Signature.named("to-text", CATEGORY)
                .summary("flatten anything into plain text")
                .input(ValueType.ANY)
                .output(ValueType.STRING)
                .example("ls | get name | to-text")
                .build();
        return Cmd.of(signature, args -> {
            Value input = args.input();
            if (input instanceof Value.ListVal list) {
                StringBuilder sb = new StringBuilder();
                for (Value item : list.items()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(Values.display(item));
                }
                return new Value.Str(sb.toString());
            }
            return new Value.Str(Values.display(input));
        });
    }
}
