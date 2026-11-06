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
