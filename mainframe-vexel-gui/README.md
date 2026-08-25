# MainFrame on vexelray-gui

MainFrame as a window, and the seams an application plugs its own commands and
screens into.

```bash
mvn -pl mainframe-vexel-gui compile exec:exec
```

That is MainFrame with nothing hosting it: the console **is** the application's
main window. Needs `--enable-native-access=ALL-UNNAMED`, which the `exec:exec`
configuration already passes.

## What this is

The shell is a library — a `Session` that prints through a `Renderer` over two
streams — so a window does not have to drive a subprocess or scrape a terminal.
It hands MainFrame two line sinks and gets lines back. This module is the window
that does that, packaged so it is the *same* window everywhere: same look, same
keys, same session-outlives-the-display behaviour, whether it opened out of an
editor, a calculator, or nothing at all.

It is a **shell console, not a terminal emulator**: lines in, lines out. There is
no character grid, no pseudo-terminal and no escape-sequence state machine, and
MainFrame needs none.

## Embedding one

```java
Console console = new Console(ConsoleSpec.builder()
        .windowName("terminal").title("Terminal")
        .memory(windowMemory)
        .project(() -> ProjectScope.at(projectFolder, ".myapp"))
        .app(new ProfileApp(new ProfileStore(settings)))
        .app(myApp)
        .build());

console.show(guiApp, cwd);   // beside your application
// ...
console.tick();              // once per frame
```

`ConsoleSpec` is the whole of what one console can disagree with another about:
its name and title, its size, its theme, the apps in it, the project it is
pointed at, what it says when it opens, what its bottom line offers, and the one
fact it shows in the header. Everything has a default that works, so the smallest
useful console is `new Console(ConsoleSpec.builder().build())`.

## Plugging something in

A `ConsoleApp` is what an application looks like from the console's side: a name,
some commands, some menu entries, and — when it has one — a window to open.

```java
public final class EditorApp implements ConsoleApp {
    public String name()      { return "editor"; }
    public boolean launchable() { return true; }

    public void commands(Registry registry, ConsoleContext console) {
        registry.add(editCommand());     // a real MainFrame builtin
    }
    public void launch(ConsoleContext console) { raiseTheEditorWindow(); }
}
```

The commands you register are indistinguishable from built-in ones — same
argument checking, same `help`, same `--dry-run`, same errors — because they
*are* built-in ones. `apps` lists what is plugged in and `launch "editor"` opens
one, so an application that has a window is reachable from the command line
without the console knowing what it is.

**Menu entries submit commands.** `ConsoleContext.run` echoes the line into the
scrollback, so a menu that changes the environment has taught you the command
rather than leaving you guessing at what it did — and anything the menu can do
can be scripted, piped and put in a file, because it was never anything but a
command. `ProfileApp` is the worked example: its commands, its menu and its
header badge are all contributed through this interface and nothing else.

## Data entry without drawing a screen

`ConsoleContext.form` takes a MainFrame `Form` — a list of fields — prints the
questions into the scrollback, and reads the answers off the command line. You
get `!back`, `!cancel`, `!clear`, the review step, and per-field validation, and
you did not draw anything.

```java
Value.Rec answers = console.form(Profile.definition(), starting, "New profile");
```

## Packages

| Package | What is in it |
| --- | --- |
| `dev.mainframe.gui.app` | `ConsoleApp`, `ConsoleContext`, `ProjectScope` — the seams. Knows nothing about the window. |
| `dev.mainframe.gui.console` | `Console`, `ConsoleSpec`, and the machinery behind them: the ANSI translation, the scrollback ring, the prompt pipe, the phosphor palette. |
| `dev.mainframe.gui.profile` | Environment profiles as a `ConsoleApp`: toolchains, said once and applied whole. |
| `dev.mainframe.gui.desktop` | `Desktop` — the standalone `main()`. |

## Why the console looks like a 5250

MainFrame is a shell whose pipes carry typed records rather than text, which is
the one idea it shares with the machine the screen is borrowed from. So the
window wears the part: a green tube, `Command ===>` over a boxed entry area, and
a message line that turns over into reverse video when something failed.

What is left is what earns its row. A 5250 spent its top three lines on a screen
identifier, a centred title and an instruction line that stopped being news the
second time anyone read it. Those are scrollback now; the working directory and
the clock, the only things up there that ever changed, share one.
