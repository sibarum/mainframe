# MainFrame

A terminal window that wraps the native OS shell. v1 is Windows PowerShell; the design is cross-platform.

It is a real terminal emulator, not a shell of its own: PowerShell runs unmodified inside a Windows
pseudoconsole (ConPTY), and MainFrame draws what it emits and sends it your keys. The window is built on the
vexelray GUI stack.

| Piece | What it is |
| --- | --- |
| `pty/ConPty` | `powershell.exe` on a pseudoconsole, through the foreign function API (kernel32). The only Windows-specific part. |
| `term/Terminal` | The VT screen: cells, colours, scrollback, alternate screen. Knows nothing about windows or ptys. |
| `TerminalView` | Terminal drawn as a vexelray node, plus keyboard, mouse selection, scrollback and clipboard. |
| `Main` | The window and the frame loop. |

## Run

Needs the local vexelray stack installed (see `vexelray-gui/CLAUDE.md` for the build order), Java 25 and Maven.

```bash
mvn compile exec:exec
```

A headless still of the window, for checking the drawing without a keyboard:

```bash
mvn -q compile exec:exec -Dexec.arguments="--capture,shot.png"
```

## Keys

Ctrl+Shift+C copies the selection (Ctrl+C does too while one exists); Ctrl+V or right-click pastes; Shift+PageUp/Down
and the wheel scroll the history.

## Known limits

- One mono face (Noto Sans Mono Regular): bold is faked by double-striking, italic is not drawn.
- Box drawing, block elements and braille are drawn by `CellArt`, not from the font, so they join across cells. Latin, Greek, Cyrillic, punctuation, arrows and most math come from the atlas (`vexelray-text`, face 1); powerline glyphs, emoji and CJK are not covered and draw as a box.
- No tabs, no settings, no mouse reporting to applications yet.
- Old experiments (the typed shell, assistant, templates) are at the git tag `archive/pre-v1`.
