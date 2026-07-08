package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureFormat
import java.io.File

/**
 * The **Vosk** [WakeDetector]: the recognition policy that used to live inline in [WakeMicEngine], now behind
 * the detector seam so it can be swapped or run alongside another detector. It owns the [WakeRecognizer]
 * (the Vosk boundary), the pre-ready frame buffer, and the idle-reset that bounds Vosk's decode lattice —
 * everything Vosk-specific. The engine keeps only the mic + framing.
 *
 * **Per-frame policy** ([accept], capture thread): while the model is still loading, ring-buffer frames (so
 * speech during init isn't lost); on the ready transition, flush them; then feed the recognizer, bounding the
 * native lattice via [IdleResetPolicy]. **On (re)start** ([start]) the recognizer is reset — which re-warms
 * it — so the first frame after a resume isn't garbled.
 *
 * The **post-fire cooldown** is NOT here — [WakeMicEngine] de-dupes rapid re-fires across all detectors, so a
 * match just reports to [WakeDetector.Events.onWake]. Diagnostics ([WakeDetector.Events.onDiagnostic]) carry
 * the exact `near-miss …` / `flushed final …` strings the mic owner used to log, so `debug.txt` is unchanged.
 */
class VoskWakeDetector(
    context: Context,
    initialWakeWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    // null = bundled asset (portal-wake); a dir = a downloaded, already-unpacked model (portal-assistant gen2).
    modelDir: File? = null,
) : WakeDetector {

    override val name: String = NAME

    @Volatile private var recognizerReady = false // set when the model finishes unpacking (warmed)
    private var wasRecognizerReady = false // capture-thread-only; detects ready transition for buffer flush
    private val idleReset = IdleResetPolicy() // capture-thread-only; when to bound the Vosk decode lattice
    private val preReadyBuffer = PcmRingBuffer(PRE_READY_MAX_FRAMES)

    private val recognizer = WakeRecognizer(
        context,
        initialWakeWords,
        onReady = {
            recognizerReady = true
            events.onReady(NAME)
        },
        onUnavailable = { events.onUnavailable(NAME) },
        modelDir = modelDir,
    )

    /** Reset recognizer state (re-warms) and the ready-transition flag on each (re)start. */
    override fun start() {
        wasRecognizerReady = false
        recognizer.reset()
        idleReset.noteFlushed(System.currentTimeMillis())
    }

    override fun accept(buf: ByteArray, n: Int) {
        // While the model loads, ring-buffer recent frames so speech during init isn't lost.
        if (!recognizerReady) {
            preReadyBuffer.add(buf, n)
            return
        }
        if (!wasRecognizerReady) {
            wasRecognizerReady = true
            flushPreReadyBuffer()
        }
        maybeIdleReset()
        tryAcceptFrame(buf, n)
    }

    /**
     * Swap the grammar to a new wake set (see [WakeRecognizer.rebuildGrammar]). MUST be on the capture thread
     * — the engine guarantees it by applying the swap at a frame boundary.
     */
    override fun updateWakeWords(words: List<WakeWord>) {
        recognizer.rebuildGrammar(words)
        idleReset.noteFlushed(System.currentTimeMillis()) // fresh recognizer — restart the idle-reset clock
    }

    override fun close() {
        recognizer.close()
    }

    // ---- recognition policy (capture thread) -------------------------------------------------------

    /**
     * Bound Vosk's native decode lattice. It grows (~3.8 MB/min observed) only while the recognizer isn't
     * endpointing — with continuous audio the "current utterance" never finalizes. Natural endpoints normally
     * flush it; this backstop [WakeRecognizer.reset] (which re-warms) covers the case where they stop.
     * [IdleResetPolicy] owns the timing (see its KDoc): reset only in decoder silence, so it can't bisect an
     * in-flight "hey …", with a hard cap for pathological continuous noise.
     */
    private fun maybeIdleReset() {
        val now = System.currentTimeMillis()
        val decision = idleReset.decide(now, recognizer::isMidUtterance)
        if (decision == IdleResetPolicy.Decision.KEEP) return
        recognizer.reset()
        idleReset.noteFlushed(now)
        when (decision) {
            IdleResetPolicy.Decision.RESET -> events.onDiagnostic(NAME, "idle reset (bounding Vosk lattice)")

            IdleResetPolicy.Decision.FORCE_RESET ->
                events.onDiagnostic(NAME, "idle reset forced mid-decode (bounding Vosk lattice)")

            IdleResetPolicy.Decision.KEEP -> {}
        }
    }

    /** Feed buffered pre-ready frames to the recognizer once the model becomes ready. */
    private fun flushPreReadyBuffer() {
        val buffered = preReadyBuffer.drain()
        if (buffered.isEmpty()) return
        events.onDiagnostic(NAME, "flushing ${buffered.size} pre-ready frame(s)")
        for (frame in buffered) {
            tryAcceptFrame(frame, frame.size)
        }
    }

    /** Run one frame through Vosk; report a match / near-miss / non-empty flush. */
    private fun tryAcceptFrame(buf: ByteArray, n: Int) {
        val outcome = recognizer.accept(buf, n) ?: return
        // Any finalized decode means Kaldi flushed the lattice and started a fresh utterance — restart the
        // idle-reset clock so the manual reset only runs after a long stretch with no endpoint at all.
        idleReset.noteFlushed(System.currentTimeMillis())
        when (outcome) {
            is WakeRecognizer.Outcome.Match ->
                events.onWake(NAME, outcome.id, outcome.transcript)

            is WakeRecognizer.Outcome.NearMiss ->
                events.onDiagnostic(NAME, "near-miss [${outcome.transcript}] rejected: ${outcome.reason}")

            is WakeRecognizer.Outcome.Flushed ->
                if (outcome.transcript.isNotEmpty()) events.onDiagnostic(NAME, "flushed final [${outcome.transcript}]")
        }
    }

    companion object {
        const val NAME = "vosk"

        // ~8 s of capture frames — must exceed the model warm-up (~6 s). At PcmCaptureFormat.FRAME_MS this is
        // the same span the engine buffered before the seam split it out.
        private val PRE_READY_MAX_FRAMES = (8_000 / PcmCaptureFormat.FRAME_MS).toInt()

        /** Vosk-only factory for [WakeMicEngine]. [modelDir] null = bundled asset; a dir = downloaded model. */
        fun factory(modelDir: File? = null) = WakeDetector.Factory { context, words, events ->
            VoskWakeDetector(context, words, events, modelDir)
        }
    }
}
