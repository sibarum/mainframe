# Workspaces, shortcuts and the Open dialog

Status: 2026-10-19. All built, but for Save tabs as workspace (§3); §4, environments, added.

Three pieces of work, in the order they will land: the small things (the settings icon, three shortcuts), then the
Workspaces editor, then an Open dialog that makes a workspace something you open from inside MainFrame rather than
from a command line. The icons all of it uses are in [utility-icons.md](utility-icons.md).

## 1. Small things

### The settings icon

**Done.** The title bar's settings button drew a ring with eight thin teeth standing apart from it, and it read as a
sun. It wears the suite's `settings` utility icon now ([utility-icons.md](utility-icons.md)).

### Three more remapped shortcuts

**Done**, and since 2026-10-19 **superseded by the key map**: every command is a rebindable action, and the Remap
keybindings switch is gone. A chord unbound in the key editor goes to the shell (README, Keys). What follows is the
design as it was.

With **Remap keybindings** on, as well as Ctrl+C, Ctrl+V and Esc:

| Keys | Does |
| --- | --- |
| Ctrl+T | a new tab, as Ctrl+Shift+T |
| Ctrl+W | closes the tab, after asking |
| Ctrl+O | the Open dialog (§3), or with no workspaces saved, the folder picker |

Ctrl+Shift+T and Ctrl+Shift+W work whether remapping is on or off, and Ctrl+Shift+W stays immediate: it is the way
to close a tab without being asked.

Remapping takes these keys from the shell, as it already takes Ctrl+C. In bash and readline Ctrl+W deletes the
previous word, Ctrl+T swaps two characters, and Ctrl+O runs a history line and fetches the next one. Ctrl+Backspace
still deletes a word. The setting's note says what is given up.

## 2. The Workspaces editor

**Done** ([WorkspacesPage](../src/main/java/dev/mainframe/WorkspacesPage.java)). The page keeps its shape (the
workspaces down the left, the selected one on the right), and its folder rows become cards.

**A card** is the tab's label, a text field, over the folder's path, a static line: dim, or red with "(not found)"
when the folder is missing. A `grip` icon on its leading edge says it can be dragged. The ↑ ↓ × buttons go.

- **Selecting.** A click anywhere on a card selects it. A click in the label puts the caret there, and the card shows
  as selected while it does. Esc or Enter leaves the field and leaves the card selected; on a selected card, ↑ and ↓
  select the next one, and Enter or F2 edits its label.
- **Reordering.** Drag a card, and a drop line shows where it will land. From the keyboard, Alt+↑ and Alt+↓ move the
  selected card, which is what the arrow buttons did.
- **Removing.** Right-click, Remove; or Backspace or Delete on a selected card. Inside the label, Backspace edits the
  text.
- **Offered as the start workspace.** While General's *Workspace to open at start* is None (or names a workspace
  that is gone), New asks whether the new workspace should open at start: *Open at start* or *Not now*. Either
  answer gives the name field back the keyboard. Undo and redo bringing a workspace back do not ask.
- **A label is never empty.** Leaving the field blank fills it with the folder's own name. A hand-edited file with a
  blank name still opens as now, with the folder's name.

**Undo.** The page has a history of its own (`dev.vexelray.gui.core.edit.History`), with Ctrl+Z and Ctrl+Y:
moving, adding, removing and relabelling a folder, and creating, renaming and deleting a workspace, each one step.
Typing in a label is saved as it happens and undone by the field itself; leaving the label records the relabel as
one step of the page's. Deleting a whole workspace still asks first, and Ctrl+Z brings it back.

**How the drag is built.** Each card is a drag source, and the list is one drop target that answers for every
point: the seam on whichever side of the cards' midlines the pointer is, lit while the drag is over it. The drop is
a `Change` recorded in the page's history, which is also the window's drop history. As everywhere in vexelray-gui,
letting go of the button keeps the card in hand, and the next click puts it down; Esc or a right click takes it home.

## 3. The Open dialog

**Done** ([OpenDialog](../src/main/java/dev/mainframe/OpenDialog.java)). Ctrl+O opens a dialog over the MainFrame
window: a grid of tiles, one per saved workspace, showing its name, how many folders it has and the first few tab
names. The first workspace has the keyboard; the arrows move between tiles, Enter opens the one with it, and Esc
closes.

It is a window of its own, not a `Modal`: a `Modal` is a title, a message and buttons, and has no room for tiles.
vexelray-gui's dialog is a window that blocks the others while it is up (`GuiApp.modalWindow`), which is what
`Modals` does too. It is asked for each time with `GuiApp.requestWindow` and gone when it closes, so it registers no
window by name.

- The first tile is always **Open folder…**: the system folder picker, and the folder opens as a new tab in this
  window.
- With no workspaces saved, Ctrl+O goes straight to the folder picker.
- Choosing a workspace starts a new MainFrame process with `--workspace=<name>`, in a window of its own
  ([SelfLaunch](../src/main/java/dev/mainframe/SelfLaunch.java): the executable itself, or the same Java and class
  path). Each program is its own process, so windows share nothing. Ctrl+Enter (or the tile's menu) opens its tabs in this window
  instead.
- The last tile is **+ New**, and each workspace tile's menu has **Edit…**. Both open Settings, Workspaces, on that
  workspace. There is one editor, and it stays in Settings: a modal inside a terminal window is a cramped place to
  drag cards about, and two editors would drift apart.
- Not built: **Save tabs as workspace**, which would make a workspace of the tabs open now.

## 4. A workspace's environment

**Done** ([EnvScripts](../src/main/java/dev/mainframe/EnvScripts.java)). Every tab of a workspace can have its
environment extended in two ways: by the workspace's script, which all its tabs source, and by the tab's own, which
is sourced after it and so overrides it. Both are plain PowerShell scripts that the tab's shell dot-sources as it
starts. Editing either opens it in a plain Vex window ([Vex](../src/main/java/dev/mainframe/Vex.java)).

### Where they are

Beside the workspace's CSV file, named after it, so a glance at the folder shows what belongs to what:

| File | What |
| --- | --- |
| `<name>.env.ps1` | the workspace's script |
| `<name>.env/<key>.ps1` | a tab's script; `<key>.ps1` is the row's `env` column |

A tab's script needs a name that is the tab's whatever happens to its row: relabelling, reordering and changing its
folder all keep it. Nothing in the row lasts through all three, so the row carries a key of its own, the new `env`
column: the tab's title in safe letters and six random hex digits (`api-3f9c2a.ps1`). The CSV format already keeps
columns it does not know, so an older MainFrame editing the file loses nothing.

**Keys are given when a workspace is opened or shown, not on the first edit.** The editor's undo keeps rows as they
were when each step was made. A key written into the file later, by the first edit, would be undone by undoing any
earlier step, and the script would be stranded under a key no row names. Given before any step can be recorded,
every step holds rows that already have their keys. The script itself is made on the first edit.

### What sources them

- A workspace's tabs, whether it opens in a window of its own or into this one with Ctrl+Enter.
- A tab opened with + in a window opened as a workspace: the workspace's script only, since it is no row of it, in
  the new tab directory. The window's workspace is the first launch tab's, or the one whose tabs took the place of the
  spare plain tab. A window opened without one, and a folder opened with Ctrl+O, source nothing.

The scripts are added to the shell's command line, `-NoExit -Command ". 'ws.env.ps1'; . 'tab.ps1'"`, each path in
single quotes with any quote in it doubled (PowerShell's curly single quotes too). That needs PowerShell, and a
command line with no command or file of its own: anything else is refused, said on the tab, and logged. A missing
script is skipped. The scripts run after the profile, under the user's execution policy.

### The generated region

Each script opens with a region MainFrame owns:

```powershell
# region MainFrame (generated; edits here are replaced)
$env:MAINFRAME_WORKSPACE = 'day'
$env:MAINFRAME_TAB = 'api'
$env:MAINFRAME_TAB_FOLDER = 'C:\work\api'
# endregion
```

It is rewritten whenever the script is about to be sourced or edited, so renames reach it without a pass over every
file. Everything else is the user's, kept byte for byte: the file is spliced as bytes (a UTF-16 file, as Windows
PowerShell's `Out-File` writes, as UTF-16 text). A script without the region gets one on top. A new script is UTF-8
with a byte-order mark, which Windows PowerShell needs to read a non-ASCII name.

### Renaming, deleting and undo

- **Renaming** a workspace moves `<name>.env.ps1` and `<name>.env/` with the CSV file; the tabs of it open in this
  process follow the new name.
- **Deleting** one moves its scripts to `workspaces/.deleted/<name> <time>/` rather than deleting them, and Ctrl+Z
  puts them back. Each window is a process with its own undo history, so what is put aside is cleared at a later
  start, after 30 days, rather than when one window closes.
- **Removing a folder** from a workspace leaves its script: undoing the removal brings the row back with its key,
  which finds it again. Nothing deletes a tab's script.
