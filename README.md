# MainFrame

A terminal window that wraps the native OS shell. v1 is Windows PowerShell; the design is cross-platform.

It is a real terminal emulator, not a shell of its own: PowerShell runs unmodified inside a Windows pseudoconsole
(ConPTY), and MainFrame draws what it emits and sends it your keys. It is a VexelRay application, built on
`vexelray-framework` from its `vexel-desktop` template, so the window, input, clipboard, clock, frame loop, dialogs
and shutdown are the framework's, and what is here is the terminal.

## Run it

Needs the local VexelRay stack installed (`vexelray-framework/docs/getting-started.md` gives the order), Java 25 and
Maven.

```bash
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for the pseudoconsole's foreign calls. Options
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

The tests cover the VT screen, the pseudoconsole, the command line, the workspace files, what `--insert-path` may
type, and the real window contents built headless with no shell started (`TreeTest`).

## Keys

Ctrl+Shift+C and Ctrl+Shift+V always copy and paste, right-click copies a selection or pastes, and Shift+PageUp/Down
and the wheel scroll the history.

With **Remap keybindings** on (the default; a switch in Settings) Ctrl+C is copy and Ctrl+V is paste, and since that
takes the interrupt away, a plain Esc sends it instead (the legacy Ctrl+C character, 0x03). Esc therefore no longer
reaches programs that want it, such as vim. Ctrl+T opens a tab, Ctrl+W closes one after asking, and Ctrl+O opens a
workspace or a folder (see Workspaces), so the shell gives up those three too (Ctrl+W's delete-word is also
Ctrl+Backspace). Turned off, every key goes to the shell as it is.

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
the front. Its General tab has font size, tab animation (slide, fade or none), key remapping and the shell new tabs
start (Windows PowerShell, PowerShell 7 when it is on the PATH, Command Prompt). Changes apply at once and are saved
in the framework's settings store, `~/.mainframe/settings.properties`, which also remembers where each window was. A
command line given when launching MainFrame overrides the shell choice for that run only.

## Workspaces

A workspace is a saved list of folders, opened with `mainframe --workspace=<name>`: one tab per folder, in order,
each named as the workspace names it. Ctrl+O shows them as tiles, after an Open folder… tile: Enter (or a click)
opens a workspace in a new MainFrame window, Ctrl+Enter opens its tabs in this one, and a tile's right-click menu has
Edit…. With no workspaces saved, Ctrl+O goes straight to the folder picker.

They are made and edited in the settings window's Workspaces tab: New, Delete and the name, and a card per folder
with its tab name over its path. Drag a card to reorder (or Alt+Up/Down), and remove one from its right-click menu or
with Backspace or Delete; Add folder picks one. A tab name left blank gets the folder's name back. Every change is
saved at once, and Ctrl+Z and Ctrl+Y undo and redo them, deleting a workspace included.

Each is a CSV file in `~/.mainframe/workspaces/<name>.csv` with a header row, so it can be edited by hand too:

```
name,path
,C:\work\api
web,C:\work\web-frontend
```

An empty `name` is the folder's own. Columns other than `name` and `path` are kept when MainFrame writes the file
back, so later versions can add fields (`Workspace`, `WorkspaceStore`).

`--insert-path=<file>` puts a file's name on the first tab's input line, quoted for its shell, once the prompt is
drawn: Vexplore's Open MainFrame Here uses it, with the file's folder as the first `--dir`. The name is typed after a
space with the cursor before it, so what you type next is the command; nothing runs until you press Enter, and
nothing is typed if you start typing first. It is typed only when it can be proved safe: every character must be one
that shell's quoting leaves inert (PowerShell's single quotes, cmd's double quotes, which do not stop `%` or `!`). A
name that fails is replaced by its 8.3 short name if that passes, and otherwise nothing is typed; either way a notice
above the terminal says so. The file must exist, be named by its full path, and sit directly in the first `--dir`; a
shell given on the command line is refused, since MainFrame cannot tell how it quotes (`InsertPath`).

## How it is put together

`Recipes` is the list of parts, one `@Provides` method each; the framework's processor writes
`MainFrameAppWiring` from it, which builds them in order (open it under `target/generated-sources` after a build).

| Piece | What it is |
| --- | --- |
| `MainFrame` | The entry point. Takes MainFrame's own options out of the command line (`Command`) and hands the framework the rest |
| `Recipes` | The parts, and the start: the first tabs open in the one recipe that takes the `Shell`, so a test's headless tree starts no shell |
| `pty/ConPty` | `powershell.exe` on a pseudoconsole, through the foreign function API (kernel32). The only Windows-specific part |
| `term/Terminal` | The VT screen: cells, colours, scrollback, alternate screen. Knows nothing about windows or ptys |
| `TerminalView` | A terminal drawn as a vexelray node, plus keyboard, mouse selection, scrollback and clipboard |
| `CellArt` | Box drawing, block elements and braille, drawn rather than taken from the font |
| `Terminals` | The tab bar and one `TerminalView` per tab, with rename and the tab switch animation. Its `tick` is the frame's `@BeforeFrame` work, and it is a `WakeSource`, so a shell writing into an idle window wakes the parked loop |
| `Command` / `Startup` | MainFrame's command line, and the tabs and `--insert-path` it comes to on disk |
| `SettingsWindow` / `AppSettings` | The settings window (a framework `NamedWindow`) and the saved settings behind it |
| `WorkspacesPage` / `Workspace` / `WorkspaceStore` | The Workspaces tab, and the saved workspaces |
| `OpenDialog` / `SelfLaunch` | What Ctrl+O opens, and starting another MainFrame to open a workspace in |

What is not here is the point: input, the clipboard, window memory, the clock, the frame loop and its pacing, the
dialogs, the title bar and closing everything in order are the framework's.

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
MSVC's and `rc.exe` is on the path. Two profiles build the same code as two editions:

```
mvn -Pnative-release package -DskipTests   # target/mainframe.exe        what ships and is signed
mvn -Pnative package -DskipTests           # target/mainframe-debug.exe  for ottermate
```

- **release** (`installer.json` points at this one): linked as a Windows GUI subsystem program, so no console window
  ever appears, and built without the automation module: `src/edition-release` declares the application without
  `AutomationStarter`, and `vexelray-framework-automation` is not on its class path, so the binary cannot open a
  driving socket. The log goes to `~/.mainframe/logs/`.
- **debug**: console subsystem, automation present: `mainframe-debug.exe --automation=0` prints
  `automation: localhost:<port>` for `ottermate`.

The plain JVM build, the tests and `exec:exec` are the debug edition. This program's own native-image metadata is
`src/main/resources/META-INF/native-image/.../reachability-metadata.json`: the entry point, the pseudoconsole's
kernel32 downcalls; the libraries and the processor register the rest, the icon atlas included.

The mark is `src/main/rc/mainframe.ico` (the window, its taskbar button and the `.exe` all wear it), and
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

## The two documents

[docs/framework-notes.md](docs/framework-notes.md) is what building MainFrame on the framework found missing in it,
written when each workaround was; [docs/TODO.md](docs/TODO.md) is what is known about MainFrame itself and not done.
