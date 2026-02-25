# Tabled Discussions

Ideas and improvements discussed but deferred for future work.

## 1. fromSubImage offset nudge

When creating a new layer via `ImageLayer.fromSubImage()`, slightly offset the new layer (~3px) so the user can visually confirm the layer was created without having to do much work to reposition it back.

**Files:** `ImageLayer.fromSubImage()`

---

## 2. LayerMultiEdit helper class

A reusable `MultiEdit` subclass that automatically refreshes affected layer icons on undo/redo, replacing the anonymous subclass pattern used in `layerViaCut()`.

```java
new LayerMultiEdit(name, comp, edits, affectedLayers, additionalAffectedLayerSupplier)
```

The `Supplier` handles layers that don't exist yet at edit creation time (e.g. the new layer created by Layer via Cut). Wait for a second use case before extracting.

**Files:** `Composition.layerViaCut()`, `history/MultiEdit.java`

---

## 3. Systematic layer icon refresh overhaul

`updateIconImage()` is called ~58 times across the codebase, always manually. Several layer methods modify the image without refreshing the thumbnail. Currently "saved" by composition-level wrappers calling `updateAllIconImages()`, but direct calls to these methods won't refresh:

- `ImageLayer.convertMode()` — directly mutates image field
- `ImageLayer.resize()` — async callback, no refresh
- `ImageLayer.toCanvasSize()` — calls `setImage()`, no refresh
- `ImageLayer.enlargeImage()` — calls `setImage()`, no refresh

### Proposed architecture

**Dirty flag + batch mode + deferred sweep:**

1. `setImage()` marks the layer dirty and schedules a refresh
2. A batch mode counter suppresses scheduling during composition-level ops
3. Exiting batch mode triggers a single sweep of all dirty layers
4. Deduplication via a `refreshPending` guard or a non-repeating `Timer(0)` with `restart()`

```
ImageLayer.setImage(img)
  → marks layer dirty
  → if (!batchMode) scheduleRefresh()

// Composition-level ops:
beginBatch();       // counter++
// ... modify N layers ...
endBatch();         // counter--, if 0 → sweep dirty layers
```

No `setImage()` / `setImageQuietly()` split needed — the batch flag is managed externally so component functions don't branch.

**Files:** `ImageLayer.java`, `Layer.java`, `Composition.java`, `history/ImageEdit.java`

---

## 4. Move selected content on virtual layer

The original motivation for Layer via Cut. In Photoshop, you can select pixels and drag them around the layer directly, leaving the background color behind. A "move content" mode would:

1. On first drag with selection active: extract selected pixels into a temporary overlay
2. Fill the hole with background/transparency on the source layer
3. Render the extracted content at a moving offset during drag
4. On mouse release: stamp the content back onto the layer at the new position

`TmpLayer` is the closest existing primitive but doesn't support repositioning. Would need adaptation or a new transient rendering surface in the compositing pipeline.

**Files:** `TmpLayer.java`, `MoveTool.java`, `View.java` (rendering pipeline)

---

## 5. Allow layers smaller than canvas

Currently `ImageLayer.setTranslation()` throws on positive offsets — layers must always fully cover the canvas. Relaxing this constraint would simplify `fromSubImage()` (no padding needed) and enable more efficient memory usage for small layers.

`fromSubImage()` was designed as a future refactoring point — when this constraint is relaxed, the padding logic is contained in one factory method.

**Files:** `ImageLayer.setTranslation()`, `ImageLayer.fromSubImage()`, rendering/compositing pipeline
