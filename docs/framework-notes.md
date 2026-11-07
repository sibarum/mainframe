# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**MainFrame** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the [retrospective](retrospective.md) at the end of the milestone has a candidate rather than a
complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

The numbers start again at FN-1 for the 0.2 rebuild; 0.1's notes are in `GitHub/mainframe`'s history.

## FN-1 · An application with a grammar of its own still has nowhere to put its parse 🔬

0.1's FN-1, met again on 0.2 (M0). `mainframe --dir=a --dir=b pwsh -NoProfile` is out of `Launch`'s reach: it
refuses `-NoProfile` as an unknown option, keeps one value per key so the second `--dir` replaces the first, and
reads a bare number in the command line (`python 3.py`) as a frame count. So `MainFrame.main` takes its own part out
with `Command.parse` and hands the framework the rest, as 0.1 did, and the parse reaches the wiring only through a
static (`Command.launched()`), set by `main` and read by the `ReadStartup` job. A step could not read it; a recipe
cannot be handed it.

**For the framework:** a way to give `VexelApplication.run` a value the wiring can provide (`run(wiring, args,
extras)`), or `@VexelApp(arguments = MyGrammar.class)` naming a parser the generated `main` calls, whose result is a
provided part; and its option names in the usage line.

Checked live in M1: `mainframe --bogus` stops before a window opens, exit 2, but with the framework's message. It
lists the framework's options only and gives the usage as `mainframe [--key=value] [frames]`, so someone who mistyped
`--dirs` is told neither that it exists nor that a command line may follow.

## FN-2 · A widget's messages are keyed by its window, not by the widget 🔬

`TerminalScreen` publishes `SENT` and `FITTED` on topics built by `Addressed.edge`/`Addressed.state`, whose key is the
window (`tree(address)`). Each tab's `Shell` is a destination keyed by the tab, so it cannot hear a screen's
messages as they are: they would all go to one key. The view keeps a map from each screen's address to its tab and
sends each on as `Typed` or `Resized` with the tab's id: two forwarding methods, and a hop on the GUI thread per
keystroke. Any application that gives each pane or tab a connection of its own meets this.

**For the framework (vexelray-gui):** let a part keyed per widget hear a widget's topic by the widget's address (a
topic keyed by `address()` as well as scoped by window), or say in `Addressed` that per-widget hearing is the
view's to forward and why.

## FN-3 · A source's `open` has no door for a failure it expects 🔬

What escapes a destination's `open` halts the application, and a command line naming no program
(`mainframe nosuch.exe`) is the person's typo. `open` cannot return "no connection, and why", so `Shell`'s connection
is a `Session(Pty pty, String problem)` that may hold nothing, `open` catches what starting throws, and `run` answers
at once with a `ShellEnded` carrying the reason. Every `write` and `close` checks for the empty session.

**For the framework:** an `open` that may return a refusal (an `Opened<C>` of a connection or a reason), which the
framework turns into the run's reply without calling `run`, `write` or `close`.

## FN-4 · A source's sender cannot be a lambda in a test 🔬

Vexplore's FN-16, met by `ShellTest`: `Sender` is not a functional interface, so collecting what `Shell` reads is a
bus, a tick, an inbox subscribed before the run, `Sender.outside(bus, topic, Cause.source(...))`, and running the
tick while waiting.

**For the framework (atchung):** `Sender.of(Consumer)`, delivering at once with no bus.

## FN-5 · A terminal's text is not readable by a script, so nothing can wait for a prompt 🔬

`ottermate` waits with `await <landmark> <text>`, which reads a node's accessible name. A `TerminalScreen`'s is
empty, so a script cannot wait for the shell's first prompt or a command's output, and `settle` does not wait for a
program. M0's check drove a background run with separate `ottermate` calls, letting the time between them be the
wait. 0.1's README had the same gap ("give it one before typing").

**For vex-suite-common (the tty widget):** the screen's visible text, or its last lines, as its accessible name or
a readout a script can `await`.

## FN-6 · The title bar's lockup names the `-gui` module, not the application 🔬

Seen in M0: under `exec:exec` the lockup reads "mainframe-gui", the `-gui` module's `<name>`, not "MainFrame". The
lockup is `About.name(info, build)`, which prefers the build's name to the `@VexelApp` title, and the template's
two-module split makes the build the `-gui` module's. Every application generated from it shows its module's name
unless it renames its pom. Not traced further.

**For the framework (template):** name the `-gui` module's build after the application, or have the lockup prefer
the title.

## FN-7 · No key button in the title bar 💡

In M0's runs the title bar showed the screenshot and automation instruments and no key button, though the view
declares eight actions on the key map and the framework says it places one on every bar. Seen in `ottermate tree`;
not looked into, so this may be a condition of the button's not yet understood here.

**Resolved, not a gap** (read in the source while drafting docs/passthrough.md, M2): the framework adds the key
bindings as a line, *Key bindings…*, in the app menu the lockup opens, once any action is declared
(`VexelApplication`, `KeymapWindow.instrument`). It has no badge, so by design it never stands on the bar. Not yet
seen live.


## FN-8 · A widget's per-character edge fills a view's mailbox sized for 64 🔬

`TerminalScreen` publishes `SENT` once per character typed, all in the frame the keys arrive in, and the view
hears them in its next frame. An `@Subscribe` mailbox holds 64 edges and one more halts the application (T4.2), so
the first `ottermate type` of a 150-character line stopped MainFrame with *mailbox for topic ...TerminalScreen.Sent is
full at capacity 64*. A person never types 64 keys in a frame; a script, a paste-as-keys tool or a long-stalled
frame can. Done: `@Subscribe(capacity = 1024)` on the view's `sent`, which moves the cliff rather than removing it.

**For the framework:** an edge produced and consumed on the GUI thread can be bounded by what one frame produces.
Either a widget sends a frame's typing as one message (the screen batching its `SENT`s, as it could at the end of
the frame body), or a view's mailbox for a topic published on its own thread is drained in the same frame. A
capacity is a guess at the traffic; neither of these needs one.

## FN-9 · A source that outruns a view halts the application 🔬

The measurement M1 asked for, `type` of an 8 MB file in a tab, never got as far as the draw. `Shell` reads the
pseudo-terminal 16 KB at a time and sends each read as an `Output`. It reads faster than the view hears, so about
250 ms in the view's `Output` mailbox was full at 64 and the bus halted MainFrame (*full at capacity 64 after 69
delivered*). Any program that writes fast enough does it: a build log, `cat` of a large file, `git log -p`.

The framework's ruling (components.md §11, question 1: "Backpressure: nothing in the framework") puts this on the
source: one that can outrun its hearers buffers and batches. Batching by time alone keeps the cliff, since a view that
takes longer to draw a big batch lets the next ones pile up. What bounds it is the source sending only when the
view has taken what it sent. That is flow control written by every application with a fast source, through a
message of its own. **Built in M1** (rebuild.md's M1 has it and the measurement): `Shown(id)` from the view per
`Output` fed, and `Shell` holding two in flight per tab, buffering the rest as one, and stopping reading at 1 MB
waiting. About 70 lines, a record and a test, which every fast reader on 0.2 would write again.

**For the framework:** a source's `Sender` that is told when its hearers have room, or a "latest unconsumed batch"
topic kind whose publisher appends to what has not been taken yet, would make every fast reader (a terminal, a log
tail, the Inspector's link) correct without a protocol of its own.

## FN-10 · `Tabs` has no place for an application's own nodes, beside the headers or over one 🔬

M2 wanted a + after the last header, the passthrough pill at the bar's end (docs/passthrough.md), and a rename field
standing in for the header it renames, as 0.1 had all three. `Tabs.bar()` may not be given children ("a stranger
among them puts every index one out"), `strip()` is to style and not restructure, and a header is the skin's. So the
+, the pill and the rename field float in a box round the tabs, pushed to its right edge by a float at x = 10,000 rem
that the parent clamps. The + no longer follows the last header, and the rename field is at the bar's end rather
than over the header. Placing a node over a header by its rect needs the dpi, which only the widget library's
`Seams` gives.

**For the framework:** a trailing slot in the tab bar (`Tabs.trailing(Node...)`), laid out after the headers and
outside their index; and an in-place header edit (`Tabs.rename(index)` publishing what was typed), which every
tabbed application with names wants.

## FN-11 · A double-click on a header is not a message a view can declare 🔬

Renaming a tab by double-clicking its header needs to hear that double-click. `Tabs` publishes `SELECTED` and
`CHOSEN`, not a header's double-click; the tree's clicks are a per-tree topic (`gui.clicks()`, with `clicks()` 2 for
a double), which a `@Subscribe` cannot name since it is not a payload's static `TOPIC`. So the view opens
`gui.mailbox(gui.clicks(), ...)` in its constructor and matches the click's node to a header: drained in the frame
like its other mailboxes, but a mailbox the processor does not see.

**For the framework:** `Tabs.ACTIVATED` (a header double-clicked or Entered), as `ListView` has for a row.

## FN-12 · A menu line cannot show its chord 💡

The Terminal menu's lines send what the key map's chords send, and a menu bar shows the chord beside each line so a
person learns it there. `Menu.Builder.item` takes a label, an icon and a message; nothing for a shortcut. 0.1 had no
menus, so this was not missed then. **For the framework:** an item that names the key-map action it stands for, its
chord read from the key map as the person has it, so a rebinding shows in the menu.

## FN-13 · The framework's key-bindings window cannot be opened by a `WindowCommand` 🔬

M3 meant to put *Key bindings…* in the Terminal menu beside *Settings…*, since an application's menu is where a
person looks for its commands. A menu line sends a message, and a window is opened by `WindowCommand.show(key)`.
But `Windows.command` finds only the windows declared through `Windows.named`, and `KeymapWindow` registers itself
through `GuiApp.window`, so `show("keymap")` is logged as "no window has that key" and ignored. The line was left
out; the app menu's *Key bindings…*, which the framework adds itself, works.

**For the framework:** declare the key-bindings window through `Windows` like any other, so a `WindowCommand` (from a
menu, a chord, a script) can open it by its key.
