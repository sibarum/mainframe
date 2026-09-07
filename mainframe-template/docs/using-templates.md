# Starting a project, from MainFrame

Two commands: **`templates`** shows what you can start from, **`new`** starts one.

```
~ > new vexel-desktop
```

That is the whole thing. It puts up a form, asks where the project goes, checks every answer, shows you what it
is about to write, and writes it. Five minutes later you have a VexelRay application that builds, tests and
opens a window.

Everything below is what happens in between, and what to do when it says no.

> The transcripts here are real output. The destination is shortened to `~/Documents/GitHub` so the lines fit,
> and the folder listing under [Choosing the folder](#choosing-the-folder) shows what a checkouts directory
> would hold rather than the temporary one it was captured in.

---

## Contents

- [Is it here?](#is-it-here)
- [Seeing what there is](#seeing-what-there-is)
- [Filling in the form](#filling-in-the-form)
- [Choosing the folder](#choosing-the-folder)
- [What you get](#what-you-get)
- [Looking before you leap](#looking-before-you-leap)
- [Answering without the form](#answering-without-the-form)
- [When it says no](#when-it-says-no)
- [Keeping answers for next time](#keeping-answers-for-next-time)
- [Flags, in one place](#flags-in-one-place)

---

## Is it here?

`new` and `templates` are not built into every MainFrame — they arrive with the `mainframe-template`
capability. The one-executable MainFrame (`mainframe.exe`) has them. If you are embedding MainFrame yourself,
they arrive with one call; see [the module README](../README.md#installing-it).

To check, ask:

```
~ > help new
new -- start a project from a template

usage  new [template] [--in path] [--set record] [--into] [--no-ask] [--dry-run]
in     any (from the pipe)
out    table
does   creates or updates files (supports --dry-run)

arguments
  template  which template; you are asked if you name none  [string, optional]

flags
  --in=path     the folder to create the project in, instead of being asked
  --set=record  answers, so they are not asked for: --set={artifactId: "plotter"}
  --into        allow a folder that already exists; existing files are still never replaced
  --no-ask      do not put up a form -- use what was given, and refuse if short
  --dry-run     show what would happen, change nothing

examples
  new vexel-desktop
  new vexel-desktop --in=~/Documents/GitHub
  new vexel-desktop --in=~/src --set={artifactId: "plotter"} --no-ask
  new vexel-desktop --dry-run
  form-recall my-defaults | new vexel-desktop
```

`new` is an ordinary MainFrame command, which is worth knowing because it means every habit you already have
works on it: `help new`, `--dry-run`, the same error format, the same confirmation before anything is written.

## Seeing what there is

```
~ > templates
start one with: new vexel-desktop
id             title                         summary                            files  from
vexel-desktop  VexelRay desktop application  a window on vexelray-gui, wired ~  15     vexel-desktop
```

One ships today: **`vexel-desktop`**, a VexelRay desktop application wired the way
[`calculator-vexel-demo`](https://github.com/sibarum/calculator-vexel-demo) — the reference implementation of
the stack — is wired.

To see what a template will ask you before you start:

```
~ > templates vexel-desktop
VexelRay desktop application -- a window on vexelray-gui, wired the way the reference implementation is
fill it in with: new vexel-desktop
name               label               holds   required  default               help
where              create it in        path    true                            the folder the proje~
artifactId         project name        string  true      my-app                lower case, dashes b~
groupId            group               string  true      dev.example           your Maven groupId, ~
summary            one-line summary    string  false     A VexelRay desktop ~  goes in the pom and ~
packageName        java package        string  false                           leave blank for <gro~
className          main class          string  false                           leave blank for a na~
title              window title        string  false                           leave blank to use t~
width              window width        int     true      1180                  in the engine's logi~
height             window height       int     true      720
vexelrayVersion    vexelray version    string  true      0.1.0-SNAPSHOT        the version of vexel~
tactrollerVersion  tactroller version  string  true      1.0-SNAPSHOT
tests              starting tests      bool    false     yes                   unit tests for the s~
```

Both of these are **tables**, not printed text, so the usual shaping works:

```
~ > templates vexel-desktop | where required == true | select name default
~ > templates | select id summary
```

Only three answers have no default and no way to work themselves out: **where**, **artifactId** and
**groupId**. Everything else can be left alone.

## Filling in the form

`new vexel-desktop` with nothing else asks for each field in turn.

**In a MainFrame window**, the whole form goes up at once as a panel — Tab between the fields, and the "create
it in" field gets a Browse button that opens your system's folder chooser.

**On a terminal**, it is printed downwards, one field at a time. That is the transcript below. Same fields,
same rules, same answers — only the asking differs.

```
~ > new vexel-desktop
========================================================================
 VEXELRAY DESKTOP APPLICATION
------------------------------------------------------------------------
 12 fields, asked one at a time
 !back the field before, !cancel the whole form
========================================================================

 CREATE IT IN ................................................. required
   the folder the project folder goes inside -- your checkouts directory, a folder to choose
   !browse looks for one, or type the path
 > ~/Documents/GitHub

 PROJECT NAME ................................................. required
   lower case, dashes between words; it is the folder name and the Maven artifactId,
   matching [a-z][a-z0-9]*(-[a-z0-9]+)*
   blank keeps my-app
 > plotter

 GROUP ........................................................ required
   your Maven groupId, e.g. dev.vexelray.demo, matching [a-z][a-z0-9_]*(\.[a-z0-9_]+)*
   blank keeps dev.example
 > dev.acme

 ONE-LINE SUMMARY ............................................. optional
   goes in the pom and the README, at most 160 characters
   blank keeps A VexelRay desktop application., !clear empties it
 >
 ...
```

While you are in it:

| you type | what happens |
| --- | --- |
| *nothing, then Enter* | takes the default shown on the line above (`blank keeps my-app`) |
| `!browse` | opens the folder chooser — see [below](#choosing-the-folder) |
| `!back` | back to the field before, to change an answer |
| `!clear` | empties an optional field that has a default |
| `!cancel` | abandons the whole form; nothing is created |

An answer that will not do is refused there and then, with the reason, and it asks again — you cannot get to
the end of the form and *then* find out.

At the end it shows you everything before it does anything:

```
========================================================================
 VEXELRAY DESKTOP APPLICATION  what you entered
------------------------------------------------------------------------
 where              ~/Documents/GitHub
 artifactId         plotter
 groupId            dev.acme
 summary            A VexelRay desktop application.
 packageName        (not given)
 className          (not given)
 title              (not given)
 width              1180
 height             720
 vexelrayVersion    0.1.0-SNAPSHOT
 tactrollerVersion  1.0-SNAPSHOT
 tests              true
========================================================================
 submit? [Y/n]
```

`(not given)` is not a gap. Those three work themselves out from what you did type — `packageName` becomes
`dev.acme.plotter`, `className` becomes `Plotter`, `title` becomes `Plotter`. Type them yourself only when you
want something other than the obvious.

## Choosing the folder

`!browse` at the "create it in" field — or the Browse button, in a window:

```
 > !browse
  CHOOSE A FOLDER  ~/Documents/GitHub
     1) [calculator-vexel-demo]
     2) [mainframe]
     3) [text-editor-vexel-demo]
    3 things
    a number goes there, .. goes up, a path goes straight to it
    blank takes this folder, !cancel goes back to the field
  pick>
```

A number goes into that folder, `..` goes up, a path jumps straight somewhere, **blank chooses the folder you
are looking at**, and `!cancel` goes back to typing the path by hand. You are choosing the folder the project
folder goes *inside*, not the project folder itself — that gets made for you, named after the project.

## What you get

```
then: cd plotter && mvn compile exec:exec
did
make plotter
write plotter/pom.xml
write plotter/.gitignore
write plotter/README.md
write plotter/docs/framework-notes.md
write plotter/docs/TODO.md
write plotter/src/main/java/dev/acme/plotter/Plotter.java
write plotter/src/main/java/dev/acme/plotter/Ui.java
write plotter/src/main/java/dev/acme/plotter/Model.java
write plotter/src/main/java/dev/acme/plotter/Doc.java
write plotter/src/main/java/dev/acme/plotter/Look.java
write plotter/src/main/java/dev/acme/plotter/Type.java
write plotter/src/main/java/dev/acme/plotter/Landmarks.java
write plotter/src/main/java/dev/acme/plotter/Capture.java
write plotter/src/test/java/dev/acme/plotter/ModelTest.java
write plotter/src/test/java/dev/acme/plotter/LookTest.java
```

Then, in a terminal of your own:

```
cd plotter
mvn compile exec:exec       # the window
mvn test                    # the tests it came with
```

A window comes up with a title bar, a card, a counter and two buttons. It is deliberately small — the point of
the starting tree is that every seam is already wired correctly, not that it does something. Read
`plotter/README.md` next; it explains which file is for what, in the order worth reading them.

The two files most worth knowing about:

- **`docs/framework-notes.md`** — where to write down anything the framework made you work around. Write it
  when you write the workaround, not in a retrospective.
- **`docs/TODO.md`** — starts with the three things you are actually meant to do first.

## Looking before you leap

`--dry-run` is honest here for the same reason it is honest everywhere in MainFrame: nothing is written until
the whole plan is worked out, so the plan is not a guess.

```
~ > new vexel-desktop --in=~/Documents/GitHub --set={artifactId: "plotter", groupId: "dev.acme"} --no-ask --dry-run
dry run create 16 items -- nothing was changed
  would make plotter
  would write plotter/pom.xml
  would write plotter/.gitignore
  would write plotter/README.md
  would write plotter/docs/framework-notes.md
  would write plotter/docs/TODO.md
  would write plotter/src/main/java/dev/acme/plotter/Plotter.java
  ...
  then: cd plotter && mvn compile exec:exec
```

## Answering without the form

Three ways in, and the same rules hold all three — a project made by a script is the project you would have got
by hand.

```
new vexel-desktop --in=~/Documents/GitHub                       # answer the rest at the form
new vexel-desktop --in=~/src --set={artifactId: "plotter"} --no-ask
echo {artifactId: "plotter", where: ~/src} | new vexel-desktop --no-ask
```

`--set` takes a **record**, in MainFrame's own notation, so answers keep their types:

```
--set={artifactId: "plotter", groupId: "dev.acme", width: 900, height: 600, tests: false}
```

> **A flag's value goes after `=`, never after a space.** `--in=~/src` binds; `--in ~/src` does not — MainFrame
> reads the path as a separate argument. This catches everybody once.

**`--no-ask` means what it says.** Without it, anything you did not supply is asked for. With it, nothing is
asked and anything still missing is an error — which is what you want in a script, because a script has nobody
to ask. If there is no terminal and no screen and you left `--no-ask` off, it tells you so rather than guessing.

Answers you do not give fall back to the defaults in `templates vexel-desktop`, on this path as well as at the
form. `--no-ask` gets you the project someone would get by pressing Enter through every field.

## When it says no

Everything is checked before a single byte is written, so a refusal always means **nothing happened**. If a
write does fail halfway — a full disk, a permission — the step that failed removes what the earlier ones made,
so you never get half a project that looks like a whole one.

### The folder already has something in it

```
~ > new vexel-desktop --in=~/Documents/GitHub --set={artifactId: "plotter"} --no-ask
error[E1502] ~/Documents/GitHub/plotter already has things in it
help to add a project to a folder that already has things in it, use --into
help nothing that is already there is ever written over, either way
```

`--into` is for the case where you made the folder yourself, or `git clone`d an empty repository into it.

### One of your files is in the way

```
~ > new vexel-desktop --in=~/Documents/GitHub --set={artifactId: "plotter"} --no-ask --into
error[E1506] 5 of these files are already in plotter
help pom.xml
help .gitignore
help README.md
help docs/framework-notes.md
help docs/TODO.md
help nothing that is already there is written over -- there is no flag for it
help move them, or point this somewhere else
```

**There is no flag for this, on purpose.** Every other mistake `new` can make is undone by deleting the folder
and running it again. Writing over a file you wrote is not. Move them, or point it somewhere else.

### An answer will not do

```
~ > new vexel-desktop --in=~/Documents/GitHub --set={artifactId: "Plot Viewer"} --no-ask
error[E1504] project name has a "P" in it, and Maven takes lower-case letters, digits, and - . _
```

Caught before anything is written, including the ones a pattern cannot catch — a package part that happens to
be a Java keyword (`dev.new.app`), a class name starting with a digit, a name Maven would not take. When more
than one answer is wrong you get the whole list, not the first one.

### It will not build yet

This one is a **warning, not a refusal** — the project is created, and it tells you why it will not compile.

```
  this will not build yet -- 3 libraries it needs are not in your local Maven repository:
    dev.vexelray.gui:vexelray-gui-widget:9.9.9
    dev.vexelray.gui:vexelray-gui-krono:9.9.9
    dev.vexelray.gui:vexelray-gui-automation:9.9.9
    run mvn install in each of those projects first
```

The VexelRay stack is installed locally rather than downloaded, so the commonest reason a fresh project does
not build is a version nobody has run `mvn install` for. `new` looks in `~/.m2/repository` for everything the
template declares and says so up front, rather than letting you find out in a wall of Maven output. It is a
warning because you may be about to install it, or building somewhere else — check the version in
`templates vexel-desktop` against what you actually have.

## Keeping answers for next time

Your group, your checkouts folder and your stack versions are the same for every project you start. Save them
once with MainFrame's ordinary form store:

```
~ > echo {groupId: "dev.acme", where: ~/Documents/GitHub} | form-save house
start from it next time with: form <fields> --prefill=house
did
save the answers saved as house (2 fields)
```

Then start from them:

```
~ > form-recall house | new vexel-desktop
```

The saved answers fill themselves in and the form asks you only for what is left. Add `--set` alongside to
override one of them, or `--no-ask` to skip the form entirely:

```
~ > form-recall house | new vexel-desktop --set={artifactId: "sketchpad"} --no-ask
```

`form-recall` on its own lists everything you have saved; `form-forget house` drops one.

## Flags, in one place

| | |
| --- | --- |
| `--in=<folder>` | where the project folder goes, instead of being asked |
| `--set={...}` | answers as a record, so they are not asked for |
| `--into` | the destination folder may already exist and have things in it |
| `--no-ask` | do not put up a form; use what was given and refuse if short |
| `--dry-run` | show the whole plan, change nothing |

And the rule that has no flag: **a file that is already on disk is never written over.**

---

Writing a template of your own — the manifest format, and where to put one — is in
[the module README](../README.md#writing-a-template).
