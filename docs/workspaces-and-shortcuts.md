# Workspaces, shortcuts and the Open dialog

Status: 2026-10-11. All built, but for Save tabs as workspace (§3).

Three pieces of work, in the order they will land: the small things (the settings icon, three shortcuts), then the
Workspaces editor, then an Open dialog that makes a workspace something you open from inside MainFrame rather than
from a command line. The icons all of it uses are in [utility-icons.md](utility-icons.md).

## 1. Small things

### The settings icon

**Done.** The title bar's settings button drew a ring with eight thin teeth standing apart from it, and it read as a
sun. It wears the suite's `settings` utility icon now ([utility-icons.md](utility-icons.md)).

### Three more remapped shortcuts

**Done.** With **Remap keybindings** on, as well as Ctrl+C, Ctrl+V and Esc:

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
