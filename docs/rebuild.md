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

**M1. One tab, one shell.** *Built with M0's shape and seen working 2026-11-05: a live PowerShell, typing, Esc's
interrupt, the font size chords re-wrapping the shell, `exit` closing the window; then right-click copy and paste
(the screen's own, seen through the real clipboard), the tab's header following the shell's title (`Retitled` and
`Header`, as shape.md sketched them), and an unknown `--option` stopping with exit 2 before a window opens (with the
framework's message, FN-1). The measurement found that a fast program halted MainFrame (FN-9); the fix below was
accepted and built the same day, and the measurement taken with it. Retrospective in [retrospective.md](retrospective.md).* PowerShell running in a window: keys through the key map, copy and paste (the chords,
the right-click), Esc's interrupt, scrolling back, the title following the shell's, the window going when the shell
exits. A command line replaces the shell (`mainframe pwsh -NoProfile`), and an unknown `--option` stops with an
error. *Measure* what vex-suite-common's TODO leaves open: the screen draws once per `Feed`, not once per frame, so
`type` a large file and see what that costs before deciding anything.

*Accepted by the author and built 2026-11-05 ("Build it"): a tab's output, paced by what the view has shown (FN-9).* `type` of an 8 MB file
halted MainFrame within about 250 ms. The shell's reads filled the view's 64-slot `Output` mailbox, and an edge that
overflows ends the application (T4.2). The framework leaves back-pressure to the source (components.md §11), so:

- **One new record, `Shown(long id)`**, view → shell, an edge keyed by the tab's id: the view sends one for each
  `Output` it has fed to that tab's screen.
- **`Shell` keeps two `Output`s in flight per tab, no more.** Its session holds two permits. `run` reads as now into
  a buffer, and sends everything buffered as one `Output` when a permit is free. With none free it goes on reading
  until the buffer holds 1 MB, then waits for one, so the program waits on the pseudo-terminal as it would on a slow
  console. `write(Session, Shown)` gives a permit back; `close` frees the wait.
- **What it does to the draw:** an `Output` is then as large as what arrived while the view drew the last one, so
  under load the screen draws about once per frame rather than once per read. That is the measurement this
  milestone asked for, taken once MainFrame survives it, and it may close vex-suite-common's TODO with no widget
  change.
- **What it does not do:** nothing waits on the GUI thread or the logic lane. The wait is on `Shell`'s own source
  thread, which is the outside's.

A test in `ShellTest`: a program writing far more than two reads' worth, with no `Shown` sent, sends exactly two
`Output`s; one `Shown` lets the rest through as one.

*Built differently in one point:* a `Shown` that finds text waiting sends it at once, from the destination's thread,
rather than freeing a slot for the reader's next read. Otherwise a shell's last words (its prompt) would wait in the
buffer until the program wrote again. The end of a program waits up to a second for what it wrote last to go.

*Measured 2026-11-05, with it built:* `type` of 8.4 MB (100,000 lines) took 4.0 s in MainFrame, against 2.9 s through
`Shell` with no screen at all (ConPTY's own pace: it hands over a line per read, 100,000 reads) and 0.14 s to `NUL`.
The view drew about 900 `Output`s, two a frame, where unpaced it would have been asked for 100,000. Its frames took
1.5 ms at the median, 2.7 ms at p90 and 9.1 ms at worst, and the GUI thread was busy 0.7 s of the 4.3 s. So drawing
once per feed costs nothing worth changing once the feeds are paced, and vex-suite-common's TODO needs no widget
change. The 1.1 s over ConPTY is not the GUI thread's; the run was under automation's trace-level recording, which is
the first suspect, and it is not chased further here.

**M2. Tabs.** *Built 2026-11-05 and seen live (below for what departs); retrospective in [retrospective.md](retrospective.md).* New, close, close without asking, next and previous; rename by Ctrl+Shift+R, a double-click or the
menu, with Escape cancelling and a blank name handing the header back; `--dir` and `--dirs`; a tab whose shell
exits closes, and the last one closes the window; the font size chords; the passthrough switch's behaviour (every key to the shell), in a look redesigned first
**(ruled 2026-11-05)**, proposed in [passthrough.md](passthrough.md). *Closing asks* through the template's close
chain (`CloseRequested` and `CloseAnswer`, the question held for the person), which is what 0.1's TODO wanted and
could not easily have: ask when any open tab was ever submitted to, by every way out (×, Alt+F4, the taskbar, the
last tab, Ctrl+W), with a test for each.

**M3. Settings.** *Built 2026-11-07 from [settings.md](settings.md) (ruled 2026-11-06) and seen live; retrospective in [retrospective.md](retrospective.md). The workspace to open at launch moved to M4.1.* The settings window's General tab (font size, tab motion, the shell new tabs start, the folder
they start in, the workspace to open at launch), changes applied at once and kept in `~/.mainframe/`; the keyboard
button's key map editor.

**M4. Workspaces.** *Slices 1, 2 and 4 sketched in [workspaces.md](workspaces.md) (ruled 2026-11-07); slices 1 and 2 built and seen 2026-11-07; slice 3's page is designed separately.* In four slices:

1. The workspace to open at launch, in Settings (moved from M3, ruled 2026-11-06). `--workspace`, the core and its tests, the environment scripts sourced as 0.1 did (PowerShell's `-NoExit -Command`,
   bash's and zsh's startup files), and the notice when they did not run.
2. Ctrl+O: the tiles after *Open folder…*, Enter for a new window, Ctrl+Enter for this one, the spare tab replaced,
   the folder picker straight away with no workspaces saved.
3. Editing workspaces, **redesigned (ruled 2026-11-05)**: *proposed in [workspaces-page.md](workspaces-page.md), for the author.* not 0.1's Workspaces tab ported. The design is proposed
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
