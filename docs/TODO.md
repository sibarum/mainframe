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

## Later

- [ ] The font size is pixels in a picture, so it ignores the display's scale and the window's zoom. Measure it in
      em (`units.md`, *what to use instead*) once the framework hands a picture its em.
- [ ] Mouse reporting to applications (xterm's 1000/1002/1006 modes), so vim and less take the wheel and clicks.
- [ ] A scripted `ottermate` run through the main path (tabs, rename, settings, a workspace, Ctrl+O), kept in the repo.
      A shell's first prompt is invisible to `settle`; publishing the cursor line as the terminal's accessible name
      would let a script `await` it instead of sleeping.
- [ ] The settings window's questions open over the main window, and the Open dialog is not modal
      (framework-notes FN-3, FN-4): revisit when the framework has a way.
