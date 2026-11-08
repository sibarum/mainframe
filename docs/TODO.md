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

- **The Linux app id does not match the installer's.** The bill is `dev.mainframe.mainframe-gui`; `installer.json`'s
  Linux `appId` is `dev.mainframe.mainframe`, so an installed desktop entry would not match the windows (FN-26). Set
  `vexelray.installer.config` to the root's `installer.json` and `vexelray.app.id` to match, or wait for the framework
  to find it; check the Windows taskbar grouping does not move when the id does.

- **The workspace's name as the main bar's title**, blank for a window with none (the author, 2026-11-07), in a shade
  that is plainly not a menu: waits on FN-27, since the title is drawn in the menus' shade and only the framework can
  change that. `Frame.State.workspace` has it; it needs a message to the view (a `Messages` change, to propose), sent at
  startup and when the workspace is renamed or deleted.
