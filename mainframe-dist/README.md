# mainframe-dist

MainFrame as one executable, with the programs it opens built into it.

```
mvn -Pdist,native package        # mainframe-dist/target/mainframe.exe
```

```
~ > apps
name        launchable  summary
calculator  true        a keypad, a tape, and a plotter for anything with a variable in it
editor      true        open files in tabs, and point the file tree at a directory

~ > calc "2^10"          1024
~ > calc                 the keypad, in a window of its own
~ > edit ./pom.xml       the editor, with that file in a tab
~ > ls | where ext == "java" | first 3 | edit
~ > reveal ./src         the file tree, pointed there
~ > new vexel-desktop    a whole VexelRay project, from a form
```

Everything stock in MainFrame is stock in this: `ls`, `where`, `select`, the pipes, the forms,
the panel protocol. `calc`, `edit` and `reveal` are not aliases this module invented — they are the commands
the calculator and the editor register for themselves, so they arrive with the same argument checking, the
same `help`, the same `--dry-run` and the same errors as every built-in.

`new` and `templates` arrive the same way, from [`mainframe-template`](../mainframe-template). Because the
template's files are resources in the image, this executable can start a whole VexelRay project on a machine
with nothing beside it but a JDK and a Maven repository —
[how to use it](../mainframe-template/docs/using-templates.md).

## Why this module is behind a profile

`mainframe-core` and `mainframe-vexel-gui` are libraries and answer to nobody. This is a leaf, and it names
applications that live in their own repositories, so a fresh clone could not build it. `mvn install` therefore
builds the two libraries and stops; `-Pdist` adds this one. The arrow direction is deliberate: an app depends
on `mainframe-vexel-gui` and never the reverse, and this module is the one place allowed to know both ends.

Nothing in here is a library. Anything that turns out to be reusable belongs in `mainframe-vexel-gui`.

### What it needs installed

```
mvn install                                          # in mainframe
mvn install                                          # in calculator-vexel-demo
mvn install                                          # in text-editor-vexel-demo
mvn -Pdist,native package                            # in mainframe
```

## Running it

```
mainframe.exe                                        # MainFrame, and the two programs in it
mainframe.exe --launch editor                        # come up with the editor already open
mainframe.exe --run "edit ./notes.md"                # come up having run a line
mainframe.exe --capture out.png                      # headless still of the console
mainframe.exe 600                                    # stop after 600 frames
```

`--run` is the hook a desktop shortcut or a file association wants: it goes through the same submit the
prompt does, so the line is echoed, it is in the history, and a line that does not parse is refused on screen
rather than swallowed before the window appears.

The JVM arrangement of exactly the same program, for when the question is whether MainFrame works rather than
whether native-image can hold it:

```
mvn -Pdist -pl mainframe-dist compile exec:exec
```

## The native build

Three switches beyond the ordinary ones, and each is load-bearing:

| switch | why |
| --- | --- |
| `-H:+ForeignAPISupport` | the graphics stack and both input backends are Panama downcalls, and Win32 calls back in through an upcall stub for the window procedure |
| `-H:+SharedArenaSupport` | the input backend gives each raw-input registration its own `Arena.ofShared`, because the window procedure that reads it runs on a different thread from the one that opened it. Without this the whole application runs and then throws on the way out |
| `-J-Djava.io.tmpdir=target/nitmp` | native-image compiles small probe executables and runs them; Windows Application Control blocks a brand-new unsigned `.exe` under `%TEMP%` |
| `/SUBSYSTEM:WINDOWS` + `/ENTRY:mainCRTStartup` | no console window. native-image links a console-subsystem executable, so Windows hands the process a black terminal before anything runs — and because that console owns the process group, closing it kills MainFrame. `/ENTRY` is not optional alongside it: `/SUBSYSTEM:WINDOWS` makes the linker look for `WinMain`, and the image's entry point is an ordinary `main`, so the link fails with an unresolved `WinMain` without it |

`mainframe-core` keeps the console subsystem, because that one is a CLI and a console is exactly what it
wants. Output from the windowed executable still arrives wherever the launcher gives it a handle, so
`mainframe.exe --run "…"` from a shell still prints; double-clicked there is nowhere for it to go, so a crash
before the window appears is silent.

### Application Control will lie to you about this build

Two failures on this machine are the security policy and not the code, and both look like real bugs:

- `BUILD FAILURE ... Unable to run 'WindowsDirectives.exe' to compute offsets in C data structures` — the
  probe executables native-image compiles and runs. Retry.
- `Permission denied` / `An Application Control policy has blocked this file` when launching the result, and —
  the nasty one — `UnsatisfiedLinkError: Can't load library: awt`. That last one is **not** a missing DLL.
  `awt.dll` imports from the `java.dll` and `jvm.dll` shims that native-image generates fresh on every build,
  those are brand-new unsigned binaries, and when the policy blocks them `awt.dll` cannot resolve its imports.
  Rebuild and retry until a binary is allowed through; it is intermittent.

### It is one executable and nine JDK DLLs, not one file

`native-image` copies `awt.dll`, `fontmanager.dll`, `freetype.dll`, `lcms.dll` and five others next to the
executable, and they are **required** — moving them away makes the exe fail on startup, not on some optional
path. The reason is `GuiApp.loadAtlasRgba`: the text atlas ships as a PNG and is decoded with `ImageIO`, so
`java.desktop` is reachable from the first frame that draws a glyph. Windows has no option for statically
linking JDK native libraries into the image.

Making it a literal single file means teaching `vexelray-gui-core` to read its atlas without `ImageIO` —
shipping the atlas as raw RGBA, or decoding the PNG directly. That is a change in the framework, not here.

### reachability-metadata.json

Three files, kept apart on purpose.

`dev.mainframe/mainframe-dist/` is the tracing agent's own output, from five runs over this module: the
headless console capture, the form-panel capture, a windowed run of each app, and a probe that opens the two
native file dialogs. It was then filtered to drop what surefire and JMX contributed, because one of the traced
runs was the editor's JUnit suite — that run is what supplies the sixteen `grammars/*.tmLanguage.json`
entries, joni's Unicode tables and TM4E's four `Raw*` grammar classes, which is the whole of the editor's
syntax highlighting.

**The agent only records what you actually do, and that is the trap.** The first four runs never opened a file
dialog, so the executable came up fine and then died the moment anyone pressed Ctrl+Shift+O. `vexelray-gui-nfd`
needs two things that nothing else in this build needs: the bundled `natives/windows-x64/nfd.dll` resource
(without it `getResourceAsStream` returns null and the loader falls through to a `System.loadLibrary("nfd")`
that cannot succeed), and the downcall descriptors that `Nfd`'s static initialiser builds — of which
`jint(jlong, void*, void*)` is the one shared by open, save and pick-folder. So when adding a trace run, go
looking for the paths a keyboard reaches and a script cannot: the file dialogs were exactly that.

`dev.mainframe/mainframe-dist-signed-jar/` is a workaround, and separate so it is obvious which is which.
The editor's TM4E dependency is a **signed** jar, and the Eclipse signing certificate genuinely ends up in
the image heap — so every type needed to represent an X.509 certificate has to be registered or the build
fails outright with `Type not found during analysis`. Discovering those one build at a time is a long
afternoon, so the whole of `sun.security.{x509,util,rsa,pkcs,ec}` is registered at once.

`dev.mainframe/mainframe-dist-assistant/` is the SDK's, and separate because it was not traced from this
program at all. The Anthropic SDK hands Jackson its own deserialisers — `JsonValue$Deserializer` and the
whole of `com.anthropic.models` — and Jackson instantiates them reflectively, so a native image without this
file **builds and links without a single warning** and then throws on the first question:

```
PROBE-FAILED java.lang.IllegalArgumentException:
    Class com.anthropic.core.JsonValue$Deserializer has no default (no arg) constructor
  at com.anthropic.core.JsonValue$Companion.from(Values.kt:360)
  at dev.mainframe.assistant.Claude.asTool(Claude.java:162)
```

That is before a request is sent — rendering the roster as tools is enough to hit it. 183 types, mostly
`com.anthropic.models`, plus the Kotlin builtins resources and the `sun.security.*` and `com.sun.crypto`
machinery an OkHttp client initialises on the way up.

**How it was traced, since it cannot be traced the way the others were.** Asking the real API needs a key,
and the point of a trace run is to be repeatable. So the agent was run over a probe that drives
`dev.mainframe.assistant.Claude` against a stand-in for `api.anthropic.com` on `127.0.0.1` — a canned
`/v1/messages` that replies with a `tool_use` block on the first turn and text on the second, which is the
assistant's whole loop and covers `ToolUseBlock` and `ToolResultBlockParam` as well as the plain answer. The
client is pointed at it with `ANTHROPIC_BASE_URL`, so nothing about the SDK's own path is stubbed. `Probe`
and `sun.launcher.LauncherHelper` were then filtered out, the same way the first file drops surefire.

**What that trace does not cover, and what would.** The stand-in speaks plain HTTP, so nothing here proves
TLS works in the image — the handshake, `api.anthropic.com`'s certificate chain, the bundled trust store. The
`sun.security.ssl`, `pkcs12` and `provider` entries the trace did pick up are the client's start-up, not a
completed handshake. Closing that gap needs one real `ask` against the real endpoint with a real key, which
has not been done. A streamed response is also untraced; nothing asks for one yet.

All three are regenerated the same way: run the JVM arrangement under
`-agentlib:native-image-agent=config-merge-dir=<dir>`, then re-apply the filter. Deleting the
`sun.security.*` entries because a native image has no jars to verify is the obvious-looking mistake; it is
what produces `Type not found during analysis: BasicConstraintsExtension`.

## What is verified

- `calc "2^10" | save ./answer.txt` writes `1024` from the executable, so the calculator, the pipe and `save`
  all work natively.
- `edit ./Sample.java` opens the editor on a real file and shuts down clean, with the java grammar's scope
  names present in the binary and no `highlighting failed` on stdout.
- `--capture` renders the console offscreen through Vulkan and writes a PNG.
- Ctrl+O and Ctrl+Shift+O in the editor each bring up their native dialog: driving the real keystroke at the
  window leaves a `#32770` (`Open`, `Select Folder`) window open in the process, which is the proof that NFD
  loaded its library and linked its downcalls. "The process did not crash" is *not* proof on its own — it is
  also what a keystroke that never arrived looks like.
- `ask "marco"` completes both turns from the executable, against the stand-in the metadata was traced from:
  `ANTHROPIC_BASE_URL=http://127.0.0.1:8787 mainframe.exe --run 'ask "marco"' 400`. The first request carries
  the real roster — 26KB of it, where the probe's one made-up tool was 324 bytes — and the second carries a
  `tool_result`, which is the whole loop: the reply deserialised, the `ls` it asked for run through the
  ordinary interpreter, the outcome sent back up. A native probe over `Claude` alone does the same, and
  without `mainframe-dist-assistant/` dies on the first turn.

  What is still not covered is TLS: the stand-in speaks plain HTTP. One real `ask` with a real key is the
  only thing that exercises the handshake, the certificate chain and the trust store in an image.

There is no pixel-level screenshot of the editor window: this machine's desktop session is not capturable
(`CopyFromScreen` returns black), so the highlighting evidence is the embedded grammar, the clean run and the
trace it was built from rather than a photograph.
