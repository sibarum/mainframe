# Working in MainFrame

MainFrame is a VexelRay desktop application, generated from `vexelray-framework`'s `vexel-desktop` template. It
started as the template's reference application, a small tabbed text editor with a file navigator, and is being
turned into something else. [README.md](README.md) is the tour: read its *How it is put together* section before
changing anything, and `Messages.java` before adding a part.

## Where things are

- **Two modules.** `mainframe-core` is the controller: `Editor.java`, which makes every decision as declared steps
  on one state, and the jobs that touch the disk. `mainframe-gui` is the view and the framework integration:
  `View.java`, a `@View` on the GUI thread that shows what the editor decides and tells it what the person did, and
  everything about the window. Run every `mvn` command from this folder, the parent of both.
- **The parts talk by messages, and nothing else.** Every message is a record in `-core`'s `Messages.java`. A part
  never holds another part across lanes (the processor refuses it), so a new feature is a message, a mailbox
  (`@Subscribe`) where it arrives, and a `@Publishes` where it is sent. If message passing is awkward for something,
  stop and say so rather than reaching around it with a shared object or a callback.
- **Disk and anything else slow is a `@Job`**: a request on a topic, a reply on another, run on the offload lane. What
  can go wrong is a field of the reply, never a throw.
- **`Recipes.java`** is what `-gui` builds: one `@Provides` method per part. `MainFrameAppWiring` is generated from
  it, the components and the jobs by an annotation processor while `-gui` compiles. Nobody writes it, so never create
  or edit a file of that name. After a build it is in `mainframe-gui/target/generated-sources/annotations/`, and it
  shows what is built in which phase.
- **The editor's state changes only by its steps.** Everything it knows is one immutable `Editor.State` on a ledger,
  and a step is a static method `(state, message, out) → next state`, declared in `Editor`'s constructor and run on
  each message it hears (`.on(topic, step)`). A step sends through its `Out` and never touches the disk, waits,
  or reads anything but its state and its message: the processor refuses one that does, and says what to do instead.
  A new decision is a new step; a new thing the editor is in the middle of is a field of `State`, not a field of
  `Editor`. Something that waits on the person or the disk is a chain (closing is the example): its step holds the
  run and a later step resumes it.
- **`-core`'s `Core.java` lists its components and jobs.** The processor compiling `-gui` cannot see `-core`'s
  sources, so a component or job missing from that list is never built, with no error. Add every new one there. A
  component that takes an `Inbox` runs on the framework's logic lane and hears through it, never through `@Subscribe`.
- **`Doc.java`** is the session's shape, part of the editor's state, replaced and never mutated. `Ui.java` holds no
  state.
- **`-core` builds against no widget**, so its tests need no window. Code with no `Gui` in it goes there.
- **`Landmarks.java`** names everything an automation script may refer to. A new control a test needs to reach gets
  a landmark there, not a string literal.
- **Icons** come from the suite's set, `dev.vexelray.gui.icons.Icon`, first. An icon it does not have, and only this
  application draws, goes in `mainframe-gui/src/main/icons/small` or `large` (SVG, or a black-on-white or transparent PNG), and the
  build bakes it into `MainFrameIcon`, which goes anywhere an `Icon` does. Never draw an icon in code or load an
  image for one. The `-gui` pom's `app-icons` profile says how.
- **The stack** (vexelray, vexelray-gui, vexelray-framework, tactroller, atchung, kronometer, ...) is usually checked
  out in the folder above this one. **Treat it as read-only.** Its source and javadoc are the documentation; read
  them instead of guessing an API. If something you need is missing there, do not patch the stack. Write it up in
  `docs/framework-notes.md` (below) and say so.

## Rules the code does not show

Breaking one of these rarely fails a build. It shows up as a hang, a stutter or a broken native image, so hold to
them even when a shortcut looks harmless:

- **Nothing reflects.** No `Class.forName`, no runtime annotation reads, no reflective serialization. This compiles to
  a GraalVM native image, and the framework's wiring is generated at compile time so that nothing needs reflection.
- **Nothing in the frame loop allocates, logs or blocks.** No disk, no network, no locks there.
- **Vulkan, the window and present stay on the main thread.** The editor runs on the logic lane, the view (a
  `@View`) on the GUI thread in each frame, and disk work is a job on the offload lane. The view only shows and tells:
  a widget says what it did as a message, and a chord or menu item is declared with the message it sends, so no code
  of yours runs at a click. README's *Threading, in one paragraph* is the whole model. Jobs are in no order at all,
  so never write code that needs one job's answer to arrive before another's.
- **All input comes through tactroller.** No polling the OS keyboard, and no native listener of your own.
- **Log through `sibarum.probe.Log`**, as `Log.of("mainframe.<area>")`. stdout is for lines another program parses
  (`automation: localhost:<port>`), and for nothing else, including diagnostics.
- **Nothing appears, moves or grows on hover**, and only command keys auto-repeat.
- **A compile error beats a startup error beats a runtime error.** Prefer the check that fails earliest.
- **No sealed `switch` and no `default` that throws.** Turn it into a method on the type, or a sink.
- **Every build carries the automation sockets**, the release one too: `MainFrameApp` names `AutomationStarter`.
  They start off, and the title bar's plug shows whether they are open and whether anything is or was connected.

## Building and checking

```
mvn compile exec:exec                       # run it
mvn test                                    # unit tests, and the real window tree built headless
native-build.cmd -Pnative package -DskipTests   # the native debug build, mainframe-gui/target/mainframe-debug.exe
```

**Native builds go through `native-build.cmd`**, not plain `mvn -Pnative...` (`./native-build.cmd` from Git Bash).
It loads the Visual Studio environment that `rc.exe` and the MSVC `link.exe` need, which a plain shell lacks; under
Git Bash, coreutils' `link.exe` would otherwise be found instead of the linker. README's *A native executable* has
both builds.

**A change to the window is not done until it has been seen.** "It compiles" and "the tests pass" are not evidence
that it looks or behaves right. Drive it with `ottermate`, the stack's automation client (see the stack's
`vexelray-gui/docs/guides/ottermate.md`), and look at the screenshot before saying it works:

```
ottermate --script check.txt --launch mvn.cmd compile exec:exec -Dautomation=0
```

A script is one command per line: `settle`, `tree`, `click <landmark>`, `type <text>`, `key CTRL+S`,
`await <landmark> <text>`, `shot <file.png>`. It stops at the first failure. Screenshots come only from `ottermate`.
Never add a `--capture` flag, a robot or a screen grab to the application.

When a run fails, read `mainframe-gui/target/logs/mainframe.log` (`target-linux/logs/` on Linux) before changing code. Under automation it is at trace level.

## The three documents

- **`docs/framework-notes.md`**: when you work around something the framework does not do, add an FN entry **at the
  moment you write the workaround**, in the format at the top of that file. That is how the gap gets fixed upstream
  instead of copied into every application.
- **`docs/retrospective.md`**: **when a milestone is done, before starting the next**, read the milestone back and
  add its section, in the format at the top of that file: the shapes in the notes since the last one, what would stop
  each, your own mistakes, and what to keep. Write it while this conversation still remembers what each finding
  cost, and put your own mistakes in as plainly as the framework's. Never delete a section.
- **`docs/TODO.md`**: work on this application that is known about and not done. Delete an entry when it is done.

## How to work here

- **One slice at a time**: a change from the model to the screen, with its test and its `ottermate` check. Not
  several features at once.
- **Copy a precedent.** The template's own files, and the applications generated from it (VexelRay IDE, Calculator,
  MainFrame, Scheduler), have solved most problems once. Find how they did it before inventing a way. Scheduler
  is the one to read for motion: it keeps its nodes and changes them, so nothing on screen changes in one frame.
- **For anything that touches `Recipes.java`, `Messages.java`, a lane or the shape of `Doc`, propose the change before making it.**
