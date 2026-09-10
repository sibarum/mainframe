# ${title}

${summary}

A VexelRay desktop application. Generated from MainFrame's `vexel-desktop` template, which follows
`calculator-vexel-demo` — the reference implementation of this stack.

## Run it

```
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. The window comes up where
you last left it, at ${width}x${height} the first time.

```
mvn compile exec:exec -Dapp.args=120                      # 120 frames and quit
mvn compile exec:exec -Dapp.args="--capture out.png"      # a PNG, with no window
mvn compile exec:exec -Dautomation=on                     # a driving socket on the default port
mvn test                                                  # the state model and the palette
```

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to
have happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-gui-widget` | the widget shelf, and the framework core and draw modules behind it |
| `vexelray-gui-krono` | the clock: animations, transitions, timed events |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

## How it is put together

Four files carry the whole shape, and it is worth reading them in this order.

| file | what belongs in it |
| --- | --- |
| `${className}Wiring.java` | **what this application builds, and in which phase.** One method per phase, called in order. New components go here. |
| `Model.java` | the one authoritative state, and the only way to change it. Every edit is a function of the current value, committed through atchung's `State`. |
| `Doc.java` | what the application knows, as one immutable record. Add fields here rather than adding state elsewhere. |
| `Ui.java` | the tree. Holds no state; `show(Doc)` writes everything derived from the document. |

`${className}.java` is the entry point and the constants, and nothing else. What used to be there — the
**application edge**: input, the clipboard, window memory, the clock, the frame loop with its wakes and its
pacing, the dialogs, the command line and the shutdown order — is `vexelray-framework`'s. It was three hundred
lines, and it was very nearly the same three hundred lines in every application on this stack.

`Look.java`, `Type.java` and `Landmarks.java` are the three vocabularies: colour, size, and the names an
automation script is allowed to depend on.

### Phases

A phase is a **correctness** constraint rather than a scheduling detail, and having them as a type is what
makes the constraints structural instead of remembered:

| phase | what exists by then |
| --- | --- |
| `CONFIG` | the settings store and the look — values, before there is a `Gui` to apply them to |
| `MODEL` | what the application knows, before there is anything to draw it with |
| `GUI` | the `Gui` and the clock; the theme applied and the zoom range set, before the first widget |
| `TREE` | the widgets. Buildable with no window, which is what makes a headless capture possible |
| `WINDOW` | the device and the window handle. Main-thread from here on |
| `ATTACH` | anything that needed the handle: chrome controls, the close gate, the driving socket |

A component's phase is decided by **what it needs** — the latest phase of anything it depends on — so it is a
consequence of the code rather than a second thing to keep in agreement with it. `Shell`'s accessors refuse to
hand over what does not exist yet, so asking too early is a message naming the phase rather than a null three
frames later.

### Threading, in one paragraph

The GUI loop runs on its own thread. Every handler — a click, a text change — runs on a worker. They meet at
`Model`, whose compare-and-set puts concurrent edits in an order, and that is the only synchronisation in the
application. A listener registered with `Model.onChange` fires on the committing thread, which is also a
worker; writing a node's props from there is correct and is the framework's own idiom, because a prop written
off the GUI thread is queued and applied by the next drain. What is not allowed is the other direction:
nothing inside the frame loop reads the model.

## The two documents

`docs/framework-notes.md` and `docs/TODO.md` start empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two.
