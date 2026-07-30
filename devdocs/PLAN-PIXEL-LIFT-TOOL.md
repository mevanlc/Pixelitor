# Pixel Lift Tool Plan

## Context / Problem

Pixelitor already supports "grab the selected pixels and drag them, filling the hole
behind them", but only as a modifier chord layered on top of two different tools:

1. Rectangle/Ellipse Selection tool — drag a marquee.
2. Hold `Ctrl` → `MarqueeSelectionTool.controlPressed()` starts a *temporary* Move tool
   (`Tools.startTemporaryTool`).
3. Hold `Cmd` and drag → `MoveTool.dragStarted()` sees `e.isMetaDown()` and calls
   `Composition.layerViaFillCut()`, then moves the extracted layer.
4. Release `Ctrl` → `MoveTool.temporaryToolRestored()` merges the extracted layer back
   down if the marquee tool's **Auto Merge** checkbox is on.

That works, but it needs two modifiers held in the right order, the option lives on a
different tool than the one that consumes it, the merge fires on Ctrl-release rather than
mouse-release, and one gesture costs **three** undo steps
(`Layer via Fill Cut`, `Move Layer`, `Merge Down`).

**Pixel Lift** is a dedicated tool that collapses the whole thing into: *select, then drag
inside the selection*. It is the pragmatic implementation of item 4 in
`TABLED-DISCUSSIONS.md` ("Move selected content on virtual layer") using real layers plus
auto-merge, rather than a new transient rendering surface.

## Behavior contract

- The base behavior is **exactly** the rectangular marquee selection tool: drag to select,
  Shift/Alt combinators, Alt expand-from-center, Space repositioning, arrow-key nudge,
  Esc to deselect, Crop / Convert-to-Path buttons, click-to-deselect.
- **Except**: press inside the current selection and drag → the tool runs
  `layerViaFillCut()` on the source layer and moves the resulting layer with the drag.
- **Auto Merge** checkbox — on mouse release, merge the lifted layer back down (on) or
  leave it as a separate layer (off).
- **Auto Select Layer** checkbox — same semantics as the Move tool: resolve the layer
  under the press point and make it active before the cut.

### Decisions

| Decision | Choice |
|---|---|
| Toolbar / hotkey | Own button after `ELLIPSE_SELECTION`; own hotkey `J`. Rectangle only. |
| Undo granularity | **One** history entry per lift gesture, named `Pixel Lift`. |
| Selection during drag | Travels with the pixels → `MoveMode.MOVE_BOTH`. |
| Selection after drop | Kept, so repeated lift-drags work without re-selecting. |

Hotkey note: `X` and `D` are *not* free — `FgBgColorSelector.setupKeyboardShortcuts()`
binds them to swap-colors and reset-colors, `O` is consumed by the Crop tool, and `A`/`Q`
are claimed by `RandomGUITest`. `J` was chosen for the Photoshop mnemonic —
Ctrl+J / Ctrl+Shift+J are Layer via Copy / Layer via Cut, which is exactly what this tool
automates.

---

## Design

### 1. History transaction API

To get one undo entry per gesture, `history/History.java` gained a small grouping facility.
Everything `History.add()` receives while a transaction is open is buffered instead of
being pushed onto the undo manager.

```java
private static MultiEdit transaction;

/** Starts grouping subsequent edits into a single named edit. Not reentrant. */
public static void startTransaction(String name, Composition comp)

/** Ends grouping and adds the combined edit. Returns the added edit, or null if empty. */
public static PixelitorEdit endTransaction()

/** Undoes and discards the buffered edits without adding anything to the history. */
public static void abortTransaction()

public static boolean isInTransaction()
```

The buffering branch in `add()` sits **after** the `rejectEdits` / `ignoreEdits` guards but
**before** `checker.registerAdd(...)`. The ordering matters: `HistoryChecker` tracks the
simulated undo/redo stack, and buffered children never reach that stack — only the final
combined edit does, so they must not be registered.

- Transaction open and `edit.canUndo()`: do the `edit.makesDirty()` → `setDirty(true)`
  handling, then `edit.setEmbedded(true)`, `transaction.add(edit)`, and return.
  `setEmbedded(true)` is exactly the flag `Composition.layerViaCut()` already uses for its
  children — it suppresses `History.notifyMenus()` and the per-edit dirty bookkeeping in
  `PixelitorEdit`.
- Transaction open and `!edit.canUndo()`: keep the existing `discardAllEdits()` semantics
  and drop the transaction — a non-undoable edit invalidates the whole history anyway.

`endTransaction()`:
- null or empty transaction → add nothing, return null.
- Exactly one child → unset its `embedded` flag and `add()` that child directly, so a
  degenerate group doesn't get a misleading wrapper name.
- Otherwise `add(transaction)`.

`abortTransaction()` calls `transaction.undo()` then `transaction.die()`. The children were
never handed to the undo manager, so nothing else needs cleanup.
`PixelitorEdit.undo()` already brackets itself with `History.setRejectEdits(true)`.

`undo()` and `redo()` return silently while a transaction is open (rather than asserting,
because assertions are off in production): an interactive gesture is mid-flight, and
undoing the *previous* edit while the current one is still uncommitted would corrupt the
stack. `clear()` and `onAllViewsClosed()` drop any open transaction.

There are ~96 `History.add()` call sites, so the branch is strictly additive: with no
transaction open, `add()` behaves exactly as before.

**Gotchas**
- Always close a transaction from a `finally` block; a leaked transaction silently
  swallows all later history.
- `MultiEdit` had no heaviness of its own, but `ImageEdit` and `MergeDownEdit` children are
  heavy. `MultiEdit.isHeavy()` now returns true if any child is heavy, so that the
  "minimum undo levels" accounting in `TwoLimitsUndoManager` stays correct. This also fixes
  a pre-existing bug: the `MultiEdit` built by `layerViaCut()` wraps a heavy `ImageEdit`
  but was counted as light.

### 2. Reusing the marquee tool

`MarqueeSelectionTool` got a `protected` constructor taking the short name, hotkey, and
status-bar message, so that `PixelLiftTool` can subclass it instead of duplicating the drag
logic and the subtle `altUsedForCombinator` / expand-from-center invariants.

Everything else — the combinator combo box, Alt handling, `finalizeDragBasedSelection`, the
existing **Auto Merge** checkbox with its `isAutoMerge()` accessor and
`saveStateTo`/`loadUserPreset` entries — is inherited as is. Its tooltip moved into an
overridable `getAutoMergeToolTip()`, because the two tools create fill-cut layers in
different ways.

`MoveTool.temporaryToolStarted()` tests `primaryTool instanceof MarqueeSelectionTool`, so
`PixelLiftTool` **also** participates in the existing Ctrl→temp-Move→Cmd-drag auto-merge
path, reusing the same checkbox with the same meaning. That is intended.

### 3. `PixelLiftTool`

`tools/selection/PixelLiftTool.java`, a `MarqueeSelectionTool` fixed to
`SelectionType.RECTANGLE`, driven by a three-state machine:

```java
private enum LiftState { NONE, CANDIDATE, LIFTING }
```

#### Lift detection is lazy (on first real motion, not on press)

`dragStarted()` only decides *candidacy* — if `canLift(e)`, it records `CANDIDATE` and
returns **without creating a `SelectionBuilder`** (whose constructor hides or freezes the
existing selection, which would then have to be undone). Otherwise it delegates to `super`.

`canLift()` requires all of:
- `e.isLeft()` — right-drag keeps the right-click-to-deselect meaning.
- No Shift and no Alt — those are the selection combinator modifiers.
- No Ctrl and no Meta — reserved for the temporary Move tool workflows.
- `getCombinator() == ShapeCombinator.REPLACE` — the combo box can ask for selection
  editing without any modifier being held.
- A valid, non-hidden selection whose **shape** (not its bounds) contains the press point,
  so an L-shaped Add/Subtract selection has an exact lift area.
- `findLiftSource(...) != null`.

`findLiftSource()` resolves `comp.findLayerAtPoint(imPoint)` when Auto Select is on, else
the active layer, and returns null unless it is a plain `ImageLayer` and not mask-editing.
That pre-check is what keeps `Composition.layerViaCut()` from popping its
`Messages.showInfo` modal.

`ongoingDrag()` promotes `CANDIDATE` → `LIFTING` on the first non-click motion by calling
`startLift()`, then moves via `comp.moveActiveContent(MOVE_BOTH, drag.getDX(), drag.getDY())`.
`getDX()/getDY()` are measured from the press point, so the layer lands correctly even
though the cut happened one event later. If `startLift()` fails (the conditions changed
since the press), the gesture degrades to an ordinary selection drag.

`startLift()` mirrors `MoveTool.dragStarted()`: open the transaction, `layerViaFillCut()`,
verify the active layer actually changed, `prepareMovement(MOVE_BOTH, false)`, and set
`repositionOnSpace = false` — otherwise `Drag.pan()` would translate both drag endpoints
and freeze the pixels in place. A `finally` block calls `cancelLift()` on any failure.

`dragFinished()` commits with `finalizeMovement(MOVE_BOTH)` and, when Auto Merge is on and
`holder.canMergeDown(liftedLayer)`, merges the layer down — then closes the transaction in
a `finally`. The selection is deliberately not touched: `layerViaCut()` never deselects, and
`MOVE_BOTH` already carried the marching ants to the new position. A `CANDIDATE` that ends
without motion (a plain click inside the selection) resets and does *not* deselect, so the
selection stays reusable.

#### Painting, cursor, and lifecycle

- `paintOverCanvas()` draws `comp.drawMovementContours(g2, MOVE_BOTH)` plus the
  `REL_MOUSE_POS` overlay while lifting, and `isDirectDrawing()` returns false then.
- `mouseMoved()` shows `Cursors.MOVE` over the lift area. The cheap shape test runs before
  `findLiftSource()`, which reads pixels.
- `altPressed()`/`altReleased()` and `arrowKeyPressed()` are suppressed during a lift: they
  act on the drag or nudge the selection alone, which would desynchronize it from the
  pixels being moved.
- `escPressed()` cancels the drag, calls `cancelLift()`, and returns **without** calling
  `super`, so Esc during a lift cancels the lift instead of the selection. Without the
  override, `DragTool.escPressed()` would cancel the drag, `DragTool.mouseReleased()` would
  return early, `dragFinished()` would never run, and the transaction would leak.
- `toolDeactivated()`, `forceFinish()`, `reset()`, and `compReplaced()` all defensively
  close an open lift. In practice `Tools.MouseDispatcher.beforeToolChange` synthesizes a
  mouse release first, so `dragFinished()` normally runs before deactivation — but
  `Tools.setActiveTool()` bypasses that path.

#### Options panel and presets

`initSettingsPanel()` calls `super` (combinator + Crop + To Path + Auto Merge) and appends
the **Auto Select Layer** checkbox. `saveStateTo`/`loadUserPreset` extend `super` with an
`Auto Select` key, so `Tools.setDefaultTool()` auto-loads
`~/.pixelitor/presets/Pixel Lift Tool/Default.txt` at startup with no extra wiring.

### 4. Registration

- `ToolIcons.paintPixelLiftIcon()` — a dashed marquee in the upper left with its content
  being lifted out towards the lower right.
- `Tools.PIXEL_LIFT`, inserted into `allTools` right after `ELLIPSE_SELECTION` (array order
  is toolbar order). `getSharedHotkeyGroups()` is unchanged — `J` is unshared, so
  `ToolsPanel.createButtonsPanel()` registers it automatically.

---

## Files

**New**
- `src/main/java/pixelitor/tools/selection/PixelLiftTool.java`
- `src/test/java/pixelitor/tools/selection/PixelLiftToolTest.java`
- `src/test/java/pixelitor/history/HistoryTransactionTest.java`

**Modified**
- `src/main/java/pixelitor/history/History.java` — transaction API + buffering in `add()`
- `src/main/java/pixelitor/history/MultiEdit.java` — `isHeavy()` from the children
- `src/main/java/pixelitor/tools/selection/MarqueeSelectionTool.java` — protected
  constructor, overridable Auto Merge tooltip
- `src/main/java/pixelitor/tools/Tools.java` — `PIXEL_LIFT` field + `allTools` entry
- `src/main/java/pixelitor/tools/ToolIcons.java` — `paintPixelLiftIcon`
- `website/user_guide.html`, `devdocs/TABLED-DISCUSSIONS.md`

---

## Verification

### Unit tests

`HistoryTransactionTest` (12 cases) covers the transaction API in isolation: empty
transaction adds nothing; a single child is added under its own name and un-embedded;
multiple children collapse into one `MultiEdit` whose undo/redo reaches every child;
collected edits are marked embedded; heaviness propagates from the children;
`abortTransaction()` undoes and adds nothing; `endTransaction()` is idempotent; undo and
redo are blocked while a transaction is open; `clear()` drops an open transaction; and
edits outside a transaction are unaffected.

`PixelLiftToolTest` (21 cases) drives the tool with synthetic `PMouseEvent`s over a
synthetic "screenshot" fixture (a solid widget rectangle on a contrasting background), so
that the filled hole and the moved pixels can both be checked:

- drags outside the selection, without a selection, Shift-drag, Alt-drag, and right-drag
  all produce ordinary selections and never lift;
- a drag inside the selection lifts, and with Auto Merge on the layer count returns to 1
  while with it off a second layer remains;
- one gesture is exactly one `Pixel Lift` history entry, one undo restores the pixels and
  the selection, redo reapplies it, and repeated lifts stay independent;
- a click inside the selection changes nothing and keeps the selection;
- no lift for a non-`ImageLayer` active layer (and no modal dialog), a hidden selection, a
  non-Replace combinator, a press outside a non-rectangular selection's shape, or when
  Auto Select finds no layer;
- Auto Select cuts from the layer under the point;
- Esc mid-lift restores everything and adds nothing to the history, while Esc without a
  lift still deselects;
- tool deactivation mid-lift commits instead of leaking the transaction;
- undo is ignored while lifting;
- the preset round-trips both checkboxes.

Run with
`./mvnw test -Dtest='PixelLiftToolTest,HistoryTransactionTest,MarqueeSelectionToolTest,MoveToolTest,TrailMoveToolTest,ToolTest,LayerViaFillCutTest'`,
then the full suite (`./mvnw clean package`) to confirm the `History.add()` change did not
disturb the many existing history assertions.

### Manual check

`./mvnw clean package`, then run `pixelitor.Pixelitor`. Open an image, press `J`, and
verify the new toolbar button and its two checkboxes:

1. Drag a marquee; drag again inside it — the pixels lift, the hole fills with the
   surrounding color, the marching ants follow.
2. With Auto Merge on, release → the Layers panel stays at one layer; **one** Ctrl+Z
   restores everything; Ctrl+Y redoes it.
3. With Auto Merge off, release → a new layer appears; still one undo step.
4. Drag from outside the selection — ordinary rectangle selection; Shift/Alt combinators
   and Alt expand-from-center still work; Space repositions the marquee.
5. Hover inside vs. outside the selection — the cursor switches between move and default.
6. Turn on Auto Select Layer with a multi-layer image and confirm the cut comes from the
   layer under the pointer.
7. Confirm the tool's Default preset round-trips: set both checkboxes, save a `Default`
   preset from the tool button's right-click menu, restart, and verify they are restored.
