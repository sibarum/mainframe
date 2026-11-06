# The shape, for M0's second step

What [rebuild.md](rebuild.md)'s M0 builds now that the editor is gone: the controller's state, the messages M1 and M2
need, the parts the wiring builds, and the window's regions. A sketch to agree before code is written against it, as
`CLAUDE.md` asks of anything that touches `Messages`, `Recipes`, a lane or the state. Names are proposals.

M0's second step builds the controller, `Shell` and the regions with M1's messages; M1 makes them work end to end, and
M2 adds the records marked *M2*. Settings (M3) and workspaces (M4) add fields and records; nothing here should have to
change shape for them.

## One tab, from end to end

The path a keystroke and a line of output take, which everything below serves:

```
                 AddTab(id)                 Resized(id, cols, rows)  (first one)
   Frame ─────────────────────► View ──────────────────────────────► Frame
     │                            │  TerminalScreen per tab              │
     │                            │  FITTED ─► Resized ──┐               │ OpenShell(id, line, dir, cols, rows)
     │                            │  SENT   ─► Typed  ───┤               ▼
     │                            │                      └──────────► Shell  (@Source @Destination, keyed by id)
     │                            │  Feed ◄── Output(id, text) ◄─────── run: reads the pty
     │ ◄──── ShellEnded(id, code) ──────────────────────────────────── run returns
     │ CloseTab(id) ────────────► View
```

- **The frame decides when a shell starts**, and it starts at the size the screen was laid out at: the frame opens a
  tab's shell on the first `Resized` it hears for it, so there is no start at a guessed 80×24 and a resize after.
- **The view forwards, and decides nothing.** `TerminalScreen` publishes `SENT` and `FITTED` addressed by the
  *window*, not by the terminal (`Addressed.edge` keys by `tree(address)`), so a destination keyed per tab cannot hear
  them as they are. The view turns each into `Typed` or `Resized` with the tab's id, on topics keyed by it. That is one
  line per message, and a framework note (below).
- **`Shell` sends `Output`, not `Feed`.** `-core` builds against no widget, and `Feed` is the terminal library's,
  which stands on `vexelray-gui-core`. So `Shell` (in `-core`, standing on the JDK-only `vex-suite-common-pty`) sends
  `Output(id, text)`, and the view hands it to that tab's screen as a `Feed`.

## The controller: `Frame`

`Editor` comes back as `Frame`: one `@Withers` `State` on a `Ledger`, static steps, a `@Component` on the logic lane
that takes an `Inbox`.

```java
record State(
    List<Tab> tabs,                // in the bar's order
    long front,                    // the tab shown
    long ids,                      // the last id given; never reused, so a late message about a closed tab finds none
    Optional<Startup> startup,     // what the launch asked for, once ReadStartup has answered
    Map<Long, Closing> closings)   // a close in flight, by its run: the template's pattern, M2

record Tab(
    long id,
    Path dir,                      // where its shell starts
    String line,                   // the command line it runs
    boolean opened,                // its OpenShell is sent
    String name,                   // given by the person; blank follows the shell's title (M2)
    String title,                  // the shell's own, as TITLED last said (M2)
    boolean submitted)             // a CR or LF was ever typed into it: closing it asks (M2)
```

`spare` (the plain tab Ctrl+O may replace), `workspace` and `insert` come with M4, as rebuild.md lists them.

## The messages

**The frame and the view** (records in `Messages`, each beside its topic):

| Record | From → to | Topic | Says |
| --- | --- | --- | --- |
| `AddTab(long id, String header)` | frame → view | edge | a tab: its screen built and put in the bar |
| `CloseTab(long id)` | frame → view | edge | the tab goes |
| `Front(long id)` | frame → view | state | the tab to show and give the keyboard |
| `Header(long id, String text)` | frame → view | state, by id | a tab's header (M2: the name, else the shell's title) |
| `Resized(long id, int cols, int rows)` | view → shell, frame | state, by id | the screen's size; the frame opens the shell on the first |
| `Typed(long id, String text)` | view → shell, frame | edge, by id | what goes to the program; the frame marks `submitted` on a CR or LF (M2) |
| `Shown(long id)` | view → shell | edge, by id | the view fed one of the tab's `Output`s to its screen: the shell may send another (M1, FN-9) |
| `Fronted(long id)` | view → frame | state | the person picked a tab (M2) |
| `Retitled(long id, String title)` | view → frame | state, by id | the shell named itself, from `TITLED` (M2) |
| `NewTab()`, `CloseAsked(long id, boolean ask)`, `Rename(long id, String name)` | view → frame | edge | M2's gestures |

**The frame and the shell:**

| Record | Topic | Does |
| --- | --- | --- |
| `OpenShell(long id, String line, Path dir, int cols, int rows)` | edge, by id | starts the tab's shell |
| `CloseShell(long id)` | edge, by id | the stop message: ends it |
| `Output(long id, String text)` | edge | what the program wrote, decoded (`Utf8Chunks`), as `run` reads it |
| `ShellEnded(long id, int code)` | edge | `run`'s reply: the program and its terminal are gone. The frame closes the tab, and the window with its last |

**The frame and its job:**

| Request → reply | Job | Does |
| --- | --- | --- |
| `ReadStartup()` → `Startup(List<Path> dirs, String line, String problem)` | `ReadStartup` | what the launch asked for. M1: the command line that replaces the shell, else the platform's first shell (`PtyPlatform.shells()`). M2 adds `--dir` and `--dirs` |

The window closing is the template's close chain: `CloseRequested` heard, `CloseAnswer` sent. In M1 it always lets
the window go. M2 makes it ask when any tab was submitted to, as rebuild.md's M2 says.

## `Shell`

```java
@Source(stop = CloseShell.class) @Destination
public final class Shell {
    private final Sender<Output> output;
    public Shell(Sender<Output> output) { ... }

    public Pty open(OpenShell r)              // Pty.start(r.line(), r.cols(), r.rows(), r.dir())
    public ShellEnded run(OpenShell r, Pty p) // read until -1, Output per read; then ShellEnded(id, p.waitFor())
    public void write(Pty p, Typed t)         // p.write(t.text())
    public void write(Pty p, Resized r)       // p.resize(r.cols(), r.rows())
    public void close(Pty p)                  // p.close()
}
```

In `-core`, beside the frame. Its tests need a `PtyPlatform`, which ServiceLoader finds only with
`vex-suite-common-pty-windows` (or `-linux`) on the class path, so `-core` takes the platform's module as a test
dependency and runs a real `cmd.exe /c echo` (Linux: `sh -c`). `-gui` takes it at run time, as 0.1 did.

## The command line

`mainframe pwsh -NoProfile` is still out of `Launch`'s reach: it refuses `-NoProfile`, keeps one value per key so a
second `--dir` replaces the first, and reads a bare number as a frame count. So `MainFrame.main` does as 0.1's did:
0.1's `Command` (brought back as it was) takes MainFrame's options and the trailing command line out and hands the
framework the rest. The result still has no way into the wiring but a static set once by `main`, which `ReadStartup`
reads: a job, never a step. That is 0.1's FN-1 again, on 0.2, and it is filed as this project's FN-1 when it is
written, with its framework TODO entry.

## What the wiring builds

`Core` lists `Frame`; the job `ReadStartup`; and `Shell` among the sources and the destinations. `Recipes` stays
`look()` and `ui(gui, titleBar)`. `View`'s constructor takes one `Sender` per record it sends (`Resized`, `Typed`, and
M2's), plus the screen commands the key map binds.

## The window

```
title bar (the framework's)
tab bar: Tabs, one header per tab                                      (M2: the +, the passthrough switch)
page: the front tab's TerminalScreen, filling it
```

`Ui` builds `Tabs` with `slide(motion.change)` and keeps it; the view adds a page per `AddTab` and never rebuilds one,
so nothing changes in one frame. A screen is `new TerminalScreen(gui, glyphs, face, px).pseudoconsole()`, named
`terminal-<id>` so the view can turn an address back into a tab; the mono face and its size come from `Type`, as 0.1's
did (Noto Sans Mono, double-struck for bold).

**The key map, M1:** copy or interrupt (Ctrl+Shift+C, Ctrl+C: `CopyOrInterrupt`, which copies a selection and
otherwise sends 0x03), paste (Ctrl+Shift+V, Ctrl+V), interrupt (Esc: `Typed(id, "\u0003")`), scroll by a page
(Shift+PageUp, Shift+PageDown: `Page`). Each sends to the front tab's screen. Every other chord the key map does not
hold is typing, which the focused screen takes itself.

## What this needs looked at in the framework

Each is checked when the code is written. A friction found is an FN note here and an entry in the framework's TODO
(`docs/framework-notes.md`'s format).

1. **A widget's messages are keyed by window, not by widget**, so nothing keyed per widget can hear them; the view
   forwards (above). Holds for every application that gives each tab or pane a connection of its own.
2. **The command line**: 0.1's FN-1, above.
3. **The lockup reads "mainframe-gui"** under `exec:exec`, not "MainFrame" (`About.name(info, build)`), seen in M0's
   first step.
4. **No key button in the title bar** in M0's first step's run, though the view declares three actions and the
   framework claims to place one on every bar. Seen in the tree, not looked into.

## What M0 built differently

Built 2026-11-05. Where the code departs from the sketch above, and why:

- **`ShellEnded` carries a `problem`.** What escapes `open` halts the application, so `Shell`'s connection is a
  `Session(Pty pty, String problem)`, and a program that will not start is a reply (FN-3). The frame keeps that
  tab open and writes the reason into it, rather than closing a window the person never saw start.
- **No `CloseWindow`:** the frame sends the framework's `WindowCommand.close(MAIN)`.
- **The view feeds the screens directly.** `TerminalScreen.feed`, `copyOrInterrupt`, `paste` and `page` are its own
  methods on the GUI thread, which is the view's, so `Output` becomes `screen.feed` and a key-map command a method
  call, with no `Feed` or command message sent to itself.
- **Nothing hears `CloseRequested` yet**, so closing the window closes it, and the framework stops every tab's shell
  at shutdown. M2 adds the chain that asks.
- **Titles are followed from M1, ahead of M2** (2026-11-05): `Retitled` and `Header` as sketched, the name half waiting
  for M2. A title is shortened as 0.1 shortened it (a path to its last name, without `.exe`), since conhost titles a
  pseudoconsole with its program's whole path; a blank one hands the header back to the folder.
- **`Output` is paced by `Shown`** (M1, FN-9): `Shell` keeps two of a tab's `Output`s on their way to the view, and
  sends what it read meanwhile as one when the view says it has shown one. Without it, `type` of a large file halted
  MainFrame.
- **M1 came with it.** The tab's shell, the key map (copy or interrupt, paste, Esc, Shift+PageUp/PageDown, the font
  size chords), the command line replacing the shell and the window closing with its last tab were all needed to see
  the shape work, so they are built and seen; rebuild.md says what of M1 is left.

## What M2 built differently

Built 2026-11-05. Where the code departs from the sketch:

- **`Submitted(id)` from the view, not `Typed` heard by the frame.** The view sees a CR or LF the person typed and says
  so once, on a state topic keyed by the tab, so the frame's inbox carries one message per tab rather than every key
  (a script's line of keys in one frame would overflow it, as FN-8 found for the view's).
- **No `Header` from the view's own picks:** the bar selects a tab itself on a click or Ctrl+Tab and the view says
  `Fronted`; the frame only keeps its front in step and sends `Front` for the tabs it adds or closes.
- **Closing is the template's chain without its saves:** `CloseAsked` or `CloseRequested` in, `Busy` asks, the
  answer's first button closes and anything else keeps. No `Closing` waits on a bound, since only the person's answer
  is waited on (`holdForPerson`).
- **The terminal's chords are the front screen's** (`Action.within`, moved as a tab comes to the front), so the rename
  field's Ctrl+V and Esc are its own, as 0.1's README says they were.
- **The +, the pill and the rename field are at the bar's right end** (FN-10); a header's double-click is heard
  through the tree's clicks (FN-11).

## Ruled

The sketch was accepted as written by the author 2026-11-05 ("Build it"), with its two proposals:

1. **Ctrl+= and Ctrl+- set the terminal's font size**, as in 0.1, saved from M3; the framework's zoom is left unbound.
2. **The tab bar is always shown**, even with one tab.
