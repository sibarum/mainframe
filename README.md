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

## Build

Needs GraalVM (JDK 25) and Maven.

```bash
mvn package
```

That produces `target/mainframe.jar`, runnable with `java -jar target/mainframe.jar`.

For the real thing — a single binary that starts instantly:

```bash
mvn -Pnative package
```

That produces `target/mainframe` (`mainframe.exe` on Windows) — an 18 MB binary
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

The public API is the `dev.mainframe.api` package and nothing else:
`MainFrame`, `CommandSpec`, `Command`, `PlannedCommand`, `Invocation`, `Data`,
`DataType`, `Effect`, `ShellError`. MainFrame's own command line is built on it,
which is the cheapest way to be sure it can carry a whole shell.

There is a worked example in
[`HostApp.java`](src/test/java/dev/mainframe/api/HostApp.java) — a task list with
a shell in it:

```bash
java -cp target/classes:target/test-classes dev.mainframe.api.HostApp
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

Types: `nothing`, `bool`, `int`, `float`, `string`, `size` (`10mb`), `time`,
`path`, `mime`, `list`, `record`, `table` (a list of records), `block`.

Operators: `== != < <= > >=`, `=~` and `!~` (substring, or glob when the pattern
holds `*`/`?`), `and`/`or`/`not`, `+ - * / %`. `+` also joins two strings, two
lists or two records. One value counts as a list of one, so `... | first | get name`
does what you meant.

## Commands

| | |
|---|---|
| **getting around** | `help` `describe` `pwd` `cd` `echo` `which` `version` `exit` |
| **files** | `ls` `cat` `mime` `save` `mkdir` `cp` `mv` `rm` |
| **shaping data** | `where` `select` `reject` `sort-by` `first` `last` `reverse` `length` `get` `each` `uniq` `count-by` `sum` |
| **converting** | `to-json` `from-json` `lines` `to-text` |
| **searching** | `index-build` `index-sync` `index-list` `index-drop` `from-index` `find` |
| **environment** | `env` `env-set` `env-remove` `path` `path-add` `path-remove` |

`cp` and `mv` take their destination as `--to=<path>`, never as a trailing
argument, so the last thing you typed is never mistaken for a target.

## State

Everything MainFrame keeps lives in one directory, `~/.mainframe`:

```
~/.mainframe/indexes/   saved indexes, one text file each
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
