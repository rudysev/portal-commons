package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureFormat
import java.io.File

/**
 * The **Vosk** [WakeDetector]: owns the [WakeRecognizer] (the Vosk boundary), the pre-ready frame buffer,
 * and the idle-reset that bounds Vosk's decode lattice. [WakeMicEngine] keeps only the mic + framing.
 *
 * **Per-frame policy** ([accept], capture thread): while the model is still loading, ring-buffer frames (so
 * speech during init isn't lost); on the ready transition, flush them; then feed the recognizer, bounding the
 * native lattice via [IdleResetPolicy]. **On (re)start** ([start]) the recognizer is reset — which re-warms
 * it — so the first frame after a resume isn't garbled.
 *
 * Post-fire handoff cooldown is enforced by [WakeMicEngine] via [WakeHandoffCooldown]. While any phrase is
 * cooling down, [accept] skips inference and idle-reset. A match reports via [WakeDetector.Events.onWake];
 * diagnostics carry the exact `near-miss …` / `flushed final …` strings the mic owner used to log.
 */
class VoskWakeDetector private constructor(
    context: Context,
    initialWakeWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    private val handoffCooldown: WakeHandoffCooldown,
    modelDir: File?,
) : WakeDetector {

    override val id: String = ID

    @Volatile private var recognizerReady = false
    private var wasRecognizerReady = false
    private val idleReset = IdleResetPolicy()
    private val preReadyBuffer = PcmRingBuffer(PRE_READY_MAX_FRAMES)

    private val recognizer = WakeRecognizer(
        context,
        initialWakeWords,
        onReady = {
            recognizerReady = true
            events.onReady(ID)
        },
        onUnavailable = { events.onUnavailable(ID) },
        modelDir = modelDir,
    )

    override fun start() {
        wasRecognizerReady = false
        recognizer.reset()
        idleReset.noteFlushed(System.currentTimeMillis())
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (!recognizerReady) {
            preReadyBuffer.add(buf, n)
            return
        }
        if (!wasRecognizerReady) {
            wasRecognizerReady = true
            flushPreReadyBuffer()
        }
        if (handoffCooldown.isAnyCoolingDown()) return
        maybeIdleReset()
        tryAcceptFrame(buf, n)
    }

    override fun updateWakeWords(words: List<WakeWord>) {
        recognizer.rebuildGrammar(words)
        idleReset.noteFlushed(System.currentTimeMillis())
    }

    override fun close() {
        recognizer.close()
    }

    private fun maybeIdleReset() {
        val now = System.currentTimeMillis()
        val decision = idleReset.decide(now, recognizer::isMidUtterance)
        if (decision == IdleResetPolicy.Decision.KEEP) return
        recognizer.reset()
        idleReset.noteFlushed(now)
        when (decision) {
            IdleResetPolicy.Decision.RESET ->
                events.onDiagnostic(ID, "idle reset (bounding Vosk lattice)")

            IdleResetPolicy.Decision.FORCE_RESET ->
                events.onDiagnostic(ID, "idle reset forced mid-decode (bounding Vosk lattice)")

            IdleResetPolicy.Decision.KEEP -> {}
        }
    }

    private fun flushPreReadyBuffer() {
        val buffered = preReadyBuffer.drain()
        if (buffered.isEmpty()) return
        events.onDiagnostic(ID, "flushing ${buffered.size} pre-ready frame(s)")
        flushBufferedFrames(buffered, handoffCooldown::isAnyCoolingDown, ::tryAcceptFrame)
    }

    private fun tryAcceptFrame(buf: ByteArray, n: Int) {
        val outcome = recognizer.accept(buf, n) ?: return
        idleReset.noteFlushed(System.currentTimeMillis())
        when (outcome) {
            is WakeRecognizer.Outcome.Match ->
                events.onWake(WakeEvent(ID, outcome.id, outcome.transcript))

            is WakeRecognizer.Outcome.NearMiss ->
                events.onDiagnostic(ID, "near-miss [${outcome.transcript}] rejected: ${outcome.reason}")

            is WakeRecognizer.Outcome.Flushed ->
                if (outcome.transcript.isNotEmpty()) {
                    events.onDiagnostic(ID, "flushed final [${outcome.transcript}]")
                }
        }
    }

    companion object {
        const val ID = "vosk"

        private val PRE_READY_MAX_FRAMES = (8_000 / PcmCaptureFormat.FRAME_MS).toInt()

        internal fun flushBufferedFrames(
            frames: List<ByteArray>,
            isAnyCoolingDown: () -> Boolean,
            acceptFrame: (ByteArray, Int) -> Unit,
        ) {
            for (frame in frames) {
                if (isAnyCoolingDown()) break
                acceptFrame(frame, frame.size)
            }
        }

        fun factory(modelDir: File? = null): WakeDetector.Factory = WakeDetector.Factory { host ->
            VoskWakeDetector(host.context, host.wakeWords, host.events, host.handoffCooldown, modelDir)
        }
    }
}
