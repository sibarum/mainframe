# TODO

Work on **MainFrame** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] **Save tabs as workspace**: a workspace made of the tabs open now. The one part of
      [workspaces-and-shortcuts.md](workspaces-and-shortcuts.md) not built.
- [ ] **The terminal as a library**, [terminal-library.md](terminal-library.md): cut the view's tie to `ConPty` in place
      first (a `Channel`), then move `term`, `pty` and the view out.
- [ ] **The stack's environment**, [stack-env.md](stack-env.md). None of it is built.
- [ ] **`--insert-command=<text>`**, for the Inspector's *Open in MainFrame*
      (`vexelray-inspector/docs/roadmap.md`, *The command menu*). A sibling of `--insert-path`, with the same contract:
      typed once the prompt is drawn, never run, not typed if the user types first, refused with a shell given on the
      command line. The safety rule is a character set rather than one quoted name: letters, digits, space and
      `-_.=:/\~` only (no `#`, `,`, `@` or quotes), and otherwise nothing is typed and the notice says why.
- [ ] **Rethink the passthrough switch's presentation.** Keep what it does; the " Keys: MainFrame " / " Keys: all to
      shell " text in the tab bar (`Terminals.PASSTHROUGH`) is the part that has to go. No design yet.
- [ ] **A shell per tab.** Each tab in a workspace names its own shell: Command Prompt or PowerShell on Windows, the
      installed shells (sh, bash, zsh…) elsewhere, from `PtyPlatform.current().shells()`. The tab header shows which.
      `EnvScripts` already picks a dialect per shell line, so env scripts should follow the tab's shell.
- [ ] **Confirm closing MainFrame while a tab is running something**, and test it thoroughly. Only Ctrl+W asks now
      (`Terminals.askClose`), and always; closing the window asks nothing. Needs a test for "a process is running"
      (a child of the shell, not the shell itself), and to cover every way out: the title bar's ×, Alt+F4, the
      taskbar, the last tab closing, one tab busy among idle ones. Rule: ask if any open tab has ever been submitted
      to: a CR or LF through `TerminalView.send` (Enter, keypad Enter, Ctrl+M, Ctrl+J, a paste with a line end).
      Env scripts do not count. No detection of what is running: a false alarm is fine, a false negative is not.

## Later

- [ ] The font size is pixels in a picture, so it ignores the display's scale and the window's zoom. Measure it in
      em (`units.md`, *what to use instead*) once the framework hands a picture its em.
- [ ] Mouse reporting to applications (xterm's 1000/1002/1006 modes), so vim and less take the wheel and clicks.
- [ ] A scripted `ottermate` run through the main path (tabs, rename, settings, a workspace, Ctrl+O), kept in the repo.
      A shell's first prompt is invisible to `settle`; publishing the cursor line as the terminal's accessible name
      would let a script `await` it instead of sleeping.
- [ ] The settings window's questions open over the main window, and the Open dialog is not modal
      (framework-notes FN-3, FN-4): revisit when the framework has a way.
