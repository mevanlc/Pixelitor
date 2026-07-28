# Full-Blown Touchpad Support Plan

## Status and Decision

This plan was written on 2026-07-22 after auditing Pixelitor, the macOS JDK 26
runtime installed on the development machine, OpenJDK's macOS AWT sources,
Apple's AppKit event APIs, and the current Rococoa fork.

The recommended production architecture is:

1. Keep gesture policy, session state, view transforms, and tests in pure Java.
2. Use a small Objective-C/JNI shim on macOS to report `NSEvent` data that AWT
   does not expose faithfully.
3. Keep coarse, traditional mouse-wheel behavior on the existing Java/AWT path.
   When the enhanced touchpad profile is active, precise native scroll over an
   image view belongs exclusively to that profile and means pan, not wheel zoom.
4. Use OpenJDK's hidden `com.apple.eawt.event` bridge only as a capability probe
   and, if retained, an explicitly selected reduced-development fallback, not as
   the production foundation.
5. Do not add Rococoa to Pixelitor for this feature.

Rococoa is a reasonable general Java-to-Cocoa bridge, and it was worth
investigating. It is not the best boundary for this job. Pixelitor needs a small,
stable stream of scalar event data, not general Objective-C object creation and
proxying. The narrow shim makes that contract explicit and keeps the rest of the
feature portable and testable.

This is deliberately a phased plan. Two-finger pan and pinch zoom can ship before
view rotation, pressure-sensitive painting, and swipe polish are complete.

## Desired User Experience

| Gesture/input | Behavior over an image view |
| --- | --- |
| Two-finger scroll | Smooth two-axis pan, including native momentum; no Space key required |
| Pinch | Continuous zoom around the gesture anchor |
| Two-finger rotate | Rotate the view non-destructively around the same anchor |
| Smart magnify/two-finger double-tap | Toggle between Fit Space and the previous manual view transform |
| Horizontal swipe | Move to the previous/next open image when macOS emits a real swipe event |
| Pressure during a drag | Feed normalized pressure to pressure-aware brush dynamics when enabled |
| Traditional wheel | Preserve the existing `MouseZoomMethod` preference exactly |
| Space-drag/middle-drag | Preserve the existing `PanMethod` behavior exactly |

Important distinctions:

- View rotation is display-only. It must not rotate image pixels, create an undo
  edit, dirty the composition, or alter exported output.
- Two-finger scroll is panning, while pinch is zooming. Do not infer pinch from
  scroll deltas or require Ctrl for a native pinch.
- Mouse-wheel settings and touchpad settings are separate. A user who chooses
  `Mouse Wheel` zoom must still get two-finger touchpad pan.
- Natural/reverse scrolling is already represented by AppKit's delivered deltas.
  Pixelitor must not apply a second inversion.
- A gesture that begins over a view remains owned by that view through its end
  and momentum phase, even if the pointer moves over another component.

## Touchpad Mode, Activation, and Compatibility

Treat image-area input as one of two mutually exclusive gesture profiles. Do not
build a hybrid in which each precise-scroll event can be interpreted by both the
legacy wheel listener and the touchpad controller.

Persist a `TouchpadMode` preference with these user-facing choices:

- `Enhanced when available` (recommended and the default after upgrade): use
  conventional image-editor touchpad verbs whenever the full native provider is
  operational.
- `Legacy wheel behavior`: do not claim native touchpad gestures; preserve the
  current AWT behavior, including two-finger vertical motion arriving as a wheel
  event and zooming according to `MouseZoomMethod`.

The effective profile is `ENHANCED` only when the preference requests it and the
provider meets a minimum capability floor: reliable precise X/Y scroll,
phase/momentum, magnification, image-view hit testing, and duplicate-AWT-event
suppression. Otherwise it is `LEGACY`. A partial hidden-JDK bridge must not
silently advertise the enhanced profile; expose it only as an explicitly labeled
development/reduced fallback if it is retained at all.

Provider availability is not the same as proving that a physical trackpad is
connected. AppKit can tell Pixelitor that the event backend is ready and an
observed native gesture can establish that suitable input was seen this session,
but startup code should not promise hardware detection that the event API cannot
reliably provide. Preferences and diagnostics should therefore report these
separately, for example `Native provider ready` and `Touchpad gesture observed`.

Once `ENHANCED` is effective, use a coherent touchpad vocabulary throughout the
image area:

| Input over an eligible image view | `LEGACY` profile | `ENHANCED` profile |
| --- | --- | --- |
| Precise two-finger scroll | Current AWT wheel route: normally zoom; Ctrl requirement follows `MouseZoomMethod`; Space routes to stepped pan | Smooth two-axis pan with momentum; consume the duplicate AWT wheel event |
| Pinch | Current platform/AWT behavior; Pixelitor makes no native claim | Continuous anchored zoom |
| Two-finger rotate | Current platform/AWT behavior; Pixelitor makes no native claim | Non-destructive anchored view rotation |
| Smart magnify | Current platform/AWT behavior; Pixelitor makes no native claim | Fit/previous-transform toggle |
| Native swipe | Current platform/AWT behavior; Pixelitor makes no native claim | Previous/next image when its independent option is enabled |
| Coarse physical mouse wheel | Existing `MouseZoomMethod` route | Same existing `MouseZoomMethod` route |
| Space + coarse wheel | Existing stepped pan | Same existing stepped pan |
| Gesture over Navigator or ordinary UI | Existing component behavior | Existing component behavior; never claimed by the touchpad controller |

In the enhanced profile, modifier keys do not silently turn precise two-finger
pan back into legacy wheel zoom, nor does Shift synthesize horizontal scrolling.
Pinch is the touchpad zoom verb. Zoom remains accessible through pinch, a coarse
mouse wheel, the Zoom Tool, menu/keyboard commands, and the status-bar control;
Space-drag and middle-drag remain available for pan. A user who specifically
wants the old two-finger-scroll-to-zoom binding can select `Legacy wheel
behavior`. Do not add separate core toggles for `two-finger pan` and `pinch zoom`:
those would recreate the conflicting hybrid this mode is meant to avoid.

Changing the preference takes effect only at a gesture boundary. If it changes
during direct input or momentum, cancel the current session, release native
ownership, discard queued updates from that session, and then install the new
profile. Never reinterpret or replay half of a gesture under the other profile.

## Current Pixelitor Architecture

### Existing strengths

- `View` is the single image-display component and already owns zoom state,
  coordinate conversion, scrollbar adjustment, painting, and tool dispatch.
- `View.panViewport(int, int)` centralizes clamped viewport movement.
- `ViewportPanner` already expresses drag-based panning independently of any
  particular tool.
- `MouseZoomMethod` is installed on `View` and `Navigator`, consumes wheel events
  used for zoom, and preserves pointer focus during zoom.
- `View` already caches image-to-component and component-to-image transforms.
- `macDeployToApplications` owns creation of the local self-contained macOS app,
  so it is a natural place to compile and stage a small native library.

### Gaps that matter for this work

- `MouseZoomMethod` currently treats fractional wheel rotation as a likely
  trackpad and accumulates it into discrete zoom steps. That makes two-finger
  scroll compete with panning.
- Space-wheel panning uses `getWheelRotation()`, a fixed 20-pixel step, and one
  axis at a time. It discards precise deltas and native gesture/momentum phases.
- `View` stores a discrete `ZoomLevel`; `zoomScale` is derived from it. Pinch zoom
  requires continuous scale. `devdocs/PLAN-ZOOM-REDUX.md` already describes that
  migration and remains authoritative for the status-bar slider and zoom snap
  catalog. This plan supersedes Zoom Redux's `Trackpad wheel zoom` input mapping:
  its continuous-scale core is reused for pinch, while enhanced two-finger scroll
  means pan.
- The display transform is axis-aligned. Several methods independently implement
  `translate + scale`, including scalar X/Y conversion helpers. Arbitrary view
  rotation cannot be added only in `paintComponent` without breaking tools,
  guides, selections, overlays, scrollbars, and focus preservation.
- `Canvas.coWidth/coHeight` represent only scaled dimensions. A rotated display
  needs a view-owned transformed bounding box instead of pushing view rotation
  into the image model.
- The repository builds its universal JAR on Linux with JDK 25 and 26. Direct
  references to macOS-only JDK classes or unconditional native build steps would
  break the current build and release model.

## Bridge Investigation

### 1. OpenJDK's built-in macOS gesture bridge

The installed Homebrew JDK 26.0.1 contains these public classes in
`java.desktop`:

- `com.apple.eawt.event.GestureUtilities`
- `MagnificationListener` / `MagnificationEvent`
- `RotationListener` / `RotationEvent`
- `SwipeListener` / `SwipeEvent`
- `GesturePhaseListener` / `GesturePhaseEvent`

This was verified locally with `javap`. OpenJDK's
[`GestureUtilities`](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/classes/com/apple/eawt/event/GestureUtilities.java)
attaches listeners to `JComponent`s. Its
[`GestureHandler`](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/classes/com/apple/eawt/event/GestureHandler.java)
routes a native gesture to the deepest registered Swing component and then up its
parent chain. The native
[`AWTWindow.m`](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/native/libawt_lwawt/awt/AWTWindow.m)
already receives AppKit begin/end, magnify, rotate, and swipe callbacks.

This is useful, but it is not a complete public API:

- `java --describe-module java.desktop` lists `com.apple.eawt.event` as contained,
  not exported.
- Direct compilation needs unsupported symbol/export flags. Reflective use needs
  the package opened at runtime.
- An executable JAR can request this with the standard
  [`Add-Opens`](https://docs.oracle.com/en/java/javase/24/docs/specs/jar/jar.html)
  manifest attribute, and `jpackage` can receive the equivalent launcher option,
  but Pixelitor would still be relying on a deliberately concealed JDK API.
- The public event objects omit the native event location, phase details,
  two-axis scroll deltas, momentum phase, smart magnify, and pressure.
- OpenJDK bug
  [`JDK-8154865`](https://bugs.openjdk.org/browse/JDK-8154865) and existing probes
  indicate that swipe and phase delivery cannot be assumed across JDK/macOS
  combinations.

Conclusion: use this bridge in the Phase 0 probe. If retained afterward, expose
it only through an explicitly selected development/reduced mode for isolated
magnify/rotate testing. Do not install it as an automatic production fallback,
let it make the enhanced profile effective, or make full support depend on it.

### 2. Rococoa

The actively maintained project is spelled **Rococoa**. The current
[`umjammer/rococoa`](https://github.com/umjammer/rococoa) fork released 0.8.15 on
2026-01-29. It supports Objective-C objects, Java implementations of Objective-C
interfaces, and Objective-C blocks, so it could install an AppKit local event
monitor.

Advantages:

- No Pixelitor-specific Objective-C source or JNI callback plumbing.
- General Cocoa access would be available for future unrelated features.
- Its LGPL-3.0 license is
  [compatible with GPL-3.0](https://www.gnu.org/licenses/license-list.en.html#LGPL)
  according to the GNU Project.

Costs and risks visible in the current project:

- Installation is documented through JitPack; it is not a normal Maven Central
  dependency for Pixelitor's existing build.
- The project warns that support is for macOS versions after Ventura and that it
  intends to stop supporting Intel.
- Its parent POM brings a general bridge stack centered on JNA 5.17 and Byte
  Buddy. This is a much larger runtime and shaded-JAR surface than the event
  contract requires.
- Its README still documents Objective-C calling limitations, including varargs
  and argument-count constraints.
- Pixelitor would still need custom Cocoa mappings, event routing into the Swing
  hierarchy, AppKit-thread/EDT handoff, gesture ownership, and duplicate-event
  suppression. Rococoa removes syntax and ABI work, not the hard application
  design work.
- A general bridge makes it easier for Cocoa object lifetimes and callbacks to
  leak into application code. This plan wants the opposite: native objects stop
  at one boundary and immutable scalar events cross it.

Conclusion: do not add Rococoa for touchpad support. Reconsider it only if
Pixelitor later develops several independent Cocoa integrations that justify a
general bridge and its deployment policy changes to match Rococoa's platform
matrix.

### 3. Narrow Objective-C/JNI shim

Apple's [`NSEvent`](https://developer.apple.com/documentation/appkit/nsevent)
API exposes exactly what is missing from AWT:

- `scrollingDeltaX/Y` and `hasPreciseScrollingDeltas`
- gesture `phase` and scroll `momentumPhase`
- `magnification` and rotation
- smart magnify and swipe event types
- pressure, stage, and stage transition
- event timestamp, modifiers, window, and location

An app-local monitor receives events before dispatch and may pass them through or
consume them. It does not need Accessibility permission because it observes only
Pixelitor's own events.

The shim should therefore do only four jobs:

1. Install and remove one app-local `NSEvent` monitor.
2. Normalize relevant fields into primitives.
3. Call one static Java callback.
4. Return the original event, or `nil` only when Java's immutable hit-test
   snapshot says Pixelitor claimed it for an image view.

No `NSView` subclasses, Objective-C proxies, Cocoa objects, or native gesture
policy should escape this file.

## Input and Threading Architecture

### Normalized Java event model

Add a small package such as `pixelitor.input.touchpad` with these concepts:

- `TouchpadEvent`: immutable event data.
- `TouchpadEvent.Kind`: `SCROLL`, `MAGNIFY`, `ROTATE`, `SMART_MAGNIFY`, `SWIPE`,
  and `PRESSURE`.
- `GesturePhase`: `MAY_BEGIN`, `BEGIN`, `UPDATE`, `END`, `CANCEL`, and `NONE`.
- `MomentumPhase`: the corresponding scroll-momentum lifecycle.
- `TouchpadTarget`: stable view identifier plus a weak/reference-safe route to a
  live `View`.
- `TouchpadProvider`: install, availability, capabilities, and close methods.
- `TouchpadMode`: the persisted `ENHANCED_WHEN_AVAILABLE` or `LEGACY` user choice.
- `EffectiveTouchpadProfile`: resolves mode plus provider capabilities to the
  currently installed `ENHANCED` or `LEGACY` behavior.
- `TouchpadGestureController`: the only class that turns normalized events into
  Pixelitor behavior.

Do not expose `NSEvent`, native pointers, JDK gesture classes, or JNI handles in
this package.

### Native callback contract

The Objective-C file should call one Java entry point with primitive fields:

```text
kind, phase, momentumPhase, timestamp,
screenX, screenY,
deltaX, deltaY, magnification, rotationDegrees,
pressure, pressureStage, pressureStageTransition,
modifierFlags, precise, directionInverted
```

The callback runs on the AppKit thread. It must not inspect or mutate Swing.
Instead it:

1. Reads an `AtomicReference<TargetSnapshot>` built on the EDT.
2. Hit-tests the event against immutable visible image-viewport rectangles.
3. Pins or resolves the target for the gesture session.
4. Enqueues the normalized event onto the EDT.
5. Returns whether the native monitor should consume the original event.

The snapshot must be refreshed when views are opened, closed, activated, moved,
resized, zoomed, when tabs/frames mode changes, and when a top-level window moves
between displays. Snapshot entries need stable IDs rather than strong native-held
references to `View`s.

### Event ownership and coalescing

- Resolve the effective profile before claiming an event. In `LEGACY`, the native
  provider claims nothing and current AWT dispatch remains authoritative.
- Claim only events whose begin/location hit an eligible visible image viewport.
- Never steal gestures from preference controls, layer lists, text fields,
  dialogs, menus, the Navigator, or other scroll panes.
- Pin a claimed target until end/cancel; pin scroll momentum to the target of the
  originating scroll gesture.
- In `ENHANCED`, claim every precise native scroll event that begins over an
  eligible image view, route it to pan regardless of `MouseZoomMethod` or held
  modifiers, and consume it before AWT turns it into a `MouseWheelEvent`. This
  prevents pan-plus-zoom duplicates.
- Pass coarse wheel events through untouched so existing mouse behavior remains
  under `MouseZoomMethod`.
- Coalesce only consecutive update events of the same kind/session when the EDT
  is behind. Sum scroll/rotation/magnification deltas; keep the newest pressure.
  Never drop begin, end, or cancel.
- Reject non-finite data and clamp absurd deltas at the boundary. A malformed
  native event must not corrupt `View` state.
- Cancel ownership if the target closes, a modal dialog opens, the active tool
  starts an incompatible mouse drag, or the effective profile changes.

## View Geometry Refactor

Pinch and rotation should share one view-transform model rather than growing
separate special cases.

### `ViewTransform`

Introduce an immutable value with at least:

- continuous `scale`
- view-only `rotationRadians`
- helpers for normalized angle, snap detection, and finite/range validation

`View` owns the active transform. `ZoomLevel` remains the catalog of standard
scale snap points, not the complete stored state. This is the core model described
by `PLAN-ZOOM-REDUX.md`.

### `ViewGeometry`

Create one geometry object per `View` update that computes:

- image-to-component affine transform
- exact inverse transform
- transformed canvas shape and axis-aligned display bounds
- preferred component size
- canvas center and gesture-anchor conversions

The transform order should be explicit and tested:

```text
component center/placement
    * rotation about image center
    * continuous scale
    * image coordinates
```

Preserving an anchor during a combined pinch/rotate means:

1. Convert the component-space anchor to image space with the old inverse.
2. Apply the new scale/rotation and synchronously lay out the view.
3. Move the viewport so that image point returns to the same viewport offset.

### Remove axis-only assumptions

Before arbitrary rotation is enabled, migrate production call sites away from
independent X/Y conversion where a point is meant:

- `PPoint`, `Drag`, and `DraggablePoint`
- guides
- crop and transform boxes
- selection and tool overlays
- rectangle conversion helpers
- scrollbar/focus calculations

At non-zero rotation, converting a rectangle means transforming all four corners
and returning either a `Shape` or an explicitly named axis-aligned bounding box.
Do not silently keep the current width/height-only formulas.

Move display-size responsibility out of `Canvas.coWidth/coHeight` or make those
methods clearly scale-only. Image-model `Canvas` must not store transient view
rotation. `ViewGeometry` should provide the transformed size used by layout and
scrollbars.

Painting should apply the same image-to-component transform used for input.
Checkerboard, image/mask content, selection, guides, pixel grid, and tool overlays
must all agree. The pixel grid should draw transformed image-space grid lines; it
must not remain an axis-aligned component-space overlay on a rotated image.

### Rotation policy

- Keep the angle continuous during the gesture.
- Add magnetic snapping with hysteresis near multiples of 90 degrees, including
  zero, without making the gesture feel sticky elsewhere.
- Show a short-lived angle HUD while rotating.
- Add View-menu actions for `Reset View Rotation`, `Rotate View 90° Left`, and
  `Rotate View 90° Right`; these remain non-destructive.
- Store the view angle per open `View`, not in PXC/ORA/image data and not in undo
  history.
- Fit calculations must use rotated display bounds.

## Gesture Behaviors in Detail

### Smooth two-axis pan

- Add a double-precision viewport accumulator so subpixel deltas are not lost
  when `JViewport` ultimately accepts integer positions.
- Use AppKit's precise deltas directly for trackpad events.
- Claim these deltas only in the enhanced profile. In legacy mode, allow the
  existing AWT listener and fractional-wheel accumulator to handle them.
- For a coarse wheel in either profile, retain existing step behavior and
  preference routing.
- Clamp against `max(0, viewSize - extentSize)` on both axes.
- Continue native momentum after the fingers lift and stop on cancel, new direct
  input, target close, or an incompatible mode change.
- Allow diagonal movement from one event; do not translate horizontal movement
  from a synthetic Shift modifier.
- Space, Ctrl, Shift, and Command do not change enhanced precise-scroll pan into
  zoom. Modifiers remain available to the active tool, but gesture semantics are
  selected by the profile and native event kind.

### Continuous pinch zoom

- Complete the continuous-scale core from `PLAN-ZOOM-REDUX.md` first.
- Apply magnification multiplicatively. Apple's event value is a change in
  magnification; use a tested accumulation formula and clamp to the chosen
  min/max scale.
- Preserve the gesture's image-space anchor within one screen pixel.
- Do not magnetically snap every update. Apply snap/hysteresis only near standard
  zoom points or on gesture end, according to the Zoom Redux policy.
- Menu, keyboard, Zoom Tool, and status-bar commands may continue stepping through
  standard `ZoomLevel`s.

### Smart magnify

- On first smart magnify, remember the current manual `ViewTransform` and switch
  to Fit Space.
- On the next one, restore the remembered transform around the gesture anchor.
- If already at Fit Space, restore the last manual transform immediately.
- Reset the remembered pair when the composition/view is replaced or closed.

### Swipe

- Act only on a real AppKit swipe event. Never promote a large two-finger scroll
  into document navigation.
- Default horizontal mapping: swipe left selects the next open image; swipe right
  selects the previous open image, matching visual document movement after a
  hardware smoke test confirms AppKit sign conventions.
- Leave vertical swipes unbound initially.
- Do nothing when there is only one view, a modal dialog is open, or an active
  operation cannot safely yield focus.
- Make document-navigation swipes independently disableable.

### Pressure

Pressure support should establish a reusable pointer-pressure abstraction rather
than directly changing one brush:

- Correlate native pressure only with an active primary-button drag over the same
  view.
- Normalize stage-1 pressure to `[0, 1]`; do not concatenate stage ranges.
- Reset pressure on mouse release, cancel, focus loss, or target close.
- Add an opt-in brush-dynamics setting: `Off` (default), `Size`, `Opacity`, or
  `Size + Opacity`, with editable response curves only after the basic mapping is
  proven.
- Keep ordinary mouse events at pressure `1.0` so existing brush output is
  unchanged.
- Do not attempt to override macOS Force Click/Look Up settings. If macOS does not
  deliver pressure to Pixelitor, the feature remains inactive.

## Native Build and Packaging

### Source and build output

Add one native source file, for example:

```text
src/main/native/macos/PixelitorTouchpad.m
```

Build it with the system `clang`, JDK JNI headers, and AppKit/Foundation. Keep
warnings enabled and treat warnings as errors in CI. The output is:

```text
target/native/macos/libpixelitor_touchpad.dylib
```

Prefer a universal arm64+x86_64 library while Pixelitor supports Intel. If the
available SDK/toolchain cannot produce both slices, publish architecture-specific
macOS app artifacts rather than silently dropping an architecture.

### `macDeployToApplications`

Extend the script to:

1. Compile the shim before `jpackage`.
2. Stage the dylib in the same temporary `--input` directory as the shaded JAR,
   causing it to land in `Pixelitor.app/Contents/app` before bundle signing.
3. Do not open hidden JDK packages in the production launcher unless an explicit
   reduced fallback is deliberately shipped and tested.
4. Verify the dylib architecture and dependencies.
5. Add a final `codesign --verify --deep --strict` validation after `jpackage`.

Java should load an explicit sibling path with `System.load`, not mutate the
process-wide `java.library.path`. Allow a development-only system property to
override the path when running from an IDE.

Loading must be optional and fail closed:

- non-macOS: mark the native provider unavailable and use the legacy profile
- macOS with a compatible dylib: install the native provider and resolve the
  effective profile from the user's mode
- macOS without/wrong dylib: log one diagnostic, mark the provider unavailable,
  and use the legacy profile
- native initialization failure: never prevent Pixelitor startup or silently
  enable a partial gesture vocabulary

### CI and release artifacts

- Keep the current Linux JDK 25/26 universal-JAR jobs unchanged.
- Add a macOS job that compiles the native library, runs Java tests with a display,
  inspects the dylib with `file`, `lipo`, and `otool`, packages an app image, and
  verifies code signing.
- A Linux-built universal JAR remains usable everywhere but cannot promise the
  full native backend by itself.
- Release a zipped macOS app image for full support. Do not hide a platform dylib
  in the universal JAR unless extraction, architecture selection, signing, and
  cleanup are designed and tested separately.

## Preferences and Diagnostics

Add a Touchpad section rather than overloading the current Mouse section:

- `Image-area touchpad behavior`: `Enhanced when available` (recommended/default)
  or `Legacy wheel behavior`
- read-only effective status: `Enhanced`, `Legacy (selected)`, or
  `Legacy (native provider unavailable)`
- read-only capability/hardware evidence: provider readiness and whether a
  qualifying touchpad gesture has been observed this session
- `Natural two-finger pan` is informational; direction remains a macOS setting
- `Pinch zoom sensitivity` only if hardware testing shows the native value needs
  user scaling
- `Rotation snapping` (default on)
- `Swipe between images` (default on after sign/direction validation)
- `Pressure dynamics` (default off)

Keep the existing mouse choice, but relabel its row to `Zoom with mouse wheel:`
and explain that it controls coarse wheels and legacy mode only. It must not
change enhanced two-finger pan. Rotation, swipe, and pressure may remain
independently disableable because they are optional extensions; pan and pinch are
the inseparable core of the enhanced profile.

Do not add sensitivity controls preemptively for every gesture. Start with native
1:1 deltas and add preferences only where hardware testing demonstrates a real
need.

Development mode should expose a Touchpad Diagnostics dialog showing:

- selected provider and capabilities
- native library path/version/architecture
- last event kind, phases, target, and raw/normalized values
- coalesced/dropped event counters
- current gesture owner and transformed view state

Rate-limit the display/logging so diagnostics do not create the latency they are
meant to measure. Never log every gesture in normal mode.

## Implementation Phases

### Phase 0: Hardware and bridge spike

- Build a tiny in-repo development probe for raw `MouseWheelEvent`, hidden JDK
  gesture callbacks, and a minimal `NSEvent` local monitor.
- Record delivery for two-finger scroll, momentum, pinch, rotate, smart magnify,
  swipe, and pressure on the current JDK/macOS.
- Confirm event signs, whether magnification/rotation values are incremental,
  mixed Retina/non-Retina coordinate conversion, and which events AWT duplicates.
- Confirm the capability floor and the exact native criteria that distinguish
  claimable precise scrolling from a coarse physical wheel. Record limitations
  for devices such as Magic Mouse rather than guessing a hardware identity.
- Confirm that the local monitor needs no Accessibility permission.
- Exit gate: a documented event trace and a fixed JNI primitive contract. Do not
  implement user behavior against guessed signs or phases.

### Phase 1: Pure-Java input foundation

- Add normalized event records, provider/capability interfaces, immutable target
  snapshots, session ownership, coalescing, and a synthetic provider.
- Add persisted `TouchpadMode`, effective-profile resolution, safe profile
  transitions, and user-visible provider status.
- Add controller tests before any native callback drives production behavior.
- Keep `LEGACY` as the feature flag during development so incomplete touchpad
  handling cannot steal existing wheel events.

### Phase 2: Continuous scale and smooth pan

- Implement the continuous `View` scale foundation from `PLAN-ZOOM-REDUX.md`.
- Centralize viewport movement in a double-precision pan controller shared by
  touchpad, Hand Tool, keyboard, and existing wheel paths where appropriate.
- Complete the controller-side precise two-axis pan and pinch behavior against the
  synthetic provider; these form the first releasable native slice in Phase 3.
- Preserve all existing coarse-wheel and zoom-command behavior, and preserve the
  complete current precise-wheel path whenever the legacy profile is effective.

### Phase 3: Native provider and packaged app

- Implement the narrow Objective-C/JNI monitor and Java loader.
- Add synchronous immutable hit testing and safe EDT handoff.
- Suppress only claimed duplicate AWT events.
- Expose the mode selector only when profile resolution and live switching are
  complete; a provider that misses the capability floor remains legacy.
- Enable and ship precise two-axis pan and pinch zoom before rotation.
- Extend `macDeployToApplications` and add the macOS CI/package job.
- Deploy and test the real `/Applications/Pixelitor.app`; an IDE run does not
  validate app-bundle loading or signing.

### Phase 4: View rotation

- Introduce `ViewTransform`/`ViewGeometry` and migrate axis-only coordinate APIs.
- Update painting, overlays, pixel grid, guides, layout, scrollbars, Navigator
  viewport representation, and Fit calculations.
- Add continuous rotate, anchor preservation, snapping, HUD, and View-menu reset/
  quarter-turn actions.
- Exercise every interactive tool at non-zero angles before enabling rotation by
  default.

### Phase 5: Smart magnify, swipe, and pressure

- Add transform toggle for smart magnify.
- Add conservative document-navigation swipe behavior.
- Introduce opt-in pressure dynamics through a reusable pressure abstraction.
- Keep each feature independently disableable so hardware/OS regressions do not
  disable pan and pinch.

### Phase 6: Hardening and cleanup

- Isolate the old fractional-wheel-as-discrete-zoom workaround to the legacy and
  no-provider paths. Remove it only if no supported profile still needs it.
- Finalize preferences and diagnostics.
- Test both image-area modes, multiple displays/scales, view close during momentum,
  modal dialogs, popups, Navigator, and every supported input fallback.
- Update README/build documentation for the macOS native dependency and app
  artifact.

## Automated Testing

### Pure unit tests

- AppKit phase/momentum bitmask to Java enum normalization
- target hit testing and target pinning
- begin/update/end/cancel state transitions
- close/cancel cleanup and stale-view rejection
- coalescing math and ordering guarantees
- natural delta sign handling without double inversion
- persisted-mode and provider-capability combinations resolve to the correct
  effective profile
- profile changes cancel direct/momentum sessions without replaying queued input
- double-precision panning residuals and clamping
- magnification accumulation and scale clamping
- rotation normalization, hysteresis, and snap exit/entry thresholds
- smart-magnify transform toggle
- pressure normalization/reset and disabled dynamics

### Geometry/property tests

For representative scales, angles, canvas aspect ratios, and anchors:

- inverse(transform(point)) returns the original point within tolerance
- transformed canvas bounds contain all four transformed corners
- zoom/rotation keeps the chosen image point at the same viewport offset
- reset rotation reproduces current axis-aligned coordinates
- rectangle-to-bounds helpers transform all four corners
- non-finite/degenerate transforms are rejected without changing the old state

### Swing integration tests

- synthetic provider pans/zooms only the targeted `View`
- controls and unrelated scroll panes do not get stolen events
- enhanced precise scroll pans exactly once and never reaches `MouseZoomMethod`,
  including with Space, Ctrl, Shift, or Command held
- legacy precise scroll follows the current `MouseZoomMethod`/Space behavior
- coarse wheel still follows both `MouseZoomMethod` choices
- Navigator wheel behavior remains unchanged
- tool mouse drags cancel or exclude incompatible gestures
- tab and internal-window modes refresh target snapshots correctly
- view close during a queued/momentum gesture is harmless

### Native/package checks

- JNI library loads from the packaged app and reports its ABI version
- missing, incompatible, and deliberately failing libraries fall back cleanly
- `clang` warning-clean build
- expected architectures and AppKit/Foundation dependencies only
- packaged bundle passes `codesign --verify --deep --strict`
- installed app is launched and exercised, not merely compiled

Run the full graphical Maven suite with the required JDK after final production
changes, then rerun `./macDeployToApplications`. Headless compilation is not proof
that touchpad events, native loading, or app-bundle signing work.

## Manual Hardware Matrix

At minimum test:

- built-in MacBook trackpad and external Magic Trackpad
- natural scrolling on and off
- tap-to-click and Force Click settings on and off
- arm64 packaged app; Intel hardware or an explicit Intel tester while supported
- one display, multiple displays, and mixed Retina scaling
- small image (no scrollbars), one-axis overflow, and two-axis overflow
- tabs and internal windows
- pointer over canvas, canvas margin, layer panel, Navigator, dialogs, and popups
- active brush/selection/transform drag when another gesture begins
- rapid view switching/closing during direct and momentum phases
- raw `java -jar` fallback versus packaged app
- enhanced, legacy, provider-unavailable, and live profile-switch cases

Subjective acceptance targets:

- pan tracks fingers without visible quantization or axis switching
- momentum stops at bounds without bounce/jitter or moving another view
- pinch/rotate anchor drift is no more than one screen pixel
- no gesture creates an undo edit unless pressure is affecting an actual brush
  stroke
- no EDT backlog remains after fingers leave the trackpad
- traditional mouse workflows are indistinguishable from current behavior
- enhanced touchpad input never alternates between pan and wheel zoom because of
  modifiers, fractional deltas, or duplicate AWT delivery

## Main Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Native/AWT duplicate scroll handling | Claim via immutable snapshot and consume only the native event routed to a touchpad controller |
| AppKit callback touches Swing off-EDT | Callback reads immutable data only; all behavior is queued to EDT |
| Coordinate mismatch on multiple displays | Phase 0 trace plus explicit top-left/logical-point conversion tests on mixed scaling |
| View rotation breaks tool math | Central affine geometry; remove scalar-axis assumptions before enabling rotation |
| Native library makes startup fragile | Optional provider, ABI handshake, one diagnostic, and automatic effective-profile fallback to legacy |
| Intel or old-macOS regression | Universal/dual artifacts and an explicit tested platform matrix; do not inherit Rococoa's narrowing silently |
| Legacy wheel zoom and enhanced pan both react | One effective profile, native ownership before AWT dispatch, and mode/source matrix tests |
| Provider readiness is mistaken for hardware detection | Report backend readiness and observed gestures separately; do not claim connected-device discovery |
| Gesture steals UI scrolling | Eligible viewport snapshots and gesture ownership beginning only over image views |
| Excessive update rate | EDT coalescing that preserves phase boundaries and total deltas |
| Pressure changes existing brushes | Default off and ordinary mouse pressure fixed at 1.0 |
| Hidden JDK API changes | Phase 0 or explicit reduced-development use only; full backend uses public AppKit through the shim |

## Definition of Done

The project has full touchpad support when:

- precise two-finger pan, momentum, pinch zoom, view rotation, and smart magnify
  work in the packaged macOS app;
- `Enhanced when available` selects one conventional touchpad vocabulary, while
  `Legacy wheel behavior` reproduces the current two-finger-wheel path;
- provider failure visibly and safely resolves to legacy, and changing profiles
  cannot split or replay a gesture;
- swipe and pressure are available where macOS/hardware deliver them and can be
  disabled independently;
- pan, zoom, and rotation preserve their image-space anchor and target across the
  full gesture lifecycle;
- view rotation is non-destructive and all tools remain coordinate-correct;
- native events never touch Swing off-EDT and no duplicate AWT action occurs;
- the universal JAR and non-macOS builds remain functional without the dylib;
- coarse mouse-wheel, Space-drag, middle-drag, Navigator, menu, keyboard, and Zoom
  Tool behavior remain compatible, while pinch and other zoom entry points keep
  zoom readily accessible in the enhanced profile;
- automated geometry/controller tests, the full graphical Maven suite, native
  checks, code-sign verification, and the manual hardware matrix pass;
- `macDeployToApplications` installs the verified final build in
  `/Applications/Pixelitor.app`.
