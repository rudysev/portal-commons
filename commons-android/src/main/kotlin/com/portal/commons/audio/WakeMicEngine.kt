package com.portal.commons.audio

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession

/**
 * Owns the **mic + capture thread + frame assembly** over the shared [PcmCaptureSession], and fans each
 * captured frame to one or more [WakeDetector]s. It is deliberately **detector-agnostic**: what counts as a
 * wake lives behind the [WakeDetector] seam.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** — the consumer drives [pause]/[start] for handoff
 * and call stand-down. [pause] yields the slot; [start] reacquires it, running [beforeStart] first.
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
    @Volatile private var pendingWakeWords: List<WakeWord>? = null
    @Volatile private var pendingPhraseModels: List<OpenWakeWordDetector.PhraseClassifierConfig>? = null
    private val cooldown = FireCooldown(COOLDOWN_MS)

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

    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = AudioRecordPcmDevice(),
            onFrame = ::onFrame,
            onStarted = ::onStarted,
            onStopped = { if (session === built) onStopped() },
            onError = { if (session === built) onError(it) },
            log = { DebugLog.log(it) },
            threadName = "wake-capture",
            rebuildAfterReadFailures = REBUILD_AFTER_READ_FAILURES,
            idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        )
        return built
    }

    fun start(): Boolean {
        beforeStart()
        if (session.start()) return true
        DebugLog.log("wake capture wedged — rebuilding session and retrying")
        session = buildSession()
        return session.start()
    }

    /** Hot-swap bundled wake words (portal-assistant). See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords = words
    }

    /** Hot-swap explicit phrase classifier configs (portal-wake plugin/bundled mix). */
    fun updatePhraseModels(configs: List<OpenWakeWordDetector.PhraseClassifierConfig>) {
        pendingPhraseModels = configs
    }

    fun pause() {
        session.stop()
        DebugLog.log("mic paused (yielded slot)")
    }

    fun shutdown() {
        session.stop()
        detectors.forEach { it.close() }
    }

    private fun onStarted() {
        cooldown.reset()
        detectors.forEach { it.start() }
    }

    private fun onFrame(buf: ByteArray, n: Int) {
        applyPendingUpdates()
        detectors.forEach { it.accept(buf, n) }
    }

    private fun applyPendingUpdates() {
        pendingWakeWords?.let { words ->
            pendingWakeWords = null
            DebugLog.log("wake set changed → updating bundled phrase models (${words.size} word(s))")
            detectors.forEach { it.updateWakeWords(words) }
        }
        pendingPhraseModels?.let { configs ->
            pendingPhraseModels = null
            DebugLog.log("wake set changed → updating explicit phrase models (${configs.size} classifier(s))")
            detectors.filterIsInstance<OpenWakeWordDetector>().forEach { it.updatePhraseModels(configs) }
        }
    }

    private fun handleWake(name: String, id: String, detail: String) {
        if (!cooldown.tryFire(id, System.currentTimeMillis())) return
        val tag = if (detectors.size > 1) " ($name)" else ""
        DebugLog.log("wake detected$tag → $id [$detail]")
        onWake(name, id, detail)
    }

    companion object {
        const val COOLDOWN_MS = 1_500L
        const val REBUILD_AFTER_READ_FAILURES = 40

        /**
         * openWakeWord engine — the neural KWS reading bundled ONNX assets (`assets/oww/…`, shipped via
         * `commons-android`), no runtime model download.
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
