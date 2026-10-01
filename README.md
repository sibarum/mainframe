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
| `CellArt` | Box drawing, block elements and braille, drawn rather than taken from the font. |
| `TerminalTabs` | The tab bar and one `TerminalView` per tab, with rename and the tab switch animation. |
| `SettingsPanel` / `AppSettings` | The docked settings panel and the saved settings behind it. |
| `Main` | The window and the frame loop. |

## Run

Needs the local vexelray stack installed (see `vexelray-gui/CLAUDE.md` for the build order), Java 25 and Maven.

```bash
mvn compile exec:exec
```

Anything after the goal is a command line, and replaces the shell for that run, for example
`mvn compile exec:exec -Dexec.arguments="cmd.exe"`.

## Screenshots

Screenshots are taken with `ottermate` (`vexelray-gui/docs/guides/ottermate.md`), which drives the running window over
vexelray's automation socket. The socket is off unless asked for: `-Dautomation=0` (a free port) or `--automation=<port>`.
`ottermate` takes one command per invocation, so a settle-then-shot goes in a script file:

```bash
printf 'settle\nshot C:/work/shot.png\n' > shot.txt
ottermate --script shot.txt --launch mvn.cmd -q compile exec:exec -Dautomation=0
```

## Keys

Ctrl+Shift+C and Ctrl+Shift+V always copy and paste, right-click copies a selection or pastes, and Shift+PageUp/Down
and the wheel scroll the history.

With **Remap keybindings** on (the default; a switch in Settings) Ctrl+C is copy and Ctrl+V is paste, and since that
takes the interrupt away, a plain Esc sends it instead (the legacy Ctrl+C character, 0x03). Esc therefore no longer
reaches programs that want it, such as vim. Turned off, every key goes to the shell as it is.

## Settings

The Settings button at the end of the tab bar opens a panel docked to the right: font size, tab animation (slide,
fade or none), key remapping and the shell new tabs start (Windows PowerShell, PowerShell 7 when it is on the PATH, Command Prompt).
Changes apply at once and are saved by vexelray's per-application settings file. A command line given when
launching MainFrame overrides the shell choice for that run only.

## Tabs

Each tab is its own shell. Ctrl+Shift+T opens one (so does the + in the bar), Ctrl+Shift+W closes the current one,
Ctrl+Tab and Ctrl+Shift+Tab cycle, and a tab's right-click menu has Close. A tab whose shell exits closes itself;
closing the last one closes the window. Tab titles follow the shell's own title.
Rename a tab with Ctrl+Shift+R, by double-clicking its header, or from its right-click menu: Enter or clicking away keeps the name,
Escape cancels, and a blank name hands the header back to the shell's own title. Switching tabs slides the new one in over 160 ms (`Tabs.slide` in `TerminalTabs`; swap in `Tabs.crossfade` for a plain dissolve).

## Known limits

- One mono face (Noto Sans Mono Regular): bold is faked by double-striking, italic is not drawn.
- Box drawing, block elements and braille are drawn by `CellArt`, not from the font, so they join across cells. Latin, Greek, Cyrillic, punctuation, arrows and most math come from the atlas (`vexelray-text`, face 1); powerline glyphs, emoji and CJK are not covered and draw as a box.
- Settings are limited to font size, tab animation, shell and key remapping, no mouse reporting to applications yet.
- Old experiments (the typed shell, assistant, templates) are at the git tag `archive/pre-v1`.
