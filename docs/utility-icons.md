# The suite's icons in MainFrame

Status: **done**, 2026-10-13. Every icon MainFrame shows comes from vexelray-gui's icon atlas; MainFrame carries no
art of its own and decodes nothing.

The master copy of the suite's art is `vexelray-icons` beside this repository: its README says where each set comes
from (the design canvas) and how it is refreshed. vexelray-gui bakes every mask in it into one distance-field atlas
(`vexelray-gui-icons`: `vexelray-icons/tools/Bake.java` writes the atlas and the `Icon` enum), so MainFrame names an
icon rather than copying its file.

## How an icon is drawn

```java
IconView.of(gui, Icon.GRIP).size(Length.dp(16)).tint(Role.FAINT).node()
```

An `IconView` is a node that draws one cell of the atlas, cut at its edge by the shader: sharp at any size, display
scale or zoom, in the colour it is tinted, from one texture the renderer uploads once per device. Nothing at run time
reads an SVG, so nothing waits for a window to exist before it can be drawn, and nothing has to be redrawn when a box
moves.

The atlas holds **masks**, one solid shape each with the details knocked out, so an icon is one colour: a theme role
for the chrome (`tint(Role.INK)`), or a colour of its own (`tint(Color)`) where the suite's drawing is coloured.

This replaced `Icons`, which decoded SVG copies through vexelray-codec and imagelib whenever an icon's box changed.
A box that moves counts as changed, so a card carried or scrolled re-rasterised its grip every frame, on the handler
lane, until the lane had a thread per decode: choppy frames, a busy idle, and a shutdown that overran its bound and
was halted. MainFrame no longer depends on vexelray-codec or links imagelib.

## Where MainFrame uses them

| Icon | Where | Drawn |
| --- | --- | --- |
| `Icon.SETTINGS` | the title bar's settings button | `Role.INK`, 16 dp |
| `Icon.GRIP` | the leading edge of a folder card in the Workspaces editor | `Role.FAINT`, 16 dp |
| `Icon.NEW` | the **New workspace** tile in the Open dialog | `Role.DIM`, 32 dp |
| `Icon.FOLDER` | the **Open folder…** tile in the Open dialog | `#e4b750`, the body of the suite's colour folder drawing, 32 dp |

Not used: `edit` on a workspace tile is a context-menu item, which marks with glyphs; a tab's close is drawn by
vexelray-gui's `Tabs`; the title bar's screenshot button is `SaveScreenshot` in vexelray-gui-nfd, shared by every
application. The atlas now has `Icon.OPEN` and `Icon.WORKSPACE`, which the Open dialog's tiles could wear.
