# MainFrame

A terminal window that wraps the native OS shell. It is a real terminal emulator, not a shell of its own: PowerShell
runs unmodified inside a Windows pseudoconsole (ConPTY), and MainFrame draws what it writes and sends it your keys.

This is MainFrame rebuilt on VexelRay 0.2, milestone by milestone ([docs/rebuild.md](docs/rebuild.md)). What is here
now is tabs of shells (M2) and their settings (M3); workspaces come back in M4. 0.1's README, in
`GitHub/mainframe`, says what the finished application does.

## Run it

From this folder, the parent of the two modules:

```
mvn install -DskipTests
mvn exec:exec -pl mainframe-gui
```

`exec:exec` rather than `exec:java`, so native access can be enabled for the pseudoconsole's foreign calls. The tab
starts where MainFrame was started (under Maven, `mainframe-gui`), running this platform's first shell: Windows
PowerShell on Windows. A command line after MainFrame's own options runs instead of the shell:

```
mvn exec:exec -pl mainframe-gui "-Dapp.args=cmd.exe"
mvn exec:exec -pl mainframe-gui -Dautomation=0         # a driving socket on a free port (see Automation)
mvn test                                               # the frame, the shell on a real pseudo-terminal, the tree headless
```

When the shell exits, its tab closes, and with the last tab the window. A command line that will not start keeps its
tab open and says why in it.

## Keys

Every command is an action on the framework's key map, with a name and chords that are yours to change from the
title bar. A chord the key map holds outranks typing; every other key goes to the shell.

| Action | Keys it starts with |
| --- | --- |
| Copy the selection, or interrupt when nothing is selected | Ctrl+Shift+C, Ctrl+C |
| Paste | Ctrl+Shift+V, Ctrl+V |
| Interrupt (0x03, what Ctrl+C sends a shell) | Esc |
| Scroll back / on a page | Shift+PageUp / Shift+PageDown |
| Larger, smaller, normal text | Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's |
| New tab | Ctrl+Shift+T, Ctrl+T |
| Close the tab, after asking | Ctrl+W |
| Close the tab without asking | Ctrl+Shift+W |
| Rename the tab | Ctrl+Shift+R |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab |

The first four apply while a terminal has the keyboard, so in the rename field Ctrl+V and Esc are the field's. The
text size is the terminal's own (14 to 24 px), not the window's zoom, since a terminal is a grid of cells. The
**Terminal** menu, after the window's name in the title bar, has the tab and text commands too.

**Every key to the shell**, the Terminal menu's last line, lets go of all of these until you take them back, so
Ctrl+C, Esc and the rest reach a program that wants them (vim, emacs, tmux). While it is on, a pill at the tab bar's
end says so, and a click on it gives MainFrame its keys back. It is not saved.

A right-click copies the selection, or pastes when nothing is selected.

## Tabs

`--dir=<folder>` (again for more) and `--dirs=<file>` (a folder per line, relative to the file) open a tab per
folder; with neither, one tab in the folder chosen in Settings (your home folder unless you chose another). The + at
the bar's end, or Ctrl+Shift+T, opens one more there. A tab's header is the name you gave it (Ctrl+Shift+R, a double-click, or its header's
right-click menu; a blank name hands it back), else the title its program gives itself, else its folder's name.

A tab whose shell exits closes, and the last one closes the window. Closing a tab you have pressed Enter in asks
first, since something may still be running in it; so does closing the window with such a tab, by any way out.
Ctrl+Shift+W closes without asking.

## Settings

**Settings…** in the Terminal menu, or Ctrl+,, opens the settings window; a second time brings it to the front. It
has the text size (14 to 24 px, which Ctrl+= and Ctrl+- walk too), the tab motion (slide, fade or none), the shell
new tabs run (this platform's: Windows PowerShell, PowerShell 7 when it is on the PATH, Command Prompt), and the folder
they start in (your home folder unless another is chosen). A change applies at once and is saved in the framework's
store, `~/.mainframe/settings.properties`, under 0.1's keys, so a 0.1 MainFrame's choices carry over. A command line
given when launching MainFrame runs instead of the chosen shell, for that run only, and the window says so.

The key bindings are the app menu's (click the window's name in the title bar): **Key bindings…**.

## A native executable

Two builds, made with GraalVM native-image (a GraalVM JDK as `JAVA_HOME`, and Visual Studio with the "Desktop
development with C++" workload):

```
native-build.cmd -Pnative-release package -DskipTests   # mainframe-gui/target/mainframe.exe        what ships: no console
native-build.cmd -Pnative package -DskipTests           # mainframe-gui/target/mainframe-debug.exe  a console: prints the automation port line
```

Both carry the automation sockets, off until asked for ([Automation](#automation)), so ottermate can drive either.

On Linux there is one build, with no console subsystem or icon step to choose, and plain `mvn`:

```
mvn -Pnative-linux package -DskipTests                  # mainframe-gui/target-linux/mainframe
```

`native-build.cmd` is `mvn` inside a Visual Studio developer environment: native-image needs the MSVC `link.exe` and
the icon step needs `rc.exe`, and neither is on `PATH` in a plain shell. Under Git Bash it is worse, since coreutils'
own `link.exe` comes first. The script finds `vcvars64.bat` with `vswhere`, loads it, and runs `mvn` with the
arguments it was given (`./native-build.cmd ...` from Git Bash). Plain `mvn` works too from a developer prompt; from
anywhere else the icon step stops and says to use the script.

This program's own native-image metadata is `mainframe-gui/src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
and it is short because almost nothing is this program's: the libraries register their own, and the processor
registers the icon. If a native run fails on something the JVM run did not (a `Missing...RegistrationError`), run it
on a JVM under `-agentlib:native-image-agent=config-output-dir=...`, do the thing that failed, and add
only the entries that name this program.

## The icon

`mainframe-gui/src/main/rc/mainframe.ico` is the application's mark, and it is one file read three ways: the window and its
taskbar button wear it (`@VexelApp(icon = MainFrame.ICON)`; the pom puts it on the class path), and so does the
title bar the framework draws; the `.exe` links it (`src/main/rc/mainframe.rc`), and so Explorer, a shortcut and a
pinned button show it too. It starts as the framework's "new app" mark, whose source is `mainframe.svg` beside it.

A second window — a viewer, a settings window — wears the window mark, on the taskbar and in its title bar, with
nothing to write: declare it in a recipe with `windows.named(...)`, and the framework builds its tree and claims it
wearing `shell.windowMark()`. That mark is this same one unless
a `mainframe-window.ico` sits beside it, which is then found by name and registered for the native image with
the mark. The VexelRay suite's applications ship one: a band of the application's colour over its glyph, so a
second window reads as its own and is still told apart from the main one.

To make it yours, draw a 64x64 SVG and render it at every size Windows asks for, 16 to 256 px, into that `.ico` —
`vex-suite-common`'s `tools/Ico.java` does exactly this, from each size's own rasterisation rather than shrinking the
largest. Replace the file, and the window and the executable change together. An `.ico` or `.png` that is missing is
a compile error; one that does not decode costs the window its mark, logged, and nothing else.

Run from `mvn exec:exec`, the process is `java.exe`, so the window and its taskbar button wear the mark but Windows
groups the button with other Java programs, and pinning it pins Java. The native executable has neither problem.

## How it is put together

The shape of `vexelray-framework`'s `vexel-desktop` template: two modules, split where the dependency edge falls, and
parts that never hold each other and talk only by messages (every message is a record in `-core`'s `Messages.java`).
[docs/shape.md](docs/shape.md) draws one tab from end to end.

**`mainframe-core`**, which builds against no widget, so its tests need no window:

| Piece | What it is |
| --- | --- |
| `Frame` | The controller, on the logic lane: which tabs there are, what each runs, and when its shell starts (on its screen's first size, so a program never starts at a guessed size), and when closing asks first |
| `Shell` | A tab's program on a pseudo-terminal: a `@Source` that reads what it writes and a `@Destination` that writes what is typed and its size, one per tab, keyed by its id. The framework holds each tab's connection. It sends no more than two `Output`s ahead of what the view says it has `Shown`, so a program writing faster than the screen waits on its terminal |
| `ReadStartup`, `Folders` | A job: what the launch asked for, and the folders `--dir` and `--dirs` name |
| `SaveSettings`, `PrefsStore` | A job that saves the settings, and the store's keys, 0.1's, in one place |
| `Command` | MainFrame's own command line, taken out before the framework reads the rest |
| `Messages`, `Core` | Every message, and the list of this module's parts the processor builds |

**`mainframe-gui`**:

| Piece | What it is |
| --- | --- |
| `MainFrame`, `MainFrameApp` | The entry point, which splits the command line, and the declaration the wiring is generated from |
| `View` | A `@View` on the GUI thread: a `TerminalScreen` (vex-suite-common-tty) per tab, what the program wrote fed to it, and what it sends forwarded to the tab's shell with the tab's id |
| `Ui`, `Recipes` | The title bar over the tab bar, built once; and the parts |
| `SettingsPage`, `SettingsView` | The settings window, declared by name in `Recipes`, and its own `@View` |
| `Intent`, `Look`, `Type`, `Motion`, `Landmarks` | The key map's commands, the colours, the faces and sizes, the tempo, and the names a script may use |

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to have
happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-framework-api`, `vexelray-gui-core` | all `-core` builds against: the framework's annotations, and the values (a span, a colour, the settings store) and the bus that are safe from any thread |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that checks both modules and writes the wiring |
| `vexelray-gui-widget` | the widgets: `Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`, `Cues` |
| `vexelray-gui-krono` | the clock: every transition and cue |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

## Automation

MainFrame can be driven and read from outside, by a script, a macro or an agent, through the framework's two loopback
sockets: **remote control** (the keys, clicks and reads a person has) and **diagnostics** (the flight recorder, the
log, the Inspector). Both are in every build, the release one too, and both start off.

- **Switched on by hand**: the plug in the title bar opens the switches, one per socket, each with a port (blank for
  a free one).
- **Switched on at launch**, for an agent that means to: `--automation=0` (both, on a free port),
  `--automation.control=<port>`, `--automation.diagnostics=<port>`, or `VEXELRAY_AUTOMATION=0` in the environment.
  **The diagnostics socket is all the Inspector needs**: a run that opens it at launch records everything the
  Inspector reads, whatever `-Dprobe` says, so there is nothing else to set.
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

Screenshots are `ottermate`'s, and only its: it photographs the running window on the application's own device, so
the picture is right about content as well as chrome. There is no `--capture` flag in this project on purpose.

```
ottermate shot out.png --launch mvn compile exec:exec -Dautomation=0
```

Say what the picture is *of* with options, and `ottermate` sets it first and stops if it cannot. Everything before
`--launch` is ottermate's; everything after it is the application's command line (on Windows, `mvn.cmd`):

```
ottermate --zoom 1.5 --dpi 2 --size 1600x900 shot big.png --launch ...   # pixels
ottermate --size 36emx20em shot smallest.png --launch ...                # the minimum, in em
```

The landmarks in `Landmarks.java` are the names a script uses: `await terminal` waits for the tab in front to exist,
and `click tabs` reaches the bar. A terminal's text is not readable by `await` yet (docs/framework-notes.md, FN-5), so a
script cannot wait for a prompt. `ottermate help` lists every verb.

## Logging

The framework configures logging for you, before anything else runs, and the defaults follow what kind of run it is:

| Run | Console | File |
| --- | --- | --- |
| a test | warnings | none |
| from your checkout (`mvn exec:exec`) | info | `mainframe-gui/target/logs/mainframe.log`, debug |
| driven by `ottermate` or `-Dautomation` | debug | `…/mainframe.log`, **trace**, with a probe trace beside it |
| a native image | warnings | `~/.mainframe/logs/mainframe.log`, info |

Write to it with a logger, not `System.out` — the jobs do, as `mainframe.files`:

```java
private static final Log LOG = Log.of("mainframe.files");   // sibarum.probe.Log
LOG.warn("could not save {}", path, exception);               // the exception goes last; its stack reaches the file
```

`--log=debug` (or `off`) changes the level for one launch, and `-Dlog.level.mainframe.files=trace` raises one
logger. Levels, settings and the rules for what belongs at which level are in `atchung/docs/logging.md`.

## The three documents

`docs/framework-notes.md` and `docs/TODO.md` start nearly empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two. `docs/retrospective.md` is filled in at the end of
each milestone: the pass over the notes that looks for what they have in common, and the one the framework reads
first.
