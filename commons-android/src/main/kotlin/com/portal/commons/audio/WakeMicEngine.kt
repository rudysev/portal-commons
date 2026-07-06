package com.portal.commons.audio

import android.content.Context
import android.media.MediaRecorder
import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmLevel
import java.io.File

/**
 * Wake **recognition policy** over the shared [PcmCaptureSession]: it owns the [OwwRecognizer]
 * (openWakeWord), the pre-ready frame buffer, and the post-fire cooldown, while the session owns the
 * capture thread + device lifecycle (open/read/robust stop/rebuild). A matched frame invokes
 * `onWake(id)`.
 *
 * **Per-frame policy** ([onFrame], capture thread): while the models are still loading, ring-buffer
 * frames (so speech during init isn't lost); on the ready transition, flush them; then feed the
 * recognizer with a post-fire cooldown. **On (re)start** ([onStarted]) the recognizer is reset so
 * audio captured before a pause can't linger into the next session.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** — `WakeService` drives [pause]/[start] for
 * both handoff and call stand-down via its capture gate. [pause] yields the slot: the session's reads
 * are non-blocking, so its stop deterministically exits the capture thread and releases the mic.
 * [start] reacquires it; the optional [beforeStart] hook runs first on each acquire — portal-wake
 * passes its `MicLiberator.freeMic` there to free the Portal's own wake services for the single mic
 * slot, while the assistant (foreground app) passes nothing.
 *
 * Caller must hold RECORD_AUDIO. If a wake model asset is missing/unusable, [onUnavailable] fires.
 */
class WakeMicEngine(
    context: Context,
    wakeWords: List<OwwWakeWord>,
    private val onUnavailable: () -> Unit,
    private val onWake: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val beforeStart: () -> Unit = {},
    // On-device benchmark/tuning: log every frame's openWakeWord score ("oww-score <id> <s> rms=<r>") and
    // do NOT fire (no onWake, no cooldown), so a played corpus can be scored continuously without triggering
    // a conversation. Off in production. See portal-assistant's wakebench marker.
    private val benchMode: Boolean = false,
    // Capture source A/B (tuning): VOICE_RECOGNITION (default, AGC/NS off) vs MIC / UNPROCESSED.
    private val captureSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    // Wake-only input gain for the amplitude-sensitive melspectrogram (1.0 = production). Tuning only.
    private val wakeGain: Float = 1.0f,
    // Diagnostic: when non-null, the raw captured frames are written to this WAV for inspection. Tuning only.
    private val dumpFile: File? = null,
) {
    private val appContext = context.applicationContext

    @Volatile private var recognizerReady = false // set when the models finish loading
    private var wasRecognizerReady = false // capture-thread-only; detects ready transition for buffer flush

    @Volatile private var pendingWakeWords: List<OwwWakeWord>? = null // queued wake-set swap, applied on capture thread
    private val preReadyBuffer = PcmRingBuffer(OwwTuning.PRE_READY_MAX_FRAMES)

    @Volatile private var recognizer = buildRecognizer(wakeWords)

    private var cooldownUntil = 0L // capture-thread-only; reset on each (re)start
    private var lastRms = 0 // capture-thread-only; last frame RMS, for the bench score log
    private val wavRecorder = dumpFile?.let { runCatching { WavRecorder(it) }.getOrNull() }

    private fun buildRecognizer(words: List<OwwWakeWord>) = OwwRecognizer(
        appContext,
        words,
        onReady = {
            recognizerReady = true
            DebugLog.log("wake recognizer ready (source=$captureSource gain=$wakeGain)")
        },
        onUnavailable = onUnavailable,
    ).apply {
        gain = wakeGain
        if (benchMode) scoreLogger = { id, s -> DebugLog.log("oww-score $id ${"%.3f".format(s)} rms=$lastRms") }
    }

    // Rebuildable: a wedged capture thread (start() refused) is recovered by discarding the session and
    // building a fresh one (new device + thread, no shared state with the wedged one). start/pause/shutdown
    // all run on the single controlling thread (WakeService's arbiter), so this needs no mutual exclusion.
    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = AudioRecordPcmDevice(captureSource),
            onFrame = ::onFrame,
            onStarted = ::onStarted,
            // Forward stop/error only from the CURRENT session (a rebuilt-away zombie's late callback is dropped).
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
     * Open the mic and start capturing (idempotent). Runs [beforeStart] first. The session refuses to
     * start only while a prior capture thread is still alive (a native open/stop/release hung); recover
     * by discarding it and starting fresh. Returns whether a capture thread is now running.
     */
    fun start(): Boolean {
        beforeStart()
        if (session.start()) return true
        DebugLog.log("wake capture wedged — rebuilding session and retrying")
        session = buildSession()
        return session.start()
    }

    /**
     * Swap the wake set (a plugin was installed/removed) without restarting capture. The swap is queued
     * here and applied on the capture thread in [onFrame] at a frame boundary — never closing a native
     * recognizer mid-inference. openWakeWord has no grammar, so this rebuilds the recognizer with the new
     * wake models (cheap: small ONNX sessions, loaded off-thread; frames buffer until ready again).
     */
    fun updateWakeWords(words: List<OwwWakeWord>) {
        pendingWakeWords = words
    }

    /** Release the mic so a consumer (or a call) can take the slot. Non-blocking reads make stop deterministic. */
    fun pause() {
        session.stop()
        DebugLog.log("mic paused (yielded slot)")
    }

    /** Full teardown: release the mic and close the wake models. */
    fun shutdown() {
        session.stop()
        recognizer.close()
        wavRecorder?.close()
    }

    // ---- recognition policy (capture thread) -------------------------------------------------------

    /** Reset recognizer state (drops stale audio) and the ready-transition flag on each (re)start. */
    private fun onStarted() {
        wasRecognizerReady = false
        cooldownUntil = 0L
        // Discard frames buffered during a prior session's load window, so pre-pause speech can't be
        // flushed into the recognizer after a resume (a spurious wake right after a conversation ends).
        preReadyBuffer.clear()
        recognizer.reset()
    }

    private fun onFrame(buf: ByteArray, n: Int) {
        applyPendingWakeWords()
        // Diagnostic dump of the RAW captured frame (pre-gain) + its level, for on-device inspection.
        wavRecorder?.write(buf, n)
        if (benchMode) lastRms = PcmLevel.rms(buf, n).toInt()
        // While the models load, ring-buffer recent frames so speech during init isn't lost.
        if (!recognizerReady) {
            preReadyBuffer.add(buf, n)
            return
        }
        if (!wasRecognizerReady) {
            wasRecognizerReady = true
            flushPreReadyBuffer()
        }
        tryAcceptFrame(buf, n)
    }

    /** Apply a queued [updateWakeWords] on the capture thread (rebuild the recognizer). No-op when nothing pending. */
    private fun applyPendingWakeWords() {
        val words = pendingWakeWords ?: return
        pendingWakeWords = null
        DebugLog.log("wake set changed → rebuilding recognizer (${words.size} word(s))")
        recognizerReady = false
        wasRecognizerReady = false
        recognizer.close()
        recognizer = buildRecognizer(words)
    }

    /** Feed buffered pre-ready frames to the recognizer once the models become ready. */
    private fun flushPreReadyBuffer() {
        val buffered = preReadyBuffer.drain()
        if (buffered.isEmpty()) return
        DebugLog.log("flushing ${buffered.size} pre-ready frame(s)")
        for (frame in buffered) tryAcceptFrame(frame, frame.size)
    }

    /** Run one frame through openWakeWord; fire [onWake] on a match (respects post-fire cooldown). */
    private fun tryAcceptFrame(buf: ByteArray, n: Int) {
        if (System.currentTimeMillis() < cooldownUntil) return
        // Bench mode: still run the recognizer (so the scoreLogger fires) but never trigger a wake.
        if (benchMode) { recognizer.accept(buf, n); return }
        val match = recognizer.accept(buf, n) ?: return
        // Log the score so a false fire is diagnosable, e.g. `wake detected → jarvis [0.87]`.
        DebugLog.log("wake detected → ${match.id} [${"%.2f".format(match.score)}]")
        cooldownUntil = System.currentTimeMillis() + OwwTuning.COOLDOWN_MS
        onWake(match.id)
    }

    private companion object {
        // Android mic robustness (not detector tuning — that's in [OwwTuning]): rebuild the AudioRecord after
        // a short run of read errors.
        const val REBUILD_AFTER_READ_FAILURES = 40
    }
}
