# Entries that do more than hold text

**Status: a proposal. Nothing here is built.** Two additions to the data entry framework, written up so the
decisions can be argued about before any code moves. The second one touches
[PROTOCOL.md](../../PROTOCOL.md), which is why it is a document rather than a commit.

| | |
|---|---|
| **Open choices** | a dropdown you can also type your own answer into |
| **Expression fields** | a field whose content is an expression — `${~pwd}/${projectName}` — showing what it evaluates to except when you are in it |

They are written up together because they are the same question asked twice: what else can an entry be, without
the protocol growing a part for every idea anyone has.

---

## What is already there

Worth stating first, because three of the four things these need turn out to exist already.

**A dropdown is `choose:`, and it works today.** A string field with `choose: ["free", "team", "enterprise"]`
becomes a `choice` part on a panel — the protocol says "render as a dropdown, a radio group, or a menu" and the
editor decides which. An editor that did not claim `choice` gets the options on a hint line and a plain entry,
and never learns it missed anything. On a terminal it is a numbered list, and a number or the text both answer
it. Nothing below changes any of that.

**`FormPanel` already keeps what was typed, apart from what it was read as.** `asTyped` holds the raw text of a
field whose reading failed, so somebody being told their answer is wrong can still see the answer they gave.
Expression fields are that idea taken seriously: the durable thing is the text, and what is shown is derived
from it.

**The protocol already carries focus.** Every event has `focus`, "the field the cursor was in", and
`FormPanel` already tracks it (`if (event.focus() != null) focus = event.focus()`). Nothing new is needed to
know where the cursor is *when an event arrives*.

**`notify` is specified and not implemented.** PROTOCOL.md documents `notify: "change"` on an entry — "report
every edit, not just on submit" — but `Screen` has no way to set it and `FormPanel` never asks for it. So today
a form hears from the editor only on submit, cancel, click and resize. This is a hook that was designed and
never built, not a protocol change. Expression fields need it.

---

## Part 1 — open choices

A list of the usual answers, that does not stop you giving an unusual one.

```
form [{name: "licence", choose: ["MIT", "Apache-2.0", "GPL-3.0"], open: true,
       match: "[A-Za-z0-9.-]+"}]
```

### What changes

| | |
|---|---|
| `Form.Field` | a `boolean open`, meaningful only alongside `choose` — refused otherwise, the way `secret` refuses the combinations it cannot honour |
| `Field.problem` | when open, stop enforcing membership and fall through to `min` / `max` / `match` |
| `Field.read` | when open, text that is neither an index nor a listed option is the answer, rather than a refusal |
| `Screen.choice` | carries `"open": true` |
| `FormScreen` | the numbered list, and a line saying an answer of your own is allowed |

The division of labour is the nice part: **`choose` offers the common answers, `match` bounds the uncommon
one.** Without `open`, `match` on a `choose` field is redundant — every acceptable answer is already listed.
With it, the pattern is the only thing standing between the list and anything at all, which is exactly the job
a pattern is for.

### How it degrades

`open` needs a capability (`choice-open` in `can`), and it is the easy kind. An editor that rendered a closed
dropdown for an open choice would make an answer MainFrame accepts impossible to give — so it must not be an
offer an editor can silently ignore. But the fallback is trivial and complete: **a plain entry with the options
on a hint line.** That is what `choice` already degrades to, and here it is not even a loss, because a plain
entry can express every answer the field will take. Better degradation than closed `choice` gets.

### The one wart

On a terminal, `2` means "the second option". For an open field whose own answers might be `1`, `2`, `3`, that
is ambiguous, and there is no escape character to reach for. It is a string field with a `choose` list whose
values look like small integers — rare enough to document rather than design around. **Index wins**, and the
help line says so.

---

## Part 2 — expression fields

### The model

A field holds an **expression**. What you see depends on whether you are in it.

```
        not focused          focused
       ┌──────────────────┐ ┌──────────────────────────┐
where  │ ~/src/plot-viewer│ │ ${~pwd}/${projectName}   │
       └──────────────────┘ └──────────────────────────┘
         what it comes to     what it actually says
```

Editing the field edits the expression. A literal is an expression with no placeholders in it, which evaluates
to itself — so a person who never types a `${` never sees one, and nothing about the ordinary case changes.

### Why this is better than the version I proposed

I had this as a computed *default* with a rule about when it stops following — touched-or-not, cleared-re-arms,
and so on. Every one of those rules is a guess about intent that the person cannot see, and they all fail the
same way: the field stops tracking and nothing on screen says why.

Holding the expression removes the question. There is no "does it still follow?" because **what it does is
written in the field.** If it says `${~pwd}/${projectName}` it follows; if it says `~/src/one-off` it does not;
and you change which by typing, in the place you would look. The mechanism and its explanation are the same
object.

### The four things it unifies

Each of these was a separate feature in the earlier sketch. They are one feature here.

| what you wanted | the expression that does it |
|---|---|
| a default built from other fields | `${groupId}.${artifactId}` |
| shell values | `${~pwd}`, `${~home}` |
| relative paths shown canonical | `./plotter` — a path is an expression whose evaluation resolves it |
| a live default for the folder | `${~pwd}/${projectName}` |

The third is worth dwelling on. `Field.read` **already** resolves a typed path against the working directory
(`SafeFs.resolve(from, typed)`), so a relative path is already canonical in the answer. What you asked for is
that the canonical form be *visible*, and under this model it falls out: the field says `./plotter`, and
because you are not in it, it shows `~/src/plotter`. No path-specific machinery at all.

### The vocabulary

Where the expression comes from is a field key. Two candidates, and they are not the same feature:

| | |
|---|---|
| `compute: "${~pwd}/${name}"` | the field **starts** holding this expression. The person may replace it with anything, including another expression. |
| `default:` unchanged | a fixed starting value, as today |

I would add only `compute`, and let any field's text be an expression once the machinery exists — so a person
can type `${~home}/work` into an ordinary path field and have it mean something. If expressions are only
allowed in fields the form author marked, the feature is much smaller and the rule is harder to state.

**`~` marks a value that is not a field.** A closed set, deliberately:

```
${~pwd}    the working directory
${~home}   the user's home
${~user}   the account name
${~date}   today, as the form's date format writes it
```

Closed rather than open onto the environment. `${~env.AWS_SECRET_KEY}` in a template somebody was handed is a
way to read the environment into a field and then into a file, and the whole reason the template engine's own
substitution refuses to compute is that a template is a thing you can be handed. Add names to this list one at
a time, on request.

---

## What has to change

### `Form`

`compute` joins the field keys. A field with `compute` is otherwise an ordinary field — its type, its `match`,
its `required` all apply, and they apply **to the evaluation**, not to the expression.

### `FormPanel` — the crux

This is where the real work is, and where it can go wrong. The loop already has the right shape: it holds
`answers`, `asTyped` and `focus` across events, and repaints the whole screen each round. What it gains is a
fourth map — the expression per field — and one hard problem.

**The echo problem.** The protocol's whole-screen rule says every event carries the current text of *every*
entry. If MainFrame paints the evaluated text into a blurred field, the editor faithfully sends the evaluated
text back, and `absorb` — which today re-reads every field from `event.field(name)` — would take
`~/src/plot-viewer` as the person having replaced the expression with a literal. The expression would be
destroyed by being displayed.

The fix is reconciliation rather than trust: **MainFrame remembers what it last painted into each field, and a
value that comes back unchanged is an echo, not an edit.** Only text that differs from what was sent is a
change. This is not a new idea in the codebase — it is what `asTyped` is doing already, one field at a time —
but it has to become the rule for every field rather than the exception for a failed one.

It is worth being clear that this is the load-bearing piece. Get it wrong and the symptom is a folder field
that mysteriously stops following the project name, intermittently, depending on what else the person touched.

### `Screen` and the protocol

Three deltas, all small, and the first is finishing something already written down.

**1. Implement `notify`.** `Screen.entry` gains a `notify` argument that emits the documented key. Not a
protocol change — the key is already in PROTOCOL.md and has never had a way to be sent.

**2. Add `notify: "focus"` and `did: "focus"`.** This one *is* a protocol addition, and expression fields need
it more than the earlier design did, because here the displayed text flips when focus moves. Without it, you
would type an expression, Tab away, and go on seeing the raw expression until you happened to do something
else.

It is safe, and the reason is negotiation: an editor sends `focus` only because MainFrame asked for it with
`notify`, and an older MainFrame never asks. So the case that would be bad — an older MainFrame receiving a
`did` it does not know and treating it as a cancel — cannot arise. Rule 5 ("silence is never meaningful")
already anticipates exactly this: if MainFrame needs to know something, it asks.

**3. Mark computed fields with a key, not a style.** You asked for a different shade for values that are
derived and move. Style names are the obvious route and the wrong one: rule 3 says an unknown style renders as
`plain`, so an editor that predates the change would lose the entry styling altogether and the field would look
*less* like an entry, not differently shaded. An unknown **key** is ignored (rule 1), which degrades to exactly
today's appearance.

```json
{"at": [6, 20], "entry": "where", "width": 40, "value": "~/src/plot-viewer",
 "holds": "path", "computed": true, "notify": "focus"}
```

So: `"computed": true`, and an editor that knows about it shades the value however its own palette shades
something derived. What it must not do is make it look uneditable — it is not; you are seeing the answer, and
the expression is one keystroke away.

### `FormScreen` — the terminal

There is no focus on a terminal: fields are asked one at a time, in order, and the question is over when you
press Enter. So the flip has nothing to hang on, and the rendering is the honest one — show both.

```
 CREATE IT IN ................................................. required
   a folder to choose
   ${~pwd}/${projectName}
     → ~/src/plot-viewer
   blank keeps it, !browse looks for one
 >
```

Blank accepts the expression. Typing replaces it, with either a literal or another expression. Because the
fields above have already been answered by the time this one is asked, the evaluation is never a guess — which
is a small advantage the panel does not have.

---

## The hard parts, stated plainly

**Validating a half-typed expression.** `${proj` is not an error, it is somebody mid-word. Live validation has
to be suppressed while a field is focused and run when it is left — which is another thing that wants the focus
event, and another reason the terminal is easier.

**An expression that does not evaluate.** `${nosuch}` is a different failure from an answer that breaks its
field's rules, and should read differently: *nothing here is called nosuch — the fields are name, group,
where.* Cheap to do well, easy to do badly by reporting it as a validation error on the value.

**Two `${}` systems.** The template engine already has substitution — `Answers`, `fill blank`, `fill always` —
evaluated *after* the form is submitted, and with transforms (`type`, `slug`, `path`) that a form default has no
use for. Adding `${}` to the form means the same notation means two things at two times, and the seam between
them is invisible in a manifest. Options, in the order I would consider them:

1. **Leave them separate and name them differently in the docs** — the form's are *live*, the template's are
   *worked out*. Cheapest, and the one that ships.
2. **Let `compute` subsume `fill blank`.** A template slot with `compute` is a field the person can see and
   override, which is strictly better than a fill they never see. `fill always` stays, because a value nobody
   should be asked about should not be on the screen.
3. **Unify.** Most work, and the transforms make it awkward — `${~pwd}` and `type ${artifactId}` are not the
   same kind of thing.

I would do 1 now and 2 when it stops feeling speculative.

**What `form-save` stores.** If a field holds an expression, does saving the answers save `${~pwd}/${name}` or
`~/src/plotter`? Saving the *expression* is more powerful — `form-recall house` in a different directory would
do the right thing — and it changes what a saved answer sheet is, from a record of values to something with
behaviour. That is a real fork and I have no strong recommendation; it is on the list below.

---

## To decide

1. **Expressions everywhere, or only in `compute` fields?** Everywhere is a better rule and a bigger blast
   radius — every existing string field would start interpreting `${`.
2. **Does `form-save` keep the expression or the value?**
3. **Is `${~date}` in the first set?** It is the only one that is different every time you evaluate it, which
   makes a form that redraws twice show two answers.
4. **`compute`, or another name?** It reads like "read-only, derived", which is the opposite of the intent —
   the field is fully editable and the expression is only where it starts. `suggest` or `starts` may be
   truer.
5. **Open choices first, separately?** They share nothing with this beyond both being entries, they need no new
   loop state, and they are perhaps a day's work. There is no reason for them to wait on this.

---

## Worked example

What `new vexel-desktop` would look like with both, on a panel:

```
 CREATE IT IN     [ ~/Documents/GitHub                        ]  [Browse]
 PROJECT NAME     [ plot-viewer                               ]
 GROUP            [ dev.acme                                  ]
 JAVA PACKAGE     [ dev.acme.plotviewer                       ]   ← computed, shaded
 MAIN CLASS       [ PlotViewer                                ]   ← computed, shaded
 LICENCE          [ Apache-2.0                              ▾ ]   ← open: pick, or type
```

Click into JAVA PACKAGE and it says `${groupId}.${slug(artifactId)}` — or whatever the transform question
settles into. Type over it and it is yours, and it says so, because it now says what you typed. Clear it and
retype the expression and it follows again. Nothing to remember, because nothing is remembered.

The manifest lines behind it:

```
slot where
    kind      folder
    compute   ${~pwd}

slot packageName
    compute   ${groupId}.${artifactId}
    rule      java-package

slot licence
    choose    MIT Apache-2.0 GPL-3.0
    open      yes
    match     [A-Za-z0-9.-]+
```

Note `where` is `${~pwd}` and not `${~pwd}/${artifactId}`: it is the folder the project is created *inside*, and
the project lands at `${~pwd}/plot-viewer` either way. If you meant `where` to name the project folder itself
that is a different change — a smaller one — and worth saying which you want.
