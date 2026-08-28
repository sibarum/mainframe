# The panel protocol

How MainFrame drives a screen it does not own.

MainFrame has no cursor addressing and no key handling — see *Not there yet* in
the [README](README.md). Rather than grow a terminal library, it borrows a
display: an editor, or any program that can fill a rectangle with text, renders
what MainFrame describes and reports what the person did to it.

This is the 3270 arrangement, and it is worth saying why rather than treating it
as nostalgia. A 3270 terminal received a datastream describing where the fields
were, painted it, handled typing *within* a field itself, and sent back the
fields that changed plus the key that ended the transaction. It knew nothing
about what any of it meant. That split is why the terminal could stay simple for
fifty years while the software behind it changed completely, and it is the split
this protocol makes.

## The shape of it

```
                    {"screen": {...}}   describe a screen
   MainFrame  ----------------------->  Editor
              <-----------------------
                    {"event": {...}}    say what happened to it
```

MainFrame sends a screen and waits. The editor paints it, lets the person type,
click and tab about inside it, and sends one event back when something happens
that MainFrame has to decide about. Then MainFrame sends the next screen.

Two rules follow from that, and everything else in this document is a consequence
of them:

**The editor holds no meaning.** It knows about rows, columns, a handful of
primitive parts and a handful of style names. It does not know what a form is,
what validation is, or what any field is for. Adding a feature to MainFrame must
never require a new editor.

**MainFrame holds no pixels.** It says "row 4, column 12"; the editor decides how
wide a character is. Nothing in the protocol is measured in anything but
character cells, which is what makes the same screen renderable by an editor, a
web page, or — one day — a real terminal.

## Framing

One JSON document per line, UTF-8, in both directions. No headers, no length
prefixes: a newline ends a message.

`mainframe --panel` speaks this and nothing else on its standard streams. That is
deliberate — there is no marker to hunt for and no interleaving with ordinary
output, because in panel mode there is no ordinary output. Every line out is a
message; every line in is a message.

JSON is not a second format MainFrame had to learn. Any JSON document is already
a MainFrame expression (see *JSON is a subset of the language* in the README), so
a screen is a MainFrame record and an event is a MainFrame record. They can be
saved, replayed, compared and tested with the commands that already exist.

## Editor to MainFrame

### hello

The first line the editor sends, before anything else.

```json
{"hello": {
  "editor": "acme-edit 1.4",
  "protocol": 1,
  "size": {"rows": 40, "cols": 120},
  "can": ["entry", "choice", "action", "box", "click", "resize", "pick"]
}}
```

`can` is the whole of capability negotiation. MainFrame renders down to what is
offered: an editor that does not list `choice` gets a plain `entry` with the
options written above it, and never has to know it missed anything.

### event

```json
{"event": {
  "screen": 7,
  "did": "submit",
  "key": "F12",
  "focus": "tier",
  "fields": {"name": "Ada Lovelace", "email": "ada@example.com", "tier": "team"}
}}
```

| | |
|---|---|
| `screen` | the id of the screen this is about, so a late event is discardable |
| `did` | `submit`, `cancel`, `click`, `change`, `exit`, `resize` |
| `key` | which key ended it, when one did: `Enter`, `Esc`, `F1`–`F24`, `Tab` |
| `focus` | the field the cursor was in |
| `on` | for `click` and `change`, the name of the part it happened to |
| `fields` | the current value of every entry on the screen, as text |

`fields` carries **all** the entries, not the changed ones. Diffing is an
optimisation, and an optimisation that can disagree with itself is not worth the
bytes; a whole screen of text fields is a few hundred bytes.

Values arrive as text. MainFrame declared what type each field holds when it sent
the screen and will read the text back as that type — the editor never has to
know what a size or a moment is.

### bye

```json
{"bye": {}}
```

The person closed the panel. MainFrame treats it as a cancel, the same as running
out of input does today.

## MainFrame to editor

### screen

```json
{"screen": {
  "id": 7,
  "title": "NEW CUSTOMER",
  "size": {"rows": 18, "cols": 72},
  "focus": "name",
  "keys": [{"key": "F12", "does": "submit", "text": "Submit"},
           {"key": "F3",  "does": "cancel", "text": "Cancel"}],
  "parts": [...]
}}
```

`size` is what MainFrame laid out for. An editor with more room may leave the
rest blank or centre it; an editor with less should scroll rather than reflow,
because MainFrame placed those cells deliberately.

### print, done

For output that is not a screen — a command's result, a note, an error.

```json
{"print": {"text": "12 files, 4.2 MB", "style": "plain"}}
{"done": {"exit": 0}}
```

## The parts

The closed set. Every part has `at`, which is `[row, col]`, one-based, from the
top left of the screen.

```json
{"at": [2, 4],  "text": "FULL NAME", "style": "label"}
{"at": [2, 20], "entry": "name", "width": 30, "value": "Ada", "style": "entry"}
{"at": [4, 4],  "box": [9, 64], "text": "CONTACT", "style": "frame"}
{"at": [12, 20], "choice": "tier", "of": ["free", "team", "enterprise"], "value": "team"}
{"at": [16, 4], "action": "add-phone", "text": "+ Add another", "key": "F5"}
```

| part | what it is |
|---|---|
| `text` | characters at a position. The only part that is always available. |
| `entry` | somewhere to type. The value of `entry` is the name it reports under. |
| `choice` | one of a fixed list. Render as a dropdown, a radio group, or a menu. |
| `box` | a frame `[rows, cols]` from `at`, with an optional caption in its top edge. |
| `action` | something to click. Reports `did: "click"` with `on` set to its name. |

An entry may also carry:

| | |
|---|---|
| `locked` | `true` — show it, do not let it be typed in |
| `hint` | a line to show beneath it |
| `notify` | `"change"` to report every edit, not just on submit |
| `holds` | `"string"`, `"int"`, `"size"`, `"time"`, … — a hint for the editor's own keyboard, never a rule. MainFrame validates. |
| `pick` | `"file"`, `"folder"` or `"save"` — offer a file chooser for this entry. Only sent to an editor that claimed `pick`. |

### Choosing a file

`pick` is an offer, and the entry stays an entry. An editor that claimed `pick`
opens whatever chooser it has — a native file dialog, its own file tree — and puts
the result in the entry as text; the value comes back in `fields` like anything
else. The three words are three different questions: `file` and `folder` want one
that is there, `save` wants a name that need not be.

```json
{"at": [6, 20], "entry": "jdk-home", "width": 40, "value": "", "holds": "path", "pick": "folder"}
```

An editor that did *not* claim `pick` is never sent the key, and is not missing
anything: MainFrame browses for the file itself, by sending an ordinary screen of
`text`, `entry` and `action` parts and reading the clicks back. So a file chooser
arrived without a single editor being touched, which is rule four and the reason
the whole document is arranged this way. Claim `pick` when you have something
better than that to offer, and not otherwise.

## Styles

A style is a **name**, never a colour. The editor maps it to its own palette, so
a panel looks like the editor rather than like a screenshot of one.

`title` · `label` · `entry` · `entry-focus` · `entry-locked` · `frame` ·
`hint` · `error` · `status` · `action` · `plain`

## The rules that keep it evergreen

These are the whole of the compatibility story. They matter more than the
vocabulary above, because the vocabulary will grow and these will not.

1. **An unknown key in any object is ignored.** Never an error. This is a
   deliberate inversion of how MainFrame treats everything else — a form spec
   with an unknown key gets a "did you mean", because that is a person making a
   mistake now. A protocol message with an unknown key is a newer version talking
   to an older one, which is not a mistake at all.
2. **An unknown part is skipped**, and its cells left blank. A screen with one
   part the editor does not recognise is still mostly a screen.
3. **An unknown style renders as `plain`.**
4. **The editor announces what it can do.** MainFrame renders down to it. Adding
   a part to this document must never break an editor that predates it.
5. **Silence is never meaningful.** If MainFrame needs to know something, it asks
   for it — `notify`, an `action`, a key in `keys`. An editor that reports only
   what it was asked to report is a correct editor.
6. **The editor never validates.** It may help — a numeric keyboard for
   `holds: "int"` — but MainFrame decides what is acceptable and says so by
   sending a new screen with an `error` on it. There is exactly one place the
   rules live.

## What an editor has to implement

Painting text at a row and column, an entry the person can type in, and sending
one event back. That is the whole of it. `box`, `choice`, `action`, mouse
clicks and resize are all optional — leave them out of `can` and MainFrame will
manage without them.

An editor that supports only `text` and `entry`, reports `submit` and `cancel`,
and ignores everything else is a conforming editor.
