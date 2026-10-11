# Workspaces, shortcuts and the Open dialog

Status: **planned**, 2026-10-11. Only the settings icon is built.

Three pieces of work, in the order they will land: the small things (the settings icon, three shortcuts), then the
Workspaces editor, then an Open dialog that makes a workspace something you open from inside MainFrame rather than
from a command line. The icons all of it uses are in [utility-icons.md](utility-icons.md).

## 1. Small things

### The settings icon

**Done.** The title bar's settings button drew a ring with eight thin teeth standing apart from it, and it read as a
sun. It wears the suite's `settings` utility icon now ([utility-icons.md](utility-icons.md)).

### Three more remapped shortcuts

With **Remap keybindings** on, as well as Ctrl+C, Ctrl+V and Esc:

| Keys | Does |
| --- | --- |
| Ctrl+T | a new tab, as Ctrl+Shift+T |
| Ctrl+W | closes the tab, after asking |
| Ctrl+O | the Open dialog (§3) |

Ctrl+Shift+T and Ctrl+Shift+W work whether remapping is on or off, and Ctrl+Shift+W stays immediate: it is the way
to close a tab without being asked.

Remapping takes these keys from the shell, as it already takes Ctrl+C. In bash and readline Ctrl+W deletes the
previous word, Ctrl+T swaps two characters, and Ctrl+O runs a history line and fetches the next one. Ctrl+Backspace
still deletes a word. The setting's note says what is given up.

## 2. The Workspaces editor

The page keeps its shape (the workspaces down the left, the selected one on the right), and its folder rows become
cards.

**A card** is the tab's label, a text field, over the folder's path, a static line: dim, or red with "(not found)"
when the folder is missing. A `grip` icon on its leading edge says it can be dragged. The ↑ ↓ × buttons go.

- **Selecting.** A click anywhere on a card selects it. A click in the label puts the caret there, and the card shows
  as selected while it does. Esc leaves the field and leaves the card selected.
- **Reordering.** Drag a card, and a drop line shows where it will land. From the keyboard, Alt+↑ and Alt+↓ move the
  selected card, which is what the arrow buttons did.
- **Removing.** Right-click, Remove; or Backspace or Delete on a selected card. Inside the label, Backspace edits the
  text.
- **A label is never empty.** Leaving the field blank fills it with the folder's own name. A hand-edited file with a
  blank name still opens as now, with the folder's name.

**Undo.** Today only a label's own typing can be undone; moving, adding, removing and renaming are saved the moment
they happen, for good. The page gets a history of its own (`dev.vexelray.gui.core.edit.History`), with Ctrl+Z and
Ctrl+Y: moving, adding, removing and relabelling a folder, and renaming a workspace, each one step. A run of typing
in one label is one step. Deleting a whole workspace still asks first.

**How the drag is built** is decided when it is built. The framework's reorder (`Reorder`, a `Change` per drop,
recorded so it can be undone) belongs to `TreeView`, and these cards are hand-built rows. Either the cards become a
flat `TreeView`, or they use the drag-source and drop calls under it directly. Neither needs vexelray-gui to change.

## 3. The Open dialog

Ctrl+O opens a modal in the MainFrame window: a grid of tiles, one per saved workspace, showing its name, how many
folders it has and the first few tab names.

- The first tile is always **Open folder…**: the system folder picker, and the folder opens as a new tab in this
  window.
- With no workspaces saved, Ctrl+O goes straight to the folder picker.
- Choosing a workspace starts a new MainFrame process with `--workspace=<name>`, in a window of its own. Each program
  is its own process, so windows share nothing. Ctrl+Enter (or the tile's menu) opens its tabs in this window
  instead.
- The last tile is **+ New**, and each workspace tile's menu has **Edit…**. Both open Settings, Workspaces, on that
  workspace. There is one editor, and it stays in Settings: a modal inside a terminal window is a cramped place to
  drag cards about, and two editors would drift apart.
- Perhaps: **Save tabs as workspace**, which makes a workspace of the tabs open now.
