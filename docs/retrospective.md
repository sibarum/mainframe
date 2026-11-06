# Retrospective

What each milestone of **MainFrame** showed when it was read back: the shapes in
[framework-notes.md](framework-notes.md) since the last one, what would stop each, what went wrong in how the work
was done, and what to keep. The notes are written one at a time, as each workaround is written. This file is the pass
over them, and it is where the framework takes its proposals from.

**When.** At the end of every milestone, before the next one starts, and always before a rebuild or an archive. A
milestone with no new findings gets one line saying so. The framework's `docs/developers/retrospective.md` has the
reasons and the full method.

**Never delete a section.** Newest milestone first. A rebuild carries this file over.

## How to write one

    ## Milestone name · 2026-10-22
    
    The count: N findings (FN-a to FN-b), how many of them architectural.
    
    ### The shapes
    | | The promise | What happened | How it looked |
    
    ### What would stop each
    1. One structural change per shape, numbered so a plan can cite it.
    
    ### My own mistakes
    What was trusted, what it turned out to be, and the rule that covers it.
    
    ### What to keep exactly as it is
    What carried the milestone, and why.

## Milestones

## M0 · the template becomes a terminal · 2026-11-05

Seven findings (FN-1 to FN-7). Two are architectural, in that they shaped where code went: FN-2 (who may hear a
widget) and FN-3 (how a connection says it could not open). The rest are missing knobs, and FN-1 and FN-4 are
other applications' findings met again.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| FN-1 | The framework reads the command line | It reads its own grammar only | `pwsh -NoProfile` refused; the parse kept in a static a job reads |
| FN-2 | A widget talks by message, addressed | Addressed by window, so nothing keyed per widget hears it | The view forwards `SENT` and `FITTED` with the tab's id |
| FN-3 | A source's expected failures are its reply | `open` has none: what escapes it halts | A connection that may hold nothing and the reason |
| FN-4 | A source is testable alone | Its sender needs a bus and a tick | Six lines to collect what `Shell` reads |
| FN-5 | A script can wait for the application | Not for a terminal's text | A background run, driven by separate calls |
| FN-6 | The lockup names the application | It names the `-gui` module | "mainframe-gui" in the title bar |
| FN-7 | A key button on every bar | None seen | Not looked into |

### What would stop each

1. A provided value from the application's own `main`, or a declared argument parser (FN-1).
2. Widget topics a part keyed per widget can hear by the widget's address (FN-2).
3. An `open` that may refuse with a reason the framework turns into the run's reply (FN-3).
4. `Sender.of(Consumer)` for tests (FN-4).
5. A terminal's visible text as something `await` reads (FN-5).
6. The template's `-gui` build named for the application, or the lockup preferring the title (FN-6).

### My own mistakes

- **I sketched `Shell` sending the widget's `Feed`.** `-core` cannot see a widget, which CLAUDE.md says in its first
  section, and the sketch had to be corrected before code. The rule: place each record by the module that can see
  its type before naming who sends it.
- **I wrote a `CloseWindow` message** before looking for the framework's (`WindowCommand.close(MAIN)`). The rule: grep
  `-core`'s `framework/core` for a message before inventing one.
- **A helper named like a record component** (`Tab.opened()`), a compile error a moment's reading would have saved.
- **The first launch crashed on another session's half-written jar**, and I nearly read it as MainFrame's fault. The
  install timestamp said otherwise. The rule: when a class goes missing at launch, compare the jar's time with the
  launch's before changing code.
- **A Git Bash path given to `ottermate shot`.** It says how to fix it; `cygpath -m` first.

### What to keep exactly as it is

- **Deleting first, as its own green commit,** then the sketch, then the build: Vexplore's order, and it held again.
- **Starting the shell on the screen's first size.** No 80×24 start and resize after; the text wrapped correctly on
  the first prompt and re-wrapped when Ctrl+= changed the cell size.
- **`ShellTest` on a real pseudo-terminal.** It found nothing wrong, but it is what makes `Shell` trustworthy without
  a window.
