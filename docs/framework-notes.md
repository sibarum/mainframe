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
with that framing, so the retrospective has a candidate rather than a complaint.

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

Each finding below also says what it means for **freezing v1** (`vexelray-framework/docs/v1.md`): whether the gap
is in a surface v1 freezes, and whether closing it later would be additive or breaking.

## The rebuild, in brief

Rebuilt in October 2026 from `vexel-desktop`, against vexelray-framework `cf013a1`, vexelray-gui `8fa0cff` and
atchung `7e76359`. The hand-wired MainFrame (`b465748`) was deleted first and its features brought back one at a
time, copying from history where the code still fit: the VT screen (`term/Terminal`), the pseudoconsole
(`pty/ConPty`, `pty/ShortName`), `CellArt`, `InsertPath`, and the workspace file format, each with its tests, came over
unchanged. What touched the old frame loop, its windows or its dialogs was rewritten.

## Findings

## FN-1 · An application with a grammar of its own has nowhere to put its parse 🔬

MainFrame's command line ends in a command line for somebody else (`mainframe pwsh -NoProfile`), and `--dir` repeats.
The framework's `Launch` has neither: each `--key` once, a dash that is not one of its keys refused, and a bare
number taken as a frame count, so `mainframe timeout 30` would run thirty frames. `Launch` itself says an
application with a grammar of its own "intercepts its own flag before handing the rest here".

So `MainFrame.main` does: `Command.parse` takes MainFrame's options and the trailing command line out, and hands
the framework the rest. But the result then has no way into the wiring. A recipe is handed parts the framework
built, `Recipes` is constructed by the generated code with no arguments, and a record behind `@Provides` is refused
(rightly, for a value). So the parse goes through a static, `Command.launched()`, set once by `main` and read by
the start recipe.

Two smaller costs follow. The usage line printed for a misspelled option lists only the framework's keys
(`known: automation, log, profile`), never `--dir` or `--workspace`, because only `@Setting` keys reach
`AppInfo.settingKeys`. And `main` reads the OS's own arguments (`NativePlatform.arguments`) itself, because the
framework only does that to what it is handed, which is after MainFrame has read its folders.

**For the framework:** a way to hand `VexelApplication.run` a value the wiring can take (`run(wiring, args,
extras)`), or `@VexelApp(arguments = MyGrammar.class)` naming a parser the generated `main` calls; and a way to add
names to the usage. **For v1:** additive.

## FN-2 · The automation socket answers before the main window is listed 🔬

`ottermate --launch ... -Dautomation=0` with `settle` as its first command gets `err no window is open yet`: the
port is printed in `ATTACH`, and the window list is empty until the loop has run. MainFrame's start recipe opens a
pseudoconsole, which widens the gap, but nothing about it is MainFrame's. A script has no verb to wait for a window,
so driving it meant polling `ottermate windows` from outside until it said `ok`.

**For the framework:** print the port once the main window is listed, or have `Windows.command` wait (bounded) for
the first window rather than answering `err`. **For v1:** behaviour only.


## FN-3 · A question asked from a named window is asked over the main one 🔬

The Workspaces editor lives in the settings window, and asks before deleting a workspace. `Dialogs` is one value,
parented to the main window, so the confirmation opens over the main window and dims it, while the user is looking
at the settings window. The folder picker behind *Add folder…* is parented the same way. The hand-wired MainFrame
did no better (`Modals` was installed on the application, which is the main window), so nothing regressed, but
the old code had at least found the settings window's handle for the picker, and the framework gives a named
window no way to do even that.

**For the framework:** `Window.dialogs()`, the same `Dialogs` parented to that window, or a `Dialogs.over(Window)`.
**For v1:** additive.
