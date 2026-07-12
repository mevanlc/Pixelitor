# Zoom Redux Plan (Continuous Zoom + Snap Points)

## Context / Problem

Pixelitor’s current zoom model is discrete: `View` stores a `ZoomLevel zoomLevel`, and `zoomScale` is derived from it. Most user interactions (mouse wheel, zoom tool click, menu “Zoom In/Out”, status-bar slider) ultimately call `View.zoomIn/zoomOut` which move along the predefined `ZoomLevel.zoomLevels[]` chain.

This has two pain points (especially on trackpads and other devices with high-precision on the relevant axes):

1. **Trackpad wheel events are high-frequency + fractional**, so treating each event as “one notch” makes zoom feel too fast/jittery.
2. **Discrete zoom steps feel chunky**, and trackpads highlight that because they offer fine-grained gesture control.

We want to explore a “continuous zoom” model where the zoom bar and trackpad can adjust zoom at near-infinite precision, while **some operations still snap to fixed points** (100%, 200%, Fit, etc.).

## Current Architecture (Audit)

### State

- `View` stores:
  - `ZoomLevel zoomLevel` (`src/main/java/pixelitor/gui/View.java:72`)
  - `double zoomScale` derived from `zoomLevel` (`src/main/java/pixelitor/gui/View.java:658`)
- Coordinate transforms and painting already use `zoomScale` directly in many places.

### Entry Points

- Mouse wheel zooming is installed via `MouseZoomMethod` on `View` and on the `Navigator` (`src/main/java/pixelitor/gui/MouseZoomMethod.java:29`, `src/main/java/pixelitor/gui/Navigator.java:165`).
- Menu zoom actions call `View.zoomIn()` / `View.zoomOut()` (`src/main/java/pixelitor/menus/view/ZoomMenu.java:49`).
- Auto-zoom actions call `View.setZoom(AutoZoom)` → `ZoomLevel.calcBestFitZoom(...)` (`src/main/java/pixelitor/gui/View.java:637`).
- Status bar zoom slider maps directly to `ZoomLevel.zoomLevels[index]` (`src/main/java/pixelitor/menus/view/ZoomControl.java:125`).
- Zoom tool uses discrete `view.zoomIn/out` and zoom-to-rectangle uses discrete best-fit (`src/main/java/pixelitor/tools/ZoomTool.java:56`, `src/main/java/pixelitor/gui/View.java:707`).

### Discrete zoom ladder

- Defined by `ZoomLevel.ZOOM_PERCENTAGES` and linked as a chain (`src/main/java/pixelitor/menus/view/ZoomLevel.java:34`).
- The sequence is roughly exponential (equal ratios per step).

## Goals

1. **Smooth trackpad zoom** using `MouseWheelEvent.getPreciseWheelRotation()` with a mapping that feels consistent across the full zoom range.
2. **“Infinite precision” zoom bar**: user can scrub to arbitrary zoom values (likely log-mapped), not only the discrete ladder.
3. **Snap points remain** for:
   - menu “Zoom In/Out”
   - Zoom tool clicks (optional: still discrete)
   - Auto-zoom (“Fit Space/Width/Height”, “Actual Pixels”)
   - typed percent (if/when implemented)
4. **Predictable focus**: zoom towards mouse pointer when applicable; otherwise preserve view center.
5. **Minimal regressions**: existing workflows still work; the discrete ladder remains available.

## Non-Goals (initially)

- Redesigning the entire View menu/UI.
- Changing the discrete zoom ladder values (we can revisit later).
- Making traditional mouse wheels feel perfect immediately (the user asked to dial trackpad behavior first).

## Proposed Model: Continuous Zoom Scale + Optional Snapping

### Core idea

Make `View` zoom state continuous, with snap points layered on top:

- **Source of truth:** `double zoomScale` (continuous).
- **Snap catalog:** existing `ZoomLevel.zoomLevels[]` remains the list of “standard” zoom points.
- **Derived/UI state:** “snapped” vs “custom” is computed based on proximity to a snap point (with hysteresis).

### Suggested internal representation

Internally treat zoom as “log2 scale” because it makes gesture mapping and slider mapping natural:

- `zoomLog2 = log2(zoomScale)`
- “smooth zoom” applies `zoomLog2 += delta`, then `zoomScale = 2^zoomLog2`

This matches the behavior observed in the standalone tester: a linear mapping in exponent space (multiplier ~0.01–0.1) feels good across the range.

### New View API (sketch)

- `double getZoomScale()`
- `void setZoomScale(double newScale, Point coFocusPoint, ZoomChangeOrigin origin)`
- `void setZoom(ZoomLevel newZoom, Point coFocusPoint)` remains for snap commands, but becomes a thin wrapper over `setZoomScale(newZoom.getScale(), ...)`.
- `ZoomLevel getNearestZoomLevel()` (or `Optional<ZoomLevel> getSnappedZoomLevel()`).
- `void zoomIn/zoomOut(Point)` becomes “snap stepping”:
  - if currently snapped: next/prev in ladder
  - else: find nearest ladder point, then step from there (or step from current scale using a helper)

### Snapping policy

We need a consistent definition of when zoom is “snapped”:

- Compute nearest snap point by scale (binary search on `ZoomLevel` percent list).
- Snap if “close enough”. Prefer comparing in log space:
  - `abs(log2(scale) - log2(snapScale)) < snapEpsilon`
- Add hysteresis so the UI doesn’t flicker between “Custom” and “100%”:
  - snap-enter threshold smaller than snap-exit threshold.

Example constants to tune:

- `SNAP_EPSILON_ENTER = 0.01` (in log2 units)  ≈ within ~0.7%
- `SNAP_EPSILON_EXIT  = 0.02` (in log2 units)  ≈ within ~1.4%

### Pixel grid / snapping thresholds

Anything currently tied to `ZoomLevel.getPercent()` should be expressed in terms of `zoomScale` instead.

Example:

- Pixel grid allowed when `zoomScale > 15.0` (because 1500% == 15.0).

## Input Mapping (Trackpad First)

### Trackpad wheel zoom (continuous)

For events that look like trackpad scrolling (fractional precise wheel rotation):

- Use `precise = e.getPreciseWheelRotation()`
- Convert to an exponent delta:
  - `zoomLog2Delta = -precise * sensitivity`
  - `newScale = oldScale * 2^(zoomLog2Delta)`

Where `sensitivity` is similar to the tester’s multiplier:

- Recommended initial default: `0.05`
- User-tunable range: `0.01 … 0.1` (based on tester feedback)

Optional extras:

- Gesture boundary detection: reset “accumulator” after inactivity (already done in the current discrete workaround).
- Clamp zoom range: keep existing min/max zoom behavior (currently implicit in the ladder).

### Traditional mouse wheel (defer)

Keep current discrete ladder stepping (or the current accumulator-based approach) for now; revisit after trackpad UX is locked down.

## Zoom Bar (“Infinite Precision”)

### UI concept

Replace the current index-based slider with a log-mapped slider:

- Slider value is linear in `zoomLog2` over a fixed range (e.g. 6.25% … 6400%).
- Label shows:
  - snapped value (e.g. `100%`) when snapped
  - otherwise `Custom: 137.2%` (formatting TBD)
- Discrete snap points can be rendered as tick marks (optional).

### Interaction rules

- Dragging slider updates zoom continuously (with optional “magnetic” snap near standard points).
- Clicking “Fit / 100%” uses snap commands.

## Affected Modules (Expected)

- `src/main/java/pixelitor/gui/View.java`
  - replace “zoom == ZoomLevel” assumption with continuous scale
  - update scrollbars-after-zoom math to use `prevScale/newScale` instead of `ZoomLevel prevZoom/newZoom`
- `src/main/java/pixelitor/gui/MouseZoomMethod.java`
  - trackpad path: continuous zoom via `setZoomScale(...)`
  - wheel path: keep discrete for now
- `src/main/java/pixelitor/menus/view/ZoomControl.java`
  - replace discrete index slider with continuous/log slider
  - support “Custom” display + snapping feedback
- `src/main/java/pixelitor/menus/view/ZoomLevel.java`
  - add helpers: nearest-by-scale, next/prev-from-scale (without making `ZoomLevel` the stored state)
- `src/main/java/pixelitor/menus/view/ZoomMenu.java`
  - keep `zoomIn/zoomOut` as snap-stepping, but update logic if `View` no longer stores `ZoomLevel`
- `src/main/java/pixelitor/gui/Navigator.java`
  - uses `MouseZoomMethod` and has its own fixed thumbnail zoom presets; ensure “zoom the active view” behavior remains sane
- `src/main/java/pixelitor/tools/ZoomTool.java`
  - decide whether click zoom is discrete (snap) or continuous (probably keep discrete initially)
- Tests:
  - `src/test/java/pixelitor/guitest/MainGuiTest.java` wheel behavior may need updates once trackpad path becomes continuous (might be platform-dependent).

## Rollout Strategy (Phased)

### Phase 0: Plan + spike (no mainline changes)

- Keep using the standalone tester app to tune sensitivity curves.

### Phase 1: Continuous zoom in `View` (internal), keep UI mostly discrete

- Introduce `View.setZoomScale(...)` and refactor internals (scrollbars, title, pixel grid threshold) to be scale-based.
- Keep `ZoomControl` slider discrete for now but show “Custom” percent label when scale is not exactly a snap point.
- Trackpad zoom becomes continuous; wheel zoom remains discrete.

### Phase 2: Continuous zoom control (status bar)

- Convert `ZoomControl` slider to log-scale continuous mapping.
- Add snap tick marks / “magnetic snapping” if desired.

### Phase 3: Optional preferences + polish

- Add a preference for trackpad zoom sensitivity (and possibly snapping behavior).
- Decide whether to unify wheel and trackpad mapping.

## Testing / Validation

1. **Unit tests** (new):
   - mapping from scale ↔ nearest `ZoomLevel`
   - snap hysteresis behavior
   - zoom stepping from custom scale chooses the expected next snap point
2. **GUI tests**:
   - keep current wheel-rotation tests for discrete wheel zoom
   - add focused tests for continuous zoom clamping and focus-point maintenance (might require new test helpers)
3. **Manual smoke checklist**:
   - trackpad smooth zoom in/out feels stable
   - zoom keeps pointer focus
   - slider updates reflect actual zoom (snapped vs custom)
   - fit/100% buttons still correct
   - navigator wheel zoom still zooms the active view

## Open Questions (Need Review)

1. Should **AutoZoom** compute an exact scale (continuous) or continue snapping to the nearest ladder step?
2. Should “Zoom In/Out” from the menu step by the old ladder always, or step by a fixed exponent increment (and treat ladder as UI-only)?
3. What is the desired min/max zoom scale when we are no longer bound to the ladder?
4. How should “Custom” be formatted (precision, rounding, and when to show trailing decimals)?
5. Do we want “magnetic snapping” on trackpad zoom (snap when near) or only on slider/menu?

