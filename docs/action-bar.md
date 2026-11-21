# The action bar

An optional bar at the foot of the main window: a many-line field and **Send**, which type into the tab in front, and
a row of the person's own **macro** buttons, each sending keys or running a script. Hidden until asked for. Sketched
before code is written against it, as `CLAUDE.md` asks of anything that touches `Messages`, `Recipes` or the frame's
state. Names are proposals; the questions at the end are the author's.

## The window

```
title bar (the framework's)
┌ tab bar ──────────────────────────────────────── + ┐
│ the front tab's TerminalScreen                     │   shrinks by the bar's height: FITTED, Resized, the
│                                                    │   shell told the new rows, as any resize does
└────────────────────────────────────────────────────┘
┌ action bar ────────────────────────────────────────┐   a row of the root's column, under the tabs' box,
│ [▶ Build] [⟳ Pull] [■ Stop server] ...        [+]  │   never floated over them
│ ┌──────────────────────────────────────┐  [ Send ] │
│ │ git commit -m "..."                  │           │   multiline TextField, 3 rows, scrolls past that
│ └──────────────────────────────────────┘           │
└────────────────────────────────────────────────────┘
```

- **It takes its own height out of the window.** `Ui`'s body is today one box: `Tabs` with the bar's end floated in
  it. The root becomes title bar, that box (`grow(1)`), then the action bar (its own height). Showing it shrinks the
  terminal, which re-fits and tells its shell as a window resize does; nothing overlaps.
- **Hidden by default, shown and hidden without a cut.** `Ui` builds the bar once and the view only calls
  `visible(...)`, which the framework fades; the terminal's rows change once, at the end (the conhost snap in memory
  is the thing to watch here, and the `ottermate` check looks at the prompt after a show and a hide).
- **Opened by** a check item in the Terminal menu, *Action bar*, and a chord, `view.actionbar` (Ctrl+Shift+B to
  start, rebindable). Whether it is shown is a setting, kept as `actionbar.shown`, so it comes back as left.

## Send

- **Ctrl+Enter sends; Enter is a new line** (the field is multiline, and that is what multiline `TextField` already
  does with Enter). The field keeps the keyboard after a send, and is emptied.
- **What reaches the shell:** the text, as one bracketed paste when the shell asked for bracketed paste
  (`InsertText.typed`'s rule, so PSReadLine and bash take a many-line block as one edit), then a CR. A shell that did
  not ask gets the lines with CR between them. Control characters other than newlines are refused, as
  `--insert-path` refuses them (`InsertText.unsafe`).
- **To the front tab.** The macro shape below keeps a `target` so *every tab* or *the workspace's tabs* can come
  later without a new record; the first version has only *the front tab*.
- Sending marks the tab `submitted` (closing it then asks), as typing a CR does.

## Macros

A macro is a value in `-core`:

```java
record Macro(String id,               // stable, never reused: what the view's buttons and the store key by
             String label,            // under or beside the icon; may be blank for an icon alone
             MacroIcon icon,          // one of the library's (below)
             Swatch colour,           // one of the palette's
             Effect effect,           // PLAIN, GLOW, SHADOW, OUTLINE, BOLD
             MacroAction action,      // Keys(String spelled) or Script(Path file)
             String confirm)          // blank: runs on click; else asked first, in these words
```

- **Keys** are spelled as text with braces for what is not text: `git status{Enter}`, `{Ctrl+C}`, `{Up}{Enter}`,
  `{Esc}:wq{Enter}`. `Keys.bytes(spelled)` turns that into what the terminal sends (a pure function in `-core`, with a
  test for each key it knows, and an unknown `{...}` refused when the macro is saved, not when it is clicked).
- **Script** runs *in the front tab's shell*, typed as a command line the shell understands
  (`& 'C:\…\build.ps1'` in PowerShell, `call "…\build.cmd"` in Command Prompt, `. '…/build.sh'` in bash), then a CR.
  So it sees the tab's folder, environment and workspace script, and its output is in the terminal where the person
  is looking. It is quoted by the same code that quotes `--insert-path`.
- **Confirm**: a click on a macro with a message asks it through the framework's `Ask` (*Run* and *Cancel*), held for
  the person, and runs only on *Run*. That is the frame's close chain again: the click starts a chain, the `Ask` is
  its step, the answer resumes it.

**Where they are kept:** `~/.mainframe/macros.csv`, one line per macro, read with the startup and written by a job
on each change (one write in flight, the latest held, as `SavePrefs` does). New scripts go in
`~/.mainframe/macros/`, named from the label (`build.ps1`, `build-2.ps1` on a clash), in the dialect of the shell new
tabs run (`EnvScripts.Dialect`).

### The library

- **Icons** come from the suite's set, a curated two dozen that read as actions: `PLAY`, `STOP`, `PAUSE`, `REFRESH`,
  `SAVE`, `TRASH`, `SEARCH`, `TERMINAL`, `SHELL`, `FOLDER`, `FILE`, `EDIT`, `COPY`, `PASTE`, `ARROW_UP`, `ARROW_DOWN`,
  `KEY_ENTER`, `KEY_ESC`, `BOOKMARK`, `LINK`, `LOCK`, `NOTIFICATION`, `SETTINGS`, `CAUTION`, `SUCCESS`, `WORKSPACE`,
  `AUTOMATION`. Any the library wants and the set lacks (a hammer for *build*, a rocket for *deploy*, a bug, a
  database, git's branch) are drawn in `vexelray-icons` first, as all suite art is, and reach MainFrame as
  `src/main/icons/large` masks baked into `MainFrameIcon`. `MacroIcon` names each by a stable key, so the CSV survives
  the set changing.
- **Colours**: eight swatches (ink, blue, teal, green, amber, orange, red, violet), each declared in `Look` with a
  light and a dark value, so a macro looks right in both themes. `Look`'s palette is still to be re-anchored (TODO), so
  these take the same construction.
- **Effects**: `IconView` already draws them: plain, `glow`, `shadow`, `outline`, and `weight` (bold).

### Editing one

A macro's own menu (right-click, and a ⋯ on the button's focus): *Edit macro…*, *Edit script* (script macros: opens
the file in VexelRay IDE through `Vex.edit`, as *Edit environment…* does), *Move left*, *Move right*, *Delete*
(asks). The + at the bar's end makes one.

*Edit macro…* and + open a **Macro** window, a named satellite as Settings is:

```
┌ Macro ──────────────────────────────────────────── × ┐
│  Label        [ Build                        ]       │
│  Icon         ▶ ■ ⏸ ⟳ 💾 🗑 🔍 >_ ...                │   the library, a grid; the chosen one ringed
│  Colour       ● ● ● ● ● ● ● ●                        │
│  Effect       ( Plain | Glow | Shadow | Outline | Bold )
│  Preview      [▶ Build]                              │   the button as the bar will draw it
│                                                      │
│  Does         ( Send keys | Run a script )           │
│   keys:       [ git status{Enter}            ]       │   refused spellings said under the field
│   script:     C:\Users\…\.mainframe\macros\build.ps1 │
│               [ Choose… ] [ New script ] [ Edit ]    │   Choose: the framework's file picker
│                                                      │   New: made, then opened in VexelRay IDE
│  Ask first    [ Deploy to production?        ]       │   blank: no question
│                                                      │
│                                [ Delete ]  [ Done ]  │
└──────────────────────────────────────────────────────┘
```

Changes apply as they are made, as in Settings; *Done* closes.

## What crosses

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `Preferences` gains `boolean actionBar` | | | shown or not; kept as `actionbar.shown` |
| `Macros(List<Macro> all)` | frame → views | state | the bar's buttons, in order |
| `Send(String text)` | view → frame | edge | the field's text, sent; the frame types it into the front tab (`TypeInto`) |
| `MacroClicked(String id)` | view → frame | edge | starts the run chain: asks if the macro has a question, then types its keys or its script's command line into the front tab |
| `ChangeMacro(Macro m)`, `DeleteMacro(String id)`, `MoveMacro(String id, int to)` | macro window, view → frame | edge | edits |
| `SaveMacros(List<Macro>)` → `MacrosSaved(String problem)` | frame → job → frame | edge | the file written |
| `NewScript(String macro, String label)` → `ScriptMade(String macro, Path file, String problem)` | frame → job → frame | edge | made in `~/.mainframe/macros/`, then opened in the IDE |
| `EditMacroScript(Path file)` | view → `OpenScript`-like job | edge | *Edit script* |

`Startup` gains the macros read at launch. `Frame.State` gains `List<Macro> macros` and the save-in-flight pair, as
it has for the settings. `Recipes` gains the Macro window. `Core` lists the two new jobs.

## Slices

Each with its test and its `ottermate` check, one at a time:

1. **The bar and Send.** Hidden by default; the menu item and chord; `actionbar.shown` saved; the field and Send to
   the front tab. No macros yet. `ViewTest`: shown and hidden without a cut, the terminal re-fitted. `ottermate`:
   show it, type two lines, Ctrl+Enter, `shot`, hide it, check the prompt did not jump.
2. **Key macros on the bar.** `Macro`, `Keys.bytes`, `macros.csv`, the buttons drawn with icon, colour and effect,
   the confirmation. Made by hand in the CSV for this slice.
3. **The Macro window.** Icon grid, swatches, effects, preview, keys field, delete, reorder.
4. **Script macros.** Choose an existing file, *New script* made and opened in VexelRay IDE, *Edit script*, run in
   the front tab's shell. Any icons the library lacks, drawn in `vexelray-icons`.

*Later, not designed here:* targets beyond the front tab (every tab, the workspace's tabs, a tab by name), macros per
workspace, a history in the field (Up on its first line).

## For the author

1. **A script runs in the front tab's shell**, typed as a command line, rather than as a process of its own? It then
   sees the tab's folder and environment and its output is in the terminal; the cost is that it is typed at the
   prompt, so it does nothing useful while a program has the terminal.
2. **Ctrl+Enter sends and Enter is a new line**, rather than Enter sends and Shift+Enter is a new line?
3. **Macros are the person's, in `~/.mainframe/macros.csv`, the same in every window**, rather than per workspace
   (which can come later)?
4. **Keys spelled as `text{Enter}{Ctrl+C}`** in a field, rather than recorded by pressing them?

## Ruled

All four accepted as recommended by the author 2026-11-21 ("Accept, build slice 1"): a script runs in the front tab's
shell, Ctrl+Enter sends and Enter is a new line, macros are the person's and the same in every window, and keys are
spelled as text with braces.

## Slice 1, as built

Built 2026-11-21 and seen live with `ottermate` on a home of its own: Ctrl+Shift+B showing the bar and the terminal
giving it its rows, two lines typed and sent by Ctrl+Enter and both run by PowerShell, a line sent by a click on Send,
the bar hidden with the prompt where it was and the keyboard back on the terminal, and a relaunch opening with the bar
shown (`actionbar.shown=true`). Where it departs from the sketch:

- **No `Send` message.** The view types the field into the tab in front itself, as `Typed`, with `Submitted` and
  `Touched` as a typed Enter has; that is what the screen does with a key, and the view already sends the interrupt
  the same way. The frame needed no new step: showing and hiding is a `ChangePrefs` like any other setting.
- **Control characters are dropped, not refused.** A tab becomes a space and every other control character but the
  line ends is left out (`InsertText.sent`), so nothing the person cannot see in the field is sent.
- **The field does not overhang.** A multiline `TextField` grows over its neighbours while focused, by default; that
  would cover the terminal, so it is off.
- **The re-fit is not in `ViewTest`.** A headless frame never ticks the clock, so the bar's arrival never ends there
  and the terminal is never re-laid (FN-30). The test checks the bar is laid out at the window's foot; `ottermate` saw
  the re-fit.
- **Not driven live:** the Terminal menu's *Action bar* line (its toggle is declared beside the chord's command), and
  a shell that asked for bracketed paste (Windows PowerShell's PSReadLine did not; `InsertTextTest` holds the paste).

## The keys: the field has MainFrame's, the terminal has the rest

Ruled by the author 2026-11-21, after slice 1: the passthrough switch goes. "If you want the keyboard shortcuts, use
the input field. If you want keystrokes sent to the terminal, focus the terminal." With the two questions that raised,
answered as recommended: Ctrl+Shift+B stays the way out of the terminal, and the terminal keeps Ctrl+Shift+C and
Ctrl+Shift+V.

- **While a terminal has the keyboard**, the key map holds only Ctrl+Shift+C (copy the selection; nothing with none),
  Ctrl+Shift+V (paste), Shift+PageUp and Shift+PageDown (scrollback), each the front screen's, and Ctrl+Shift+B. So
  Ctrl+C, Ctrl+V, Ctrl+W, Ctrl+T and Esc reach the program. The interrupt action (Esc) is gone, and Ctrl+C and Ctrl+V
  are no longer copy's and paste's.
- **MainFrame's chords are the field's** (`within` the field, `FOCUSED`): the tab commands, the text size, Open,
  Settings, and Ctrl+Enter's Send. The Terminal menu still has them all, for the mouse.
- **Ctrl+Shift+B, everywhere:** with the bar hidden, it shows it and puts the keyboard in the field; with the bar shown
  and the keyboard elsewhere, it goes to the field; from the field, it hides the bar and the keyboard goes back to the
  terminal. The menu's *Action bar* line only shows and hides.
- **Gone:** the pill, the Terminal menu's *Send keys to* group, `Keymap.paused`, and the `passthrough` landmark.

Seen live 2026-11-21: Ctrl+T and Ctrl+W typed in the terminal went to PowerShell and opened and closed nothing, Esc
cleared PowerShell's line, and from the field Ctrl+T opened a tab. A chord that opens or switches a tab leaves the
keyboard on that tab's terminal (the frame's `Front` focuses it), so the next chord is Ctrl+Shift+B away again.
