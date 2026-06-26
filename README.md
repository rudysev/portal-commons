# portal-commons

Small, **rock-solid** shared library for the Portal wake/voice apps — mic capture, loudness math, and
file logging. It holds only code that is genuinely common, stable, and worth trusting without a second
thought — kept deliberately tiny and fully unit-tested. (Plugin contracts are **not** here: each lives in
the app that owns it — `WakeContract` in portal-wake, `ToolContract` in portal-assistant — since the
literal wire strings are the contract and consumers just mirror them.)

Two modules: **`:commons`** is pure Kotlin/JVM (no Android dependency), so it builds and tests in
milliseconds off-device; **`:commons-android`** is a tiny Android library for the few shared shells that
must touch the Android SDK (the mic `AudioRecord` wrapper). `:commons-android` depends on `:commons`.

## What's in it

| Class | Purpose |
|---|---|
| `DebugLog` | Best-effort file log → `files/debug.txt` (the Portal restricts `logcat`). Never throws, no-op until `file` is set — safe to call from a real-time capture thread. |
| `PcmCaptureSession` / `PcmDevice` | The mic capture thread + its concurrency contract, driving a `PcmDevice` seam (the Android `AudioRecord` shell lives in `:commons-android`, so the session stays Android-free and unit-testable). |
| `PcmCaptureFormat` | The single source of truth for the capture shape (16 kHz mono 16-bit, `BYTES_PER_SAMPLE`, derived `FRAME_BYTES`) that capture, level math, and the Android seam all key off. |
| `PcmLevel` | RMS / level math for little-endian 16-bit mono PCM. Powers the recording-level indicator (`normalized`); `rms` exposes the raw amplitude for any other level check. Pure, fully tested. |

All package `com.portal.commons`.

### `:commons-android` (Android library)

| Class | Purpose |
|---|---|
| `AudioRecordPcmDevice` | The shared `AudioRecord`-backed `PcmDevice` (VOICE_RECOGNITION, 16 kHz mono, no effects — the proven Portal capture config), used by consuming apps' capture sessions. Lives here, not in `:commons`, because it touches `android.media.*`. Package `com.portal.commons.audio`. |

## Build / test (standalone)

Requires JDK 21.

```bash
./gradlew test
```

## How apps consume it

An app wires it in via a Gradle **composite build** pointing at this repo as a sibling directory — no
Maven publish, no version juggling:

```kotlin
// <app>/settings.gradle.kts
includeBuild("../portal-commons")
```
```kotlin
// <app>/app/build.gradle.kts
dependencies {
    implementation("com.portal:commons")
}
```

Apps that capture audio also depend on the Android library — the same `includeBuild` substitutes it, no
extra wiring:

```kotlin
// <app>/app/build.gradle.kts
dependencies {
    implementation("com.portal:commons-android")
}
```

Gradle substitutes `com.portal:commons` (and `com.portal:commons-android`) with this included build, so
consuming apps always compile against the one shared source tree.

## Disclaimer

portal-commons is an independent community project — **not affiliated with, endorsed
by, or sponsored by Meta**. "Meta Portal" and "Portal" are trademarks of Meta
Platforms, Inc., used here only to identify compatible hardware. The apps built on this
library install and run on discontinued devices and are **use-at-your-own-risk** (may
void warranty; no guarantees). See [DISCLAIMER.md](DISCLAIMER.md) for the full text and
privacy notes.
