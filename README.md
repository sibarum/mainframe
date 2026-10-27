# MainFrame

A terminal window that wraps the native OS shell. v1 is Windows PowerShell; the design is cross-platform.

**Install it** on Windows, with the other VexelRay applications, from [sibarum.github.io](https://sibarum.github.io/).

It is a real terminal emulator, not a shell of its own: PowerShell runs unmodified inside a Windows pseudoconsole
(ConPTY), and MainFrame draws what it emits and sends it your keys. It is a VexelRay application, built on
`vexelray-framework` from its `vexel-desktop` template, so the window, input, clipboard, clock, frame loop, dialogs
and shutdown are the framework's, and what is here is the terminal.

## Run it

Needs the local VexelRay stack installed (`vexelray-framework/docs/getting-started.md` gives the order) and
[vex-suite-common](../vex-suite-common) (`mvn install` there), Java 25 and
Maven.

```bash
mvn compile exec:exec
```

From this folder, the parent of `mainframe-core` and `mainframe-gui`. `exec:exec` rather than `exec:java`, so native
access can be enabled for the pseudoconsole's foreign calls. Options
and a command line go in `app.args`:

```bash
mvn compile exec:exec "-Dapp.args=--dir=C:/work/api --dir=C:/work/web"
```

```bash
mvn compile exec:exec "-Dapp.args=cmd.exe"
```

```bash
mvn test
```

The tests cover the controller alone, with no window and no shell (`FrameTest`: which tabs a launch opens, the spare
tab, closing, what follows a rename), the command line, the workspace files and their environment scripts, what `--insert-path` may
type, and the real window contents built headless with no shell started (`TreeTest`). The VT screen and the
pseudoconsole are tested where they live, in vex-suite-common.

## Keys

Every command is an action on vexelray-gui's key map (`vexelray-gui/docs/reference/keymap.md`): it has a name, and
its chords are yours to change. The keyboard button in the title bar opens the editor. A chord no action holds goes to
the shell as it is, so to give a key back to the shell, remove it from its action there. What you change is kept in
`~/.mainframe/keys.properties`.

| Action | Keys it starts with |
| --- | --- |
| New tab | Ctrl+Shift+T, Ctrl+T |
| Close the tab, after asking | Ctrl+W |
| Close the tab without asking | Ctrl+Shift+W |
| Rename the tab | Ctrl+Shift+R |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab |
| Open a workspace or a folder | Ctrl+O |
| Larger, smaller, back to normal size | Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's |
| Copy the selection | Ctrl+Shift+C, Ctrl+C |
| Paste | Ctrl+Shift+V, Ctrl+V |
| Interrupt (what Ctrl+C sends a shell, 0x03) | Esc |
| Scroll back / on a page | Shift+PageUp / Shift+PageDown |

The last five apply while a terminal has the keyboard, so in the rename field Ctrl+V and Esc are the field's.
Right-click copies a selection or pastes, and the wheel scrolls the history.

Taking Ctrl+C for copy takes the interrupt away, which is why Esc sends it, and Esc no longer reaches programs that
want it, such as vim. Ctrl+T, Ctrl+W and Ctrl+O are the shell's too (Ctrl+W's delete-word is also Ctrl+Backspace).
Unbind any of these and the shell has it again. This replaced the **Remap keybindings** switch: if you had it off,
the first run since then unbinds Ctrl+T, Ctrl+W, Ctrl+O, Ctrl+C, Ctrl+V and Esc for you, which is what off meant.

## Tabs

Each tab is its own shell. Ctrl+Shift+T opens one (so does the + in the bar), Ctrl+Shift+W closes the current one,
Ctrl+Tab and Ctrl+Shift+Tab cycle, and a tab's right-click menu has Close. A tab whose shell exits closes itself;
closing the last one closes the window. Tab titles follow the shell's own title. Rename a tab with Ctrl+Shift+R, by
double-clicking its header, or from its right-click menu: Enter or clicking away keeps the name, Escape cancels, and
a blank name hands the header back to the shell's own title.

To open several tabs at once, each starting in its own folder, give MainFrame the folders when launching it, before
any shell command line:

```
mainframe --dir=C:/work/api --dir=C:/work/web --dir=~
mainframe --dirs=C:/work/projects.txt
```

`--dir` can be repeated, and `--dirs` reads a file with one folder per line (blank lines and `#` comments are
skipped, and relative paths are taken from the file's own folder). The two can be mixed, and the tabs open in the
order given. Each tab is named after its folder, the first one is selected, and a folder that does not exist is
logged and skipped. Tabs opened later with + start where MainFrame itself was started. Switching tabs slides the new
one in (Settings has Fade and None).

The first argument that is not an option, and everything after it, is a command line that replaces the shell for
that run: `mainframe pwsh -NoProfile`. Any other `--option` is the framework's (`--automation`, `--log`), and one
nobody knows stops MainFrame with an error rather than being run as a program (`Command`).

## Settings

The settings button (the gear) in the title bar opens the settings window; clicking it again brings that window to
the front. Its General tab has font size, tab animation (slide, fade or none), the shell new tabs
start (Windows PowerShell, PowerShell 7 when it is on the PATH, Command Prompt), the folder they start in (your user
profile unless another is chosen), and a workspace to open whenever MainFrame is launched without folders or a
workspace named (None by default: one plain tab). Changes apply at once and are saved
in the framework's settings store, `~/.mainframe/settings.properties`, which also remembers where each window was. A
command line given when launching MainFrame overrides the shell choice for that run only.

## Workspaces

A workspace is a saved list of folders, opened with `mainframe --workspace=<name>`: one tab per folder, in order,
each named as the workspace names it. Ctrl+O shows them as tiles, after an Open folder… tile: Enter (or a click)
opens a workspace in a new MainFrame window, Ctrl+Enter opens its tabs in this one, and a tile's right-click menu has
Edit…. With no workspaces saved, Ctrl+O goes straight to the folder picker. If the window still has only the plain
tab it started with, and nothing has been typed or pasted into it, whatever Ctrl+O opens takes its place: the tab
closes, and when the workspace opens in a new window, this window closes with it.

They are made and edited in the settings window's Workspaces tab: New, Delete and the name, and a card per folder
with its tab name over its path. Drag a card to reorder (or Alt+Up/Down), and remove one from its right-click menu or
with Backspace or Delete; Add folder picks one. A tab name left blank gets the folder's name back. Every change is
saved at once, and Ctrl+Z and Ctrl+Y undo and redo them, deleting a workspace included. While no workspace is set
to open at start, New asks whether the new one should be; once one is, it does not ask.

Each is a CSV file in `~/.mainframe/workspaces/<name>.csv` with a header row, so it can be edited by hand too:

```
name,path
,C:\work\api
web,C:\work\web-frontend
```

An empty `name` is the folder's own. Columns other than `name` and `path` are kept when MainFrame writes the file
back, so later versions can add fields (`Workspace`, `WorkspaceStore`). MainFrame adds one itself, `env`, below.

### A workspace's environment

Every tab of a workspace can have its environment extended by scripts that its shell dot-sources as it starts: the
workspace's, which all its tabs share, and then the tab's own, so a tab's script overrides the workspace's. They are
in the language of the shell new tabs start: PowerShell scripts (`.ps1`) for Windows PowerShell and PowerShell 7, sh
scripts (`.sh`) for bash and zsh on Linux. They sit beside the CSV file:

```
~/.mainframe/workspaces/day.csv
~/.mainframe/workspaces/day.env.ps1               every tab of day, in PowerShell
~/.mainframe/workspaces/day.env.sh                every tab of day, in bash or zsh
~/.mainframe/workspaces/day.env/api-3f9c2a.ps1    the tab whose env column says api-3f9c2a.ps1
~/.mainframe/workspaces/day.env/api-3f9c2a.sh     the same tab, in bash or zsh
```

**Edit environment…** makes a script if it is not there and opens it in VexelRay IDE, a window of its own: on the Workspaces
tab for the workspace, on a card's right-click menu for that tab, and on a workspace tab's right-click menu in the
terminal window (*Edit environment…* for the tab, *Edit workspace environment…* for the workspace). VexelRay IDE is found by
its install record, or else as `text-editor` on the `PATH`; without it, a dialog says where the script is.

Each script starts with a region MainFrame rewrites whenever the script is about to be sourced or edited:

```powershell
# region MainFrame (generated; edits here are replaced)
$env:MAINFRAME_WORKSPACE = 'day'
$env:MAINFRAME_TAB = 'api'
$env:MAINFRAME_TAB_FOLDER = 'C:\work\api'
# endregion
```

An sh script's region sets the same variables with `export MAINFRAME_WORKSPACE='day'`. (The workspace's has
`MAINFRAME_WORKSPACE` and `MAINFRAME_WORKSPACE_ENV_DIR`, the folder of the tab scripts.)
Everything outside the region is yours and is kept byte for byte. A missing script is skipped.

- **Which tabs.** A workspace's tabs, opened in a window of their own or into this one with Ctrl+Enter. A tab opened
  with + in a window opened as a workspace sources the workspace's script only, and starts in the new tab directory
  as any + tab does. A folder opened with Ctrl+O, and every tab of a window opened without a workspace, sources
  nothing.
- **Which shells.** PowerShell's scripts are added to its command line as `-NoExit -Command ". 'a'; . 'b'"`, after
  the profile. They run under your execution policy; where it is `Restricted`, Windows' own default, PowerShell
  refuses them in the tab, and `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` allows them. On Linux, bash and
  zsh start on startup files MainFrame keeps in `~/.mainframe/shell/` (bash's `--rcfile`, zsh's `ZDOTDIR`), which
  read yours as the shell would have (the profile for a login shell, else `~/.bashrc`; each of zsh's) and then
  source the scripts. Either way only a command line with no command or file of its own is added to; for any other
  line, or any other shell, the tab says above the terminal that its scripts did not run.
- **The key.** A tab's script is named by the `env` column, which every row gets the first time its workspace is
  opened or shown on the Workspaces tab, so the script stays the tab's when it is renamed, moved or pointed at
  another folder.
- **Renaming, deleting, undo.** Renaming a workspace moves its scripts. Deleting one puts them aside in
  `workspaces/.deleted`, and Ctrl+Z puts them back; what is there is cleared after 30 days. A folder removed from a
  workspace keeps its script, which Ctrl+Z finds again (`EnvScripts`).

`--insert-path=<file>` puts a file's name on the first tab's input line, quoted for its shell, once the prompt is
drawn: Vexplore's Open MainFrame Here uses it, with the file's folder as the first `--dir`. The name is typed after a
space with the cursor before it, so what you type next is the command; nothing runs until you press Enter, and
nothing is typed if you start typing first. It is typed only when it can be proved safe: every character must be one
that shell's quoting leaves inert (PowerShell's single quotes, cmd's double quotes, which do not stop `%` or `!`). A
name that fails is replaced by its 8.3 short name if that passes, and otherwise nothing is typed; either way a notice
above the terminal says so. The file must exist, be named by its full path, and sit directly in the first `--dir`; a
shell given on the command line is refused, since MainFrame cannot tell how it quotes (`InsertPath`).

## How it is put together

The shape of `vexelray-framework`'s `vexel-desktop` template: two modules, split where the dependency edge falls, and
three kinds of part that never hold each other. **Components** have a lane of their own and are reached only by
messages; **jobs** do slow work (the disk, another process) on the offload lane, a request in and a reply out;
**recipes** build a part once and hand it to whoever takes it. Every message is a record in `-core`'s `Messages.java`.
Run every `mvn` command from this folder, the parent of both modules.

**`mainframe-core`, the controller.** It builds against no widget, so its tests need no window.

| Piece | What it is |
| --- | --- |
| `Messages` | Every message the parts send each other, grouped by who sends it to whom. A record holds no list, so a set is a message per member, and a workspace crosses as the text of its file |
| `Frame` | The controller, a component on the `frame` lane: which tabs a launch opens, which shell a tab starts and where, which tab is spare, whether closing asks, that the window goes with its last tab, and what follows from a workspace renamed, deleted or made |
| `ResolveTabs`, `PrepareShell`, `CheckInsert`, `LaunchWindow`, `MakeScript` | The jobs that open tabs: what a `--dir`/`--dirs`/`--workspace` names, a tab's command line with its environment scripts, what `--insert-path` may type, another MainFrame, a script made |
| `ListWorkspaces`, `LoadWorkspace`, `SaveWorkspace`, `RenameWorkspace`, `DeleteWorkspace`, `RestoreWorkspace`, `CreateWorkspace`, `FindWorkspace`, `PruneDeleted` | The jobs that keep the workspace files |
| `Command` / `Startup` / `InsertPath` | MainFrame's command line, and the tabs and `--insert-path` it comes to on disk |
| `AppSettings` | The saved settings, over the framework's store; read by both modules |
| `Workspace` / `WorkspaceStore` / `EnvScripts` / `SelfLaunch` | A workspace and its CSV file, the folder of them (a record, so a job can hold it), the environment scripts, and the command line that starts another MainFrame |
| `Core` | The list of this module's component and jobs, which `MainFrameApp` names as a starter: the processor compiling `-gui` sees only `-gui`'s sources, so a component or job missing here is never built |

**`mainframe-gui`, the views and the framework integration.**

| Piece | What it is |
| --- | --- |
| `MainFrame` / `MainFrameApp` | The entry point, which takes MainFrame's own options out of the command line (`Command`) and hands the framework the rest; and the declaration the processor writes `MainFrameAppWiring` from (open it under `mainframe-gui/target/generated-sources` after a build) |
| `Recipes` | The parts, and the start: it tells the frame to begin in the one recipe that takes the `Shell`, so a test's headless tree starts no shell |
| `View` / `Intent` | The main window's view, a component on the `view` lane: the frame's messages shown (`AddTab` is a tab, `Ask` a dialog, `Tile` a tile of the Open dialog) and the person's gestures told. `Intent` carries a chord or a menu item onto its lane |
| `SettingsView` | The settings window's view, a component on the `settings` lane: what the workspace jobs did, handed to the window and its Workspaces page |
| `TerminalView` | One tab: a shell on vex-suite-common's `ConPty`, drawn by its `TerminalScreen`, what the key map's terminal commands do to it (copy, paste, interrupt, scroll), the notice and `--insert-path`'s typing. A pseudoconsole cannot cross a lane, so the view starts it |
| `Terminals` | The tab bar and one `TerminalView` per tab, with rename and the tab switch animation. Its `tick` is the frame's `@BeforeFrame` work, and it is a `WakeSource`, so a shell writing into an idle window wakes the parked loop |
| `SettingsWindow` / `WorkspacesPage` | The settings window (a framework `NamedWindow`) and its Workspaces tab, which asks the workspace jobs itself and records a step once the job's answer has landed |
| `OpenDialog` | What Ctrl+O opens, from the tiles the frame lists |
| `OpenInIde` / `Vex` | A job that opens a script in VexelRay IDE, found by the suite's install record |

What is not here is the point: input, the clipboard, window memory, the clock, the frame loop and its pacing, the
dialogs, the title bar and closing everything in order are the framework's.

The terminal itself is [vex-suite-common](../vex-suite-common)'s `tty` package, shared with VexelRay IDE's build console: the
pseudoconsole (`ConPty`, kernel32 through the foreign function API, the only Windows-specific part), the VT screen
(`Terminal`), reading one into the other (`Pump`), the screen drawn as a node with scrolling, selection and copying
(`TerminalScreen`), and box drawing, block elements and braille drawn rather than taken from the font (`CellArt`).

## Automation

MainFrame can be driven and read from outside, by a script, a macro or an agent, through the framework's two loopback
sockets: **remote control** (the keys, clicks and reads a person has) and **diagnostics** (the flight recorder, the
log, the Inspector). Both are in every build, the release one too, and both start off.

- **Switched on by hand**: the plug in the title bar opens the switches, one per socket, each with a port (blank for
  a free one).
- **Switched on at launch**, for an agent that means to: `--automation=0` (both, on a free port),
  `--automation.control=<port>`, `--automation.diagnostics=<port>`, or `VEXELRAY_AUTOMATION=0` in the environment.
- **Not there at all**: `--automation.available=none` (or `control`, or `diagnostics`), or
  `VEXELRAY_AUTOMATION_AVAILABLE` in the environment. A socket that is not available cannot be switched on by
  anything, and with neither available the plug is gone.

Whatever turned it on, **the plug's lamps say what each socket is doing**, remote control's (red) left of the plug and
diagnostics' (blue) right:

| Lamp | Means |
| --- | --- |
| none | off, and nothing has connected to it this session |
| ring | open, and nothing has connected yet |
| filled ring | open, and something is connected now |
| ring with a grey dot | open; something was connected and has gone |
| grey dot | off, after something had connected to it this session |

The plug's tooltip and the switches' window say the same in words, with the port and how many are connected.

## Taking a screenshot

Screenshots are `ottermate`'s (`vexelray-gui/docs/guides/ottermate.md`), which drives the running window over its
automation socket. The socket is off unless asked for: `-Dautomation=0` (a free port) or `--automation=<port>`.

```bash
ottermate shot out.png --launch mvn.cmd -q exec:exec -Dautomation=0
```

Every open window can be driven. Commands go to the main window until `window settings` (or `window open`) picks
another; `click settings` (the gear) opens the settings window. The landmarks a script may use are in `Landmarks`.
A shell takes a moment to draw its first prompt, which `settle` does not wait for, so give it one before typing.

## The stack's environment (planned)

MainFrame is to define the stack's environment and hand it to any terminal as a script to source: the stack's
tools (`ottermate` first) on the `PATH`, and for AI agents every application's automation socket open. The plan is
[docs/stack-env.md](docs/stack-env.md), and the terminal moving to a library of its own is
[docs/terminal-library.md](docs/terminal-library.md); none of either is built yet.

## Native builds

Windows, GraalVM 25 as `JAVA_HOME`, from a Visual Studio developer prompt (or after `vcvars64.bat`) so `link.exe` is
MSVC's and `rc.exe` is on the path. Two profiles build the same code:

```
mvn -Pnative-release package -DskipTests   # mainframe-gui/target/mainframe.exe        what ships and is signed
mvn -Pnative package -DskipTests           # mainframe-gui/target/mainframe-debug.exe  for ottermate
```

- **release** (`installer.json` points at this one): linked as a Windows GUI subsystem program, so no console window
  ever appears. The log goes to `~/.mainframe/logs/`.
- **debug**: console subsystem: `mainframe-debug.exe --automation=0` prints `automation: localhost:<port>` for
  `ottermate`.

Both carry the automation sockets, off until asked for ([Automation](#automation)). This program's own native-image metadata is
`mainframe-gui/src/main/resources/META-INF/native-image/.../reachability-metadata.json`: the entry point, the pseudoconsole's
kernel32 downcalls; the libraries and the processor register the rest, the icon atlas included.

The mark is `mainframe-gui/src/main/rc/mainframe.ico` (the window, its taskbar button and the `.exe` all wear it), and
`mainframe-window.ico` is what the settings and Open windows wear. Both are rendered from the SVGs beside them by
`vex-suite-common`'s `tools/Ico.java`; `vexelray-icons` is where the art is drawn.

## Known limits

- One mono face (Noto Sans Mono Regular): bold is faked by double-striking, italic is not drawn.
- Latin, Greek, Cyrillic, punctuation, arrows and most math come from the atlas; powerline glyphs, emoji and CJK are
  not covered and draw as a box.
- The font size is in pixels and does not follow the display's scale.
- No mouse reporting to applications yet.
- The Open dialog does not block the main window while it is up, and the settings window's questions open over the
  main window (`docs/framework-notes.md`, FN-3 and FN-4).
- Old experiments (the typed shell, assistant, templates) are at the git tag `archive/pre-v1`; the hand-wired MainFrame
  this replaced is `b465748`.

## The three documents

[docs/framework-notes.md](docs/framework-notes.md) is what building MainFrame on the framework found missing in it,
written when each workaround was; [docs/TODO.md](docs/TODO.md) is what is known about MainFrame itself and not done.
[docs/retrospective.md](docs/retrospective.md) is filled in at the end of each milestone: the pass over the notes that
looks for what they have in common, and the one the framework reads first.
