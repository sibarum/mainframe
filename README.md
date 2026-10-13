# MainFrame

A terminal window that wraps the native OS shell.

A VexelRay desktop application, generated from the `vexel-desktop` template: a small text editor with tabs, a file
navigator and Markdown in colour. It is the reference implementation of this stack, so every seam the framework
offers is wired in the tree you are reading — and it is a working editor, so it is also something to change into
whatever you are actually building.

## Run it

```
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. The window comes up where you
last left it, at 1100x700 the first time, with the folder and the files you last had open. The first time,
the navigator shows the working directory.

```
mvn compile exec:exec "-Dapp.args=C:/notes"               # open a folder (or files) named on the command line
mvn compile exec:exec -Dapp.args=120                      # 120 frames and quit
mvn compile exec:exec -Dautomation=0                      # a driving socket on a free port (see Taking a screenshot)
mvn test                                                  # the model, the file policy, Markdown, the real tree headless
```

## A native executable

Two editions, built with GraalVM native-image (a GraalVM JDK as `JAVA_HOME`, and a Visual Studio developer prompt,
which native-image needs for `link.exe` and the icon step needs for `rc.exe`):

```
mvn -Pnative-release package -DskipTests      # target/mainframe.exe        what ships: no console, no driving socket
mvn -Pnative package -DskipTests              # target/mainframe-debug.exe  a console, and ottermate can drive it
```

`MainFrameApp`, the class the processor reads, is written twice — `src/edition-debug` names `AutomationStarter`
and `src/edition-release` does not — and the pom compiles one. Keep the two annotations identical apart from
`starters`. Everything else is shared; an ordinary build, `exec:exec` and the tests are the debug edition.

This program's own native-image metadata is `src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
and it is short because almost nothing is this program's: the libraries register their own, and the processor
registers the icon. If a native run fails on something the JVM run did not (a `Missing...RegistrationError`), run the
debug edition on a JVM under `-agentlib:native-image-agent=config-output-dir=...`, do the thing that failed, and add
only the entries that name this program.

## The icon

`src/main/rc/mainframe.ico` is the application's mark, and it is one file read three ways: the window and its
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

## Using it

| Keys | Does |
| --- | --- |
| Ctrl+L | the path bar: type a folder to show it, a file to open it, a new name to create it; Enter goes |
| Ctrl+S | save |
| Ctrl+W | close the tab (asks if it is unsaved) |
| Ctrl+Tab, Ctrl+PageDown / Ctrl+Shift+Tab, Ctrl+PageUp | next / previous tab |
| Ctrl+Shift+E | put the keyboard in the navigator |
| Ctrl+F | find in the file (Enter / Shift+Enter step, Escape closes) |
| Ctrl+Z / Ctrl+Y | undo / redo |
| Ctrl+= / Ctrl+- / Ctrl+0 | zoom |

Selecting a file in the navigator opens it — a click, or walking the tree with the arrow keys, which keep the
keyboard in the tree. A tab's menu has Save, Close, Close others, Close all and Reveal in navigator. A bullet in
front of a tab's name means unsaved changes; closing anything unsaved asks first, and so does quitting.

There is no file dialog, by choice: the path bar is the whole of "open", and it works the same in a test as in use. The
framework brings native pickers (`Dialogs.openFile`, `openFolder`, `saveFile`) for a project that wants them.

## How it is put together

Read these in this order.

| File | What belongs in it |
| --- | --- |
| `Recipes.java` | **what this application builds.** One `@Provides` method per part; `MainFrameAppWiring`, which builds them in order, is generated from it while the project compiles. A part's phase is the latest phase of anything it takes, so there is none to declare. New parts go here. |
| `Doc.java`, `Model.java` | the shape of the session — which files, which in front, which unsaved, which folder — as one immutable value, changed only by functions of the current value, committed through atchung's `State`. |
| `Ui.java` | the window. Holds no state; `show(Doc)` writes everything derived from the session. |
| `Workspace.java`, `Buffer.java` | the tab bar and one `Buffer` per tab: a `TextField`, its spans, its save bookkeeping. |
| `Navigator.java`, `FolderSource.java` | the path bar and the file tree, over the disk, read lazily off the frame loop. |
| `Actions.java` | every command, the questions some of them ask, and the close gate. |
| `Session.java` | what comes back next time, in the framework's one settings store. |
| `Motion.java` | the one tempo every widget moves at, on the framework's clock. |
| `text/TextFile.java`, `text/Markdown.java` | **code with no `Gui` in it**, in a sub-package with public types: the policy between bytes and text, and Markdown as spans. Testable in milliseconds; this is where a real application's non-GUI code goes. |

`MainFrame.java` is the entry point and the constants, and nothing else. The application edge — input, the
clipboard, window memory, the clock, the frame loop with its wakes and its pacing, the dialogs, the command line and
the shutdown order — is `vexelray-framework`'s. `Look.java`, `Type.java` and `Landmarks.java` are the three
vocabularies: colour, size, and the names an automation script is allowed to depend on.

### Phases

A phase is a **correctness** constraint rather than a scheduling detail, and having them as a type is what makes the
constraints structural instead of remembered:

| phase | what exists by then |
| --- | --- |
| `CONFIG` | the settings store and the look — values, before there is a `Gui` to apply them to |
| `MODEL` | what the application knows, before there is anything to draw it with |
| `GUI` | the `Gui` and the clock; the theme applied and the zoom range set, before the first widget |
| `TREE` | the widgets. Buildable with no window, which is what lets a test build it with no GPU |
| `WINDOW` | the device and the window handle. Main-thread from here on |
| `ATTACH` | anything that needed the handle: chrome controls, the close gate, the driving socket |

Open `target/generated-sources/annotations/.../MainFrameAppWiring.java` after a build to see which part landed where.

### Threading, in one paragraph

The GUI loop runs on its own thread. Every handler — a click, a key, a submitted path — runs on a worker, and every
read and write of a file runs on the offload lane, so a slow disk is never a frozen window. Workers meet at `Model`,
whose compare-and-set puts concurrent changes in an order. A listener registered with `Model.onChange` fires on the
committing thread; writing a node's props from there is correct, because a prop written off the GUI thread is applied
by the next drain. Two rules keep it deadlock-free, and both are written on `Workspace`: nothing commits to the model
while holding the workspace's lock, and nothing a model listener reaches takes it. Nothing inside the frame loop
reads the model.

### Spans

`Markdown.spans` is a pure function from text to `Span`s — foreground, background, underline — and `Buffer` runs it
on a worker after each edit and hands the result to the field. The text is never changed; the field draws the
colours and moves each span with its text as you type. A `.txt` file gets none.

### Motion

The widgets animate only when handed a ramp, and without one they cut — so leaving the clock out costs no error,
only a stiffer window. `Motion` makes the ramps from the framework's clock: the tabs slide, folders open, and a cue
rings a tab you asked to open that was already open, or the status line when something was refused.

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to have
happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that writes the wiring |
| `vexelray-gui-widget` | the widgets: `Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`, `Cues` |
| `vexelray-gui-krono` | the clock: every transition and cue |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

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

The landmarks in `Landmarks.java` are the names a script uses: `await status.file notes.md` waits for a file to be in
front, and `await path <folder>` for the navigator to be showing one. `ottermate help` lists every verb.

## Logging

The framework configures logging for you, before anything else runs, and the defaults follow what kind of run it is:

| Run | Console | File |
| --- | --- | --- |
| a test | warnings | none |
| from your checkout (`mvn exec:exec`) | info | `target/logs/mainframe.log`, debug |
| driven by `ottermate` or `-Dautomation` | debug | `…/mainframe.log`, **trace**, with a probe trace beside it |
| a native image | warnings | `~/.mainframe/logs/mainframe.log`, info |

Write to it with a logger, not `System.out` — `Actions` does, as `mainframe.files`:

```java
private static final Log LOG = Log.of("mainframe.files");   // sibarum.probe.Log
LOG.warn("could not save {}", path, exception);               // the exception goes last; its stack reaches the file
```

`--log=debug` (or `off`) changes the level for one launch, and `-Dlog.level.mainframe.files=trace` raises one
logger. Levels, settings and the rules for what belongs at which level are in `atchung/docs/logging.md`.

## The two documents

`docs/framework-notes.md` and `docs/TODO.md` start nearly empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two.
