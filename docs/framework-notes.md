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

## FN-14 · A key-map action can only be declared application-wide, then moved 🔬

The Open dialog (M4.2) wanted Esc on its list, scoped to it, as the rename field (M2) wanted Esc on its field. An
action is born application-wide (`Keymap.action`) and moved with `within`, and the key map refuses two actions that
default to one chord in one place, checked at the declaration. The terminal's Esc is application-wide until the first
tab comes to the front, so whichever of two views declares its Esc second fails, and the order views are built in is
the wiring's, not the application's. Both scoped Escs are declared first in the main view's constructor, and the
dialog's is sent to the dialog's view as a message (`OpenPage.Key`).

**For the framework:** `keys.action(id, label, chords).in(gui, node, scope)`, declaring an action straight into its
place so the refusal is checked there; or the shared-default check done when the chords are claimed, not when the
action is named.

## FN-15 · A headless tree runs the real jobs against the real home 🔬

`VexelApplication.tree` builds the application without a window, and `ViewTest` drives it by message, which is what
makes the view testable at all. But the tree runs the real components and the real jobs, with the framework's
`Settings` on the person's own home. From M3 on, a test's `ChangePrefs` reached the frame, which saved it: the real
`~/.mainframe/settings.properties` (shared with a 0.1 MainFrame) was overwritten with a test's text size and motion,
and the keys a 0.1 MainFrame had for its shell, folder and launch workspace were removed. In M4.2 a test's
`OpenWorkspace("night", false)` reached the real `LaunchWindow`, which started a real MainFrame on the real home.
Found when the build hung on that child's inherited output.

Done: the parent pom's surefire gives every test JVM a home of its own (`user.home` = `target/test-home`) and
`mainframe.launch=off`, which `SelfLaunch` honours; `ViewTest` refuses to build its tree without both. A switch in
production code for a test is the cost.

**For the framework:** a tree for tests that runs on a home of its own by default, and a way to stand a job in for
a test (`VexelApplication.tree(wiring, args, Jobs.answering(...))`), so a view test hears what the view sent without
the frame and the jobs acting on it.

## FN-16 · A Board card has no place for a field, and no key of its own 🔬

The Workspaces window (M4.3) shows a workspace's folders as cards on a `Board`, and the design (docs/workspaces-page.md)
renames a card's tab in place: its name becomes a field, Enter keeps it, Escape gives it back; and Delete removes the
selected card. A card is a node the application's face builds once, and the board owns where it is, so a field in it
would be one more widget per card, named per card, made and dropped as cards come and go; and a key for a card cannot
be declared, since a keymap action is born application-wide (FN-14) and a `FOCUSED` claim needs the node that holds
focus, which is whichever card the board focused.

Done: one rename field under the cards ("Rename front to [ ]"), as the tab bar's is beside its headers (FN-10), opened
from the card's menu; Remove only from the menu, not by Delete. What the framework could do: a card face that can
hold an in-place editor (a `Face` with an editing state, as a list row's would want too), and a deck-level action that
reaches the focused card's item, as `ListView.action` does for a row.

## FN-17 · A list says back a selection the application made as if the person had made it 🔬

`ListView.selection().at(row)` selects a row, and the list then publishes `SELECTED`, a frame or more later, exactly
as for a click. The Workspaces view selects the row the component says is shown, and tells the component when the
person picks another. With the echo arriving late, it read as the person picking the old row: the component had moved
on to a workspace just made, and the echo selected the one before it again. Seen live in the first run of M4.3.

Done: the view keeps the selections it made and drops each when the list says it back (`WorkspacesView.echoes`). What
the framework could do: say who selected (`Selected.byPerson`), or not publish for a selection set in code, as
`TextField.text` makes no undo entry.

## FN-18 · A field put away keeps its typing in the window's history, and undo stops there 🔬

A `TextField` records its typing in the history bound above it: in a framework application, the window's. The
Workspaces window records its steps there too (Vexplore's `Journaled`), and a card's rename field is shown, typed in
and put away. The next Ctrl+Z undid the rename; the one after reached the typing, whose field was hidden, and was
refused (`Gui.undo` refuses an entry whose target has left the tree). Every step behind it was out of reach. Seen live:
two Ctrl+Z, one undo.

Done: both of the window's fields keep their own history (`TextField.history(new History())`), so inside a field Ctrl+Z
undoes its typing and outside the window's steps. What the framework could do: drop, or step past, an entry whose
target is hidden for good; or say in `TextField`'s javadoc that a field shown for a moment wants its own history, as it
says a document does.

## FN-19 · ottermate cannot answer a folder picker 🔬

`PickFile` opens the platform's dialog, and a live check driving the app by `ottermate` has no verb for it, so *Add
folder…* and *Change folder…* (and Settings' *Choose…*, and Ctrl+O's *Open folder…*) are checked only in `ViewTest`,
by publishing `Picked`. The live check of M4.3 used folders a workspace already had. What the framework could do: let
an automation run answer `PickFile` itself (`ottermate pick <path>`), as `Dialogs` can be replaced in a test.

## FN-20 · A text field's larger text loses its descenders 🔬

The Workspaces window's name is a heading that is a field: a `TextField` with `textSize(1.5rem)`. Its "y" and "g"
were cut at the baseline ("day" read "dav"), with the field's default height or one of 2.75rem. At 1.25rem they are
whole. Done: 1.25rem. What the framework could do: size the field's line from its text size, or clip at the node
rather than at the line.

## FN-21 · A terminal cannot say its program is waiting for input 🔬

`--insert-path` types a file's name on the first tab's input line once its prompt is drawn, and only then (M4.4).
`TerminalScreen` says what the program wrote, its title and its size, but not that the program is now waiting for
input, so "the prompt is drawn" is 0.1's guess: it has written, then been quiet for 300 ms. On 0.2 that guess needs a
clock, which a step may not read, so the view keeps it with the framework's `Timer` (a beat after each output, counted
only if none came since). It worked live; a slow prompt that pauses mid-draw, or a shell writing a banner in two
bursts more than 300 ms apart, would get the text early. What vex-suite-common could do: shell integration's marks
(OSC 133's "prompt start"/"command start", which PowerShell, bash and zsh can be set to send) parsed by `Terminal`
and said as `TerminalScreen.PROMPTED`, with the quiet guess only where no mark ever comes.

## FN-22 · The Windows pseudoconsole carries no native-image metadata 🔬

A native MainFrame stopped as its first tab opened: `MissingForeignRegistrationError` for `CreatePipe`'s downcall,
raised in `Shell`'s `open`, which halted the application (a failure `open` did not declare, FN-3). The JVM run needs no
registration, so nothing before M5 could see it. `vex-suite-common-pty-linux` ships its own `reachability-metadata.json`
with its downcalls; `vex-suite-common-pty-windows` ships none, for `ConPty`'s fifteen kernel32 calls and
`GetShortPathNameW`. The error's suggested entry was blank, and the tracing agent records every downcall in the process
with no name, the window system's included, so the signatures were read from `ConPty`'s `FunctionDescriptor`s.

Done: the eleven distinct signatures are in MainFrame's own metadata, with a note saying whose they are. What the
stack could do: `vex-suite-common-pty-windows` carrying them, as the Linux module does, so every application with a
terminal gets them; and a test in that module that builds a native image of a tiny program opening a pseudoconsole,
since only a native run can miss them.

## FN-23 · A script cannot wait for a tab, or find one 🔬

The kept check of the main path (M5, `checks/main-path.txt`) opens a tab, renames it, and opens a workspace's two tabs.
`await <landmark> <text>` reads a landmark's name, and the tab bar's (`tabs`) is blank: its headers are not part of
it. `find <text>` did not match a header named `check-home` that `tree` showed, and it answers `ok (nothing
matches …)` when nothing does, so it cannot stand in as an assertion either. Done: those steps are photographed, not
awaited; the check asserts what the Workspaces and Settings windows name, and the runner reads the workspace files
afterwards. What the framework could do: the tab bar named by its headers (as a box is named by its texts), or each
header a landmark of its own; and `find` answering `err` when nothing matches, or an `expect` that does.

## FN-24 · A script cannot wait for a window to open 🔬

Ctrl+O's dialog opens when a job has listed the workspaces, so `window open` straight after the key answers `no
window 'open'`, and two `settle`s do not cover the job. The guide says a target may name its window (`await
open/open.list`) and act there from any window; the running application refused it: *'settings/settings.dir' is in
window 'settings', and this driver is window 'main'*. That was with the stack as installed on 2026-11-07, whose
startup warns that its pieces were built against different commits, so the guide and the server may simply disagree
by version. Done: `stream gpu for 1500` before each `window` that a job opens, as a bounded wait, which is a
workaround and reads as one. What the framework could do: `await window <name>`, bounded like `await`; and the
cross-window target the guide describes.

## FN-25 · Nothing says how to try the installer locally 🔬

Building and running MainFrame's installer on this computer, without publishing, took reading three repositories:
`installer.json` names no tool; the `installer-core` CLI jar is what reads it (`vexelray-installer`'s README); a local
install needs `--base-url` and `--asset-base-url` at `127.0.0.1`, the assets copied to `releases/<tag>/` and
`serve.ps1` serving the folder (`vexelray-dist/deploy.ps1 -Local`, which builds the whole suite from the GitHub
checkouts, not one app from here); and a snapshot already installed under the same version needs `-Force`. A comment
in `installer.json` would have pointed the way, but the config is read with `FAIL_ON_UNKNOWN_PROPERTIES`, so a
`"comment"` key fails generation, and the template makes no `installer.json` at all (`getting-started.md` says to carry
it over). Done: those steps by hand, into the scratchpad, on port 8417. What the framework could do: `installer-core`
taking a `"comment"` key and the template writing an `installer.json` with one; or one command, `--local`, that
generates, stages and serves an app's installer for a try on this computer.

## FN-26 · The build bill never finds an application's own installer.json 🔬

The title bar said `mainframe-gui`, in every window and the framework's dialogs. The framework names a bar
`About.name`: the bill's name, which `vexelray-build`'s `identify` takes from `installer.json`, else the pom's
`<name>`. It looks for `installer.json` beside the module's pom (`vexelray.installer.config`), and a generated
application keeps it at the repository's root, beside the parent pom, so the bill never reads it: the name falls to
the gui module's `<name>`, and the app id stays `<groupId>.<artifactId>` (`dev.mainframe.mainframe-gui`), not the
`appId` the installer's Linux desktop entry declares (`dev.mainframe.mainframe`), so on Linux the dock would not match
the windows to their icon, and the check that says so never runs. Vexplore's bar says `vexplore-gui` for the same
reason; Rasterware's says its name because its pom says `<name>Rasterware</name>`. Done: the gui module's `<name>` is
`MainFrame`, as Rasterware's is, which fixes the bar and leaves the app id as it was. What the framework could do:
look for `installer.json` up to the repository's root, or have the template set `vexelray.installer.config` to the
root's; then a mismatched app id fails the build, as it is meant to.
