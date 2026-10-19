# The terminal as a library

Status: **moved, more simply than planned below**, 2026-10-15. The emulator is the `tty` package of
[vex-suite-common](../../vex-suite-common), not a repository of its own, because Vex's build console wanted it first
and both applications already sit beside that library. What moved, and how the tie below was cut:

- `Terminal`, `ConPty` and `CellArt` moved unchanged, with `TerminalTest` and `ConPtyTest`, and `ConPty`'s downcalls
  moved into that jar's `reachability-metadata.json`. `ShortName` stayed, as planned.
- No `Channel`. The tie is cut the other way round: the shared view, **`TerminalScreen`**, owns no input at all. It
  draws a `Terminal`, fits it to its node (telling its owner the new size through `onFit`, which MainFrame hands to
  `ConPty.resize`), scrolls, selects and copies, and says what a key or a paste would send (`keySequence`,
  `pasteText`) without sending it. `Pump` reads a pty or a stream into a `Terminal`. So a read-only console is simply a
  screen whose owner sends nothing, and the remap question below is answered: it stays in MainFrame's `TerminalView`.
- The face is passed in, as is the colour behind the default background, so Vex's console can be grey.

The rest of this note is the plan as it was written, kept for its reasoning.

MainFrame is two things in one artifact: a terminal emulator, and an application around it (tabs, settings,
workspaces, `--insert-path`). The emulator is wanted elsewhere. Vexplore wants a read-only console that shows the
git commands it runs (`vexplore/docs/ideas/git.md`). Vex could have a build panel. Tests and `ottermate` could read
a screen without a window. So the emulator moves to a project of its own, and MainFrame becomes its first user.

## What is already separate

Less moves than it sounds, because the code is already layered that way:

| Piece | Lines | Depends on | Goes to |
| --- | --- | --- | --- |
| `term/Terminal` | 553 | the JDK only. *"No knowledge of windows or of where the bytes come from"* | **core** |
| `pty/ConPty` | 255 | the JDK only, plus kernel32 through the foreign function API | **pty** |
| `CellArt` | 186 | `vexelray.canvas.Color`, `vexelray.gui.draw.Sketch` | **view** |
| `TerminalView` | ~640 | vexelray-gui core, text, tactroller input; **and `ConPty`** | **view**, once the tie below is cut |
| `Terminals`, `SettingsWindow`, `AppSettings`, `Workspace*`, `InsertPath`, `pty/ShortName`, `Command`, `Startup`, `Recipes` | | | stay in MainFrame. They are the application. `ShortName` serves only `InsertPath` |

`Terminal.Host` (`reply`, `title`) is already the screen's only way out, and `TerminalTest` / `ConPtyTest` move
with their classes.

## The one tie to cut: the view starts its own pty

`TerminalView` calls `ConPty.start(commandLine, cols, rows, cwd)` in its constructor, and after that it uses five
things: `read` on its reader thread, `write` for keys and for the screen's replies, `resize`, and `close`. That set
is the interface:

```java
/** Where a terminal's bytes come from, and where its keys go. */
public interface Channel extends AutoCloseable {
    int read(byte[] buf);              // blocks; -1 at the end
    void write(byte[] data);           // keys and replies; a read-only channel drops them
    void resize(int cols, int rows);
    boolean interactive();             // false: the view shows no cursor or input and takes no keys
    void close();
}
```

`TerminalView` takes a `Channel` instead of a command line. `ConPty` implements it unchanged. The new
implementation is **`Feed`**: bytes from a stream (a named pipe, a socket, or simply an `InputStream` handed over in
process), not interactive, with resize as a no-op. Read-only becomes a property of the channel, so the view only has
to draw it, by hiding the cursor, refusing keys, and keeping selection, copy and scrollback.

## The artifact

One new repository with three modules, so that something which only needs the screen pays nothing for the GUI:

| Module | Holds | Depends on | For |
| --- | --- | --- | --- |
| `terminal-core` | `Terminal`, `Channel`, `Feed` | nothing | Tests that assert on a screen; headless capture; anything that wants to parse VT output |
| `terminal-pty` | `ConPty`, and later a POSIX pty | `terminal-core` | Running a real shell |
| `terminal-view` | `TerminalView`, `CellArt` | `terminal-core`, vexelray-gui | A terminal in a vexelray window |

It doesn't go into `vexelray-gui`, because two of the three modules have nothing to do with a GUI, and a terminal
emulator is not a framework widget every application should carry. `terminal-view` follows the framework's
widget conventions, and could still move into `vexelray-gui-widget` later if a third application wants it there.
The name and group id are still open (`vexelray-terminal`?).

**Native-image metadata travels with the code.** `ConPty`'s downcalls are traced today into MainFrame's
`reachability-metadata.json`. In `terminal-pty` they go under its own `META-INF/native-image/`, so an application
that uses the module gets them without re-tracing. Vexplore's TODO already makes the same point about metadata
belonging upstream.

## Order

1. **Cut the tie in place, in MainFrame.** Add `Channel`, make `ConPty` implement it, and give `TerminalView` a
   channel. MainFrame behaves exactly as before. The diff is small and the `ottermate` screenshots should come out
   identical. Do this after the work in progress (workspaces, the settings window) is committed, not on top of it.
2. **Move the classes** to the new repository as the three modules, install them, and make MainFrame depend on
   them. Packages change from `dev.mainframe.term` / `pty`, which is the only change MainFrame itself sees.
3. **Add `Feed`** with a test in `terminal-core` (bytes in, screen asserted), and the read-only drawing in
   `terminal-view`.
4. **Then the users:** MainFrame's `--feed=<pipe>` tab, and Vexplore's git console.

## What it changes for Vexplore's git console

Once `terminal-view` exists, Vexplore doesn't need MainFrame to show what git did. It can open **its own console
window**, a `TerminalView` over a `Feed` fed in process, with no pipe, no second process and no dependency on
MainFrame being installed. MainFrame's feed tab is still worth having, for "send this transcript to my terminal",
but it stops being the only way to see git at work.

## Open questions

- **Key mapping.** `TerminalView` translates keys to bytes, including MainFrame's *Remap keybindings* (Ctrl+C as
  copy, Esc as interrupt). Is that a view option the application sets, or does the remap stay in MainFrame?
  *Answered 2026-10-19:* neither. Copy, paste and interrupt are actions on MainFrame's `Keymap`; the view sends any
  chord no action holds.
- **The font.** The view draws with the atlas's mono face (face 1). Is the face something the application passes
  in, so Vexplore's console matches Vexplore?
- **Writing to a feed.** A read-only console that lets Esc cancel the command it is showing needs the feed to carry
  one signal back. Is that `Channel.write` on a non-interactive channel, or a separate `interrupt()`?
- **POSIX.** The README says the design is cross-platform. `terminal-pty` is where `forkpty` would go, and `Channel`
  is the shape it has to fit, so it is worth checking now that it does.
