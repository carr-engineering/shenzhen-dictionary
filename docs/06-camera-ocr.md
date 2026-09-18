# 06 — Camera / OCR

**Android and iOS only** (`PROD-022`). Read [02 — Global Conventions](02-global-conventions.md) and
[07 — Text Analysis](07-text-analysis-reading.md) first — the tap-to-word behaviour is shared.

This spec contains four **new requirements** that do not exist in the reference app: tap to focus
(`CAM-030`), explicit flash control (`CAM-035`), saving the captured image to the photo library
(`CAM-070`), and an auto-save setting (`CAM-080`).

---

## 1. Surface and entry points

`CAM-001` `[MUST]` The camera is a full-screen surface presented over the current destination, not a
primary navigation destination. It slides in from the trailing edge (240 ms) and out (220 ms), and the
primary navigation is hidden while it is open.

`CAM-002` `[MUST]` Entry points:
1. the `Scan with camera` access card on the empty Search screen;
2. the `Choose from gallery` access card (skips the viewfinder, goes straight to review);
3. a static launcher/quick-action shortcut `Open camera` (`SET-050`);
4. a share-image intent from another app (`SET-052`);
5. the Android assistant integration (`SET-055`).

`CAM-003` `[MUST]` Opening the camera resets any previous capture, recognition result, selection and
error.

`CAM-004` `[MUST]` The surface has exactly two phases:
- **Capture phase** — no image yet: either the live viewfinder (source = camera) or a decode
  spinner/error (source = gallery/share).
- **Review phase** — an image exists: the image with recognition overlay, or the text list.

`CAM-005` `[MUST]` `source` is `CAMERA` or `GALLERY` and determines the review header's primary action
and the back behaviour.

---

## 2. Permission handling

`CAM-010` `[MUST]` The camera permission is requested **lazily**, only when the viewfinder is first
shown — never at app start.

`CAM-011` `[MUST]` Pre-grant state shows a permission prompt panel inside the viewfinder frame: a
camera icon, the rationale `Camera access is needed to recognize Chinese text in photos.`, and one
button:
- first time: `Allow camera` (triggers the system prompt);
- after a soft denial: `Try again`;
- after a permanent denial: `Open app settings` (deep-links to the app's settings page).

`CAM-012` `[MUST]` Permanent denial is detected by the platform's "should show rationale" signal being
false after a denial (Android) / the authorisation status being `denied` or `restricted` (iOS).

`CAM-013` `[MUST]` Permission state is re-checked on every resume, so granting it in system settings
and returning starts the viewfinder without further interaction.

`CAM-014` `[MUST]` The "already asked" flag survives configuration change so the copy does not revert
to `Allow camera` after a rotation.

`CAM-015` `[MUST]` If the camera device cannot be bound (no camera, hardware in use, emulator without
a camera), the frame shows `The camera is unavailable on this device.` and the gallery path remains
available.

`CAM-016` `[MUST]` The gallery path uses the platform's **photo picker** (Android Photo Picker /
iOS `PHPickerViewController`), which grants access to the single chosen image only and therefore
requires **no** media permission. A broad photo-library read permission must not be requested.

`CAM-017` `[MUST]` Declared permissions: camera (`android.permission.CAMERA`;
`NSCameraUsageDescription`). Saving to the gallery: Android needs no permission when writing through
`MediaStore` on API 29+, and `WRITE_EXTERNAL_STORAGE` scoped to `maxSdkVersion 28` if API 26-28 is
supported; iOS needs `NSPhotoLibraryAddUsageDescription` (add-only). Microphone and
broad-photo-library permissions must never be declared.

---

## 3. Viewfinder

`CAM-020` `[MUST]` The preview is a **4:3** frame, centred, horizontally inset 20 dp, with large
corner rounding. 4:3 is deliberate: 16:9 modes crop sensor rows, and OCR wants the full sensor area.
The preview and the capture share the same aspect ratio so what the user frames is exactly what is
recognised.

`CAM-021` `[MUST]` Preview scaling is fit-centre (letterboxed), never cropped.

`CAM-022` `[MUST]` The rear camera is used by default. No front/rear toggle in v1.

`CAM-023` `[MUST]` A 72 dp circular accent shutter button sits bottom-centre inside the frame. While a
capture is in flight it is disabled and shows a 30 dp progress indicator.

`CAM-024` `[MUST]` Below the frame: a text button `Choose from gallery`, and any error text.

`CAM-025` `[MUST]` The camera is bound to the surface's lifecycle: released when the surface is
disposed or the app is backgrounded, rebound on return. A capture that completes after disposal is
discarded and its resources closed.

`CAM-026` `[MUST]` Captures deliver an **upright** image: the sensor rotation is applied, EXIF is
resolved, and the longest side is capped at 2560 px (bounding a decoded frame at roughly 26 MB). All
downstream coordinates are in this upright image's pixel space.

`CAM-027` `[MUST]` Capture failure shows `Could not take a photo. Try again.`; post-processing failure
shows `Could not process the captured photo.` Both leave the viewfinder usable.

### 3.1 Tap to focus — NEW

`CAM-030` `[MUST]` Tapping a point in the preview requests **autofocus and auto-exposure metering at
that point**.

`CAM-031` `[MUST]` The tap coordinate is converted from view space to sensor/preview metering space
using the same fit-centre transform as the preview, so the focus point is exactly under the finger.

`CAM-032` `[MUST]` A focus reticle is drawn at the tap point: it appears immediately, animates a small
scale-in, then indicates the outcome (converged / failed) and fades out after about 1.2 s.

`CAM-033` `[MUST]` The focus request has a timeout of about 5 s, after which the camera returns to
continuous autofocus. Tapping again cancels and replaces an in-flight request.

`CAM-034` `[MUST]` Where the device does not support point metering, the tap is a no-op with no
reticle and no error.

### 3.2 Flash / torch — NEW

`CAM-035` `[MUST]` A flash control in the viewfinder's top-trailing corner cycles or toggles the
illumination mode. Minimum required behaviour: an explicit **on/off** toggle that the user controls.

`CAM-036` `[SHOULD]` Offer three states — `Off`, `On`, `Auto` — with icons, cycling on tap, and label
the current state for accessibility.

`CAM-037` `[MUST]` The control is hidden when the active camera reports no flash unit.

`CAM-038` `[MUST]` The chosen state persists for the session and is re-applied when the camera is
rebound after backgrounding. *(Whether it persists across launches is `Q-040`; the recommendation is
that it does not, matching platform camera apps.)*

`CAM-039` `[MUST]` `On` means the flash fires at capture. If a torch mode is offered for framing in
low light it must be visually distinct from capture-flash and must be turned off when the surface is
disposed.

---

## 4. Image ingestion (gallery / share / assistant)

`CAM-040` `[MUST]` Picking an image immediately switches the surface to the capture phase with
`source = GALLERY` and shows a centred spinner while decoding.

`CAM-041` `[MUST]` Decoding applies EXIF orientation and downscales to the same 2560 px cap, using a
hardware-independent (software) allocation so the bitmap can be read by the recogniser.

`CAM-042` `[MUST]` Decode failure shows the error text plus a `Choose another image` button.
Messages: `Could not open that image.` (picker) / `Could not open the shared image.` (share intent).

`CAM-043` `[MUST]` A share/assistant intent is **consumed** once — the action is cleared so activity
recreation or rotation does not re-handle the same image.

`CAM-044` `[MUST]` Decoding must not be tied to a composable's lifetime; it runs on a scope that
survives the picker's own recomposition so a rotation mid-decode does not lose the image.

---

## 5. Recognition

`CAM-050` `[MUST]` Recognition runs on a background thread against the upright image, with a **20 s
timeout**. While it runs, the review phase shows the image with a centred spinner over it; the header
and view toggle remain usable.

`CAM-051` `[MUST]` The recogniser must be a **Chinese-capable, on-device, offline** text recogniser.
Recommended: ML Kit Chinese text recognition on Android; Apple Vision (`VNRecognizeTextRequest` with
`zh-Hans`/`zh-Hant`) on iOS. Neither may require network access.

`CAM-052` `[MUST]` The recognition result is a list of **lines**. Each line has:
- `text` — the concatenation of its symbol texts, **not** the platform's line text. Platform line text
  joins elements with spaces and would desynchronise character offsets from geometry.
- `quad` — four corner points in clockwise order from top-left, in upright-image pixels (falls back to
  the axis-aligned bounding box when corner points are unavailable).
- `chars` — one entry per recognised symbol: its text (usually one Hanzi, possibly a surrogate pair or
  rarely a multi-glyph cluster), its UTF-16 start offset into `text`, and its quad.
- `hasHanzi` — whether the line contains any Hanzi (`GLB-001`).

`CAM-053` `[MUST]` When a recogniser returns elements without symbols (a real ML Kit behaviour for
some CJK outputs), fall back to per-code-point splitting of the element text sharing the element's
quad. Never drop the line.

`CAM-054` `[MUST]` Lines with no characters are dropped. If no surviving line contains Hanzi, show the
inline message `No Chinese text found. Retake to try again.` while still displaying the image.

`CAM-055` `[MUST]` Each recognised line's text is analysed into word segments (`TXT-010`) so its
characters are tappable. Analysis failure for one line leaves that line untappable (rendered as plain
muted text) without failing the whole capture.

`CAM-056` `[MUST]` Recognition failure shows `Unable to recognize text` (plus detail) and, for a
timeout, `Text recognition timed out. Try a smaller or clearer image.` The image stays on screen.

`CAM-057` `[MUST]` Because segment/passage identifiers are dataset-scoped (`GLB-044`), replacing the
dataset while a capture is on screen must **re-run** the analysis against the new dataset (the raw
image is dataset-independent). Taps are ignored until the re-analysis completes.

---

## 6. Review phase

`CAM-060` `[MUST]` A translucent result header pinned to the top holds:
- a primary action: `Retake` (source = camera) or `Pick new image` (source = gallery);
- the new `Save photo` action (`CAM-072`);
- a two-segment `Image` / `Text` view toggle.

`CAM-061` `[MUST]` Its bottom gradient shadow fades in only when the text list can scroll up.

### 6.1 Image view

`CAM-062` `[MUST]` The image is displayed fit-centre on black, with the recognition overlay drawn in
the same transform:
- each line's quad filled at 10% accent and stroked in accent (1.5 dp, scaled down by the zoom so the
  stroke stays hairline);
- the selected character's quad filled at 35% accent and stroked in `onAccent`.

`CAM-063` `[MUST]` Pinch-to-zoom between 1x and 8x with pan. The pinch centroid stays stationary.
Translation is clamped so the image always covers the viewport (no rubber-band gaps).

`CAM-064` `[MUST]` Double-tap toggles between 1x and 2.5x, zooming toward the tapped point.

`CAM-065` `[MUST]` A single tap maps the view point back through (pan, zoom, fit-scale, letterbox) to
image pixels and hit-tests the recognised symbols:
- a point inside a symbol's quad wins immediately (convex containment, winding taken from the quad so
  rotated text works);
- otherwise the nearest symbol centre within a slop radius of 24 dp (divided by zoom and fit-scale, so
  the tolerance is constant in screen space) is selected;
- whitespace-only symbols are never selectable; a symbol's selectable offset is its first
  non-whitespace code point.

`CAM-066` `[MUST]` Selecting a character opens the word-candidate sheet for that (line, offset)
(`TXT-040`). The selection highlight moves immediately, before the lookup resolves.

`CAM-067` `[MUST]` Stepping to the previous/next character from the sheet header also **animates the
image pan** (220 ms) so the newly selected glyph is centred in the viewport.

`CAM-068` `[MUST]` Zoom and pan reset when the image changes.

### 6.2 Text view

`CAM-069` `[MUST]` A vertical list of cards:
- first, when any text was recognised, an `All text` card with the concatenation of all line texts
  (newline-joined) and the sentence action row (play / translate / copy);
- then one card per line: the line rendered as an interactive segmented passage (word-boundary
  underlines, tone colours, tappable characters — `TXT-030`), or plain muted text when the line could
  not be analysed; each with its own sentence action row.

`CAM-070` `[MUST]` Selecting a character in the text view opens the same sheet; stepping between
characters scrolls the list to the owning line, offset by the header height.

### 6.3 Save the captured image — NEW

`CAM-071` `[MUST]` The user can save the captured photograph to the device's **user-accessible photo
library / gallery**, so it survives app uninstall. App-private storage is not acceptable.

`CAM-072` `[MUST]` A `Save photo` action in the review header performs the save. It is:
- enabled only when the image came from the camera (there is nothing to save for an image already in
  the library, and for a share/assistant image the app is not the owner);
- shown as a progress state while writing;
- confirmed with a brief inline confirmation (`Saved to Photos` / `Saved to Gallery`);
- reported inline on failure (`Could not save the photo.`) with the action still available to retry;
- idempotent for one capture: after a successful save the action shows a saved state and does not
  write a duplicate.

`CAM-073` `[MUST]` The saved image is the **original captured photograph**: full captured resolution
where available, with sensor rotation applied and no OCR boxes, no segmentation underlines, no
selection highlight, and no UI overlay of any kind. The overlay exists only in the view layer.

`CAM-074` `[SHOULD]` The saved image should be the pre-downscale capture when the pipeline keeps it;
if only the 2560 px working copy exists, that is acceptable, but the choice must be deliberate and
documented in the implementation, not accidental. *(See `Q-041`.)*

`CAM-075` `[MUST]` Platform mechanics:
- **Android**: insert into `MediaStore.Images` under `Pictures/<AppName>/` with `IS_PENDING` during the
  write, JPEG at quality 92 (or the platform's lossless capture format when available), a
  timestamp-based display name, and correct `DATE_TAKEN`. No permission on API 29+; on API 26-28 use
  the legacy path with the scoped `WRITE_EXTERNAL_STORAGE` declaration.
- **iOS**: `PHPhotoLibrary` add-only request (`addOnlyAccessLevel`), creating an asset in the user's
  library; if the user has previously denied add access, show the failure message with a
  `Open Settings` affordance.

`CAM-076` `[MUST]` The save never blocks the UI thread and never blocks OCR.

### 6.4 Auto-save setting — NEW

`CAM-080` `[MUST]` A persisted boolean setting, default **off**, labelled `Save photos to gallery`
with the helper text `Automatically save pictures you take in the app.` It lives in Settings
(`SET-010`).

`CAM-081` `[MUST]` When enabled, every **camera** capture is saved automatically, immediately after
capture, without further interaction. Gallery-picked and shared/assistant images are never auto-saved.

`CAM-082` `[MUST]` With auto-save on, the manual `Save photo` action shows the already-saved state for
that capture rather than offering a duplicate write.

`CAM-083` `[MUST]` The first auto-save on iOS may trigger the system add-only permission prompt. If it
is denied, auto-save is reported inline once per capture, the setting stays on, and the manual action
remains available. The app must not silently drop saves.

`CAM-084` `[MUST]` Auto-save failures must never block or delay showing the recognition result.

---

## 7. Lifecycle and background behaviour

`CAM-090` `[MUST]` Backgrounding during the capture phase releases the camera; returning rebinds it and
re-checks permission (`CAM-013`) and re-applies the flash state (`CAM-038`).

`CAM-091` `[MUST]` Backgrounding during the review phase keeps the image and the recognition result;
returning shows them without re-running OCR.

`CAM-092` `[MUST]` The recogniser is closed when the owning state holder is destroyed.

`CAM-093` `[MUST]` The capture bitmap must be retained across configuration change without being
re-decoded, and must be released when the camera surface is exited.

`CAM-094` `[MUST]` A screenshot handed in by the assistant integration must not be written to disk
(`SET-055`).

---

## 8. Back navigation

`CAM-100` `[MUST]` Precedence within the camera surface (`PROD-056`):
1. an open word-candidate sheet dismisses;
2. reviewing a **camera** capture returns to the viewfinder (a "retake");
3. the viewfinder, and any gallery/share review, exits the surface back to Search.

---

## 9. Platform differences

| Concern | Android | iOS | Web |
|---------|---------|-----|-----|
| Camera + OCR | Required | Required | **Out of scope** (`PROD-022`) |
| Recogniser | ML Kit Chinese (on-device) | Apple Vision | n/a |
| Image picker | Photo Picker (no permission) | PHPicker (no permission) | file input, only if Web OCR is added |
| Save to library | MediaStore (`Pictures/<App>/`) | PHPhotoLibrary add-only | n/a |
| Save permission | none (API 29+) | add-only usage description | n/a |
| Share-image target | `ACTION_SEND` `image/*` | Share Extension *(optional, `Q-042`)* | n/a |
| Launcher shortcut | static shortcut | Home Screen quick action | n/a |
| Assistant integration | `VoiceInteractionService` | not available | n/a |

`CAM-110` `[MUST]` The camera, picker, recogniser and photo-library writer are each behind a small
platform-abstraction port so shared UI and state code are identical across platforms (`ARCH-090`).

---

## 10. Edge cases (all `[MUST]`)

`CAM-120` A capture with no recognised text still shows the photo, the `No Chinese text found` message,
and the working `Save photo` action.

`CAM-121` Rotated or skewed text: quads are non-axis-aligned; containment uses the quad's winding so
hit-testing still works.

`CAM-122` A recognised symbol without geometry is ignored for hit-testing but still contributes its
text to the line and to the text view.

`CAM-123` Non-Hanzi symbols (Latin, digits, punctuation) are selectable — the sheet will simply show
`No dictionary entry` for them.

`CAM-124` A very large image (over the cap) is downscaled before recognition; overlay coordinates are
in the downscaled space, so overlay and image always agree.

`CAM-125` Tapping between two lines selects the nearest symbol within slop, or nothing at all.

`CAM-126` Rapid taps keep only the latest lookup (`GLB-040`).

`CAM-127` A capture arriving after the surface has been exited is discarded.

`CAM-128` Saving the same capture twice is prevented (`CAM-072`); saving after a retake saves the new
capture.

`CAM-129` If the photo library is full or unavailable, the save fails with the inline message and the
app continues normally.
