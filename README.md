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
| `PcmCapture` | The module every app captures the mic through, one **capture request** at a time (see `GLOSSARY.md`): `start(listener)` / `pause()` / `close()`. Each request's callbacks reach only its own listener and never run at once; at most one capture loss per request, posted to the main thread; `close()` is terminal and bounded. |
| `PcmDevice` | The microphone seam `PcmCapture` drives through its internal single-use capture session (the Android `AudioRecord` shell lives in `:commons-android`, so capture stays Android-free and unit-testable). |
| `PcmCaptureFormat` | The single source of truth for the capture shape (16 kHz mono 16-bit, `BYTES_PER_SAMPLE`, derived `FRAME_BYTES`) that capture, level math, and the Android seam all key off. |
| `PcmLevel` | RMS / level math for little-endian 16-bit mono PCM. Powers the recording-level indicator (`normalized`); `rms` exposes the raw amplitude for any other level check. Pure, fully tested. |
| `WakeMatcher` / `WakeWord` | Pure wake-decision logic and phrase model (`com.portal.commons.audio`). |
| `CaptureLoss` | A **capture loss** (see `GLOSSARY.md`): `NotGranted`, `Dropped` or `Silenced`, each with a `reason` for the log (`com.portal.commons.audio`). Reaches apps through `PcmCapture.Listener.onLost` (the wake engine through `WakeMicConfig.onCaptureLost`). |

All package `com.portal.commons`.

### `:commons-android` (Android library)

| Class | Purpose |
|---|---|
| `AudioRecordPcmDevice` | The shared `AudioRecord`-backed `PcmDevice` (VOICE_RECOGNITION, 16 kHz mono, no effects — the proven Portal capture config). Package `com.portal.commons.audio`. |
| `WakeMicEngine` | Mic + capture thread; feeds PCM frames to its **wake detector** (Vosk), or with `WakeMicConfig.hybrid` set to the **hybrid wake detector** beside it, on one injected monotonic clock, and delivers the wakes it returns; the **handoff cooldown** after a wake is the wake detector's. Consumer wiring via `WakeMicConfig` (`WakeEvent(wakeId, transcript)`, `onReady()` / `onUnavailable()`). `start()` / `pause()` / `close()`: see below; `markMiss()` saves recent audio to the hybrid's evidence. |
| `HybridWakeConfig` | Turns on the **hybrid wake detector** (see `GLOSSARY.md`) for one wake id: the app supplies the `WakeGate` (openWakeWord) and `KeywordSpotter` (sherpa-onnx), the live `WakeMode` and **Vosk shadow** switches, and an `onEvidence(HybridEvidence)` sink. |
| `WakeModel` | The **wake model** `WakeMicConfig.model` names: `WakeModel.Bundled` (the APK's `assets/model-en-us`, unpacked to filesDir; portal-wake) or `WakeModel.Unpacked(dir)` (an already-unpacked model on disk, e.g. downloaded at runtime; portal-assistant on gen2). |
| Wake detector (`internal`) | Model load, grammar building + hot-swap, warm-up, pre-ready buffering + replay (stopping at the first replayed wake), the **handoff cooldown** (one window after a wake in which every frame is ignored, so no further wake arises; `wakeHandoffCooldownMs`), idle-reset, and the `WakeMatcher` call. Native libvosk sits behind an internal seam, so the policy and the engine are JVM-tested over fakes. |

### Wake mic engine lifecycle

- **Capture request** (defined in `GLOSSARY.md`): the span from a `start()` that begins capture to the next
  `pause()`, `close()` or capture loss. The engine captures through one `PcmCapture`, which binds each request's
  callbacks to that request and captures it on a fresh device. Every `start()` passes the engine's one listener, so a
  `start()` joins an open request unless a loss has been raised for it. A wedged older capture thread never feeds the
  wake detector once a newer request has begun.
- **Capture loss** (defined in `GLOSSARY.md`) arrives as `onCaptureLost(CaptureLoss)`, at most once per capture
  request. A loss that a `pause()` or a newer `start()` has overtaken reports nothing, even if it was already posted.
  After a loss the engine is idle, as after `pause()`, and never retries on its own: retry by calling `start()`.
  `start()` returns nothing.
- **Posting rule.** `onCaptureLost`, `onReady` and `onUnavailable` are always posted to the main
  looper, never run inline, even from the main thread or the engine's constructor. `onWake` stays synchronous on the
  capture thread, so the consumer can pause within about one frame.
- **Closed state.** `close()` is terminal and idempotent. After it nothing is delivered, including callbacks already
  posted, and `start()` / `pause()` are logged no-ops.
- **Bounded close.** `close()` is synchronous and safe from any thread, including the wake callback; the mic slot is
  free when it returns. The capture stop, the wait for a running wake detector call and the wait for a wake being
  delivered share one ~400 ms deadline (`PcmCapture.STOP_DEADLINE_MS`). A wake detector still busy then is closed
  by the capture thread as it leaves that call, with a `🔴` log line; if that call never returns, its native resources
  leak until the process dies. The wake detector's close itself is not under the deadline: when the wait succeeds it runs
  on the calling thread, and freeing the Vosk model takes ~0.5 s on Portal+ gen1.

## Build / test (standalone)

Requires JDK 21.

```bash
./gradlew check   # unit tests + Android Lint + detekt + ktlint (kotlinter); ./gradlew formatKotlin fixes style
```

Enable the git hooks once per clone. They need the ktlint and shellcheck CLIs (`brew install ktlint shellcheck`) and
the detekt 2.0.0-alpha.6 CLI, `detekt-cli`: Homebrew only has detekt 1.x, so unzip
[the release zip](https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip)
and put its `bin/` on your `PATH`.

```bash
git config core.hooksPath .githooks
```

`pre-commit` runs ktlint and detekt on the staged Kotlin files and shellcheck on the staged shell scripts, and
`pre-push` runs `./gradlew check`. `--no-verify` skips
them deliberately.

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
