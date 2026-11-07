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

## M4 · workspaces · 2026-11-07

Eight findings (FN-14 to FN-21). Two are architectural. FN-15 decided how every test in this repository runs: a
headless tree runs the real jobs on the real home. FN-14 decided where a window's keys are declared: in the main view,
first. Three are widget gaps met in the Workspaces window (FN-16, FN-17, FN-20), one is an undo rule that bites a field
shown for a moment (FN-18), and two are what automation and the terminal cannot say (FN-19, FN-21).

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| FN-14 | A key is declared where it applies | Born application-wide, then moved | Every window's Esc declared by the main view, before the terminal's |
| FN-15 | A view test drives the view | It drives the real frame and jobs, on the real home | The person's settings overwritten; a real MainFrame started |
| FN-16 | Cards on a board, edited in place | No field in a card, no key on one | A rename field under the cards; Remove by menu only |
| FN-17 | A list says what the person selected | It says what the code selected too | The view drops the echoes of its own selections |
| FN-18 | One Ctrl+Z for everything in a window | A put-away field's typing stops it | Each field keeps its own history |
| FN-19 | A live check drives the app | Not the folder picker | Add folder and Change folder seen only in `ViewTest` |
| FN-20 | A field's text at any size | Larger text loses its descenders | The heading at 1.25rem |
| FN-21 | A terminal says what its program did | Not that it waits for input | 0.1's 300 ms quiet guess, kept with `Timer` |

### What would stop each

1. A key-map action declared straight into its place (FN-14).
2. A test tree on a home of its own by default, and a way to stand a job in for a test (FN-15).
3. A card face with an editing state, and a deck action that reaches the focused card's item (FN-16).
4. `Selected` saying whether the person made it, or no publish for a selection set in code (FN-17).
5. `Gui.undo` stepping past an entry whose target is gone for good (FN-18).
6. `ottermate pick <path>`, answering the next `PickFile` (FN-19).
7. A text field whose line follows its text size (FN-20).
8. Shell integration's prompt marks said as `TerminalScreen.PROMPTED` (FN-21).

### My own mistakes

- **I ran `ViewTest` against the person's own home.** Its tree saved a test's settings into the real
  `~/.mainframe/settings.properties`, which a 0.1 MainFrame shares, and removed keys I cannot restore. It also started
  a real MainFrame I was not permitted to stop. The person closed it. Nothing in a view test looked like disk work,
  and that was the trap: the tree runs every job. The rule: before a test builds a tree, ask what its jobs write and
  start, and give the test run a home of its own. The parent pom does that now, and `ViewTest` refuses to run without it.
- **The Workspaces component named a new workspace before it had read the disk**, so Ctrl+O's *New workspace…* chose a
  name already taken. Its unit test passed because the test's disk did not hold that name. The rule: a test of
  "after X lands" puts on the disk what would make the wrong order visible.
- **I trusted the list's selection as the person's.** The first live run showed the echo undoing a selection the
  component had just made (FN-17). The rule: a widget's message after code changed that widget is suspect until the
  widget's docs say otherwise.
- **I put the card rename field's typing in the window's history without thinking about it**, and undo stopped at
  it (FN-18). The rule: a field that comes and goes gets its own history.
- **I left a stray helper method at the end of a new class.** It had no purpose, and I deleted it before the build. The
  rule: read a new file through once before compiling it.
- **Shell editing failed four ways:**
  - A heredoc with an apostrophe ate a whole command.
  - Python is not installed here.
  - A `perl` substitution anchored with `^` matched nothing, because it lacked `/m`.
  - A Windows path in a Java string lost its backslash (M1's rule, broken again).

  The rule: new files and multi-line Java go in with the Write and Edit tools, and every scripted edit is checked by
  a grep.
- **`window open` straight after Ctrl+O failed**, because the dialog waits for a job. A script waits for a window
  that a job opens.

### What to keep exactly as it is

- **One disk job in flight, and a save waiting as a name.** Renames, saves and deletes of one file can never pass
  each other, several edits in a row are one write, and a rename that lands takes a waiting save with it. Thirteen
  component tests and a ten-step live undo/redo agreed.
- **The journal holding only numbers in the history** (Vexplore's `Journaled`). What a step did stays the component's,
  so a delete's stash, learnt only when the job answers, is kept up to date at no cost to the window.
- **`Turn` on every job request and reply.** One `Moved` step serves a rename, its undo and its redo, and a refused
  undo says "Could not undo" without a branch of its own.
- **A home of the run's own for every live check, and the files read after each step.** The CSV, the scripts and
  `.deleted` on disk are what showed the undo was real, not only drawn.
- **Proposing the page before building it.** All three of its questions were answered "as recommended", and the build
  departed from it only where the widgets did (FN-16 to FN-20).

## M3 · settings · 2026-11-07

One finding (FN-13), a missing knob: the framework's own key-bindings window is the one window a message cannot open.
The milestone's other surprise was the processor refusing a job with changing state, which was the framework working
as meant, and it moved the save order into the frame where it belonged.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| FN-13 | A window is opened by its key | Not the framework's key-bindings window | *Key bindings…* left out of the Terminal menu |

### What would stop each

1. The key-bindings window declared through `Windows`, so a `WindowCommand` opens it (FN-13).

### My own mistakes

- **I sketched a job that dropped older saves by remembering the newest.** The processor refused it at compile time,
  with the fix in its message. The rule: anything that must happen in order belongs to the component that sends the
  requests, never to a job.
- **A scripted edit to `Recipes` silently matched nothing** because the file's line endings had changed when git
  committed it, and the build's error ("nothing provides SettingsPage") was the first sign. Afterwards I checked
  every scripted edit had landed and found one more that had not. The rule: after a scripted edit to a committed
  file, grep for the result before building on it; better, use the Edit tool.
- **The first live run's settings window broke on a long path.** The tests never put one in. The rule: a field
  showing a path is tested live with a long one.
- **Twice a run died on a stack class missing**, because another session was installing `vexelray-gui-core` and
  `-widget` as I ran. M0's rule held: compare the jar's time with the crash before touching code; rebuild against
  what is installed, then relaunch.
- **I nearly ran the live check against the real `~/.mainframe`,** which a 0.1 MainFrame also reads. A home of the
  run's own (`-Duser.home` through `app.jvmArgs`) kept it untouched, and let the run start from a 0.1-style file.

### What to keep exactly as it is

- **The frame holding the settings.** A click in the window and a chord in the main one are one message and one
  save, and the window and the terminals both follow what the frame says, so neither can disagree with the file.
- **0.1's keys.** A 0.1-style file was read as it was, which no migration code would have made as certain.
- **A home of the run's own for a live check**, and a relaunch as part of it: it is what showed the settings come
  back.

## M2 · tabs · 2026-11-05

Three findings (FN-10 to FN-12), none architectural: each is a widget without a place an application needs, and
each was met by building around it in `-gui`, with no change to the frame or the messages. FN-7 was closed as no gap.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| FN-10 | A tabbed window from the widget library | Nowhere to put a +, a pill or a rename field | Floated in a box round the tabs, at its right end |
| FN-11 | Every gesture a view hears is declared | A header's double-click is only the tree's clicks | A mailbox opened by hand on `gui.clicks()` |
| FN-12 | A menu bar teaches its chords | A menu line has no chord | The Terminal menu without its shortcuts |

### What would stop each

1. A trailing slot in `Tabs`, and an in-place header edit that says what was typed (FN-10).
2. `Tabs.ACTIVATED` for a header double-clicked (FN-11).
3. A menu item naming the key-map action it stands for, its chord read from the key map (FN-12).

### My own mistakes

- **I asserted "nothing asked" before taking what the frame sent, with a `took` that forgot everything.** Five red
  tests that were the test's fault, and a debug print to find it. The rule: a helper that consumes must consume only
  what it was asked for.
- **I wrote two Esc actions and learnt the key map refuses a shared default in one place.** Every action starts
  application-wide until moved; declaring the field's first and moving it at once fixed it. The rule: read
  `Keymap.action`'s throws before declaring a chord twice.
- **I reached for a header's words (`Node.text()`) after learning in M1 that a node's words cannot be read back.**
  The rule: what the view sets, the view keeps.
- **A heredoc with a quote in it ate a whole command, twice in this conversation.** Test files go in with the file
  tools.
- **I tried `ottermate window 2` and then a separate call**, which is a new connection with no window chosen. A
  script keeps the choice.

### What to keep exactly as it is

- **Closing as the template's chain.** Two ways in, one question, held for the person, every gate answered once:
  twelve frame tests covered every way out before the window was ever opened, and the live run did what they said.
- **The frame deciding and the view forwarding.** M2 added five messages and seven steps, and no step touches a
  widget; the view's departures (FN-10, FN-11) changed nothing in `-core`.
- **The proposal before the look** (docs/passthrough.md). It was accepted as written, and building it was then
  only a matter of what the widgets could do.

## M1 · one tab, one shell · 2026-11-05

Two findings (FN-8, FN-9), both architectural in the same way: the 0.2 rule that an edge is never lost
(T4.2) turns any producer faster than its consumer into a halt, and the framework leaves pacing to the producer. FN-9
was the milestone's one real bug, and it was found only because the plan said to measure.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| FN-8 | A view hears what its widgets say | A frame's typing must fit 64 slots | One `ottermate type` of 150 characters halted the app |
| FN-9 | A source sends what it reads | Faster than a view hears, it halts the app | `type` of 8 MB: dead in 250 ms; a `Shown` per `Output` and two in flight fixed it |

### What would stop each

1. A widget's per-frame output as one message, or a view mailbox drained in the frame it is filled (FN-8).
2. Flow control as the framework's: a sender told when its hearers have room, or a topic that appends to the batch
   not yet taken (FN-9). Every fast reader (a terminal, a log tail, the Inspector's link) writes it otherwise.

### My own mistakes

- **I wrote the M1 plan's measurement as "what does drawing per feed cost"** without asking what happens first
  when a producer outruns a consumer under T4.2. The answer was in components.md §11 all along. The rule: for any
  source, ask what its hearer's mailbox does at ten times the expected rate before measuring anything finer.
- **My first live check of the halt was one long `ottermate type`**, which found FN-8 instead of what I was looking
  for, and cost a run. The rule: type a long line in pieces of under 64 characters until a script can say more.
- **Paste-as-heredoc ate the backslashes** of a Windows path in a Java test, twice, and the second fix was the same
  as the first. The rule: Java strings with backslashes go in with the Edit tool.
- **The first pacing draft kept waiting text until the next read.** A shell's prompt would have sat in the buffer.
  Caught while writing it, not by a test. The rule: in a credit scheme, a returned credit must flush what is waiting.

### What to keep exactly as it is

- **Measuring before deciding.** The plan said to measure the per-feed draw before changing the widget; the
  measurement found the halt, and then showed the widget needs no change at all (two feeds a frame, 1.5 ms frames).
- **The pacing in the source, on the source's threads.** Nothing waits on the GUI thread or the logic lane, and the
  program waits on its terminal exactly as on a slow console.
- **A no-screen baseline beside the live number.** ConPTY's own 2.9 s is what made 4.0 s readable.

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
