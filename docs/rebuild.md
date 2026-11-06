# The rebuild

How MainFrame comes back on VexelRay 0.2, proposed before any of it is built. What MainFrame does is not reopened
here: 0.1's README (`GitHub/mainframe`, `develop`, f128b20) is the requirement, and its `docs/` (the workspaces, the
stack's environment, the terminal as a library) are kept as they were. This file says what the 0.2 version is made
of, in what order it comes back, and what it waits on.

The author's rulings are marked **(ruled)**, with the date; the first four are gathered at the end.

## The rule for the port

0.1 is not ported. The project is the template, and each feature comes back as a slice: a change from the model to
the screen, with its test and its `ottermate` check, one at a time, as Vexplore's and Rasterware's did.

Code from 0.1 is copied only where it has no GUI and no threading in it. That is most of `mainframe-core`, about
3,000 lines, and most of 0.1's tests with it:

| Comes back almost as it was | Why it can |
| --- | --- |
| `Command`, `Startup`, `InsertPath` | the command line and what it comes to on disk: pure functions over values |
| `Workspace`, `WorkspaceStore`, `EnvScripts`, `SelfLaunch` | the CSV files, the environment scripts and their regions, another MainFrame's command line |
| `AppSettings` | what is saved, over the framework's store (its keys prefixed as the template now does) |
| the jobs' bodies (`ResolveTabs`, `PrepareShell`, `CheckInsert`, `LaunchWindow`, `MakeScript`, the nine workspace jobs) | each is a request in and a reply out; only their declarations change |
| `Look`, `Type`, `Landmarks`, the marks (`mainframe.ico`, `mainframe-window.ico`, the art pair from f128b20) | values |

Rewritten: `Frame` (to the template's one immutable state changed by declared steps), `Messages` (lists may cross now,
fork 6), and all of `mainframe-gui`: `View`, `Intent`, `Terminals`, `TerminalView`, `SettingsView`, `SettingsWindow`,
`WorkspacesPage`, `OpenDialog`, `Vex`. Each existed to move work between lanes by hand, start a pseudoconsole on the
view's lane, or be woken by a reader thread, and all of that is now the framework's or the terminal library's.

## What it is made of

**`-core`, the frame.** The template's `Editor` becomes `Frame`: one immutable `State`, changed only by declared
steps. 0.1's `Frame` component is most of it:

| Field | What it holds |
| --- | --- |
| `tabs` | each tab: its id, folder, name (given or the shell's title), the shell line it started, its workspace and `env` key, and whether it was ever submitted to |
| `selected`, `spare` | the tab shown, and the plain tab a launch opened that Ctrl+O may replace |
| `workspace` | the workspace this window was opened as, if any; a + tab sources its script |
| `insert` | `--insert-path`'s name, waiting for the first tab's first prompt, until typed or given up |
| `closing` | a close in progress: which tabs it asked about, and the answer so far |

**The outside, as parts.**

| Part | Kind | Does |
| --- | --- | --- |
| `Shell` | `@Source @Destination`, keyed by tab | `open` starts a pseudoconsole on a `PrepareShell` line, folder and size; `run` reads it into `Feed`s (`Utf8Chunks`) addressed to that tab's `TerminalScreen`, and replies with the exit code; `write` takes the screen's `SENT` bytes and its `FITTED` size; `close` ends it. The connection is the framework's, held per key (components.md §12): MainFrame is that ruling's first application |
| `ResolveTabs`, `PrepareShell`, `CheckInsert`, `LaunchWindow`, `MakeScript` | `@Job` | as in 0.1 |
| the workspace jobs | `@Job` | as in 0.1; a workspace crosses as a `Workspace`, no longer as its file's text (0.1 FN-5, gone with fork 6) |
| `OpenInIde` | `@Job` | finds VexelRay IDE through the suite's install record and opens a script in it, as Vexplore's `Launch` finds its programs |
| the template's session jobs | `@Job` | the windows, and nothing else of MainFrame's |

**`-gui`, the views.** The main window is `Tabs`, one `TerminalScreen` per tab (vex-suite-common-tty, already on
0.2: it owns its `Terminal`, hears `Feed` and the key map's commands, publishes `SENT`, `FITTED` and `TITLED`), the
title bar's gear and keyboard buttons, and a notice above a terminal. The tab switch takes a `Timing`
(`tabs.slide`, `crossfade`). The settings window and the Open dialog are named windows built the way the template's
Examples window is (regions.md step 2). Views hear widget topics and tell the frame; they decide nothing.

0.1's TODO *"Starting a tab's pseudoconsole runs on the view's lane"* is answered by this shape and is not carried
over: the screen is a widget, and the pseudoconsole is the destination's connection, held by the framework.

## The order

Each milestone ends with its retrospective section in [retrospective.md](retrospective.md), and each workaround is
written in [framework-notes.md](framework-notes.md) when it is written. Commits go on `v0.2`.

**M0. The template becomes a terminal.** *Done 2026-11-05: the deletion (ec62d0d), then the shape
([shape.md](shape.md), with what was built differently). Retrospective in [retrospective.md](retrospective.md).* Two
steps, as Vexplore's M0 was, so each reviews on its own and a wrong
shape is caught before code is written against it.

1. *The deletion.* `Spike/mainframe` was generated on 2026-11-01, before the template had modules, so it is the whole
   editor. Generate it again with `--modules none` and bring across what is MainFrame's: the docs, the marks, and the
   close and `ViewTest` changes already taken (f2e9059, 49bde64) if the new template does not already carry them.
   Building and green, an empty window.
2. *The shape.* `Frame`'s `State`, the M1 and M2 messages, `Shell`'s declaration and `Recipes`, proposed as a sketch
   of the records and steps, and built once agreed. `Look` on the design system's tokens (the template's TODO asks
   for it), with 0.1's `Look` as the starting point.

**M1. One tab, one shell.** *Mostly built with M0's shape and seen working 2026-11-05: a live PowerShell, typing,
Esc's interrupt, the font size chords re-wrapping the shell, `exit` closing the window. Left: right-click copy and
paste, the tab's title following the shell's, a command line's unknown `--option` checked live, and the measurement
below.* PowerShell running in a window: keys through the key map, copy and paste (the chords,
the right-click), Esc's interrupt, scrolling back, the title following the shell's, the window going when the shell
exits. A command line replaces the shell (`mainframe pwsh -NoProfile`), and an unknown `--option` stops with an
error. *Measure* what vex-suite-common's TODO leaves open: the screen draws once per `Feed`, not once per frame, so
`type` a large file and see what that costs before deciding anything.

**M2. Tabs.** New, close, close without asking, next and previous; rename by Ctrl+Shift+R, a double-click or the
menu, with Escape cancelling and a blank name handing the header back; `--dir` and `--dirs`; a tab whose shell
exits closes, and the last one closes the window; the font size chords; the passthrough switch's behaviour (every key to the shell), in a look redesigned first
**(ruled 2026-11-05)**. *Closing asks* through the template's close
chain (`CloseRequested` and `CloseAnswer`, the question held for the person), which is what 0.1's TODO wanted and
could not easily have: ask when any open tab was ever submitted to, by every way out (×, Alt+F4, the taskbar, the
last tab, Ctrl+W), with a test for each.

**M3. Settings.** The settings window's General tab (font size, tab motion, the shell new tabs start, the folder
they start in, the workspace to open at launch), changes applied at once and kept in `~/.mainframe/`; the keyboard
button's key map editor.

**M4. Workspaces.** In four slices:

1. `--workspace`, the core and its tests, the environment scripts sourced as 0.1 did (PowerShell's `-NoExit -Command`,
   bash's and zsh's startup files), and the notice when they did not run.
2. Ctrl+O: the tiles after *Open folder…*, Enter for a new window, Ctrl+Enter for this one, the spare tab replaced,
   the folder picker straight away with no workspaces saved.
3. Editing workspaces, **redesigned (ruled 2026-11-05)**: not 0.1's Workspaces tab ported. The design is proposed
   before the slice, as the Inspector's was, with 0.1's page as ideas only. What it must still do: make, rename and
   delete a workspace, add, remove, rename and reorder its folders, and undo every change. The parts to hand: `Board`'s
   drag (`gui.core.drop`), and a `History` whose changes send messages, as Vexplore's M3 does (which also answers 0.1
   FN-6).
4. *Edit environment…* through `OpenInIde`, from the tab, the card and the workspace; and `--insert-path`, typed
   once the first prompt is drawn and only if nothing was typed first.

**M5. Shipping.** Both native profiles (release and debug), with the pseudoconsole's downcalls in the
reachability metadata; a scripted `ottermate` run through the main path kept in the repository (0.1's TODO); the
installer entry; a run on Linux through `exec:exec`. Then the README rewritten for 0.2, and the milestone's
retrospective.

**After the port.** 0.1's TODO kept these, none of them part of bringing it back: *Save tabs as workspace*,
*the stack's environment* (stack-env.md), `--insert-command` for the Inspector, *a shell per tab*, mouse reporting,
and the font following the display's scale. A shell per tab and `--insert-command`
are small once M4 is in.

## What it waits on

Things in the framework this plan expects to meet. Each is checked when its milestone reaches it, and what is found
goes in [framework-notes.md](framework-notes.md).

| What | Where it bites | Where it stands |
| --- | --- | --- |
| `@Destination` with no application using it yet | M1 | built (framework 2c06f6e, `DestinationsTest`); MainFrame is its first use |
| A terminal drawn once per feed | M1 | unmeasured (vex-suite-common TODO) |
| Two parts that ask share one `Ask` id space | M3, M4: the frame asks about closing, the settings window about deleting and the start workspace | open in the framework's regions work, and it blocks v1 |
| A question from a named window is asked over the main one (0.1 FN-3) | M4 | 📋 re-verify on 0.2 |
| A named window cannot be modal (0.1 FN-4) | M4, the Open dialog | 📋 re-verify on 0.2 |
| An application's own command-line grammar (0.1 FN-1) | M1 | 📋 re-verify: 0.1 passed the parse through a static |
| The automation socket answers before a window is listed (0.1 FN-2) | M1's `ottermate` checks | 📋 re-verify |
| Linux native builds | M5 | not done anywhere in the stack (framework TODO) |

## Ruled

All four ruled by the author 2026-11-05.

1. **The Workspaces page is redesigned**, not ported. 0.1's 860-line page is ideas only, as the 0.1.3 Inspector
   was. Its design is proposed before M4.3.
2. **The passthrough switch is redesigned.** What it does stays; its look (0.1's " Keys: … " text in the tab bar) is
   drawn anew and proposed before M2 brings it back.
3. **0.1's one-off key migration is dropped.** The first run since the **Remap keybindings** switch was removed
   unbound six chords for whoever had it off; anyone upgrading has run 0.1 since.
4. **MainFrame carries on now**, beside Vexplore, ahead of its place last in phase 8's order.
