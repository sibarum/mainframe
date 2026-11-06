# The passthrough switch, redrawn

The look of the passthrough switch, proposed before M2 builds it, as [rebuild.md](rebuild.md) rules (2026-11-05,
ruling 2): what it does stays, and how it is shown is drawn anew. Names and words are proposals.

## What it does, which stays

While it is on, the key map lets go of every chord (`Keymap.paused(true)`, which vexelray-gui already has for this
case), so every key reaches the shell as if MainFrame held none. Ctrl+C is the interrupt again, Esc reaches vim, and
Ctrl+W deletes a word. Nothing is unbound or saved: off, the chords are back as they were. It is for a stretch of
work in a program that wants the keys (vim, emacs, tmux, a TUI), and is not a setting.

It is the window's (one key map per MainFrame process), and it starts off at every launch.

**The constraint that shapes the look:** while it is on, no chord can reach MainFrame, so **turning it off is by
mouse**, and the way off has to be on screen the whole time it is on. And a window whose shortcuts have stopped
working has to say why without being asked.

## What 0.1 showed, and what was wrong with it

```
┌ ▣ MainFrame ─────────────────────────────────────────────────────── – □ × ┐
│ api │ web │ +  Keys: MainFrame                                            │   off: always there, as text
│ api │ web │ +  Keys: all to shell                                         │   on: the same, lit in the accent
```

- **Off, it was always in the bar**, a phrase read on every glance that says nothing a person needs while it is
  off. That is the part the TODO wanted gone.
- **It was a toggle that looked like a label.** Text with a background reads as a status, not a control; the only
  sign it was clickable was the pointer's feedback.
- **"Keys: MainFrame" names the default by the application's name**, which says nothing to someone who has not
  met the feature.

## Proposed

**Off, nothing shows.** It is turned on from a menu line. **On, a pill stands at the end of the tab bar**, lit,
saying what is happening, and a click on it turns it off.

```
┌ ▣ MainFrame  Terminal ───────────────────────────────────────────── – □ × ┐
│ api │ web │ +                                                             │   off
├───────────────────────────────────────────────────────────────────────────┤
│ PS C:\work\api>                                                           │

┌ ▣ MainFrame  Terminal ───────────────────────────────────────────── – □ × ┐
│ api │ web │ +                         ╭───────────────────────────────╮   │   on
│                                       │ ⌨  Every key to the shell   × │   │
├───────────────────────────────────────╰───────────────────────────────╯───┤
│ ~  vim notes.md                                                           │
```

1. **Where it is turned on: a `Terminal` menu after the lockup** (`TitleBar.menu`, which the framework gives an
   application for its own commands, ruled 2026-11-03; the VexelRay IDE's Run menu is the precedent). M2 needs one
   anyway: its commands (new tab, close, rename, next and previous, Ctrl+O, the text size) are otherwise reachable
   only by chord. The line is **Every key to the shell**, with the keyboard icon, after a rule at the end:

   ```
   Terminal
   ┌──────────────────────────────────────┐
   │ New tab                 Ctrl+Shift+T │
   │ Close tab                     Ctrl+W │
   │ Rename tab              Ctrl+Shift+R │
   │ ──────────────────────────────────── │
   │ Larger text                   Ctrl+= │
   │ Smaller text                  Ctrl+- │
   │ ──────────────────────────────────── │
   │ ⌨ Every key to the shell             │
   └──────────────────────────────────────┘
   ```

   While it is on, the same line reads **⌨ Give MainFrame its keys back**, so the menu is a second way off. It has
   no chord: one could turn it on but none could turn it off, and a chord that only works one way is a trap.

2. **While it is on, the pill.** At the trailing end of the tab bar (application space, unlike the title bar's
   right, which is the framework's badges: `WindowInstrument`'s javadoc), right-aligned so it is not mistaken for a
   tab. The keyboard icon, the words *Every key to the shell*, and a ×, filled in the accent (`Role.ACCENT`, as 0.1's was
   when on), so it reads as lit. Its tooltip: *MainFrame's shortcuts are off while this is on, so
   Ctrl+C, Esc and the rest reach the program. Click to turn them back on.* A click anywhere on it turns it off and
   gives the terminal the keyboard back.

3. **Motion:** the pill fades and slides in from the bar's end and out again (the framework's own fades, with the
   node kept and shown or hidden, never rebuilt), so turning it on is seen happening rather than found later.

4. **Nothing else moves.** The tab bar's height does not change and the terminal is not refitted, so turning it on
   in vim causes no resize and no redraw of the program's screen.

**What a script uses:** the menu line by its label, and the pill as the landmark `passthrough`; `tree` shows
whether it is there.

## Alternatives weighed

- **A band across the top of the terminal while on** ("Every key goes to the shell. [Give them back]"), using the
  notice M4 needs above a terminal anyway. The most visible, but it takes a row, so turning it on resizes the shell,
  and vim, the reason to turn it on, redraws. Turned down for that.
- **The `Terminal` menu's own label lit while on**: no new node, but a menu title that changes colour is easy to
  miss, and its way off is two clicks away. Turned down.
- **A `Toggle` (track and knob) always in the bar**: honest as a control, but always there, which is 0.1's clutter
  with a better shape.
- **A badge at the title bar's right**, where the recording and debug badges stand: the right place for "a state a
  person has to be able to see without asking", but the framework reserves it for its own instruments, and an
  application placing one there is what `WindowInstrument` says must not happen. If the author thinks this is the
  one application state that belongs there, that is a framework question, and the pill is what to build until then.

## What it needs

- The `Terminal` menu in `Ui`, its items sending `Intent`s (a menu is data: each item sends a message), declared
  again when the switch flips so its last line's words follow.
- The pill: one node built once in `Ui`, after the tab bar's headers, shown and hidden by the view.
- `Keymap.paused` from the view, which holds the key map already. The frame does not need to know: nothing it
  decides depends on it, and it is not saved.
- Tests: in `ViewTest`, the menu line pauses the key map and shows the pill, the pill's click resumes it and hides
  the pill, and neither changes anything in one frame (`Cuts`). Then `ottermate`: on from the menu, Ctrl+C reaching
  the shell as 0x03 rather than copying, the pill clicked, Ctrl+C copying again.

## For the author

1. **The pill, shown only while on, at the tab bar's end:** as proposed, or the band, or a title-bar badge (a
   framework question)?
2. **A `Terminal` menu after the lockup** for M2's commands and this switch, or no menus, with the switch turned on
   from somewhere else (the tab's right-click menu, or the key-bindings window)?
3. **The words:** *Every key to the shell* (on) and *Give MainFrame its keys back* (the menu's way off)?

## Ruled

Accepted as proposed by the author 2026-11-05 ("Accept, build M2"): the pill shown only while on, at the tab bar's
end; a `Terminal` menu after the lockup; the words as written.

## As built

Built in M2, 2026-11-05, and seen live: on from the menu, Ctrl+C reaching `ping` as the interrupt, the pill clicked,
the chords back. Where it departs from the proposal:

- **The pill has no keyboard icon.** It is a `Button`, whose label is text only; the words and the × carry it. The
  tooltip is as written.
- **The menu shows no chords.** A `Menu` line has a label and an icon and nothing for a shortcut (FN-12).
- **The pill and the + float at the bar's right end**, in a box round the tabs, since `Tabs` has no place for an
  application's nodes beside its headers (FN-10). So the + does not follow the last header as 0.1's did.
