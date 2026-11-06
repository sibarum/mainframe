# Settings, for M3

What [rebuild.md](rebuild.md)'s M3 builds, sketched before code is written against it, as `CLAUDE.md` asks of
anything that touches `Messages`, `Recipes` or a lane: the settings window's General page, what crosses between it,
the frame and the disk, and where each setting takes effect. Names are proposals.

## What comes back

0.1's General tab (0.1's README, *Settings*), changed at once and kept in the framework's store,
`~/.mainframe/settings.properties`:

| Setting | 0.1's key, kept | Takes effect |
| --- | --- | --- |
| Text size: 14, 16, 18, 20 or 24 px (18 to start) | `font.px` | every terminal at once; Ctrl+= and Ctrl+- move along the same five and are saved too (shape.md, ruled) |
| Tab motion: slide, fade or none | `tabs.motion` | the next tab switch |
| The shell new tabs run: this platform's shells (`PtyPlatform.shells()`: Windows PowerShell, PowerShell 7 when it is on the PATH, Command Prompt) | `shell.id` | the next tab opened; a command line given at launch overrides it for that run |
| The folder new tabs start in: your home folder, unless another is chosen | `tabs.dir` (absent for home) | the next tab opened, and a launch that names no folder |

**0.1's keys are kept** so a 0.1 user's choices carry over: it is the same file. **The workspace to open at launch
moves to M4.1**, which brings workspaces back; until then there is nothing to choose from.

**The key-bindings editor is already there.** The framework puts *Key bindings…* in the app menu (FN-7, resolved)
and saves what is changed in its own store. M3 checks it live, and adds the line to the Terminal menu if a
`WindowCommand` can open the framework's window; if not, that is a finding, not a workaround.

## Where it is opened

0.1 had a gear in the title bar. The bar's right is the framework's instruments' (docs/passthrough.md), so the
Terminal menu gains **Settings…**, after a rule before the passthrough line, and the key map **Ctrl+,** (the chord
most editors use), both sending `WindowCommand.show("settings")`. A second open brings the window to the front.

## The window

A named window, declared in a recipe the way the template's Examples window is (`Windows.named`, a satellite of
the main one; regions.md step 2), with its own `Gui` and title bar. One page now; M4.3 adds *Workspaces* beside it,
and the page goes into a `Tabs` then.

```
┌ Settings ─────────────────────────────────────────── × ┐
│                                                        │
│  Text size        ( 14 | 16 |[18]| 20 | 24 )           │   Segment: every choice on show, as Ctrl+= walks them
│                                                        │
│  Tab motion       ([Slide]| Fade | None )              │   Segment
│                                                        │
│  New tabs run     [ Windows PowerShell          ▾ ]    │   Select: the platform's shells
│                   A command line given at launch       │   shown only when this run has one
│                   runs instead, this time.             │
│                                                        │
│  New tabs start   C:\Users\User                        │   the folder, as text
│  in               [ Choose… ]  [ Use my home folder ]  │   the second only when another is chosen
│                                                        │
│  Could not save settings: …                            │   only when a write failed
└────────────────────────────────────────────────────────┘
```

**Choose…** asks the framework's folder picker (`PickFile.folder`, answered by `Picked`), starting in the folder
shown. A change is applied and saved as it is made: there is no OK, and closing the window keeps everything.

## What crosses

The frame holds the settings, since it decides with two of them (which shell, which folder) and is the one place a
change from the window and one from a chord meet. The view and the settings window show what it says.

```
 ReadStartup ── Startup(dirs, line, prefs, shells) ──► Frame ── Prefs(prefs, shells, line) ──► View, SettingsView
                                                         ▲                                       │
         SavePrefs(seq, prefs) ◄─────────────────────────┤◄────────── ChangePrefs(prefs) ────────┘
              │                                          │
              └────────── PrefsSaved(seq, problem) ──────┘──────────────────────────────────────► SettingsView
```

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `Preferences(int fontPx, TabMotion motion, String shell, Optional<Path> dir)` | (a value) | | the four settings; `dir` empty for home |
| `ShellChoice(String id, String label, String line)` | (a value) | | one shell on offer |
| `Startup(List<Path> dirs, String line, Preferences prefs, List<ShellChoice> shells)` | `ReadStartup` → frame | edge | gains the settings and the shells; `line` is the launch's command line, blank for none |
| `Prefs(Preferences prefs, List<ShellChoice> shells, String override)` | frame → views | state | what is set now, what could be, and the launch's command line if it overrides the shell |
| `ChangePrefs(Preferences prefs)` | views → frame | edge | the person changed one (a control, Ctrl+=, a picked folder) |
| `SavePrefs(long seq, Preferences prefs)` → `PrefsSaved(long seq, String problem)` | frame → `SavePrefs` job → frame, view | edge | write all four and save; blank problem for none |

`TabMotion` is an enum in `-core` (`SLIDE`, `FADE`, `NONE`), each value naming its `Tabs` transition in `-gui` by a
method, not a switch.

**What the frame does with them.** `Startup` puts the settings in its state and sends `Prefs`. `ChangePrefs`
replaces them, sends `Prefs` again and `SavePrefs` with the next sequence number. A new tab runs the launch's command
line if there was one, else the chosen shell's, and starts in the chosen folder (else home); a launch that names no
folder starts its one tab there too. `--dir` and `--dirs` tabs start where they say.

**Jobs are in no order**, so two quick changes could be saved in the wrong order. `SavePrefs` writes the whole of
the settings each time and keeps the highest sequence number it has written; an older one arriving after a newer is
dropped. `ReadStartup` takes the framework's `Settings` (as the template's `ReadSession` does) and reads them with
the shells, off the logic lane.

**What the view does.** It applies `fontPx` to every screen and `motion` to the tabs. Ctrl+=, Ctrl+- and Ctrl+0 send
`ChangePrefs` with the next size, rather than changing the screens themselves, so a chord's size is saved and the
window's segment follows. The settings window has a view of its own (`SettingsView`, a second `@View`, as the
Examples window's is), which shows `Prefs`, sends `ChangePrefs` from each control, asks for a folder, and shows a
failed save.

## Tests

- `FrameTest`: the settings from `Startup` are sent as `Prefs`; a change is sent back and saved with a rising
  sequence; a new tab runs the chosen shell in the chosen folder; a launch command line wins over the chosen shell
  and is said in `Prefs`; a launch with no folder starts in the chosen one.
- `SavePrefsTest` and `ReadStartupTest`: the four keys written and read on a store in a temporary home, 0.1's file
  read as it was, an older save after a newer dropped.
- `ViewTest`: a chord sends `ChangePrefs`; `Prefs` resizes every screen; the settings window's controls send
  `ChangePrefs`, all with the cut watch on.
- `ottermate`: Settings… from the menu, the size changed in the window and the terminal following, a new tab in a
  chosen folder, the file on disk, and a relaunch that comes back as it was left.

## For the author

1. **A new tab starts in the folder chosen in Settings** (home unless another is chosen), as 0.1's did, rather than
   where the front tab is, as M2 built it? The front tab's *starting* folder is all MainFrame knows, not where its
   shell has `cd`'d to, so "where the front one is" is often not where the person is.
2. **Keep 0.1's keys** (`font.px`, `tabs.motion`, `shell.id`, `tabs.dir`) so a 0.1 user's file carries over?
3. **The workspace to open at launch moves to M4.1**, with workspaces?
