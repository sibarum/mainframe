# Shipping, for M5

What [rebuild.md](rebuild.md)'s M5 asks: both native profiles, the pseudoconsole's downcalls in the reachability
metadata, a scripted `ottermate` run through the main path kept in the repository (0.1's TODO), the installer entry, a
run on Linux, and the README rewritten for 0.2. A sketch, for the author, of what is left after a first look, with what
was measured on 2026-11-07.

## Where it stands

- **Both native builds succeed as they are** (`native-build.cmd -Pnative` and `-Pnative-release`, about 40 s each,
  49 MB).
- **The pseudoconsole's downcalls were missing**, as the plan expected: the debug build stopped as its first tab opened
  (FN-22). With the eleven signatures added to MainFrame's metadata, both builds run on a home of their own:
  - a workspace's two tabs, with their scripts sourced;
  - `--insert-path` typed and run;
  - the settings, Open, Workspaces and dialog windows;
  - Ctrl+C, and `exit` closing the window.

  The log stayed clean apart from the known *Resized* warning.
- **The installer entry** (`installer.json`) still says `0.1.2-SNAPSHOT`. Its paths are right: `mainframe.exe`, the
  ffmpeg-record zip the build stages, the icon and the notices are all where it says.
- **No Linux machine is reachable from this session.** The pom builds Linux into `target-linux`, for a VM sharing this
  folder, and `vex-suite-common-pty-linux` brings its own metadata.

## Proposed

1. **The downcalls stay in MainFrame's metadata** until `vex-suite-common-pty-windows` carries its own (FN-22). The
   stack is read-only from here; the note says whose they are.
2. **A kept check of the main path**, `checks/main-path.txt` (an `ottermate` script) with `checks/main-path.cmd` to run
   it.
   - **The runner** makes a home of the run's own under `target/check-home`. It writes a workspace `day` there whose two
     folders are in that home, and launches the given command (the debug executable by default; `mvn exec:exec` for
     everyday) through `ottermate --launch` with `--automation=0`, `-Duser.home` and `-Dmainframe.launch=off`.
   - **The script**, each step awaiting a landmark rather than sleeping:
     - a new tab, and a tab renamed;
     - the text size changed in Settings;
     - Ctrl+O opening `day` here, and its tabs awaited by name;
     - a workspace made and named in the Workspaces window, a card moved, and every step undone;
     - the window closed.
   - **The verdict** is the exit status. Shots go to `target/check`. A terminal's text cannot be awaited (FN-5), so what
     a shell prints is not checked: only what MainFrame draws.
3. **`installer.json` at `0.2.0-SNAPSHOT`**, as Vexplore's 0.2 is. Publishing a release to `vexelray-dist`
   (`deploy.ps1`) is yours and not part of M5 here.
4. **The Linux run** is yours, on the VM:
   ```
   mvn -Pnative-linux package -DskipTests
   ```
   then the kept check against it, with a `.sh` runner beside the `.cmd`. Or it waits past M5, in TODO.
5. **The README rewritten for 0.2**: the parts 0.1 had and 0.2 has not (the old Workspaces tab, key migration) out,
   the tour (*How it is put together*) checked against the code, and *Run it* naming the kept check.
6. **The retrospective**, then.

## For the author

1. **The downcalls in MainFrame's metadata for now**, rather than adding them to `vex-suite-common-pty-windows` from
   here?
2. **The kept check against the native debug executable by default**, with the JVM as an option?
3. **The Linux run on your VM** (I write the `.sh` runner blind), or deferred past M5?
4. **`installer.json` at `0.2.0-SNAPSHOT`** and nothing published?
