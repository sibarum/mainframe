# TODO

Work on **${title}** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] Replace `Doc` with what this application actually knows. It holds a counter as a placeholder, and the
      counter is only there so the state path is already correct when the first real field arrives.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that
      file about which authored colours the construction can reproduce and which have to be declared.
- [ ] Decide what closing the window means. Closing currently closes, which is the right default; an
      application with unsaved state registers `shell.onClose` in `${className}Wiring.attach` and answers the
      `CloseRequest` when it knows. One gate per application -- a second registration is refused, because the
      one it replaced is as likely as not the one that knew about the unsaved documents.

## Later

- [ ] Automation coverage: `-Dautomation=on` opens a driving socket, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
