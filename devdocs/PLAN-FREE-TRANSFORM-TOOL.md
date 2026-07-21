# Free Transform Tool Plan

## Goal

Add a complete Free Transform workflow that lets the user move, scale, rotate,
skew, distort, apply perspective, and warp an active raster layer in one
continuous, live-preview session.

The implementation should build on the existing Move tool free-transform
session and `TransformBox`, not create an unrelated transformation stack. The
same session must remain undoable, cancelable, and synchronized with the tool
settings bar.

The work has two user-facing priorities:

1. Make ordinary layer scaling proportional by default, with Shift temporarily
   toggling proportional/non-proportional behavior.
2. Grow the existing affine transform box into the full Free Transform
   command, including projective and warp modes.

## Scale layers proportionally

Corner-handle scaling should preserve the original aspect ratio by default.
Holding Shift while dragging a corner temporarily switches to independent
horizontal and vertical scaling; releasing Shift during the same drag returns
to proportional scaling. This behavior applies to both the Edit command and
the Move tool checkbox.

## Transform freely

A single live session should support affine, projective, and warp adjustments
without repeatedly rendering the result of the previous adjustment. The user
can switch transformation types with pointer location, keyboard modifiers, and
the Warp control in the tool settings bar, then commit or cancel the entire
operation.

## User-Facing Contract

### Entry points

- Add **Edit > Free Transform**.
- Assign `Cmd+Shift+T` on macOS and `Ctrl+Shift+T` on Windows/Linux. In code,
  add `Keys.CTRL_SHIFT_T`, where `CTRL` continues to mean the platform menu
  shortcut modifier.
- Invoking the command activates the Move tool and immediately starts a Free
  Transform session for the active raster image layer.
- Keep the existing Move tool **Free Transform** checkbox as a second entry
  point, with its current label.
- Invoking the command while a Free Transform session is already active should
  leave that session active rather than committing and recreating it.

The command is enabled only when a composition is open. At invocation time it
must validate that the active layer is a transformable `ImageLayer` and that a
usable transform bound exists. Unsupported and fully transparent layers should
produce a focused informational message and must not change tools or history.

### Target and bounds rules

The two entry points intentionally use different layer bounds:

| Entry point / target | Initial transform bounds |
| --- | --- |
| **Edit > Free Transform** on an image layer | Minimum rectangle enclosing every pixel whose alpha is nonzero: `ImageLayer.getContentBounds(false)` |
| Move tool **Free Transform** checkbox for a layer | The layer image buffer's true edge, including transparent edge pixels: `ImageLayer.getContentBounds(true)` |
| Move tool **Free Transform** checkbox for a selection border | The selection shape bounds; retain a small component-space handle clearance so the box does not hide a rectangular selection |
| Move mode targeting both layer and selection border | Union of the layer-buffer bounds and selection bounds |

This distinction is required. The command's box begins at the first
non-transparent pixel on every edge, while the persistent Move-tool controls
use the actual layer-buffer edge.

The alpha test is `alpha != 0`, so partially transparent edge pixels count as
content. A fully transparent image layer has no opaque-content bounds and
cannot start the command, but it can still show Move-tool transform controls
using its buffer bounds.

Initially, **Edit > Free Transform** transforms the active raster image layer.
The existing Move tool modes continue to control whether the checkbox targets
the layer, the selection border, or both. Transforming selected pixels as an
independent virtual layer and transforming vector paths from this command are
separate target integrations; they should use the same session and mapping
interfaces after the raster implementation is stable. Pixelitor's existing Pen
tool path-transform mode remains available in the meantime.

### On-canvas interactions

All modifier behavior is evaluated continuously during a drag. Pressing or
releasing a modifier mid-drag must update the preview without accumulating
error or causing the handle to jump; every result is recalculated from the
drag-start geometry.

| Gesture | Result |
| --- | --- |
| Drag inside the box | Move the item |
| Drag a corner | Scale proportionally by default |
| Shift-drag a corner | Toggle to non-proportional scaling |
| Drag a side handle | Scale one axis |
| Option/Alt-drag a scale handle | Scale around the selected reference point instead of the opposite handle/edge |
| Drag outside the bounding border | Rotate around the selected reference point; show a curved two-sided rotation cursor |
| Shift-drag while rotating | Constrain rotation to 15-degree increments |
| Command/Ctrl-drag a corner | Distort by moving that corner independently |
| Command+Shift/Ctrl+Shift-drag a side handle | Skew along that side's axis |
| Command+Option+Shift/Ctrl+Alt+Shift-drag a corner | Apply perspective while preserving a valid projective quadrilateral |
| Arrow key | Move by one image pixel |
| Shift-arrow | Move by ten image pixels |

The platform menu modifier is Command on macOS and Ctrl on Windows/Linux. Java
mouse handling must normalize this deliberately; it must not assume that
`MouseEvent.isControlDown()` means the platform menu modifier on macOS.

The existing external rotation handle may remain during the first affine
milestone, but the completed interaction should also allow rotation from the
area just outside the box. Outside-rotation hit testing must use a narrow halo
around the transformed outline so that clicks far from the box remain ordinary
canvas clicks.

### Tool settings bar

While a transform is active, replace the ordinary Move settings with a compact
transform-specific set of controls:

- 3-by-3 reference-point locator.
- X and Y coordinates of the selected reference point.
- Relative-position toggle for X/Y.
- Width and Height as percentages of the session's original bounds.
- Link toggle for proportional numeric scaling.
- Rotation angle in degrees.
- Horizontal and vertical skew angles.
- **Warp** / **Free Transform** mode toggle.
- Warp-style selector while Warp mode is active.
- Commit and Cancel buttons.

The controls must be two-way bound to the transform model:

- A handle drag updates the numeric fields.
- A committed numeric edit updates the handles and live preview.
- Programmatic field refreshes do not create recursive change events.
- Invalid, non-finite, zero-area, or excessively large values are rejected
  without losing the last valid transform.
- Each completed numeric adjustment is one `TransformStepEdit`, just like one
  completed handle drag.

With relative positioning off, X/Y display absolute image-space coordinates.
With it on, X/Y display zero initially and apply deltas relative to the state at
the time relative mode was enabled. Width/Height remain percentages of the
original transform bounds, not of the previously rendered preview.

The selected reference point is the pivot for rotation and center-relative
scaling. Changing it changes the pivot without moving transformed content.

### Commit, cancel, and history

Commit the complete session when the user does any of the following:

- Presses Enter/Return.
- Clicks the Commit button.
- Double-clicks inside the transform box.
- Selects a new tool.
- Clicks another layer in the Layers panel. The existing transform is committed
  first, then the clicked layer becomes active.

Cancel the complete session when the user:

- Presses Esc.
- Clicks the Cancel button.

`Edit > Undo` during a session undoes only the last completed handle, move,
numeric, mode, or reference-point adjustment and keeps the session active.
Applying the session creates one durable content edit. Undoing that apply edit
restores the original pixels and the interactive transform session; redoing it
reapplies the committed pixels and closes the restored session.

A canceled session restores the exact pre-session layer state. Closing or
reloading the composition cancels without attempting to restore a UI session
in a no-longer-valid view.

## Current Architecture Audit

### Reusable pieces

- `MoveTool` already owns an interactive `TransformBox`, a `Transformable`
  target, the initial memento, apply/cancel behavior, and Move-tool controls.
- `TransformBox` already supports live affine translation, independent X/Y
  scale, rotation, edge and corner handles, image/component coordinate changes,
  arrow nudging, and serializable mementos.
- `ImageLayer` already previews affine transforms non-destructively by painting
  through `liveTransform`, then renders a bilinear transformed image only on
  commit.
- `Selection` and `CompositeTransformable` already implement the same
  `Transformable` session lifecycle.
- `TransformStepEdit`, `ApplyTransformEdit`, `CancelTransformEdit`, and
  `TransformUISnapshot` already model incremental changes and apply/cancel
  restoration.
- `ImageLayer.getContentBounds(boolean)` and
  `ImageUtils.calcOpaqueBounds(...)` already provide both required layer-bound
  policies.
- `PerspectiveFilter` already contains a quad-to-quad inverse projective
  mapping and bilinear/bicubic sampling that can inform the projective renderer.

### Gaps that must be addressed

- `MoveTool.createTransformBox()` currently uses canvas bounds for layer
  transforms instead of either form of layer bounds.
- Corner scaling is always independent in X/Y; handle methods do not receive
  current modifier state.
- Rotation is available from a dedicated handle, but Shift does not snap to
  15-degree increments and there is no outside-border rotation zone.
- The pivot is always the box center and is not independently selectable.
- `TransformBox.calcImTransform()` can represent only affine mappings. A four-
  corner projective transform and a warp mesh cannot be represented by
  `AffineTransform`.
- `Transformable.imTransform(AffineTransform)` and `ImageLayer.liveTransform`
  hard-code that same affine limitation.
- `PerspectiveFilter` assumes equal source/destination image dimensions. Free
  Transform needs arbitrary destination bounds and a mapping expressed in
  canvas coordinates.
- Full-resolution projective or warp rendering on every mouse event would block
  the Swing event thread. Non-affine preview work needs coalescing and stale-
  result protection.
- `MoveTool.editingTargetChanged(...)` currently cancels when the active layer
  changes; the required behavior is commit-before-switch.
- `TransformUISnapshot` stores only a Move mode and box memento. It must also
  identify the original target, bounds policy, entry point, transform mode,
  pivot/reference state, and any projective or warp geometry.

## Proposed Design

### 1. Extract a transform session from `MoveTool`

Introduce a `FreeTransformSession` owned by `MoveTool`. It should contain:

- The composition and exact target captured at session start.
- `TransformStartSource` (`EDIT_COMMAND` or `MOVE_CONTROLS`).
- `TransformBoundsPolicy` (`OPAQUE_CONTENT`, `LAYER_BUFFER`,
  `SELECTION_BOUNDS`, or `COMBINED_BOUNDS`).
- The original bounds and original target state.
- Current `TransformGeometry` and reference point.
- The transform box/widget.
- Initial and current mementos.
- Preview-render generation/cancellation state.

Move session creation, apply, cancel, restore, and target validation out of the
individual checkbox listener into this object. `MoveTool` should continue to
route pointer and key events and paint the widget, but it should no longer be
the authoritative store for every piece of session state.

Add a public, narrow entry method such as:

```java
public void startFreeTransform(TransformStartSource source)
```

The Edit action activates `Tools.MOVE`, then calls this method with
`EDIT_COMMAND`. The checkbox calls it with `MOVE_CONTROLS`. Target selection and
bounds policy are resolved once at session start and are not silently changed
if the Move mode selector changes later.

### 2. Make geometry independent from rendering

Introduce a transformation value abstraction instead of passing only
`AffineTransform`:

```java
sealed interface TransformMapping
    permits AffineMapping, ProjectiveMapping, WarpMapping {
    Rectangle2D destinationBounds();
    Point2D mapSourceToDestination(Point2D source);
    boolean isIdentity();
}
```

The concrete models should be immutable snapshots. Interactive widgets may
maintain mutable working points, but every preview and history entry receives a
complete immutable mapping derived from the session's original geometry.

- `AffineMapping` wraps the current translation/scale/rotation/skew result and
  can expose an `AffineTransform` for existing affine targets.
- `ProjectiveMapping` stores the source rectangle and four ordered destination
  corners, plus forward/inverse homographies.
- `WarpMapping` stores the source rectangle, warp style, and all mesh control
  points required to reproduce a custom warp.

Change `Transformable` to accept a `TransformMapping` for preview. Give each
target a capability set so the UI can disable projective/warp modes for targets
that have not implemented them. During migration, a default affine adapter can
forward `AffineMapping.affineTransform()` to the existing method.

Do not reconstruct a transform from the previously transformed preview. Every
mapping must be from the session-start source to the current destination; this
preserves image quality and prevents cumulative coordinate drift.

### 3. Generalize `TransformBox`

Refactor `TransformBox` from a permanently rectangular affine box into a widget
over current transform geometry.

For affine mode it continues to maintain four corners, four edge handles, a
reference point, and the transformed outline. Its operation resolver chooses a
drag behavior based on the hit target and normalized modifiers:

```text
corner + menu + alt + shift  -> perspective
corner + menu                -> free distort
side   + menu + shift        -> skew
scale handle + alt           -> scale around reference point
corner                       -> proportional scale
corner + shift               -> non-proportional scale
outside border               -> rotate
inside border                -> move
```

For proportional corner scaling, transform the pointer into the box's
drag-start local coordinate system and project it onto the aspect-preserving
ray from the fixed anchor. Use the resulting single signed scale for both axes;
the signed value preserves intentional flips. For ordinary scaling the anchor
is the opposite corner. With Option/Alt down, the reference point is fixed and
the opposite handle moves symmetrically.

For rotation, compute the raw angle from the reference point to the current
pointer. With Shift down, round the drag-start-relative delta to the nearest 15
degrees. Recalculate from raw pointer coordinates whenever Shift changes so
snapping never compounds rounding error.

For skew, keep the opposite side fixed and translate the dragged side parallel
to that side in the box's local coordinate system. This remains affine.

For free distort, move only the selected corner. For perspective, update the
paired corners according to the perspective constraint while maintaining
corner order and an invertible quadrilateral. Reject a state before it becomes
zero-area, self-intersecting, or numerically singular.

`TransformBox.Memento` must capture the complete geometry, selected reference
point, active free-transform/warp mode, and warp style/control points. Memento
equality must include all user-visible state so no-op adjustments do not create
history entries.

### 4. Add numeric controls as another geometry editor

Create a `TransformOptionsPanel` responsible only for presenting and editing
the active session model. Keep transformation math in the geometry classes.

- X/Y update the selected reference point and translate all destination
  geometry by the same delta.
- Width/Height update affine scale around the reference point. When linked,
  editing either field applies its signed scale to both axes.
- Rotation updates affine geometry around the reference point.
- Horizontal/vertical skew update affine shear relative to the original local
  axes.
- Switching into Warp mode initializes a regular mesh from the current
  destination quad; switching back preserves the last affine/projective state
  so toggling modes is reversible until commit.

One model-change notification should refresh the transform box, options panel,
preview, and enabled states. Do not have the box and fields call each other
directly.

### 5. Render affine, projective, and warp mappings

Keep the existing immediate `Graphics2D` paint path for affine previews.

For projective and warp mappings, add a raster `TransformRenderer` that:

- Accepts the pristine session-start image, its canvas translation, the mapping,
  interpolation mode, and required destination bounds.
- Uses inverse mapping so every destination pixel samples the original image.
- Uses transparent edge handling and at least bilinear interpolation for live
  preview and final commit.
- Produces a translated image whose bounds include the transformed result and
  continue to satisfy Pixelitor's current requirement that an `ImageLayer`
  buffer cover the canvas.
- Performs all width, height, area, overflow, and memory checks before
  allocation.

The existing `PerspectiveFilter` math can be extracted or adapted, but its
same-size destination assumption must not leak into the session API. Convert
canvas-space destination corners into renderer-local coordinates after the
destination bounds are chosen.

Non-affine live previews must not render synchronously on the Swing event
thread. Coalesce drag updates so at most one render is running and only the
newest requested generation may install its result. A stale completion is
discarded. Releasing the mouse or committing waits for or requests a final
full-quality render of the newest mapping.

For Warp mode, begin with a regular 4-by-4 control-point grid (3-by-3 cells).
Render it as a consistently triangulated mesh with inverse barycentric mapping
per triangle. This gives deterministic custom control-point warping and a
straightforward way to serialize presets. Shared-edge coverage rules and tests
must prevent cracks between triangles.

Named warp styles should generate mesh control points from normalized
parameters; the generated mesh then uses the same custom-warp renderer. Start
with **Custom**, **Arc**, **Arch**, **Bulge**, **Flag**, and **Wave**, and keep
the style generator API open to more presets without adding renderer branches.
Editing a generated control point changes the selector to **Custom**.

### 6. Keep session lifecycle and history target-stable

History snapshots must refer to the target captured at session start rather
than re-resolving `comp.getActiveLayer()` during undo. This is essential when a
layer click commits one target and activates another.

The layer-change flow should be:

1. Detect an active transform whose target differs from the requested layer.
2. Finalize the old target and add the apply edit.
3. End the old UI session.
4. Continue activating the requested layer.

Tool changes follow the same commit-first rule. Composition replacement,
reload, and close use cancel/cleanup because the original target or view may no
longer be valid.

Incremental step edits should swap immutable session mementos. Applying or
canceling a session should remove or close out step edits using the existing
history conventions, and undoing an apply edit should recreate the session from
its exact target descriptor, mapping, source, bounds policy, and options state.

## Implementation Sequence

### Phase 1: Proportional scaling and exact bounds

- Add a normalized modifier snapshot to transform pointer events.
- Make corner scaling proportional by default and Shift toggle independent
  X/Y scaling.
- Add Option/Alt center-relative scaling.
- Add Shift-constrained 15-degree rotation.
- Extract layer/selection bounds resolution and implement the exact command vs
  checkbox policies.
- Replace canvas bounds in `MoveTool.createTransformBox()` with resolved target
  bounds.
- Add focused `TransformBoxTest`, `MoveToolTest`, and `ImageLayerTest` coverage.

This phase is independently shippable and fulfills the “Scale layers
proportionally” priority.

### Phase 2: Command, affine options, and lifecycle

- Add `Keys.CTRL_SHIFT_T`.
- Add `FreeTransformAction` and **Edit > Free Transform**.
- Extract `FreeTransformSession` and store the exact target.
- Add the reference-point locator, X/Y, relative positioning, linked W/H,
  rotation, skew, Commit, and Cancel controls.
- Add outside-border rotation and modifier-sensitive cursors.
- Implement skew.
- Change layer switching from cancel to commit-before-switch.
- Expand history snapshots and restoration.

This phase delivers a complete affine Free Transform command.

### Phase 3: Free distort and perspective

- Introduce `TransformMapping` and target capabilities.
- Add projective geometry, homography validation, and mementos.
- Generalize corner/edge rendering from a rotated rectangle to a quadrilateral.
- Implement Command/Ctrl corner distort.
- Implement Command+Option+Shift/Ctrl+Alt+Shift perspective.
- Add coalesced non-affine raster previews and high-quality commit rendering.
- Add deterministic image fixtures for identity, affine-equivalent quads,
  perspective, transparency, translated layers, and undo/redo.

### Phase 4: Warp

- Add the Free Transform/Warp toggle and warp options UI.
- Add the 4-by-4 control grid and custom control-point editing.
- Add mesh rendering and seam tests.
- Add the first named style generators.
- Persist the full warp mesh in mementos and history snapshots.
- Add preview cancellation, stress, and maximum-allocation tests.

### Phase 5: Additional transform targets and polish

- Evaluate selected-pixel transformation as a `Transformable` target, reusing
  the selection snapshot/masking work currently local to `TrailMoveTool`.
- Adapt selection-border and path targets to the new affine mapping adapter.
- Decide separately whether shape/text/smart-object layers remain native during
  affine transforms or require rasterization for projective/warp modes.
- Add localized strings, status messages, tooltips, user-guide documentation,
  and release notes.
- Perform hands-on macOS and Windows/Linux modifier verification.

## Test Plan

### Geometry unit tests

- Proportional corner scaling from every corner, including rotated boxes and
  horizontal/vertical flips.
- Shift toggling before and during a corner drag.
- Option/Alt scaling keeps the selected reference point fixed.
- Rotation snaps to exact 15-degree deltas and returns to the raw angle when
  Shift is released.
- Skew respects local axes after rotation.
- Free-distort moves only the requested corner.
- Perspective preserves the required paired-corner constraint.
- Degenerate, self-intersecting, non-finite, and near-singular mappings are
  rejected without corrupting the previous state.
- Numeric and pointer operations produce equivalent geometry.
- Memento equality and restore cover pivot, mode, quad, and warp mesh.

### Bounds tests

- A translated image layer with transparent padding gets opaque content bounds
  from the Edit command and full buffer bounds from Move controls.
- A one-pixel, partially transparent edge is included.
- A fully transparent layer fails the Edit command but can use Move controls.
- Selection-only bounds preserve component-space handle clearance at multiple
  zoom levels.
- Combined layer-and-selection bounds use their union.

### Rendering tests

- Identity mappings are pixel-identical no-ops.
- Affine mappings through the generalized renderer agree with the existing
  affine path within the interpolation tolerance.
- Projective destination corners land at exact expected positions.
- Transparent samples outside the source stay transparent.
- Warp triangles have no transparent seams at shared edges.
- Translated and off-canvas layers commit with correct `tx`/`ty`.
- Oversized output is rejected before allocation.
- Stale asynchronous previews never replace a newer preview.

### Workflow and history tests

- `Cmd/Ctrl+Shift+T` starts the command on a valid image layer.
- The command and checkbox select the required different bounds.
- Reinvoking the command during a session does not restart it.
- Enter, Commit, double-click, tool change, and layer change each commit once.
- Esc and Cancel restore the pristine state.
- Undo during a session restores the previous geometry and keeps controls live.
- Undo after apply restores the exact original target and transform UI even if a
  different layer is currently active.
- Redo reapplies the committed raster and ends the restored session.
- Closing/reloading a composition clears pending preview work safely.

### Validation commands

Use focused tests during each phase, followed by the full suite on a graphical
desktop because Pixelitor's complete tests are not reliably headless:

```bash
./mvnw -Dtest=TransformBoxTest,MoveToolTest,ImageLayerTest test
./mvnw clean test
git diff --check
```

Use JDK 25 or newer, as required by the repository.

## Documentation Updates Required With Implementation

- Update `website/user_guide.html` with the entry points, platform modifiers,
  numeric controls, warp mode, commit/cancel behavior, and the required bounds
  distinction.
- Add the feature and shortcut to `website/release_notes.html`.
- Keep menu labels, tooltips, status-bar help, and user-guide terminology
  consistent: **Free Transform** names both the Edit operation and the existing
  Move-tool checkbox; the surrounding context distinguishes them.

## Acceptance Criteria

- The Edit command starts from the active image layer's non-transparent bounds;
  Move controls start from the true layer-buffer bounds.
- Corner scaling is proportional by default and Shift reversibly toggles it.
- All documented pointer modifiers work on macOS and Windows/Linux using the
  correct platform menu modifier.
- Drag and numeric operations stay synchronized and always derive from the
  pristine session-start source.
- Affine, projective, and warp previews match the final committed geometry.
- No preview render blocks the Swing event thread or installs stale output.
- Every commit path creates one durable content edit; every cancel path restores
  the exact original state.
- Undo/redo works both during the interactive session and after apply/cancel.
- Focused tests, the full graphical test suite, and `git diff --check` pass.
