# The suite's icons in MainFrame

Status: **started**, 2026-10-11. The settings button wears its icon; the rest arrive with the features that use them.

MainFrame's controls take their icons from the suite's art. The master copy is `vexelray-icons` beside this
repository: its README says where each set comes from (the design canvas), how it is refreshed, and which projects
hold copies. That folder is not a repository, so MainFrame copies in the files it uses, and commits them, under
[src/main/resources/dev/mainframe/icons](../src/main/resources/dev/mainframe/icons).

## How an icon is drawn

[Icons](../src/main/java/dev/mainframe/Icons.java), on vexelray-codec's image module, the suite's one way of getting
a picture onto a node. An icon is an SVG decoded by `Pictures` whenever its box changes size, and handed to the
node by `Textures`, which uploads each icon at each size once and gives back what has not been shown for a while.
So it is sharp at any display scale or zoom, and the small art, drawn on a 16-unit pixel grid, lands on whole pixels
at 16 and 32.

MainFrame draws the **masks**, one solid shape each with the details knocked out, in a colour of the theme's: their
one fill is `currentColor`, and the root's `color` is set to the theme's ink before rasterising. The chrome is drawn
in ink, and a coloured icon among the window's own buttons would stand out from them.

A texture belongs to the window's device, so an icon sized before the window exists is drawn when `Icons.bind` is
given it. MainFrame is not a framework application, so it binds `Textures` itself rather than through the codec's
`ImageStarter`. A rasterisation that fails leaves the box empty and says why on
stderr; the button still works and still has its tooltip.

imagelib is linked into the native image rather than extracted (the `imagelib.lib` and `/EXPORT` linker options in
the pom, as vexplore has them).

## Where MainFrame uses them

| Icon | Where | |
| --- | --- | --- |
| `utility/small/settings` | the title bar's settings button | **done**, replacing the drawn gear |
| `utility/small/grip` | the leading edge of a folder card in the Workspaces editor | with the editor |
| `utility/small/new` | the **+ New** tile in the Open dialog, and New on the Workspaces page | with the dialog |
| `utility/small/edit` | **Edit…** on a workspace tile | with the dialog |
| `utility/small/close` | Remove on a folder card's menu, and closing a tab | with the editor |
| `file-types/dark/folder` | **Open folder…** in the Open dialog | with the dialog |

The title bar's screenshot button is `SaveScreenshot` in vexelray-gui-nfd, shared by every application, so its icon
is a change there rather than here.

The suite has **no open or workspace icon**. The Open dialog can do without them (the folder icon, and each tile's
own name), or they are drawn on the canvas's Utility icons page and extracted with the rest.
