# TODO

Work on **MainFrame** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] Rebuild MainFrame on 0.2, milestone by milestone, as [rebuild.md](rebuild.md) proposes. M4 (workspaces) is
      built (slices 1 to 4), and M5 (shipping) but for the Linux run below. What 0.1's TODO kept for after the port
      is in rebuild.md's *After the port*.
- [ ] Run MainFrame on Linux, on the VM sharing this folder: `mvn -Pnative-linux package -DskipTests`, then
      `checks/main-path.sh`. Neither has been run there for 0.2.
- [ ] A Workspaces card whose tab has no name of its own is meant to show the folder's name dimmed; seen live
      2026-11-07 it is drawn as bright as a named one.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
