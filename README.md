# MainFrame

The shell that runs in GraalVM native-image. Designed to be super easy to use — you can't mess it up.

MainFrame is a shell with its own small command language. Values that travel
through a pipe are *typed* — records, sizes, times, media types — never text to
be re-parsed by eye. Every command declares what it takes and what it touches,
so MainFrame can check your line before running any of it, explain what went
wrong when it can't, and refuse to lose your data by accident.

```
~/projects/site > ls | where size > 1mb and mime =~ "image/" | sort-by size --reverse
name        kind  size    modified          ext  mime        path
hero.png    file  4.2 MB  2026-08-19 09:12  png  image/png   ~/projects/site/hero.png
banner.jpg  file  1.8 MB  2026-08-14 16:40  jpg  image/jpeg  ~/projects/site/banner.jpg
```

There is a guided tour in [`examples/tour.mf`](examples/tour.mf):

```bash
mainframe examples/tour.mf
```

## What is in here

Two modules, split along the line between what MainFrame *is* and what it *looks
like on a particular machine*.

| Module | What it is | Depends on |
| --- | --- | --- |
| [`mainframe-core`](mainframe-core) | The shell: the language, the interpreter, the commands, the embedding API. Zero runtime dependencies, native-image clean, no idea a screen exists. | nothing |
| [`mainframe-vexel-gui`](mainframe-vexel-gui) | MainFrame as a window, on [vexelray-gui](https://github.com/sibarum/vexelray-gui): the console an application embeds, the seams it plugs its own commands and screens into, and a `main()` that boots MainFrame on its own. | `mainframe-core`, vexelray-gui |

The shell is the program. Everything else — an editor, a calculator — is
something it opens, and `mainframe-vexel-gui` is where that list lives. See
[its README](mainframe-vexel-gui/README.md).

## Build

Needs GraalVM (JDK 25) and Maven.

```bash
mvn -pl mainframe-core package
```

That produces `mainframe-core/target/mainframe.jar`, runnable with
`java -jar mainframe-core/target/mainframe.jar`. The shell builds on its own, on
a machine that has never heard of vexelray, and that is meant to keep being true.

A plain `mvn package` at the root builds both modules, which needs the vexelray
stack installed locally.

For the real thing — a single binary that starts instantly:

```bash
mvn -pl mainframe-core -Pnative package
```

That produces `mainframe-core/target/mainframe` (`mainframe.exe` on Windows) — an 18 MB binary
that starts in about 25 ms. MainFrame has **zero runtime dependencies**, so
there is no reflection configuration to maintain and nothing to teach
native-image about.

> **Windows note.** During the build, native-image compiles tiny probe
> executables and runs them. Windows Application Control (Smart App Control /
> WDAC) blocks brand-new unsigned executables, and it always blocks them under
> `%TEMP%` — so the build points them at `target/nitmp` instead. Even there the
> policy check is flaky: if the build fails with
> `CreateProcess error=4551, An Application Control policy has blocked this file`,
> run it again. It usually succeeds within a couple of attempts.

## Running it

```bash
mainframe                    # start the shell
mainframe script.mf          # run a script
mainframe -c "ls | length"   # run one line
```

Global options: `--dry-run` (never change anything), `--yes` (answer yes to
confirmations, for scripts you trust), `--no-color`.

## The five promises

**1. Nothing destructive happens quietly.** Every command that can lose data
plans the whole job first, then either shows it, asks about it, or does it.

```
~/tmp > rm ./build --recurse --dry-run
dry run move to trash 1 item -- nothing was changed
  would trash build (directory, 214 items, 8.1 MB)
  recoverable from ~/.mainframe/trash
```

`rm` moves things to `~/.mainframe/trash`, not to oblivion. It refuses to touch
a directory without `--recurse`, refuses to run unattended without `--yes`, and
in a script — where there is nobody to ask — it stops rather than assuming.
`--dry-run` works on *every* command that changes files, because the framework
provides it, not each command in turn.

**2. Parsing has no surprises.** There is no word splitting and no re-parsing of
output. Two lexical rules make the language unambiguous:

- Binary operators need spaces around them. `a - b` subtracts; `a-b` is one
  name (which is why `sort-by` and `count-by` read the way they do). Writing
  `5-3` is an error that tells you what to do, not a silent guess.
- Text is quoted, and a quoted string is always exactly one argument. Paths and
  globs may be bare — `./src/main`, `*.java` — but they are still one word.

Assignments need `let`. Comparisons don't chain. `>` is greater-than, never a
redirect: writing to a file is `save`, which won't overwrite without `--force`.

**3. Every error says what to do next.** Errors carry a code, the exact span
that caused them, and at least one suggestion.

```
~/projects > ls | where nmae == "x"
error[E313] these rows have no column called nmae
  --> line 1:12
  |
1 | ls | where nmae == "x"
  |            ^^^^
help did you mean name?
help the columns here are: name, kind, size, modified, mime, path
help if you meant the text "nmae", put quotes around it
```

Typos in command names, flags, variables, columns and index names all get a
"did you mean" from the real list of what exists.

**4. Everything is discoverable.** `help` lists every command by category;
`help <command>` — or `<command> --help` — explains one. That text is generated
from the command's signature, so it can never drift from the behaviour. `which`
tells you whether a name is a builtin or a program on your `PATH`, and
`describe` tells you what is actually in the pipe.

**5. A command validates before it runs.** Argument counts, argument types,
flag names, flag types and the shape of the piped input are all checked before
the first side effect. A pipeline that will fail on its last stage fails before
its first.

## Media types are first class

MainFrame decides what a file *is* by looking inside it, and treats the answer
as a real value with `.type`, `.subtype` and `.detected-by` — not a string you
have to pattern-match on.

```
~/downloads > mime ./mystery ./notes.txt
name       mime              detected-by  size    name-suggests  misleading-name
mystery    image/png         content      1.2 MB                 false
notes.txt  image/png         content      847 KB  text/plain     true
```

`ls` carries a `mime` column (from the name, which costs nothing); `--deep` reads
the bytes. `cat` refuses to spray a binary file across your terminal unless you
pass `--force`. Filtering on type is just a comparison:

```
ls --recurse --deep | where mime.type == "video" | sum size
```

## Filesystem indexes

An index is a saved table of file facts you can search instantly, and keep in
step with the disk.

```
~/code > index-build code . --skip=.git --skip=target
~/code > find "*Test.java" --index=code | select name size modified
~/code > index-sync code
  code: 12 added, 3 updated, 1 gone, 8431 unchanged
```

Indexes live in `~/.mainframe/indexes`, one plain-text file each. The skips are
recorded in the index, so `index-sync` repeats exactly what `index-build` did.

Because an index is just a table of rows with a `path` column — the same shape
`ls` and `find` produce — it feeds *any* file command with no conversion step:

```
find --index=code --ext=tmp | rm --dry-run
find --mime="image/" --larger=2mb | cp --to=./big-images
from-index home | count-by ext | first 10
```

`find` works without an index too (it walks the disk), so the index is an
optimisation, never a separate world.

## The environment is live

A running Java process cannot change its own environment, so MainFrame keeps its
own: seeded from the process at startup, edited between commands, and handed to
every program it starts. There is nothing to reload and nothing to restart.

```
~/code/site > env-set EDITOR "code --wait"
~/code/site > path-add ./node_modules/.bin --front
~/code/site > which eslint
name         eslint
kind         external program
path         ~/code/site/node_modules/.bin/eslint.cmd
run-it-with  ^eslint
~/code/site > ^eslint --version
```

`path` shows where programs are looked for, in order, and flags entries that
aren't there — a broken PATH is visible instead of mysterious:

```
~ > path | where missing == true
order  directory                 missing
1      C:\Users\User\bin         true
7      C:\Tools\old-sdk\bin      true
```

Because MainFrame resolves external programs against *its own* PATH rather than
letting the OS use the one this process inherited, `path-add` takes effect on the
next command — and "not found" becomes an error that says where it looked.

A program embedding MainFrame can also install programs of its own, which run in
the JVM instead of being spawned but are still written with a caret. `programs`
lists them, and `which` says so when one stands in front of a real binary — see
[Programs of your own](#programs-of-your-own-tool-in-process).

The guardrails carry over: `path-add` refuses a directory that doesn't exist
(`--force` if you're about to create it), adding the same entry twice does
nothing and says so, and `env-remove` won't unset something like `PATH` or
`HOME` without `--force`.

The environment is a table like everything else, and readable as a live record:

```
env | where name =~ "proxy"
echo $env.HOME
env | where value =~ "node" | get name
```

Commands marked *changes this session* — `cd`, `env-set`, `env-remove`,
`path-add`, `path-remove` — touch nothing on disk, which is why they have no
`--dry-run` to offer.

Two things are read once at startup and not from this environment:
`MAINFRAME_HOME` (moving the trash or the indexes mid-session would be worse than
useless) and `NO_COLOR`.

## Embedding it: your commands, in-process

MainFrame is also a library. A program can hook its own commands into the shell
and get called back with the piped data and the checked arguments — same thread,
no process spawned, nothing serialised in either direction.

```java
MainFrame shell = MainFrame.builder()
        .command(CommandSpec.named("customers")
                        .category("my app")
                        .summary("list customers from the live database")
                        .optional("filter", DataType.TEXT, "only names containing this")
                        .output(DataType.TABLE)
                        .effect(Effect.READS)
                        .example("customers | where spend > 1000")
                        .build(),
                invocation -> Data.table(database.customers(invocation.text(0, ""))))
        .build();

Data top = shell.run("customers | where spend > 1000 | sort-by spend --reverse | first 5");
shell.repl();   // or hand the whole shell to the user
```

`customers` is now a command like any other, so it composes with everything:

```
customers | where spend > 1000 | select name email | to-json | save leads.json
```

**Every command declares itself the same way**, whether it is built in or yours —
so a hosted command gets the whole unified interface without writing any of it:

- arguments and flags are checked against the spec *before* the callback runs
- `help customers` and `customers --help` are generated from the spec
- a typo gets "did you mean customers?" from the real command list
- failures render exactly like MainFrame's own, with a code and hints
- `--dry-run` and confirmation appear on anything that changes data

That last one is the reason a command declares an `Effect`. Anything marked
`WRITES` or `DESTRUCTIVE` must be registered as a `PlannedCommand`: you say what
you *would* do, one step at a time, and MainFrame decides whether to show it, ask
about it, or run it.

```java
.command(CommandSpec.named("drop")
                .summary("drop a table")
                .argument("table", DataType.TEXT, "the table to drop")
                .effect(Effect.DESTRUCTIVE)
                .build(),
        (invocation, steps) -> steps.step(
                "drop the table " + invocation.text(0) + " (" + rows + " rows)",
                () -> database.drop(invocation.text(0))))
```

```
> drop customers
error[E302] drop can lose data, so it will not run unattended
help add --yes once you are sure, or --dry-run to see the one thing it would do
```

Data crosses the boundary with its types intact — a size is a size, a media type
is a media type, a table is rows of named fields — so a callback never parses
text the shell had already understood:

```java
for (Data row : invocation.input().rows()) {
    String name = row.field("name").text();
    long spend = row.field("spend").number();     // a number, not a string
}
```

You can also shape what the shell contains. `without(...)` drops commands that
have no business in your app, and `replacing(...)` deliberately takes over a
built-in name — plain `command(...)` refuses to shadow one, so a future MainFrame
release cannot quietly swallow your command:

```java
MainFrame.builder()
        .command(spec, callback)
        .without("rm", "mv", "cp", "save", "mkdir")   // a read-only shell
        .directory(projectRoot)
        .env("APP_MODE", "live")
        .build();
```

### A shell is also a place to run things

The environment a shell hands to programs, the PATH it searches, and programs of
your own that live in the JVM rather than on disk are one subject, and all three
are live — set a variable and the very next command sees it.

```java
shell.env("JAVA_HOME", jdk.toString());     // every program it starts sees this
shell.pathAddFirst(jdk.resolve("bin"));     // so ^javac is that JDK's javac
shell.pathRemove(oldJdk.resolve("bin"));
shell.onPath("javac");                      // where ^javac would come from, or null
```

The same four things are available to a hosted command (`invocation.env(...)`,
`invocation.pathAdd(...)`) and, on the builder, before anything runs:
`.env(name, value)`, `.pathAdd(dir)`, `.pathAddFirst(dir)`.

### Asking the user for a record

A program embedding MainFrame gets the [form framework](#data-entry) as well, so
it does not have to write a prompt loop, a validator and an error message for
every field it needs. The fields are the same data the `form` command takes, and
the answers arrive with their types intact:

```java
Data answers = invocation.form(Data.of(List.of(
                Map.of("name", "what", "required", true, "min", 4),
                Map.of("name", "priority", "required", true, "default", "soon",
                        "choose", List.of("now", "soon", "later")),
                Map.of("name", "due", "type", "time"))),
        "New task");

if (answers.isNothing()) return Data.nothing();              // they cancelled
tasks.add(answers.field("what").text(),
          answers.field("priority").text(),
          answers.field("due").time());                      // an Instant, not a string
```

Nothing there validates anything, because the fields already did. The same call
is on the shell itself — `shell.form(fields, "New task")` — for a host that wants
to ask outside a command. Either way it needs a person: a shell built without
`.interactive(true)` refuses rather than printing a form nobody will fill in.

To pre-fill from your own data, pass it as the starting record —
`invocation.form(fields, "Edit task", existing)` — and everything in it the form
does not ask about comes back untouched. The
[`--prefill` store](#saving-the-answers-and-starting-from-them-next-time) is there
too, and `.formDirectory(path)` on the builder moves it, for an app whose
preferences belong to the app rather than to the user's shell.

### Programs of your own: `^tool`, in-process

Some things are not commands. A tool has a name people type, flags it parses
itself, text it prints and an exit code at the end — and pretending it is part of
the language does nobody a favour. So register it as a **program** instead, and it
is invoked with a caret like anything on the PATH, while running in this JVM:

```java
.program(ProgramSpec.named("jdk")
                .summary("switch the JDK this session uses")
                .usage("jdk <version>")
                .example("^jdk 25")
                .build(),
        call -> {
            Path home = toolchains.get(call.argument(0, ""));
            if (home == null) {
                call.writeError("no JDK called " + call.argument(0, "") + " is installed");
                return 1;                       // a non-zero exit stops the pipeline
            }
            call.env("JAVA_HOME", home.toString());
            call.pathAddFirst(home.resolve("bin"));
            call.writeLine("JAVA_HOME is now " + home);
            return 0;
        })
```

```
~/code/app > ^jdk 25
JAVA_HOME is now /opt/jdk-25
~/code/app > ^javac --version
javac 25.0.3
~/code/app > ^jdk 17.5
error[E322] jdk failed with exit code 1
help no JDK called 17.5 is installed
help MainFrame does not guess what a failing program meant, so the pipeline stops here
```

The contract is a binary's contract: arguments as plain strings (`--jobs=4`
spelled out, nothing pre-parsed), text out, an exit code, standard error becoming
a warning — or the first hint of the error when the code is non-zero. Two things
come free from there being no process:

- **it can change the session it was run from.** A spawned program gets a copy of
  the environment and its edits die with it; `^jdk 25` really does leave
  `JAVA_HOME` set for everything that follows, as `env-set` would.
- **the piped value arrives with its types intact.** `call.input()` is the text a
  process would have read; `call.inputData()` is the value itself, rows and sizes
  and all, because nothing was serialised on the way in.

Programs are searched *before* the PATH, so one called `git` stands in front of
the real git. Nothing stops you — but nothing hides it either:

```
> which git
name         git
kind         hosted program
summary      run git against the workspace, with our credentials
usage        git <subcommand>
run-it-with  ^git
instead-of   C:\Program Files\Git\cmd\git.exe
```

`programs` lists them, and `help` says they are there, since they are not commands
and so are not in the command list. What they do *not* get is the guardrails: a
caret says MainFrame is not in charge of what happens next, and that is as true of
a hosted program as of a spawned one. `call.dryRun()` tells you the session was
told to change nothing — a well-behaved program checks it and prints what it would
have done — but MainFrame cannot enforce that on code it does not own.

Install and uninstall while the shell is running, too, for an app whose tools
arrive with a plugin or a login:

```java
shell.program(spec, callback);   // takes the place of one already installed
shell.programRemove("jdk");
shell.programs();                // the names, in the order they were installed
```

The public API is the `dev.mainframe.api` package and nothing else:
`MainFrame`, `CommandSpec`, `Command`, `PlannedCommand`, `Invocation`,
`ProgramSpec`, `Program`, `ProgramCall`, `Data`, `DataType`, `Effect`,
`ShellError`. MainFrame's own command line is built on it, which is the cheapest
way to be sure it can carry a whole shell.

There is a worked example in
[`HostApp.java`](mainframe-core/src/test/java/dev/mainframe/api/HostApp.java) — a task list with
a shell in it, including a `task-new` that asks for the details with a form:

```bash
java -cp mainframe-core/target/classes:mainframe-core/target/test-classes dev.mainframe.api.HostApp
```

## The language

```
# comments run to the end of the line

let limit = 10mb                       # variables need let; $limit to read one
ls | where size > $limit               # bare words are column names in a row test
ls | select name size | to-json        # values stay typed until you convert them

if (ls | length) > 100 { echo "busy directory" }
for name in (ls | get name) { echo $name }

ls
  | where kind == "file"               # a pipeline can start a line with |
  | count-by ext

^git status                            # a caret runs an external program
^curl -s https://example.com/x.json | from-json | get items
```

Types: `nothing`, `bool`, `int`, `float`, `string`, `size` (`10mb`), `time`
(`2026-08-21T14:30`), `duration` (`7d`), `path`, `mime`, `list`, `record`,
`table` (a list of records), `block`. `now` is a value, not a command, so it
composes: `where modified > (now - 7d)`.

## JSON is a subset of the language

This is the constraint everything else is designed around, so it is worth stating
plainly: **any JSON document is already a MainFrame expression.** Records are
written `{"name": value}` with the key quoted, lists are `[a, b]`, and the
punctuation is JSON's punctuation. MainFrame's own written form is JSON *plus*
the literals JSON lacks — `4mb`, `7d`, `2026-08-21T14:30:00.000-04:00`,
`path"./x"` — each sitting in value position, where JSON would have a number or a
string. They extend the grammar rather than colliding with it.

The practical consequence is that the two readers cannot disagree, and there is a
test that checks it across every shape JSON can take. `null` is `nothing`,
`A` is `A`, and `1.5e-3` is a number, whichever way you read the file.

It also settles which punctuation is available for anything added later: `{}` and
`[]` are spoken for by JSON, `()` is free because JSON has no parentheses, and
postfix or infix forms are free because JSON has no operators at all.

## The written form is the read form

Every value has one canonical text, that text is valid MainFrame source, and
reading it back gives you the identical value — same value, same type. This is
the rule the rest of the design hangs off, because the consequence is what people
actually do:

```
fetch | filter | save results.csv        # ...come back tomorrow...
open results.csv | aggregate
```

has to give the same answer as

```
fetch | filter | aggregate
```

Stopping at a file is a pause, not a lossy conversion. There is a test that runs
both halves and compares them, and another that writes one value of every type,
reads it back, and checks the type survived.

```
~/work > ls | first 2 | select name size modified | to-csv
name:string,size:size,modified:time
notes.md,4mb,2026-08-21T14:30:00.000-04:00
hero.png,4194305b,2026-08-19T09:12:44.031-04:00
```

The header carries the column types, so `from-csv` hands back a size rather than
a number that happens to look like one. The cells are written exactly the way the
language writes those values — `4mb` is what you would type, and `4194305b` is
what a size that isn't a round number looks like, because the form has to be
exact before it can be pretty. `to-csv --plain` drops the types for programs that
want an ordinary CSV.

Nothing is ever guessed at on the way in. A CSV from somewhere else, with no types
in its header, comes in as text — a column that merely *looks* like a date is not
turned into one, because guessing is how a spreadsheet eats a phone number. Say
what you meant and it will do it.

`to-source` and `from-source` are the same idea without the table shape: they
write MainFrame's own literals, so any value at all — a record, a list, a lone
duration — can go to a file and come back.

**A file says what is in it.** `save` picks the format from the name and `open`
picks the reader the same way, so nothing has to be declared twice:

```
ls | where size > 1mb | save big.csv
open big.csv | sort-by size --reverse | first 5
```

Filtering and ordering work on the way back because the file carried its own
schema — not because you told the shell a second time what its own columns held.
Being asked to re-declare that is the paperwork this design exists to avoid.

**A table is written as a table**, in JSON too — a header row and one array per
row, rather than the same keys repeated on every object:

```json
[
  ["name:string","size:size","modified:time"],
  ["hero.png", 4404019, "2026-08-19T09:12:03.114-04:00"],
  ["banner.jpg", 1887436, "2026-08-14T16:40:55.201-04:00"]
]
```

That is still perfectly ordinary JSON. It just uses arrays, which gives it the
same metadata slot CSV's header has always had — and it is about a third smaller
for the trouble, since the column names are stated once instead of once per row.
Cells use JSON's own types where JSON has them, so a consumer that ignores the
header still gets sensible numbers and strings.

`--plain` gives the list of objects most scripts expect, at the cost of the
types, and `save` says so when you ask for it:

```
~/work > ls | select name size | save theirs.json --plain
written without column types, so sizes and times read back as plain numbers
and text -- that is what --plain means
```

**Neither format guesses.** The type-annotated header is what marks a file as
one of MainFrame's. Without it, a CSV is a CSV of text and a JSON array of arrays
is an array of arrays — nothing is ever inferred from what the values happen to
look like. And the header-row form is only ever used for a table; anything else
is ordinary JSON, because only a table has columns to describe.

## Adapting one API to another

Two systems rarely agree on names, units or shapes. Rather than a format command
per destination, there are two commands: `cast` gives foreign data its real
types, and `morph` rebuilds each row into whatever shape the far end wants.

Coming in — their names, their units, their dates as strings:

```
open theirs.json
  | cast {signed_up: time, total_cents: int}
  | where signed_up > 2026-02-01
  | morph {name: full_name, spend: total_cents / 100, joined: signed_up}
  | save ours.csv
```

Once `cast` has said what a column is, it filters and sorts like anything else —
`signed_up > 2026-02-01` is a date comparison, not string prefix matching.

Going out — if the far end wants proper objects, it gets proper objects:

```
ls | morph {asset: {label: name, bytes: size}, updated: modified}
   | save for-them.json --plain
```

```json
[
  { "asset": { "label": "hero.png", "bytes": 4194304 }, "updated": "2026-08-19T09:12:00.000-04:00" }
]
```

Whole documents too, when an API wants its rows wrapped in something:

```
echo {count: (ls | length), files: (ls | morph {label: name})} | save envelope.json
```

`morph` needs no syntax of its own: the shape is a record literal, written in the
same expression language `where` and `sort-by` already use. `{sum: a + b}` is a
column called `sum` holding `a + b`. Nested records, lists and sub-pipelines all
work, so any JSON shape is reachable — and morphing costs you nothing on the way
out, since a reshaped table still saves with its types.

**Paths are written with forward slashes** whatever this machine calls a
separator, so a file written on Windows opens correctly on a Mac. Reading turns
them back into whatever the local system uses.

**A media type comes back knowing it was read, not detected.** `mime` records how
it decided — content, extension, shebang — and after a trip through a file the
honest answer is "written down". The media type itself survives; the story of how
you came by it does not, because it is no longer true.

**Times are local, in both directions.** A timestamp you see is the wall clock you
can look up at; a time you *write* means your wall clock too. What travels
between is an instant, since that is the only form that survives daylight saving,
a machine moving zones, and an index built on one computer being read on another.
An explicit offset is honoured; without one, `2026-08-21` means your midnight.
That is enforced rather than intended:
[`Times`](mainframe-core/src/main/java/dev/mainframe/value/Times.java) is the only place allowed
to turn a moment into text or text into a moment, and a test fails the build if a
second formatter appears anywhere in `src/main/java`.

Because moments and spans are their own types rather than numbers wearing a hat,
the arithmetic means something and the mistakes are caught:

```
ls | where modified > (now - 7d)      # a week ago
echo (now - 2026-01-01)               # 7h 3m
ls | where modified > 5               # error: cannot compare a time with an int
```

Operators: `== != < <= > >=`, `=~` and `!~` (substring, or glob when the pattern
holds `*`/`?`), `and`/`or`/`not`, `+ - * / %`. `+` also joins two strings, two
lists or two records. One value counts as a list of one, so `... | first | get name`
does what you meant.

## Data entry

Somebody has to type the data in the first place. A **form** is a table of
fields — ordinary data, so it can be written in a line, kept in a variable, or
read out of a file like anything else — and `form` shows it, asks for each field
in turn, and hands back a record.

```
~/crm > let contact = [
...        {name: "name", label: "full name", required: true, min: 2},
...        {name: "email", required: true, match: "[^@ ]+@[^@ ]+[.][^@ ]+"},
...        {name: "tier", choose: ["free", "team", "enterprise"], default: "free"},
...        {name: "quota", type: "size", min: 1mb},
...        {name: "phones", type: "table", required: true, max: 3, fields: [
...           {name: "kind", choose: ["mobile", "home", "work"], required: true},
...           {name: "number", required: true, min: 7}
...        ]}
...      ]
~/crm > form $contact --title="New customer" | save ./ada.json
```

```
========================================================================
 NEW CUSTOMER
------------------------------------------------------------------------
 5 fields, asked one at a time
 !back the field before, !cancel the whole form
========================================================================

 FULL NAME .................................................... required
   at least 2 characters
 > A
 !! full name needs at least 2 characters, and that is 1 character
 > Ada Lovelace

 EMAIL ........................................................ required
   matching [^@ ]+@[^@ ]+[.][^@ ]+
 > ada@example
 !! email does not match [^@ ]+@[^@ ]+[.][^@ ]+
 > ada@example.com

 TIER ......................................................... optional
   1) free
   2) team
   3) enterprise
   blank keeps free, !clear empties it
 > 2

 QUOTA ........................................................ optional
   a size like 4mb, at least 1.0 MB
 > 4mb

 PHONES ....................................................... required
   at least 1 entry, at most 3 entries
   [1]

     KIND ..................................................... required
       1) mobile
       2) home
       3) work
     > 1
     NUMBER ................................................... required
       at least 7 characters
     > 555-0100
   another entry? [y/N] n

========================================================================
 NEW CUSTOMER  what you entered
------------------------------------------------------------------------
 name    Ada Lovelace
 email   ada@example.com
 tier    team
 quota   4.0 MB
 phones  [1] {kind: mobile, number: 555-0100}
========================================================================
 submit? [Y/n]
```

**The answers keep their types.** `quota` comes back a size, not the text `4mb`;
a `time` field comes back a moment. So the record goes straight into `save`,
`where`, `sum` or a hosted command with nothing to re-parse — and `open ./ada.json`
gives back the same values it went in as.

**A field says what it takes**, and only these things:

| | |
|---|---|
| `name` | the field's name, which becomes a column of the answer |
| `label` | what to call it on screen; the name with its dashes opened out, by default |
| `type` | `string` (the default), `int`, `float`, `number`, `bool`, `size`, `time`, `duration`, `path`, `mime`, or `table` for a list of details |
| `required` | it will not take a blank |
| `min` / `max` | characters for text, the value itself for a number or a moment, entries for a `table` |
| `match` | a regular expression the *whole* answer has to match |
| `choose` | a list to pick from, offered as a numbered menu |
| `help` | a line of explanation shown under the label |
| `default` | offered as the answer, and checked against the field's own rules |
| `fields` | for a `table`: the questions each entry is made of |

A field that is only a name can be written as one, so a quick form is quick to
write: `form ["name", "email", "phone"]`.

**Lists of details repeat a few questions** rather than a single answer. That is
what `type: "table"` and `fields` are: the entry is a sub-form, MainFrame asks
whether there is another one, and the field comes back as a table — which is to
say it filters, sorts and saves like any other table.

**The definition is checked before the first question**, the same way a command's
arguments are:

```
~/crm > form [{name: "code", type: "int", match: "[0-9]{5}"}]
error[E1206] code is an int, so a pattern cannot be matched against it
help match works on string, path and mime fields
help a code with leading zeros or spacing rules is text, not a number -- say
     type: "string" and keep the pattern
```

A misspelt key or type gets a "did you mean", a default that breaks its own rules
is refused, and two fields cannot share a name. A form that would fail at its
last field fails before its first.

**There is always a way out.** At any prompt, `!back` returns to the field
before, `!cancel` abandons the whole form, `!clear` empties a field that came in
with something in it — or, at a list of details, the whole list — and the end of
the input, Ctrl-D, is a cancel too. The
answers are shown for approval before they count — `--no-review` hands them
straight back — and answering `n` there walks the fields again with everything
already entered offered back, so a blank keeps it. A cancelled form is `nothing`,
and says so:

```
~/crm > let answers = form $contact
cancelled -- nothing was entered
~/crm > if $answers { echo $answers | save ./ada.json }
```

**Editing is the same command.** Pipe a record in and each field starts out
holding what was there; blank keeps it. Anything in that record the form does not
ask about is carried through rather than quietly dropped, so a file can be edited
without losing the parts this form knows nothing about:

```
open ./ada.json | form $contact --title="Edit customer" | save ./ada.json --force
```

**The same rules hold with nobody at the keyboard.** `form` refuses to run
unattended — a record of blanks that nothing checked is worse than an error — and
`form-check` is the half that needs no terminal, holding data that arrived from
somewhere else to exactly the rules a person would have been held to:

```
~/crm > open theirs.csv | form-check $contact | save ours.csv
error[E1210] 3 answers do not fit these fields
help row 2: email does not match [^@ ]+@[^@ ]+[.][^@ ]+
help row 5: full name is required
help row 5: phones needs at least 1 entry, and there are 0 entries
```

Every problem, not the first one: somebody fixing a file wants the whole list,
not a round trip per mistake.

### Saving the answers, and starting from them next time

A form fills in from a record — so anything that can produce a record can pre-fill
one, and anything that can keep a record can remember it. There are two ways to
keep one, and they are the same two ways you keep anything else in MainFrame.

**A file, when you chose where it goes.** `save` and `open` already do this, and
the answers keep their types on the way through, so nothing needs re-declaring:

```
form $visit | save ./acme.json                  # write it
open ./acme.json | form $visit | save ./acme.json --force   # read it, edit it, write it back
```

**A name, when you would rather not think about a path.** `form-save` keeps a
record under a name in `~/.mainframe/forms`, and `--prefill` starts a form from
it. That is the whole preferences loop, in one line:

```
~/crm > form $visit --prefill=defaults | form-save defaults
```

The first time through there is nothing saved, and that is not a mistake — it is
said, and the form carries on:

```
nothing is saved as defaults yet, so there is nothing to start from
```

```
========================================================================
 FORM
------------------------------------------------------------------------
 4 fields, asked one at a time
 !back the field before, !cancel the whole form
========================================================================

 CLIENT ....................................................... required
   at least 2 characters
 > Acme
...
save the answers saved as defaults (4 fields)
help start from it next time with: form <fields> --prefill=defaults
```

Every time after that, the same line starts from what you last entered — and a
blank takes it, so the fields that never change cost one keystroke each:

```
 CLIENT ....................................................... required
   at least 2 characters
   blank keeps Acme
 > Globex

 OFFICE ....................................................... required
   1) london
   2) berlin
   3) austin
   blank keeps berlin
 >

 RATE ......................................................... optional
   a size like 4mb
   blank keeps 1.0 MB, !clear empties it
 >

 EXPENSES ..................................................... optional
   [1] {what: train, amount: 120}
   another entry? [y/N] y
```

**Lists of details come back too.** A saved `expenses` table is listed back as
`[1]`, `[2]`, `[3]` and you carry on adding to it — or `!clear` and start the list
again. **And every value comes back as itself**: `rate` is still a size, an
`amount` still a whole number, a `time` still a moment. The store holds
MainFrame's own written form, which is the same guarantee `save` and `open` give:

```
~/crm > cat ~/.mainframe/forms/defaults.mf
{"client": "Acme", "office": "berlin", "rate": 1mb, "expenses": [{"what": "train", "amount": 120}]}
```

That is a plain `.mf` file, so `open ~/.mainframe/forms/defaults.mf` reads it like
any other. The store is a name and a home, not a format.

**Three starting points, most specific first.** A field takes its value from what
was piped in, or failing that from `--prefill`, or failing that from its own
`default`. So the record being edited wins over your saved preferences, which win
over what the form says out of the box:

```
open ./acme.json | form $visit --prefill=defaults      # this visit, then your usual, then the field's own
```

**Keep only what should stick.** `form-save` saves the record it is given, so to
remember some fields and not others, hand it only those — `select` is already the
command for that:

```
let answers = form $visit --prefill=defaults
echo $answers | select office rate | form-save defaults    # the settings, not this client
echo $answers | save ./acme.json                           # the whole visit
```

**Seeing and forgetting.** `form-recall` on its own lists what you have kept;
with a name it hands that record back:

```
~/crm > form-recall
name      saved             on-disk  fields
defaults  2026-08-24 14:25  137 B    client, office, rate, expenses

~/crm > form-recall defaults | get office
berlin

~/crm > form-forget defaults --dry-run
dry run forget 1 item -- nothing was changed
  would forget the answers saved as defaults
```

`form-save` shows what it will do under `--dry-run` and says plainly when it is
replacing something, like `index-build` does; `form-forget` can lose data, so it
asks first and refuses to run unattended without `--yes`. Both are the ordinary
guardrails, not anything this feature invented.

## Borrowing a display

MainFrame cannot read an arrow key. There is no cursor addressing and no raw
mode, and adding them would mean native calls and a terminal library — see *Not
there yet*. So it does the other thing: it describes a screen, and something that
already knows how to draw asks the person and says what they did.

That is the 3270 arrangement, and it is worth saying why rather than treating it
as nostalgia. A 3270 terminal received a datastream saying where the fields were,
painted it, handled typing *within* a field itself, and sent back the fields that
changed plus the key that ended the transaction. It knew nothing about what any
of it meant. That is why the terminal stayed simple for fifty years while the
software behind it changed completely.

```
~/crm > mainframe --panel -c 'form $visit --title="New visit"'
```

```json
{"screen":{"id":1,"title":"NEW VISIT","size":{"rows":14,"cols":92},"focus":"client",
 "keys":[{"key":"F12","does":"submit","text":"Submit"}],
 "parts":[{"at":[4,3],"text":"CLIENT","style":"label"},
          {"at":[4,21],"entry":"client","width":59,"value":"","holds":"string","style":"entry-focus"},
          {"at":[5,21],"text":"at least 2 characters","style":"hint"},
          {"at":[6,21],"choice":"office","of":["london","berlin"],"value":""},
          {"at":[11,21],"action":"add:expenses","text":"+ Add another","style":"action"}]}}
```

and back:

```json
{"event":{"screen":1,"did":"submit","key":"F12","fields":{"client":"A","office":"berlin","rate":"lots"}}}
```

which MainFrame answers with the next screen, carrying what was typed and why it
is not acceptable:

```json
{"at":[6,21],"text":"client needs at least 2 characters, and that is 1 character","style":"error"}
{"at":[8,21],"entry":"rate","value":"lots","style":"entry-focus"}
{"at":[10,21],"text":"cannot read \"lots\" as a size","style":"error"}
```

**The whole vocabulary is five parts** — `text`, `entry`, `choice`, `box`,
`action` — placed at `[row, col]` in character cells, with a style *name* rather
than a colour so a panel looks like the editor rather than like a screenshot of
one. That is the entire surface an editor implements. It does not know what a
form is, what validation is, or what any field is for, and adding a feature to
MainFrame must never need a new editor.

**Nothing crosses that has to be understood twice.** A screen is a record and an
event is a record, in the written form everything else already uses — so a screen
can be saved, replayed against a different editor, and compared in a test, and
there is no second format to keep in step. The editor sends text, always; the
field said what type it holds, and MainFrame reads it back as that.

**The same form, both ways.** `Form` still says what a good answer is and
`Field.read` still says what typed text means, whether the questions are printed
downwards or all on view at once. What a panel adds is what a printed form could
never offer — somewhere to click to take one entry back out of a list of details.

The whole thing is specified in **[PROTOCOL.md](PROTOCOL.md)**, including the six
rules that keep it evergreen. The shortest of them: an editor that supports only
`text` and `entry`, reports `submit` and `cancel`, and ignores everything else is
a conforming editor. It says what it `can` do in its first message and MainFrame
renders down to it — an editor without `choice` gets an entry with the options
written above it, and never learns it missed anything.

A host embedding MainFrame attaches one with `.editor(...)`; the command line
speaks it with `--panel`, where standard output carries nothing but messages —
even ordinary command output leaves as `print`.

The first display MainFrame borrows is its own: the console in
[mainframe-vexel-gui](mainframe-vexel-gui/README.md) is a conforming editor, so a
`form` in that window goes up as a screen with every field on view, and a
destructive command asks for its yes on one. It was written against the record
rather than against an object — it parses cells and part kinds out of a screen
message exactly as an editor on the end of a pipe does — which is how the protocol
came to be tested rather than only specified. What that turned up was worth the
trip: `choice` should be claimed by nobody who has nowhere to open a list, and a
part that renders itself down into two rows has to be counted as two.

## Commands

| | |
|---|---|
| **getting around** | `help` `describe` `pwd` `cd` `echo` `which` `version` `exit` |
| **files** | `ls` `cat` `open` `mime` `save` `mkdir` `cp` `mv` `rm` |
| **shaping data** | `where` `morph` `select` `reject` `sort-by` `first` `last` `reverse` `length` `get` `each` `uniq` `count-by` `sum` |
| **converting** | `cast` `to-csv` `from-csv` `to-source` `from-source` `to-json` `from-json` `lines` `to-text` |
| **searching** | `index-build` `index-sync` `index-list` `index-drop` `from-index` `find` |
| **environment** | `env` `env-set` `env-remove` `path` `path-add` `path-remove` `programs` |
| **data entry** | `form` `form-check` `form-save` `form-recall` `form-forget` |

`cp` and `mv` take their destination as `--to=<path>`, never as a trailing
argument, so the last thing you typed is never mistaken for a target.

## State

Everything MainFrame keeps lives in one directory, `~/.mainframe`:

```
~/.mainframe/indexes/   saved indexes, one text file each
~/.mainframe/forms/     form data form-save kept, one record each
~/.mainframe/trash/     what rm moved, in timestamped folders
~/.mainframe/history    what you have typed
```

Set `MAINFRAME_HOME` to put it somewhere else.

## Not there yet

- No line editing, history recall or tab completion in the REPL yet. The
  signatures already describe everything completion needs; the terminal handling
  is the missing half.
- External programs are captured, not streamed, unless the caret command is the
  last stage of an interactive line — so a pager or an editor works, but
  `^top | where ...` does not.
- Environment changes last for the session only. There is no startup profile yet,
  so nothing carries over to the next run.
- No functions or user-defined commands, and no background jobs.
- On a terminal, a form asks for its fields downwards, because there is no cursor
  addressing to fill one in on the spot. A field cannot be jumped to by name —
  `!back` walks — and one entry cannot be picked out of a list of details, only
  the whole list cleared with `!clear`. Both go away when a display is borrowed;
  see [Borrowing a display](#borrowing-a-display).
- `--panel` runs a script or a `-c` line, not the shell itself: the REPL still
  reads lines, and standard input in panel mode belongs to the editor. A whole
  session over the protocol — a prompt, a table, an error, all as messages — is
  the next piece.

## Licence

MainFrame is dual-licensed:

- **Source code** under the
  [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
  See `LICENSE`.
- **Documentation** (this file, `PROTOCOL.md`, and the examples under `examples/`) under the
  [Creative Commons Attribution 4.0 International License
  (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/). See `LICENSE-docs`.
