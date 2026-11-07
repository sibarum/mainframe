# Workspaces, for M4

What [rebuild.md](rebuild.md)'s M4 builds in its slices 1, 2 and 4, sketched before code is written against it, as
`CLAUDE.md` asks of anything that touches `Messages`, `Recipes` or a lane. Slice 3, the Workspaces page, is
**redesigned (ruled 2026-11-05)** and gets a design of its own before it is built; nothing here fixes its look.
Names are proposals.

What a workspace does is 0.1's (its README, *Workspaces* and *A workspace's environment*, and
`docs/workspaces-and-shortcuts.md`), and is not reopened here: a saved list of folders, a CSV file per workspace in
`~/.mainframe/workspaces/`, one tab per folder, environment scripts sourced by each tab's shell.

## What comes back from 0.1, almost as it was

Pure functions over values and the disk, with no GUI and no threading, so they are copied (rebuild.md's rule):

| Class | Lines | What it is |
| --- | --- | --- |
| `Workspace` | 254 | the record, its rows (`Entry`: name, path, `env` key, extra columns kept), CSV read and write |
| `WorkspaceStore` | 339 | the folder: names, load, `keyed` (gives every row its `env` key), save, rename, delete to `.deleted` and restore |
| `EnvScripts` | 547 | which scripts a tab sources, their generated region, the command line that sources them, bash's and zsh's startup files |
| `SelfLaunch` | 49 | another MainFrame's command line: the executable, or the same Java and class path |
| `InsertPath` | 139 | what `--insert-path` may type, quoted for the shell (slice 4) |

Their tests come with them. `Folders` (M2) gains the `--workspace` source back from 0.1's `Startup`.

**A workspace now crosses as itself.** 0.1 sent a workspace as its file's text and listed them one message per name,
because a list could not cross lanes (0.1 FN-5); on 0.2 one can (fork 6), so `Workspace` is a message's field and a
listing is one message.

## Slice 1: opening one at launch, and its environment

`mainframe --workspace=day`, or the workspace chosen in Settings when a launch names no folder and no workspace, opens
one tab per row, each named as the row names it (blank: the folder's), in a window that remembers which workspace it
is. Each tab's shell sources the workspace's script and then the row's, as 0.1's did. A `+` tab in a workspace window
sources the workspace's script only and starts in the chosen folder, as any `+` tab does.

**A tab's command line is prepared before its shell starts.** Finding the scripts rewrites their regions, and bash or
zsh needs startup files written, so it is disk work: a job, `PrepareShell`, as in 0.1. The frame asks for it when it
adds a tab, and starts the shell when both the prepared line and the screen's first size are in, whichever comes
last. The job also checks the folder: a chosen folder that has gone falls back to home (0.1 did this; M3 does not
yet).

**When the scripts cannot run** (a command line given at launch, a shell MainFrame cannot add to, PowerShell's
execution policy is the shell's own to report), the tab starts without them and **says so in a band above its
terminal**: the notice rebuild.md's M0 named. One line in the danger colour's quiet tone, with a × that puts it away;
it is the tab's page, so it goes with the tab.

**The workspace to open at launch** (moved from M3, ruled 2026-11-06) joins Settings: *Open at launch*, a `Select` of
*None* and the workspaces there are, saved under 0.1's key, `start.workspace`.

```
ReadStartup ── Startup(tabs, line, prefs, shells, home, workspace, names) ──► Frame
Frame ── Prepare(id, line, dir, workspace, env) ──► PrepareShell ── Prepared(id, dir, line, refused) ──► Frame
Frame ── OpenShell (on Prepared and the first Resized, whichever is later) ──► Shell
Frame ── Notice(id, text) ──► View                    the band above the terminal; blank puts it away
```

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `TabSpec(Path dir, String name, String env)` | (a value) | | a tab the launch or a workspace asks for: its folder, its name (blank: the folder's), its row's script key (blank: none) |
| `Startup(…, List<TabSpec> tabs, String workspace, List<String> names)` | job → frame | edge | `dirs` becomes `tabs`; the window's workspace (blank: none); every workspace's name, for Settings and Ctrl+O |
| `Prepare(long id, String line, Path dir, String workspace, String env)` → `Prepared(long id, Path dir, String line, String refused)` | frame → `PrepareShell` → frame | edge | the line with the scripts added, the folder it starts in, and why the scripts did not run (blank: they did, or there were none) |
| `Notice(long id, String text)` | frame → view | state, by id | what the band above a tab's terminal says; blank for none |
| `Preferences` gains `startWorkspace` | | | the workspace opened at a launch that names nothing, blank for none |

The frame's `Tab` gains `env` (its row's key) and `line` becomes what `Prepared` said; `State` gains `workspace`, the
window's.

## Slice 2: Ctrl+O

Ctrl+O, and *Open…* in the Terminal menu, shows the workspaces as tiles after an *Open folder…* tile. Enter (or a
click) opens a workspace **in a new MainFrame window**, a process of its own (`SelfLaunch` with
`--workspace=<name>`); Ctrl+Enter, or a tile's menu, opens its tabs **in this window**. With no workspace saved, Ctrl+O
goes straight to the folder picker. *Open folder…* adds a tab for the folder picked.

**The spare tab.** A launch's one plain tab, while nothing has been typed or pasted into it, is spare: what Ctrl+O
opens takes its place, so opening a workspace from a fresh window does not leave an empty tab behind, and when it
opens in a new window, this window closes with its spare tab. The frame learns a tab is no longer spare from a
`Touched(id)`, which the view says on the first key typed into it, as it says `Submitted` on the first Enter.

**The dialog is a named window**, a satellite of the main one, as Settings is, with its own view. 0.1's FN-4 (a named
window cannot be modal) is re-verified when it is built; the dialog is fine unmodal, as 0.1's was.

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `OpenAsked()` | view → frame | edge | Ctrl+O |
| `ListWorkspaces()` → `Listed(List<Workspace> workspaces)` | frame → job → frame | edge | every workspace, keyed, in name order |
| `Tiles(List<Workspace> workspaces)` | frame → open dialog's view | edge | show the dialog with these; none means the view asks the folder picker instead |
| `OpenWorkspace(String name, boolean here)` | dialog → frame | edge | open it here, or in a new window |
| `OpenFolder(Path dir)` | view → frame | edge | a tab for this folder |
| `Launch(String workspace)` → `Launched(String problem)` | frame → `LaunchWindow` → frame | edge | another MainFrame on that workspace; a problem is told in a dialog (`Ask.info`) |
| `LoadWorkspace(String name)` → `Loaded(Optional<Workspace> workspace)` | frame → job → frame | edge | one workspace, keyed, for opening here |
| `Touched(long id)` | view → frame | state, by id | something was typed into the tab |

**A new window carries this run's home.** `SelfLaunch` passes `-Duser.home` on when it was given one, so a run
against a test home (M3's live checks) opens its workspaces in a window that reads the same folder. 0.1 had its own
`-Dmainframe.home` for this; on 0.2 the framework's `AppHome` reads `user.home` already.

## Slice 4: Edit environment…, and --insert-path

**Edit environment…**, on a workspace tab's header menu (*Edit environment…* for the tab, *Edit workspace
environment…* for the workspace), makes the script if it is not there (`MakeScript`, 0.1's) and opens it in VexelRay
IDE (`OpenInIde`, found through the suite's install record, as Vexplore finds its programs; else `text-editor` on the
`PATH`; else a dialog says where the script is). The Workspaces page's own *Edit environment…* comes with slice 3.

**`--insert-path=<file>`** types a file's name, quoted for the shell (`CheckInsert`, `InsertPath`), on the first
tab's input line once its first prompt is drawn, and only if nothing was typed first (`Touched`). How "the first
prompt is drawn" is told on 0.2 is settled when the slice is built: 0.1's rule is re-read then, and `TerminalScreen`
may need to say when its program first waits for input, which would be a finding.

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `EditEnv(long id, boolean tab)` | view → frame | edge | the tab's script, or its workspace's |
| `Script(…)` → `ScriptMade(Path script, String problem)` | frame → `MakeScript` → frame | edge | as 0.1 |
| `OpenInIde(Path file)` → `IdeOpened(String problem)` | frame → job → frame | edge | as 0.1 |
| `Insert(String spec, Path dir, String line)` → `InsertChecked(String type, String notice)` | job | edge | what to type, or the notice why not |
| `TypeInto(long id, String text)` | frame → view | edge | type this on the tab's input line, as the person would |

## What it waits on

- **0.1 FN-3, a question from a named window is asked over the main one**, and **FN-4, a named window cannot be
  modal**: re-verified in slice 2 (rebuild.md's table).
- **Two parts that ask share one `Ask` id space** (the framework's regions work, blocks v1): the frame asks about
  closing (M2) and will tell launch and IDE problems (here), and the Workspaces page asks about deleting (slice 3).
  This slice's asks use the frame's run ids as M2's do; the collision is noted where it can happen.

## Tests

- The 0.1 classes' tests, as they were.
- `FrameTest`: a workspace's tabs named and keyed; a shell started only once both its line and its size are in, in
  either order; a refusal as a `Notice`; a `+` tab in a workspace window preparing with the workspace's script only;
  the launch workspace from Settings, and from `--workspace`, which wins; the spare tab replaced by a workspace opened
  here and by a folder, and the window closed when the workspace opens in a new one; a touched tab no longer spare.
- `PrepareShellTest` and `LaunchWindowTest` against a temporary home.
- `ViewTest`: the notice band shown and put away; Ctrl+O's dialog with tiles, and the picker when there are none;
  Enter and Ctrl+Enter sending `OpenWorkspace` either way; `Touched` on the first key; all with the cut watch on.
- `ottermate`, on a home of the run's own: a workspace opened at launch with its scripts' variables visible in the
  shell (`$env:MAINFRAME_WORKSPACE`), the notice for a launch command line, Ctrl+O opening one here and one in a new
  window (driven by its own port), and the spare tab gone.

## For the author

1. **A tab's command line prepared by a job before its shell starts** (0.1's `PrepareShell`), so the shell starts
   once both the prepared line and its first size are in? The other way is `Shell.open` preparing it itself, one
   message fewer but disk work and a notice to send from inside the shell's connection. I recommend the job.
2. **The notice as a band above the terminal**, with a × that puts it away, in the tab's page?
3. **`Touched(id)` for "nothing typed yet"**, beside M2's `Submitted`, rather than one message that says both?

## Ruled

All three accepted as recommended by the author 2026-11-07 ("Accept, build slice 1"): the command line prepared by a
job before the shell starts, the notice as a band above the terminal with a ×, and `Touched(id)` beside `Submitted`.

## Slice 1, as built

Built 2026-11-07 and seen live on a home of its own: `--workspace=day` opening its two rows as tabs named *work* and
*front*, the workspace's script run in each (`$env:MAINFRAME_WORKSPACE` and the script's own variable set), every row
given its `env` key in the CSV; a launch command line (`cmd.exe`) starting the tabs without the scripts and saying so
in the band, which its × put away; *Open at launch* set to `day` in Settings and a launch with no arguments opening it;
and a `+` tab in that window sourcing the workspace's script and starting at home. Where it departs from the sketch:

- **`InsertPath` came in slice 1**, not 4: `EnvScripts` uses its check for control characters.
- **A row with no name is named for its folder** (the row's `title()`), as 0.1 named it, so the shell's title does
  not replace it. The first live run showed *powershell* where 0.1 showed the folder.
- **The store is found from `Settings`** (`WorkspaceStore.of`), since a job is handed the framework's `Settings`, whose
  file sits in the application's home, and nothing else needs providing.
- **The frame's `Tab` is `@Withers`** now that it has eleven fields; a shell starts from one place, `start`, once
  the tab is both prepared and sized.
