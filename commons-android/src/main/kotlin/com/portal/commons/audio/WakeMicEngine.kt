package com.portal.commons.audio

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import java.io.File

/**
 * Owns the **mic + capture thread + frame assembly** over the shared [PcmCaptureSession], and fans each
 * captured frame to one or more [WakeDetector]s. It is deliberately **detector-agnostic**: what counts as a
 * wake now lives behind the [WakeDetector] seam, so Vosk can be swapped for another engine (e.g.
 * openWakeWord) or run **alongside** it for a paired A/B (every detector sees bit-identical audio).
 *
 * **Per-frame policy** ([onFrame], capture thread): apply any queued wake-set swap at this frame boundary
 * (so a detector's native recognizer is never closed mid-[WakeDetector.accept]), then deliver the frame to
 * every detector. Pre-ready buffering, warm-up, and any decode-lattice bounding are the *detector's* concern
 * now (see [VoskWakeDetector]) — the engine only guarantees frame delivery and thread discipline.
 *
 * **Post-fire cooldown** lives here, per detector: a match is de-duped for [COOLDOWN_MS] so the ~1 handoff
 * frame before capture actually pauses can't double-fire, without coupling detectors to each other.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** — the consumer (`WakeService`/`AssistantService`)
 * drives [pause]/[start] for handoff and call stand-down. [pause] yields the slot (non-blocking reads make
 * the session's stop deterministic); [start] reacquires it, running [beforeStart] first (portal-wake frees
 * the Portal's own wake services to own the single mic slot; the assistant passes nothing).
 *
 * Caller must hold RECORD_AUDIO. If a detector's model is missing/unusable it reports via [onUnavailable].
 */
class WakeMicEngine(
    private val context: Context,
    wakeWords: List<WakeWord>,
    detectorFactories: List<WakeDetector.Factory>,
    private val onWake: (name: String, id: String, detail: String) -> Unit = { _, _, _ -> },
    private val onUnavailable: (name: String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val beforeStart: () -> Unit = {},
) {
    // Queued wake-set swap, applied on the capture thread in onFrame at a frame boundary (never closing a
    // native recognizer mid-accept — see WakeDetector.updateWakeWords).
    @Volatile private var pendingWakeWords: List<WakeWord>? = null

    // Post-fire de-dupe, keyed by detector name. Capture-thread-only (touched from onFrame/onStarted paths).
    private val cooldown = FireCooldown(COOLDOWN_MS)

    /** Reports from every detector. onWake/onDiagnostic arrive on the capture thread; onReady on a load thread. */
    private val events = object : WakeDetector.Events {
        override fun onReady(name: String) = DebugLog.log("wake detector ready ($name)")

        override fun onUnavailable(name: String) {
            DebugLog.log("wake unavailable ($name) — detector idle")
            onUnavailable(name)
        }

        override fun onWake(name: String, id: String, detail: String) = handleWake(name, id, detail)

        override fun onDiagnostic(name: String, message: String) = DebugLog.log(message)
    }

    private val detectors: List<WakeDetector> =
        detectorFactories.map { it.create(context, wakeWords, events) }

    // Rebuildable: a wedged capture thread (start() refused) is recovered by discarding the session and
    // building a fresh one. start/pause/shutdown all run on the single controlling thread (the consumer's
    // arbiter), so this needs no mutual exclusion; @Volatile just makes the recovery-path reassignment visible.
    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = AudioRecordPcmDevice(),
            onFrame = ::onFrame,
            onStarted = ::onStarted,
            // Forward stop/error only from the CURRENT session — a rebuilt-away (wedged) session's zombie still
            // fires onStopped when its native call finally unblocks. `session` is @Volatile so the old capture
            // thread sees the current reference and a stale session's callback is dropped.
            onStopped = { if (session === built) onStopped() },
            onError = { if (session === built) onError(it) },
            log = { DebugLog.log(it) },
            threadName = "wake-capture",
            rebuildAfterReadFailures = REBUILD_AFTER_READ_FAILURES,
            idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        )
        return built
    }

    /**
     * Open the mic and start capturing (idempotent). Runs [beforeStart] first. The session refuses to start
     * only while a prior capture thread is still alive (a native open/stop/release hung); recover by
     * discarding it and starting fresh. Returns whether a capture thread is now running.
     */
    fun start(): Boolean {
        beforeStart()
        if (session.start()) return true
        DebugLog.log("wake capture wedged — rebuilding session and retrying")
        session = buildSession()
        return session.start()
    }

    /**
     * Swap the wake set (a plugin was installed/removed) **without restarting capture or reloading the
     * model**: queued here and applied on the capture thread in [onFrame] at a frame boundary.
     */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords = words
    }

    /** Release the mic so a consumer (or a call) can take the slot. Non-blocking reads make stop deterministic. */
    fun pause() {
        session.stop()
        DebugLog.log("mic paused (yielded slot)")
    }

    /** Full teardown: release the mic and close every detector's model. */
    fun shutdown() {
        session.stop()
        detectors.forEach { it.close() }
    }

    // ---- capture-thread callbacks ------------------------------------------------------------------

    /** Reset per-detector state (re-warms) and clear cooldowns on each (re)start. */
    private fun onStarted() {
        cooldown.reset()
        detectors.forEach { it.start() }
    }

    private fun onFrame(buf: ByteArray, n: Int) {
        applyPendingWakeWords()
        detectors.forEach { it.accept(buf, n) }
    }

    /** Apply a queued [updateWakeWords] on the capture thread. No-op when nothing is pending. */
    private fun applyPendingWakeWords() {
        val words = pendingWakeWords ?: return
        pendingWakeWords = null
        DebugLog.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        detectors.forEach { it.updateWakeWords(words) }
    }

    /**
     * A detector fired (capture thread). De-dupe per detector for [COOLDOWN_MS] (handoff pauses capture, but
     * not before ~1 frame could re-fire), log the decode, then route to the consumer. The detector tag is
     * added to the log only when more than one detector runs, so single-detector `debug.txt` is unchanged.
     */
    private fun handleWake(name: String, id: String, detail: String) {
        if (!cooldown.tryFire(name, System.currentTimeMillis())) return
        val tag = if (detectors.size > 1) " ($name)" else ""
        DebugLog.log("wake detected$tag → $id [$detail]")
        onWake(name, id, detail)
    }

    companion object {
        const val COOLDOWN_MS = 1_500L // ignore further matches (per detector) briefly after a fire
        const val REBUILD_AFTER_READ_FAILURES = 40 // rebuild the device after a short run of read errors

        /**
         * Vosk-only engine, preserving the previous behavior and single-detector `debug.txt` format. This is
         * the drop-in for the old `WakeMicEngine(...)` constructor: the two consumers call it unchanged.
         *
         * @param modelDir null (default) = bundled `assets/model-en-us` (portal-wake); a dir = an
         *   already-unpacked, downloaded model (portal-assistant on gen2).
         */
        fun vosk(
            context: Context,
            wakeWords: List<WakeWord>,
            onUnavailable: () -> Unit,
            onWake: (String) -> Unit = {},
            onError: (String) -> Unit = {},
            onStopped: () -> Unit = {},
            beforeStart: () -> Unit = {},
            modelDir: File? = null,
        ): WakeMicEngine = WakeMicEngine(
            context = context,
            wakeWords = wakeWords,
            detectorFactories = listOf(VoskWakeDetector.factory(modelDir)),
            onWake = { _, id, _ -> onWake(id) },
            onUnavailable = { onUnavailable() },
            onError = onError,
            onStopped = onStopped,
            beforeStart = beforeStart,
        )

        /**
         * openWakeWord-only engine — the neural KWS reading bundled ONNX assets (`assets/oww/…`, shipped via
         * `commons-android`), no runtime model download. A single-detector drop-in mirroring [vosk].
         *
         * [onUnavailable] fires only if the oww assets are missing (they are bundled, so it shouldn't in
         * practice). [onWake] delivers the wake id (the fixed "jarvis" for this model).
         */
        fun oww(
            context: Context,
            wakeWords: List<WakeWord>,
            onUnavailable: () -> Unit = {},
            onWake: (String) -> Unit = {},
            onError: (String) -> Unit = {},
            onStopped: () -> Unit = {},
            beforeStart: () -> Unit = {},
        ): WakeMicEngine = WakeMicEngine(
            context = context,
            wakeWords = wakeWords,
            detectorFactories = listOf(OpenWakeWordDetector.factory()),
            onWake = { _, id, _ -> onWake(id) },
            onUnavailable = { onUnavailable() },
            onError = onError,
            onStopped = onStopped,
            beforeStart = beforeStart,
        )
    }
}
