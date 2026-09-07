# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**${title}** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the retrospective has a candidate rather than a complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

*(none yet)*

## One that comes with the template

### FN-0 · There is no button component 🔬💡

`vexelray-gui-widget` has `TextField`, `Toggle`, `Slider`, `Segment`, `Tabs`, `Rail`, `TitleBar` and a dozen
more — and no button. `Ui.button` in this project is the hand-rolled shape the reference implementation also
uses: a text node, made focusable, given a pointer cursor, a click handler and a hover wash. Four ordinary
calls, none of them wrong, all of them written again in every project on this framework.

Carried in the template rather than left to be rediscovered, because a finding that every project makes
independently is the clearest possible case for a component. Delete this section once there is one.
