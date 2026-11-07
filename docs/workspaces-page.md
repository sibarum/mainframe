# The Workspaces page, redesigned, for M4.3

Where workspaces are made and edited, **redesigned (ruled 2026-11-05)**: 0.1's 860-line Workspaces tab is ideas only,
as the 0.1.3 Inspector was. Proposed before it is built, as [rebuild.md](rebuild.md)'s M4.3 asks. Names and words
are proposals.

## What it must still do

rebuild.md's list: make, rename and delete a workspace; add, remove, rename and reorder its folders; and undo every
change. With what a workspace already is (docs/workspaces.md): each is a CSV file, every row has an `env` key that
names its own script, renaming a workspace moves its scripts, and deleting one puts it and its scripts aside in
`.deleted`, where undo finds them.

## What 0.1's page taught

| Kept | Why |
| --- | --- |
| The workspaces down the left, the chosen one on the right | One glance says what there is and what is open for editing |
| A folder as a card on a `Board`, dragged to reorder | The board was built for this, and nothing jumps, undo included |
| Saved the moment it is changed, no OK | As Settings is (M3) |
| Ctrl+Z and Ctrl+Y for everything, deleting a workspace included | So deleting needs no "are you sure" |
| A blank tab name is the folder's | As the CSV says it |

| Dropped | Why |
| --- | --- |
| A tab in the settings window | Editing several folders' names and paths wants room the 560-pixel settings window does not have, and Ctrl+O, which is where a workspace is used, is the natural way in |
| A field on every card for its tab name | Every card was a text field waiting to be clicked by mistake; renaming is a gesture, not the card's resting state |
| New asking whether the new workspace should open at launch | A question in the middle of making something; Settings' *Open at launch* is where that is set |
| A workspace crossing as its file's text, one name per message | Lists cross now (fork 6) |

## Proposed

**Its own window, *Workspaces***, a satellite of the main one like Settings and Open, wide enough for paths:

```
┌ Workspaces ───────────────────────────────────────────────────────────────── – □ × ┐
│ ┌──────────────────────┐  ┌──────────────────────────────────────────────────────┐ │
│ │ day           launch │  │ day                                                  │ │  the name: click to rename
│ │ night                │  │ 2 folders · opens at launch                          │ │
│ │                      │  │ ┌──────────────────────────────────────────────────┐ │ │
│ │                      │  │ │ ⠿  work     C:\work\api                        ⋯ │ │ │  a card: drag by any part
│ │                      │  │ │ ⠿  front    C:\work\web-frontend               ⋯ │ │ │  ⋯ is the card's menu
│ │                      │  │ └──────────────────────────────────────────────────┘ │ │
│ │                      │  │ [ Add folder… ]                [ Edit environment… ] │ │
│ │ [ New workspace ]    │  │                                                      │ │
│ └──────────────────────┘  └──────────────────────────────────────────────────────┘ │
│  night deleted.  Ctrl+Z brings it back.                                            │  the last change, said once
└────────────────────────────────────────────────────────────────────────────────────┘
```

**Ways in:** *Workspaces…* in the Terminal menu; *Edit…* on a workspace's row menu in the Ctrl+O dialog, which opens
this window on that workspace; and a *New workspace* row at the end of Ctrl+O's list.

**The list (left).** A `ListView` of the workspaces in name order, *launch* beside the one Settings opens at launch.
Its row menu: *Open*, *Open in this window*, *Rename*, *Delete*. Delete and Backspace delete the selected workspace,
with no question: the footer says it went, and Ctrl+Z brings it back with its scripts. *New workspace* makes one
named *New workspace* (then *New workspace 2*), selects it, and puts the keyboard in its name.

**The workspace (right).**
- **Its name**, large, is a field that looks like a heading until it is clicked or Ctrl+Shift+R is pressed; Enter
  renames, Escape gives it back. A name the store refuses (taken, or not a file name) is said under it in the danger
  colour, and nothing is renamed.
- **Its folders**, one card each on a `Board`'s one reorderable `Deck`: the tab's name (the folder's own, dimmed,
  when the row has none) and the path. A card is dragged to reorder, or moved with Alt+Up and Alt+Down. Its ⋯ menu,
  and its right-click: *Rename tab* (the name becomes a field in place: Enter keeps it, Escape gives it back, blank
  is the folder's), *Change folder…* (the folder picker), *Edit environment…* (slice 4), *Remove*. Delete and
  Backspace remove the selected card.
- **Add folder…** picks a folder and adds its card at the end, given its script key as it is added.
- **Edit environment…** is the workspace's script (slice 4 builds it; here it is placed).

**Undo.** Every change is a step in this window's history: making, renaming and deleting a workspace, and adding,
removing, renaming, repointing and moving a folder. Ctrl+Z and Ctrl+Y take a step back and forward, whichever
workspace it was in, and select that workspace so the change is seen happening. Inside a field, they undo its typing.

**Nothing jumps.** Cards arrive, leave and move by the board's own motion; the right side crossfades when another
workspace is selected; the footer's line fades in and is replaced by the next change's.

## What crosses

A new component in `-core`, **`Workspaces`**, on the logic lane like the frame, holds what the window shows and the
journal its history undoes: the frame is about tabs, and this is about files. It decides every change, saves through
jobs, and keeps the steps; the window's view only shows what it says and tells it what the person did.

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `EditWorkspaces(String select)` | views → `Workspaces` | edge | the window is opening; show `select` (blank: the first) |
| `Shown(List<Workspace> all, String selected, String launch, String problem, String said)` | `Workspaces` → view | state | everything the window shows: the workspaces, the one on the right, the one Settings opens at launch, a refused name, the footer's line |
| `Select(String name)` | view → `Workspaces` | edge | the person picked a workspace |
| `NewWorkspace()`, `RenameWorkspace(String from, String to)`, `DeleteWorkspace(String name)` | view → `Workspaces` | edge | the list's gestures |
| `AddRow(String workspace, Path dir)`, `RemoveRow(String workspace, int at)`, `MoveRow(String workspace, int from, int to)`, `RenameRow(String workspace, int at, String name)`, `RepointRow(String workspace, int at, Path dir)` | view → `Workspaces` | edge | a folder's gestures, by its place; the component makes the new rows |
| `Recorded(long entry)` | `Workspaces` → view | edge | a step landed: the view records one history entry for it (Vexplore's `Journaled`) |
| `Undo(long entry)`, `Redo(long entry)` | view → `Workspaces` | edge | the history took a step back or forward |
| `WorkspaceNames(List<String> names)` | `Workspaces` → frame | state | what Settings' *Open at launch* and Ctrl+O offer, after any change |

**The jobs come back from 0.1 almost as they were**, each a request and a reply: `SaveWorkspace`, `RenameWorkspace`,
`DeleteWorkspace` (to `.deleted`, under a stash name the component chooses, so undo is a restore of that name),
`RestoreWorkspace`, and `PruneDeleted` once at launch (what has waited 30 days). `ListWorkspaces` (M4.2) lists.

**Order.** Jobs are in no order, so the component keeps one write in flight per workspace and sends the latest after
it, as the frame does for settings (M3). A change of the set (new, rename, delete) shows once it has landed, and only
then is it a step: a rename the store refuses leaves nothing to undo.

## Tests

- `WorkspacesTest` (the component on a tick): every gesture making the right rows and one step; undo and redo of
  each, in order and interleaved across two workspaces; one save in flight and the latest after; a refused rename
  said and not recorded; a delete's stash name used by its undo.
- The jobs' tests on a temporary home, 0.1's where they exist.
- `ViewTest`: each gesture sending its message; `Shown` filling the list and the board; `Recorded` making one
  history entry whose undo sends `Undo`; all with the cut watch on the window's tree.
- `ottermate` on a home of its own: a workspace made, renamed, given two folders, one moved by Alt+Down and one
  removed, every step undone back to nothing and redone, a delete brought back with its script, and the CSV and the
  scripts on disk after each.

## Ruled

2026-11-07, as recommended: its own *Workspaces* window; a new `-core` component, `Workspaces`, holding the window's
state and the journal; renaming a tab as a gesture; and no question about opening at launch when a workspace is made.

## For the author (answered above)

1. **Its own window, *Workspaces***, opened from the Terminal menu and from Ctrl+O, rather than a tab in the settings
   window?
2. **A new `-core` component, `Workspaces`**, holding the window's state and the journal its undo walks, rather than
   more of the frame?
3. **Renaming a tab as a gesture** (its menu, or a double-click, turns the name into a field in place), rather than
   0.1's field on every card; and **no question about opening at launch** when a workspace is made?

## As built

Built 2026-11-07 as ruled, and seen live on a home of its own: a workspace made from Ctrl+O's *New workspace…* row and
named by typing, another workspace's card moved by Alt+Down and its tab renamed from its ⋯ menu, that workspace
deleted from the list by Delete; then those five steps undone with Ctrl+Z and redone with Ctrl+Y, the CSV files, the
script and `.deleted` checked on disk after each. 13 tests of the component (`WorkspacesTest`), 2 of its jobs on a
temporary home (`WorkspaceJobsTest`), 6 of the window (`ViewTest`, with the cut watch on its tree).

Where it differs from the proposal:

- **What crosses.** `Shown` was taken (the tabs' pacing), so the window's state is `Showing`. The jobs' requests and
  replies are their own records (`Gather`/`Gathered`, `Write`/`Written`, `Make`/`Made`, `Move`/`Moved`,
  `Stash`/`Stashed`, `Restore`/`Restored`, `Prune`), each carrying the journal entry and a `Turn` (made now, undone,
  redone), and the jobs are named for what they do (`WriteWorkspace`, `MoveWorkspace`, `StashWorkspace`, ...) so that
  `RenameWorkspace` and `DeleteWorkspace` are only the person's gestures. `NameWanted` puts the keyboard in a name just
  made. The stash name is chosen by the job, not the component, since a step may not read the clock.
- **One disk job at a time, not one per workspace.** A rename and a save of the same file would pass each other, so
  the component keeps a queue: a save waits as the workspace's name and writes the rows there when its turn comes
  (several changes in a row are one write), a rename that lands takes a waiting save with it, and a delete drops one.
  The read when the window opens waits its turn too, and a *New workspace* asked for with it is named after it lands,
  so the name is one not taken (a live run found the first version naming it before the read).
- **Renaming a card's tab** is a field under the cards ("Rename front to [ ]"), not in the card (FN-16), and Delete
  does not remove a card; the card's menu does.
- **Each field keeps its own typing** (`TextField.history`): in the window's history, a field put away stopped every
  undo behind it (FN-18).
- **The list's echo of a selection the view made is not the person's** (FN-17).
- **The name is 1.25rem**, not larger: a field's larger text loses its descenders (FN-20).
- **Prune runs when the window first opens** in a run, not at launch: a run that never opens it never needs it.
- **Adding a folder and changing one were not seen live**: `ottermate` cannot answer the folder picker (FN-19).
  `ViewTest` publishes `Picked` for both.
- *Edit environment…* is placed, greyed, on the card's menu and below the cards; M4.4 builds it.
