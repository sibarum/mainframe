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
    public String name()        { return "editor"; }
    public boolean launchable() { return true; }

    public void commands(Registry registry, ConsoleContext console) {
        registry.add(editCommand());     // a real MainFrame builtin
    }
    public void launch(ConsoleContext console) { raiseTheEditorWindow(); }
    public void tick() { myWindow.tick(); }      // frame loop, once per frame
}
```

The commands you register are indistinguishable from built-in ones — same
argument checking, same `help`, same `--dry-run`, same errors — because they
*are* built-in ones. `apps` lists what is plugged in, so an application that has
a window is reachable from the command line without the console knowing what it
is.

**Typing a program's name runs it.** Every launchable app also gets a command
named after itself — `editor` opens the editor — so the everyday way to start
something is its name, not a verb and a quoted string. `launch "editor"` is still
there for scripts, where a name that came out of a variable has to be quoted
anyway.

Names are handed out in three passes, and the order is the whole policy: `apps`
and `launch` first, so an app can't quietly take the command that opens it; then
every app's own commands; then the name-shaped shortcuts, filling only what is
left. So an app is free to claim its own name for something better — the
calculator registers `calc`, which works an expression out when given one and
opens the keypad when not.

`launch` does the thread hop for you: a command body runs on the shell's job
thread, and `launch` queues your `launch(…)` onto the frame loop before calling
it. `tick()` is called by the console rather than by whoever embedded it —
plugging an app in has to be the whole of wiring it up.

## MainFrame as the program

`Desktop.run` is the same boot the standalone console uses, with your apps handed
in. A whole application is then this:

```java
public static void main(String[] args) throws Exception {
    Desktop.run("calculator", "MainFrame",
            (settings, memory) -> List.of(new Calculator(memory)), args);
}
```

MainFrame comes up as the main window, `apps` lists what is in it, and `calc`
opens the calculator in a window of its own — which in turn opens its own plot and
history windows, so the window list is a tree rather than a list. Nothing in that
application owns a frame loop, a window memory, an input backend or a clipboard;
those are in `Desktop`, once.

```
~ > apps
name        launchable  summary
profiles    false       named sets of environment variables and binary directories
calculator  true        a keypad, a tape, and a plotter for anything with a variable in it

~ > calc                    # the keypad
~ > calc "2^10"             # 1024
```

`--launch <app>` comes up with one already open, `--capture [out.png]` writes a
headless still, `--capture-panel [out.png]` writes one of a data entry screen,
and a bare number is a frame cap.

**Menu entries submit commands.** `ConsoleContext.run` echoes the line into the
scrollback, so a menu that changes the environment has taught you the command
rather than leaving you guessing at what it did — and anything the menu can do
can be scripted, piped and put in a file, because it was never anything but a
command. `ProfileApp` is the worked example: its commands, its menu and its
header badge are all contributed through this interface and nothing else.

## Data entry, two ways of asking

`ConsoleContext.form` takes a MainFrame `Form` — a list of fields — and asks for
it. You did not draw anything either way.

```java
Value.Rec answers = console.form(Profile.definition(), starting, "New profile");
```

With a window there is a display to borrow, so the whole form goes up at once as
a screen with every field on view. Without one — the headless capture, a test —
the questions are printed into the scrollback and the answers read off the
command line, which is where `!back`, `!cancel`, `!clear` and the review step
come from.

Same `Form` both ways, and that is the point: it still says what a good answer
is, `Field.read` still says what typed text means, and the record at the end is
the same record. Only the asking differs.

### How a screen behaves

The rule the whole thing is built to: **everything can be done from the keyboard,
nothing surprising can be done by accident, and the screen says which is which as
you go.**

| | |
|---|---|
| **Tab** / Shift+Tab | move between fields **and buttons**, wrapping. Landing on a button does not press it. |
| **Enter** | on a field, the next field. On a button, press it. |
| **Esc** | give up. |
| **Space** | presses a focused button, and is otherwise a space. |
| arrows, Home, End, Backspace, Delete | inside the field, as anywhere. |
| a click | moves the caret, to the character clicked. It is the only pointer gesture that moves anything. |
| the pointer resting on something | lights it up, and moves nothing. |

Two things fall out of that and both are deliberate. Enter never submits a form
from a field, so a stray one cannot send a half-filled screen — but the buttons
are in the Tab ring and Submit is the last thing on the screen, so Enter pressed
enough times walks to Submit and then presses it. A form can be filled in and
sent without the hands leaving the keys, without anything having to be learnt.
And the function keys still work, and are still what the screen lists in `keys` —
but nothing depends on them, because a keyboard without an F12 is the ordinary
kind.

The screen shows its own state rather than relying on a legend. The caret is a
block of reversed phosphor that blinks, and holds solid while you type; a focused
button is reversed the same way, being the same idea — where the next keystroke
goes; the pointer's highlight is a lit background, with an underline on a button
to say it can be pressed. Under the screen, in the editor's own row rather than in
the screen MainFrame described, one line says what Tab, Enter and Esc will do
**now** — "Enter next field" on a field, "Enter press Submit" on the button. What
Enter is about to do should never have to be worked out.

### Fitting the window

The panel measures the tube and says how many rows and columns it has, so a form
is laid out for the window somebody actually has rather than for a number in a
source file. Drag the window and it reports a `resize`; MainFrame lays the screen
out again at the new size and the values on it come back with the event, so
nothing typed is lost in the exchange. An editor must never reflow a screen it was
given — those cells were placed deliberately — which is why the answer is a new
screen rather than a rearranged one.

A form uses up to 120 columns and no more: past a certain length an entry field is
harder to read, not easier, and the protocol says plainly that an editor with more
room than the screen asked for may do as it likes with the rest. Between the
report and the new screen there is a frame where the old one is the wrong size for
its room, and for that frame the panel scrolls rather than clipping.

The one thing on a screen whose length MainFrame does not choose is prose — a
field's `help` is however long somebody wrote it — so that is the one thing
wrapped to fit rather than placed. A hint too long for a list of details used to be
written straight through the frame drawn around it.


**The screen is the protocol, not a widget.** The panel is an implementation of
[`PROTOCOL.md`](../PROTOCOL.md) — MainFrame describes a screen in character cells
and waits, this window paints it and reports what happened to it, and it holds no
meaning at all: it does not know what a form is, and adding a feature to MainFrame
must never mean editing it. Which cuts both ways, and the useful way is the second
one: anything MainFrame learns to put on a screen appears here for free, and
`mainframe --panel` already speaks the same thing down a pipe.

It says what it can do rather than what version it is, and it deliberately says
less than it could. No `choice`, because a dropdown is a thing that opens and a
tube has nowhere to open it into — so MainFrame writes the options above the field
as text and sends a plain entry, which is both readable and what a 5250 did with a
choice. It does claim `resize`, because it measures its own room and reports one
when the room changes. An editor that claims what it cannot do well is worse than
one that lets MainFrame render down.

## Packages

| Package | What is in it |
| --- | --- |
| `dev.mainframe.gui.app` | `ConsoleApp`, `ConsoleContext`, `ProjectScope` — the seams. Knows nothing about the window. |
| `dev.mainframe.gui.console` | `Console`, `ConsoleSpec`, and the machinery behind them: the ANSI translation, the scrollback ring, the prompt pipe, the panel, the phosphor palette. |
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
