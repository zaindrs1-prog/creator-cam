# CreatorCam — Dual-Camera Studio for Vloggers

A production-quality **native Android** app (Kotlin, Jetpack Compose, CameraX)
that records the **front and rear cameras simultaneously** and fuses them into
one share-ready video — your face and your world in a single take.

No account, no backend, no ads, no internet required. Everything runs on-device.

---

## 1. How the dual-camera implementation works

### Capture: CameraX Concurrent Camera (stable API, since CameraX 1.3)

- Each lens binds its own `UseCaseGroup` of **`Preview` + `VideoCapture(Recorder)`**.
- Both groups are bound atomically with
  `ProcessCameraProvider.bindToLifecycle(listOf(rearConfig, frontConfig))`,
  which returns a `ConcurrentCamera` holding both `Camera` objects.
- **Audio is captured by exactly one owner** — the rear (primary) recording
  uses `PendingRecording.withAudioEnabled()`; the front recording is
  video-only. This avoids two consumers fighting for the microphone (a common
  cause of silent/failed takes in dual-camera apps).
- Zoom, torch, exposure and tap-to-focus use `CameraControl`/`CameraInfo` per
  camera. Stabilization and frame rate are applied for real through
  `Camera2Interop` capture-request options
  (`CONTROL_VIDEO_STABILIZATION_MODE`, `CONTROL_AE_TARGET_FPS_RANGE`) on the
  session shared by preview and recorder.

### Sync: measured, not assumed

- The record-start callback of each stream captures
  `SystemClock.elapsedRealtime()`. The delta becomes a per-stream timestamp
  offset (`TimestampSynchronizer`), applied when samples are composited.
- After composing, audio vs. video durations are validated; divergence is
  logged and surfaced instead of silently shipped.

### Composition: real dual output (MediaCodec + OpenGL + MediaMuxer)

Both encoders write independent MP4s to app-private staging. When the take
stops, `VideoComposer` fuses them:

1. Hardware-decodes both streams (`MediaCodec` → `SurfaceTexture`).
2. Renders them on a shared EGL context into one frame per the selected
   `DualLayout` — splits, side-by-side, PiP, rounded PiP and circular face
   window (SDF-masked in the fragment shader), with container-rotation and
   front-mirror handling and center-crop framing that **matches the live
   preview** (both fill the region; WYSIWYG).
3. Encodes once to AVC (`MediaCodec` input surface → `MediaMuxer`).
4. Copies the primary AAC audio track **losslessly** (no re-encode, no drift).

The final MP4 is written to **`Movies/CreatorCam/` via MediaStore**
(`RELATIVE_PATH`, `IS_PENDING`, MIME type) so it appears in the gallery
immediately. No storage permission needed on API 29+.

### Honest fallback

If either stream fails to finalize, the survivor is composed fullscreen
rather than shipping a half-black frame. If composition itself fails, the
original camera files are kept so the take is never silently lost.

---

## 2. Device requirements & hardware reality

| Capability | Requirement |
|---|---|
| OS | Android 10+ (API 29). Concurrent front+rear needs **Android 11+ (API 30)** for the platform concurrent-streaming query |
| Dual recording | Front + rear IDs must appear together in `CameraManager.concurrentCameraIds` **and** in CameraX's `availableConcurrentCameraInfos` |
| Single recording | Any device with a camera |
| Permissions | `CAMERA`, `RECORD_AUDIO` only |

**The app never pretends.** `CameraCapabilityChecker` probes real
`CameraCharacteristics` (video sizes, AE FPS ranges, stabilization modes,
max zoom, flash) and the two concurrent-camera oracles above. Unsupported
devices get a clear explanation plus single-camera mode — never a crash, never
a fake dual preview.

Known hardware limitations (true of every dual-camera app):

- Many mid-range/older phones expose no concurrent sets at all → single mode.
- Concurrent 4K+4K is thermally unsustainable on most phones; **Auto quality
  caps dual takes at 1080p** unless you explicitly choose otherwise.
- Some HALs forbid particular quality combos; each `Recorder` gets an ordered
  `QualitySelector` fallback chain, and the UI annotates every option with
  per-camera support.
- Front-camera "mirror" matches the selfie-style preview; if your device's
  HAL behaves differently, the toggle still gives you both options.

---

## 3. Project structure

```
app/src/main/java/com/creatorcam/app/
├── CreatorCamApp.kt            # Application (offline-first, no SDKs)
├── MainActivity.kt             # Single-activity Compose host
├── camera/
│   ├── CameraCapabilityChecker.kt  # Real dual-camera probe (Camera2 + CameraX)
│   ├── QualityAdvisor.kt           # Common-denominator quality matching
│   ├── DualCameraSession.kt        # Concurrent Preview+VideoCapture session
│   └── SingleCameraSession.kt      # Fallback session, same control surface
├── recording/
│   ├── DualRecordingController.kt  # Preflight → countdown → record → compose → save
│   ├── TimestampSynchronizer.kt    # Measured-offset A/V sync (unit-tested)
│   ├── StorageGuard.kt             # Preflight estimates + safe-stop thresholds
│   └── BatteryThermalMonitor.kt    # Battery + thermal safety
├── audio/
│   └── MicrophoneMonitor.kt        # Mic probe + idle level meter (AudioRecord)
├── compose/
│   ├── DualLayout.kt / LayoutSpec.kt  # Layouts; single geometry source (unit-tested)
│   ├── VideoComposer.kt            # Decoder → EGL → encoder → muxer fusion
│   ├── GlComposerRenderer.kt       # OES sampling, shapes, overlays (GLES2)
│   ├── EglCore.kt                  # EGL14 offscreen context
│   ├── OverlayFactory.kt           # Watermark / date / caption pills (Canvas)
│   └── ExportPreset.kt             # Shorts/Reels/TikTok/Square/Landscape (unit-tested)
├── media/
│   ├── MediaStoreSaver.kt          # Gallery save/query/rename/delete/thumbnails
│   ├── VideoTrimmer.kt             # Lossless trim + mute (no re-encode)
│   └── VideoMetadata.kt            # Duration/size/resolution probing
├── settings/                       # DataStore preferences (no fake settings)
└── ui/                             # Compose screens: home, permissions, device
    check, camera (+layout/quality sheets), recordings, player, editor,
    settings, teleprompter, diagnostics
app/src/test/...                    # JVM unit tests (sync, layouts, presets)
```

Key design decisions:

- **MVVM + StateFlow.** Camera and recording engines are UI-independent and
  survive rotation; the activity only declares `configChanges` as a backstop
  while takes lock orientation outright.
- **One geometry source.** `LayoutSpec.rects()` drives both the Compose
  preview panes and the GL composer — framing can't drift between them.
- **Fixed presets first, custom-ready.** `PipStyle(scale, margin, position,
  cornerRadius)` already parameterizes every PiP window; V2 adds gestures.
- **V1 honesty rules:** no noise-reduction toggle (the platform exposes no
  real hook through this pipeline), no "AI" anything, no fake zoom beyond
  `ZoomState` limits, no forced watermark.

---

## 4. How to run the project

Prerequisites: **Android Studio** (Koala or newer), **JDK 17**, Android
**SDK 35** (Studio installs it on sync if missing).

```bash
git clone <this-repo>
cd creator-cam
```

1. Open the `creator-cam/` folder in Android Studio
   ("Open an Existing Project" → select the folder containing
   `settings.gradle.kts`). Studio provisions the Gradle wrapper jar and the
   required SDK packages on first sync.
2. Connect a physical device (dual camera needs real hardware — the emulator
   has no concurrent front+rear) with USB debugging on, or create an emulator
   for UI-only work (expect single-camera fallback there).
3. Run ▶ `app`.

Command line (after Studio has synced once, or with a local Gradle 8.9+):

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest          # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> If `gradlew` complains about a missing wrapper jar before Studio ever
> synced, run `gradle wrapper --gradle-version 8.9` once with a system Gradle.

---

## 5. How to build APK / release AAB

```bash
# Signed-config-free release build (add your signingConfig for Play):
./gradlew :app:assembleRelease            # app-release.apk (minified)
./gradlew :app:bundleRelease              # app-release.aab for Play Console
```

Release notes:

- `isMinifyEnabled`/`isShrinkResources` are on with keep rules for CameraX,
  Media3 and the app package (`app/proguard-rules.pro`).
- Create a keystore and add a `signingConfigs.release` block in
  `app/build.gradle.kts` before uploading to Play.
- `versionCode`/`versionName` live in `app/build.gradle.kts`.

---

## 6. How to test dual-camera support

### On-device checklist (maps to the 20 required scenarios)

1. **Supported device** → Home → Device Check shows all ✓ → Dual Camera shows
   both previews.
2. **Unsupported device** → clear "Dual camera not available" + working
   single modes (test on an emulator too — it must not crash).
3. **Front only / rear only** → single modes record and save.
4. **Camera denied / mic denied** → friendly permission screen, settings
   shortcut; mic-denied records silent video with a warning.
5. **Low storage** → preflight refuses with an exact message; fill storage
   mid-take → safe stop, finalized file.
6. **Low battery / hot device** → warning badges; severe thermal stops safely.
7. **Rotation** → rotate freely while previewing (no crash, framing follows);
   orientation locks during takes.
8. **Background / call** → take finalizes instead of corrupting.
9. **Long recording (10+ min)** → stable, no drift (see #7 below).
10. **A/V sync** → clap test: audio within ~1 frame; front/rear lip-sync.
11. **Export/gallery** → file in Photos/Gallery under Movies/CreatorCam.
12. **Playback/share/rename/delete** → from Recordings and the player.
13. **Editor** → trim, mute, caption, Shorts/Square export, re-layout (with
    "Keep source files" on).
14. **App restart** → settings persist; nothing stranded (staging pruned).

### Fastest sync test

Record 30 s with a clap at start and end, play back: both claps must land
within a frame on audio and on both videos.

### Diagnostics

Settings → Device diagnostics shows the same probe the engine uses (IDs,
sizes, FPS ranges, stabilization, zoom, flash, storage, battery, thermal)
with one-tap share-as-text for bug reports.

---

## 7. Roadmap (foundation-ready, never faked)

- **V2:** custom PiP gestures (drag/resize/radius via `PipStyle`), dual
  photo mode (`ImageCapture` slots alongside `VideoCapture`), HDR/HLG +
  60 fps Feature-Group API, per-camera exposure UI.
- **V3:** on-device AI (captions, auto-reframe, highlights) behind a clean
  `VideoAnalysis` port — interfaces only when real models ship.
- Monetization, if ever added, stays out of the core capture path.

---

## 8. Privacy

- Permissions: Camera + Microphone only. No location, no contacts, no storage.
- All capture, composition, editing and export happen on-device.
- No accounts, analytics, ads, crash-upload SDKs or network calls of any kind.
- Uninstall (or in-app delete) removes your data; gallery files are yours.

---

## License

All rights reserved — see the repository owner for licensing terms.
```

---

*Toolchain: AGP 8.7.3 · Gradle 8.9 · Kotlin 2.1.0 · compile/target SDK 35 ·
min SDK 29 · CameraX 1.3.4 · Compose BOM 2025.01.00 · Media3 1.4.1.*
