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
| `${className}.java` | **the application edge** — input, clipboard, window memory, the clock, the frame loop, and what closing the window means. The framework deliberately does not decide any of these for you. |
| `Model.java` | the one authoritative state, and the only way to change it. Every edit is a function of the current value, committed through atchung's `State`. |
| `Doc.java` | what the application knows, as one immutable record. Add fields here rather than adding state elsewhere. |
| `Ui.java` | the tree. Holds no state; `show(Doc)` writes everything derived from the document. |

`Look.java`, `Type.java` and `Landmarks.java` are the three vocabularies: colour, size, and the names an
automation script is allowed to depend on.

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
