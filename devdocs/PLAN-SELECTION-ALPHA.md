# Alpha-Bearing Selections Plan

## Goal

Evolve Pixelitor selections from transient vector shapes into hybrid selections
that can carry true 8-bit per-pixel coverage. A selected pixel must be able to
have any strength from 0 (unselected) through 255 (fully selected), and every
operation constrained by the selection must honor that strength.

The work is intentionally phased. The selection engine and all existing
consumers must become coverage-correct before user-facing commands create soft
selections. Quick Mask and saved selection channels are part of the complete
design, but are scheduled after the fundamentals.

## User-Facing Contract

### Coverage semantics

- A selection has 8-bit coverage at every canvas pixel.
- Existing rectangle, ellipse, lasso, polygon, path, and Magic Wand workflows
  retain their current gestures and combination controls.
- Hard vector rectangles remain exact hard selections. Other vector selections
  retain exact geometry and produce antialiased edge coverage when a pixel
  operation needs a mask.
- Soft selections affect edits across their entire nonzero coverage, including
  low-coverage tails that are outside the visible marching-ants contour.
- Marching ants for a mask-backed selection follow the 50% boundary: the
  contour between coverage below 128 and coverage of at least 128.
- A selection containing only coverage values from 1 through 127 is valid and
  affects edits even though it has no marching-ants contour.

### Existing commands

- Replace, Add, Subtract, Intersect, Invert, Deselect, Copy Selection, Paste
  Selection, Modify Selection, Convert to Path, crop commands, selection
  movement, and undo/redo work with both vector- and mask-backed selections.
- Inverse Crop still requires an exact hard vector rectangle. A raster mask is
  not accepted merely because its 50% contour or bounds happen to be
  rectangular.
- Convert to Path traces the 50% contour. If a nonempty selection has no 50%
  contour, it reports that no path can be produced instead of discarding the
  low-coverage selection.
- Copy Selection and Paste Selection preserve exact coverage inside Pixelitor.
  The system clipboard remains unrelated to these commands.

### New fundamental commands

- **Select > Modify Selection...** gains **Feather** as a modification type.
  Its amount is a Gaussian blur radius measured in image pixels.
- **Select > From Layer Transparency** creates coverage from the active layer's
  rendered canvas-space alpha. Rendering includes layer opacity and an enabled
  layer mask. Adjustment layers and any other layer that cannot render an
  independent image disable the command.
- **Select > From Layer Mask** creates coverage from the attached mask's raw
  grayscale values, whether or not that mask is currently enabled.
- If a selection already exists, either source command asks for Replace, Add,
  Subtract, or Intersect using the existing combination dialog. With no active
  selection, the command creates one directly.

### Quick Mask, in a later phase

- **Select > Quick Mask** and `Q` enter or leave Quick Mask mode.
- White represents fully selected, black represents unselected, and gray
  represents partial coverage.
- The canvas displays the unselected portion as a 50%-opacity red rubylith
  overlay. Normal marching ants are hidden while the mode is active.
- Entering with no selection starts with an all-black mask. Leaving with an
  all-black mask leaves the composition deselected.
- Brush, eraser, gradient, Paint Bucket, and compatible filters edit the mask.
- Each completed edit is a normal non-dirty selection history entry. It remains
  individually undoable after Quick Mask mode is exited.
- Merely entering and exiting without changing coverage adds no history entry
  and preserves exact vector selection data.

### Saved selection channels, in a later phase

- **Select > Save Selection...** saves exact active-selection coverage under a
  case-insensitively unique name.
- **Select > Load Selection...** selects a saved channel and a combination mode:
  Replace, Add, Subtract, or Intersect. Replace is the default.
- **Select > Manage Saved Selections...** renames and deletes channels without
  introducing a new dockable Channels panel.
- Saved-channel creation, replacement, rename, and deletion are undoable and
  make the document dirty. Loading changes only the transient active selection
  and does not make the document dirty.
- Named channels are stored only in PXC files. The active selection remains
  transient and is never saved.

## Current Architecture Audit

### Vector-only selection state

`Selection` currently owns one `java.awt.Shape`, plus UI state for marching
ants, hidden/frozen flags, and an optional pre-transform shape. Rectangularity
is type information (`shape instanceof Rectangle2D`), not a geometric test.
Selection history classes likewise store only `Shape` references.

`SelectionBuilder`, `ShapeCombinator`, `SelectionModifyType`, and
`Composition.invertSelection()` use `Area`, `BasicStroke`, and other vector
operations. Magic Wand starts with a boolean pixel mask but converts it into a
potentially large `Path2D` before creating a selection.

### Inconsistent pixel application

Selection consumers do not share one selection-coverage abstraction:

- `Composition.applySelectionClipping` installs a hard Java2D shape clip for
  brushes, Paint Bucket, and rasterized shapes.
- `TmpLayer` optionally rasterizes nonrectangular shapes into an antialiased
  temporary mask, but rectangles and most callers use hard clipping.
- `ImageUtils.replaceSelectedRegion`, `CopySource`, Gradient, and Trail Move
  build their own antialiased masks from the shape.
- Layer via Cut and Fill Cut extract rectangular bounds and clear or fill the
  source through a hard shape clip.
- Creating layer masks and selection-crop hiding masks rasterizes the selection
  shape independently.

These paths can disagree at antialiased edges and none can represent persistent
partial coverage away from an edge.

### Shape assumptions outside pixel application

- Crop, Inverse Crop, transforms, selection bounds, hit testing, movement,
  Pixel Lift, Trail Move, and Fill Cut sampling query the shape directly.
- Copy/Paste Selection stores a static `Shape` clipboard.
- Convert to Path assumes an exact shape is always available.
- `SelectionShapeChangeEdit`, `NewSelectionEdit`, and `DeselectEdit` store
  shapes and are always light, non-dirty history edits.
- `Composition.selection` is transient in PXC serialization, which correctly
  implements the existing active-selection lifetime.

### Reusable raster infrastructure

- Layer masks already use `TYPE_BYTE_GRAY` images and expose transparency and
  rubylith color-model views over grayscale raster data.
- `MaskedReplaceComposite` already demonstrates replacement interpolation from
  an external mask, although it currently reads an ARGB mask's alpha rather
  than an explicit grayscale coverage sample.
- PXC image serialization already writes grayscale images as PNG.
- Gaussian blur, temporary layers, partial-image history, and the normal
  Drawable tool pipeline provide useful implementation pieces, but must be
  adapted rather than treated as selection APIs.

## Core Model

### `SelectionData`

Introduce an immutable, sealed selection-data abstraction owned by `Selection`:

```java
public sealed interface SelectionData
    permits ShapeSelectionData, MaskSelectionData {

    Rectangle getCoverageBounds();
    Shape getOutline();
    Rectangle2D getOutlineBounds();
    Optional<Rectangle2D> getExactHardRectangle();
    boolean containsAtLeast(double x, double y, int threshold);
    SelectionMask materializeMask(Canvas canvas);
    SelectionData translated(double dx, double dy, Canvas canvas);
    SelectionData transformed(AffineTransform tx, Canvas canvas);
}
```

The names are illustrative but the concepts are required. General callers
must not regain a generic `getShape()` escape hatch after migration.

`ShapeSelectionData` stores exact immutable-by-convention vector geometry. It
retains the current cheap vector behavior for ordinary selections and lazily
caches its raster coverage and outline-derived values. Exact hard rectangles
are identified by representation, preserving the distinction needed by
Inverse Crop and hard-mask fast paths.

`MaskSelectionData` stores an authoritative `SelectionMask`. Its outline is a
lazy cached 50% contour. It never reconstructs selection coverage by filling
that contour.

`Selection` continues to own lifecycle and presentation state: the view,
marching-ants timer, hidden/frozen/disposed flags, and transform backup. Shape
fields become `SelectionData` fields. Its copy constructor can share selection
data because the data is immutable.

### `SelectionMask`

`SelectionMask` is an immutable value containing:

- A tightly bounded `TYPE_BYTE_GRAY` image.
- The image's canvas-space X and Y origin.
- Cached nonzero bounds and optional 50% contour.

Every stored sample is literal coverage from 0 through 255. Coordinates outside
the stored tile return 0. Construction defensively owns or copies its raster;
public methods never expose a writable raster. Transforming or combining masks
creates a new value.

Zero-only outer rows and columns are trimmed after operations. A result with no
nonzero samples is represented as no selection, not as an empty mask object.
Operations use bounded tiles expanded only to the necessary union, intersection,
blur kernel, or destination transform bounds.

Provide an internal alpha-view image sharing the grayscale raster through an
indexed color model, as LayerMask does. Keep that writable Java2D view confined
to mask rendering helpers so it cannot violate the public immutability
contract. Mask algebra should read grayscale samples directly to avoid
color-model ambiguity.

### Vector rasterization

Materializing a vector selection produces coverage on the canvas pixel grid:

- Exact hard rectangles are rasterized without antialiasing so their covered
  pixels remain 255 and all others remain 0.
- Other shapes are filled white into a grayscale mask with Java2D
  antialiasing enabled, matching Pixelitor's existing soft-edge behavior.
- The rasterization result is cached in `ShapeSelectionData` and reused by all
  pixel consumers.

This preserves resolution-independent vector edits until an operation such as
Feather, selection-from-alpha, Quick Mask painting, or combination with a
mask-backed selection makes raster coverage authoritative.

### Contour extraction

Extract the Magic Wand mask-to-path code into a deterministic contour tracer
that accepts an 8-bit mask and threshold. It must:

- Trace the boundary of samples whose coverage is at least the threshold.
- Preserve disconnected components and interior holes with the correct winding
  rule.
- Emit pixel-edge coordinates in canvas space using the mask origin.
- Produce stable segment ordering so tests and repeated conversions agree.
- Return an empty outline for a nonempty mask containing no samples at or above
  the threshold.

Magic Wand should retain its flood-filled boolean result as a hard 0/255
`SelectionMask`, avoiding the current eager conversion to `Path2D`.

## Selection Algebra and Geometry

Rename `ShapeCombinator` to `SelectionCombinator` and make it operate on
`SelectionData`. The public display names, preset values, modifier gestures,
and history names remain Replace, Add, Subtract, and Intersect.

When both operands are vector-backed, keep the current exact vector operations
and return `ShapeSelectionData`. If either operand is mask-backed, materialize
both over the minimum required bounds and combine each byte exactly:

| Mode | Result coverage |
| --- | --- |
| Replace | `B` |
| Add | `max(A, B)` |
| Subtract | `max(0, A - B)` |
| Intersect | `min(A, B)` |
| Invert | `255 - A` over the entire canvas |

Invert is the one operation whose processing bounds are always the canvas.
After inversion, trim only if the result has zero margins; do not drop the
implicit selected area outside the old mask tile.

Translations preserve exact mask samples by moving the tile origin when the
offset is integral. Fractional translations and other affine transforms render
coverage into a destination grayscale tile with bilinear interpolation,
transparent-zero pixels outside the source, and clipping to the canvas. Canvas
crop/resize/rotate operations apply the same image-coordinate transform used by
layers and paths.

Use the 50% outline for geometric interactions: selection-border movement,
Free Transform bounds, click-inside tests in Pixel Lift and similar tools, crop
bounds, and Convert to Path. Use nonzero coverage bounds for processing,
temporary buffers, and history-region allocation.

Expand, Contract, Border, Border Outwards Only, and Border Inwards Only keep
their current vector implementation for vector-backed selections. For
mask-backed selections, implement grayscale morphology whose radius is
calibrated to match the current `BasicStroke(amount)` half-width behavior:

- Expand uses grayscale dilation.
- Contract uses grayscale erosion.
- Border is the absolute coverage difference between dilation and erosion.
- Outward Border removes the original coverage from the dilated result.
- Inward Border removes the eroded result from the original coverage.

All outputs are clamped to 0 through 255 and trimmed.

## History and Clipboard

Introduce immutable `SelectionSnapshot` values containing the optional
`SelectionData` plus hidden state where an operation needs to preserve it.
Replace shape-specific state swapping with generic edits:

- `NewSelectionEdit` and `DeselectEdit` store selection snapshots.
- Replace `SelectionShapeChangeEdit` with `SelectionChangeEdit`, which swaps or
  explicitly stores before/after snapshots.
- Transform and movement edits capture `SelectionData`, not `Shape`.

All active-selection edits keep `makesDirty() == false`. A snapshot containing
a mask is a heavy edit for undo-limit accounting. Shape snapshots remain light.
Immutable data can be shared between the live selection and history; do not
copy a mask merely to hand it to an edit.

Replace the static shape clipboard in `SelectionActions` with optional
`SelectionData`. Copy may share immutable data. Paste clips the copied data to
the destination canvas without scaling, then invokes the existing interactive
combination choice when a destination selection exists.

## Coverage-Correct Pixel Application

### One replacement primitive

Introduce one image helper that applies a replacement through selection
coverage in premultiplied-alpha-correct form:

```text
result = replacement * coverage + original * (1 - coverage)
```

The interpolation applies to premultiplied color and alpha, followed by safe
unpremultiplication when the destination image requires straight ARGB. This
avoids color fringes when original or replacement pixels are transparent.

The helper accepts canvas-space coverage plus drawable translation and clips
work to the intersection of coverage bounds, replacement bounds, and the
drawable image. Exact hard rectangles and hard vector shapes may use Java2D
fast paths only when tests prove pixel-equivalent results.

Refactor or replace `MaskedReplaceComposite` so it consumes `SelectionMask`
coverage directly instead of assuming that mask strength is stored in ARGB
alpha.

### Filters and previews

`getSelectedSubImage`, filter source creation, preview replacement, commit, and
undo/redo must use the same coverage tile. Filters may still process the
rectangular nonzero selection bounds, but the result is blended back through
coverage. Undo/redo must restore the exact affected rectangle without
reapplying the current selection, because the active selection might have
changed since the edit was created.

### Drawing tools

Remove general use of `Composition.applySelectionClipping(Graphics2D)`.
Brushes, erasing, gradients, Paint Bucket, and rasterized shapes render their
unconstrained result into a temporary image covering the affected region, then
merge it through the selection mask. Hard clips remain only as a proven
optimization.

`TmpLayer` should accept optional `SelectionData` rather than a `softSelection`
boolean. Its merge-down operation, not arbitrary drawing code, applies
coverage. This gives direct and temporary brush targets the same result and
ensures erasing is attenuated rather than all-or-nothing.

### Copy, cut, lift, and trail operations

- Copy multiplies source premultiplied alpha and color by selection coverage
  within the nonzero bounds.
- Layer via Cut creates that covered extraction and blends the source toward
  transparent by the same coverage.
- Layer via Fill Cut creates the covered extraction and blends its sampled fill
  color into the source by coverage. Boundary-color sampling uses the 50%
  contour, retaining its current outer-band, inner-band, transparent fallback.
- Pixel Lift and Trail Move snapshot coverage-masked pixels, move the complete
  mask with the content, and preserve fractional edges at every stamp.
- Self Brush resamples pixels using the current moved selection data rather
  than reconstructing a mask from an outline.

### Layer masks and crop

Selection-to-layer-mask operations copy literal grayscale coverage. For vector
selections they use the selection's single cached materialization. Hide
Selection uses `255 - coverage`; Reveal Selection uses coverage unchanged.

A mask-backed selection follows the nonrectangular selection-crop path even if
its contour is rectangular. Only Crop uses its 50% outline bounds. Crop and Hide
and Only Hide create layer masks from exact coverage, transformed into the new
canvas coordinate system where necessary.

Inverse Crop accepts only `getExactHardRectangle().isPresent()` and continues
to enforce its full-width/full-height rules.

## Phase 1: Hybrid Fundamentals

1. Add `SelectionData`, `ShapeSelectionData`, `MaskSelectionData`, and
   `SelectionMask`, including bounds trimming, vector materialization, contour
   extraction, translation, affine transformation, and inversion.
2. Change `Selection` and `Composition` to store and expose selection concepts
   instead of raw shapes. Keep narrow compatibility helpers only while call
   sites are actively being migrated; remove them by the end of Phase 2.
3. Generalize `SelectionBuilder`, combination, Magic Wand, inversion, modify,
   copy/paste, path conversion, transform, and selection history.
4. Render marching ants from the exact vector outline or the cached 50%
   contour.
5. Keep all user-visible creation commands producing the same hard/vector
   selections as before. No Feather or alpha-source menu command ships in this
   phase.

Phase 1 is complete when vector behavior passes unchanged, mask-backed
selections can be constructed and manipulated through tests, and no core
selection lifecycle assumes that a shape is authoritative.

## Phase 2: Consumer Migration

1. Add the coverage-aware replacement primitive and migrate filter preview and
   commit paths.
2. Migrate direct drawing, temporary drawing layers, gradients, Paint Bucket,
   rasterized shapes, and erasing.
3. Migrate Copy, Cut, Fill Cut, Pixel Lift, Trail Move, movement, Free
   Transform, layer-mask creation, selection crop, and any remaining shape
   consumers found by repository-wide search.
4. Remove general `getSelectionShape`, `Selection.getShape`, and
   `applySelectionClipping` APIs. Retain explicit outline access only for code
   that genuinely performs geometry.

Phase 2 is complete only when every operation constrained by a selection uses
coverage and tests demonstrate partial effects. This phase is the release gate
for all later alpha-selection UI.

## Phase 3: Feather and Alpha Sources

### Feather

Add Feather to `SelectionModifyType` and the existing Modify Selection dialog.
The amount control keeps the current 0 through 100 pixel range initially.

Preview behavior:

- Capture the original immutable `SelectionData` once when the dialog opens.
- Materialize it, pad by the Gaussian kernel radius without exceeding canvas
  bounds, blur grayscale coverage, and trim the result.
- Every preview starts from the original data rather than the previous preview.
- Cancel restores the exact original representation.
- OK adds one `SelectionChangeEdit`; amount zero makes no change.

### From rendered layer alpha

Add a layer API that renders an independent canvas-sized image with its enabled
mask and opacity applied but without its blending mode or neighboring layers.
Use it for every renderable layer type, repairing implementations such as layer
groups that currently ignore `toImage` flags. Adjustment layers return no
independent image and disable the command.

Extract the rendered image's alpha byte into a trimmed `SelectionMask`.
All-zero alpha reports that the layer has no selectable transparency content;
all-255 alpha creates a full-canvas selection.

### From layer mask

Copy the active layer mask's raw grayscale data into canvas coordinates. Ignore
whether the mask is enabled and ignore owner opacity. Disable the command when
the active layer has no mask. An all-black mask reports that nothing is
selected.

## Phase 4: Quick Mask

### Session and editing target

Introduce a transient `QuickMaskSession` owned by `Composition` and a
`QuickMaskDrawable` exposed as the active editing target while the mode is
enabled. The session contains:

- The immutable entry `SelectionSnapshot`.
- A mutable canvas-sized grayscale working image.
- Whether any edit has completed.
- The previously active drawable/mask-editing state needed for restoration.

Tools may draw through the normal grayscale Drawable pipeline, but they must
not add ordinary `ImageEdit` or `PartialImageEdit` objects targeting a disposed
temporary drawable. At each completed brush, fill, gradient, or filter action,
capture before/after immutable selection snapshots and add a
`SelectionChangeEdit`. Applying that edit updates both the live Quick Mask image
when the session exists and the ordinary active selection when it does not.
This is what makes each operation undoable after leaving the mode.

Entering and exiting are view-state transitions and add no edits. If nothing
changed, restore the exact entry representation. If coverage changed, exiting
installs trimmed `MaskSelectionData`, or deselects for an all-zero result.

### Overlay and command routing

Extract the rubylith color-model construction shared with `LayerMask` into a
reusable mask-view utility. Paint `255 - coverage` in red with 50% global
opacity over the composition and below tool outlines.

While Quick Mask is active:

- Hide marching ants and route compatible grayscale editing tools to the Quick
  Mask drawable.
- Keep layer selection visually unchanged in the Layers panel.
- Disable selection creation, selection modification, crop, inverse crop,
  layer extraction, Free Transform, and canvas geometry commands unless the
  command explicitly exits Quick Mask before continuing.
- Tool switches within the compatible set do not leave the mode.
- Closing or reloading a composition discards the transient session safely.

Bind `Q` in normal application key handling. `RandomGUITest` currently reserves
`Q` as its exit character; move that internal-only exit binding or intercept it
before application shortcuts so random GUI testing cannot toggle Quick Mask by
accident.

## Phase 5: Saved Selection Channels

### Data model and document lifecycle

Add a serializable `SelectionChannel` value containing a name and immutable
`SelectionMask`. `Composition` owns an ordered list of channels. Names are
trimmed, nonempty, and unique case-insensitively; preserve the user's original
case for display.

Saved masks use canvas coordinates and participate in crop, resize, rotate,
flip, and other image-coordinate changes. Composition duplication deep-copies
the list values while immutable raster storage may be shared until a transform
creates a new mask.

Channel mutations use dedicated heavy history edits, mark the composition
dirty, and update open management dialogs. Loading creates or combines a
transient active selection through `SelectionCombinator`, adds a non-dirty
selection edit, and does not mutate the saved channel.

### Dialogs

**Save Selection** contains a name field and the ordered existing names. Saving
to an existing case-insensitive name asks for replacement confirmation.

**Load Selection** contains a channel selector with thumbnails and a Replace,
Add, Subtract, Intersect selector defaulting to Replace. Add/Subtract/Intersect
remain valid without an active selection by treating the existing coverage as
zero; Intersect then produces nothing selected.

**Manage Saved Selections** lists thumbnail and name, with Rename and Delete
actions. Each accepted action is its own undo entry. No channel visibility or
RGB-channel concepts are introduced.

### PXC version 5

Keep the active `Composition.selection` and Quick Mask session transient. Make
the saved channel list persistent and bump `CURRENT_PXC_VERSION_NUMBER` from 4
to 5.

- Version 4 files deserialize with an empty channel list.
- Version 5 serializes each grayscale mask through the existing PXC image
  helpers and restores its canvas origin and name.
- Include saved-channel images in `Composition.countImages()` so progress
  allocation remains accurate.
- Preserve the current ability to read versions 3 and 4.
- ORA, PNG, JPEG, TIFF, and other exports omit saved channels.

## Testing

### Core mask and contour tests

- Construction rejects mutable exposure, trims zero margins, preserves canvas
  origin, and recognizes all-zero results as no selection.
- Coverage lookup and bounds work for masks partly or wholly outside a layer.
- Replace/Add/Subtract/Intersect/Invert are checked at coverage values 0, 64,
  128, and 255, including unequal mask origins and canvas edges.
- Contour tracing covers a rectangle, diagonal pixels, disconnected islands,
  nested holes, a one-pixel feature, and a nonempty below-128 mask.
- Exact rectangle rasterization is hard; ellipse/lasso rasterization has
  antialiased coverage; repeated materialization uses the same cached data.
- Integral translation is byte-exact. Fractional translation, scale, rotate,
  crop, resize, and clipping have deterministic expected masks.

### Selection lifecycle and history tests

- Create, replace, combine, modify, invert, transform, deselect, undo, and redo
  restore vector and mask representations correctly.
- Mask history edits are heavy and non-dirty; saved-channel edits are heavy and
  dirty.
- Copy/Paste Selection preserves exact bytes and clips at smaller destination
  canvases.
- Convert to Path preserves holes and reports the below-128-only case.
- Magic Wand produces a hard mask with the same selected pixels as before.
- The existing rectangular nudge, undo, and Inverse Crop regression remains
  covered; soft/mask selections are rejected by Inverse Crop.

### Pixel-consumer tests

For filters, previews, brush painting, erasing, gradients, Paint Bucket,
rasterized shapes, copy, cut, Fill Cut, Pixel Lift, and Trail Move, test at
least one mask containing 0, 64, 128, and 255 coverage. Assert exact or
one-rounding-unit pixel results as appropriate.

Include transparent and semitransparent source/replacement colors to prove that
premultiplied interpolation introduces no hidden-color fringes. Repeat key
tests on translated layers, grayscale layer masks, selections extending beyond
layer content, and undo/redo after the active selection changes.

### Feature tests

- Feather preview always starts from the original, amount zero is a no-op,
  cancel restores exact vector data, and OK creates one edit.
- Layer Transparency includes opacity and enabled masks for each supported
  layer type; unsupported adjustment layers and all-transparent results are
  handled without history corruption.
- Layer Mask reads raw values while disabled and combines correctly with an
  existing selection.
- Quick Mask covers selected and deselected entry, unchanged exit, compatible
  tools, filters, all-zero exit, per-operation undo inside and after exit,
  overlay rendering, command disabling, and the `Q` test-harness collision.
- Saved channels cover naming validation, replacement confirmation, every load
  combinator, mutation history, dirty state, canvas transforms, PXC v5
  round-trip, and v4 migration.

## Validation and Rollout

Each code phase requires:

1. Focused tests for the changed selection subsystem and consumers.
2. `./mvnw clean test` under JDK 25 or newer on a graphical desktop.
3. `git diff --check`.
4. Manual GUI verification at several zoom levels, including ants, Feather,
   drawing through partial coverage, undo/redo, and any phase-specific dialogs.
5. `./macDeployToApplications` after the phase is stable so the packaged app is
   ready for user testing.

The plan document itself needs Markdown review and `git diff --check`; it does
not require an application deployment.

## Explicit Non-Goals and Defaults

- Active selections remain transient, do not mark documents dirty, and are not
  restored after save/reopen.
- Selection coverage is 8-bit, not float or 16-bit.
- The initial ordinary soft-selection display is only the 50% marching-ants
  contour; a persistent overlay arrives with Quick Mask.
- Saved selections use Select-menu dialogs, not a dockable Channels panel.
- Named channels are PXC-only and do not attempt Photoshop channel import or
  OpenRaster extension support.
- There is no compatibility layer for the old internal shape-only APIs after
  all consumers migrate.
- Quick Mask and saved channels must not be pulled into the fundamentals merely
  because the core representation can support them.
