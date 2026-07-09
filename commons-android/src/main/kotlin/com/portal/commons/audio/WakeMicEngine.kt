package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureSession

/**
 * Owns the **mic + capture thread + frame assembly** over the shared [PcmCaptureSession], and fans each
 * captured frame to every configured [WakeDetector]. Recognition policy lives behind the [WakeDetector] seam.
 *
 * Post-fire de-dupe is per wake id for [WakeMicConfig.wakeHandoffCooldownMs] so the ~1 handoff frame before
 * capture actually pauses can't double-fire. Detectors query [WakeHandoffCooldown] via [WakeDetector.Host] to
 * skip inference during that window.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** — the consumer drives [pause]/[start] for handoff
 * and call stand-down. [pause] yields the slot; [start] reacquires it, running [WakeMicConfig.beforeMicAcquire]
 * first.
 *
 * Caller must hold RECORD_AUDIO. Consumer callback threading is documented on [WakeMicConfig].
 */
class WakeMicEngine(
    context: Context,
    private val config: WakeMicConfig,
) {
    private val postToMain = WakeCallbackThreads.mainThreadPoster()

    private val eventHandler = WakeMicEventHandler(
        handoffCooldownMs = config.wakeHandoffCooldownMs,
        wakeConsumer = config.onWake,
        onDetectorReady = config.onDetectorReady,
        onDetectorUnavailable = config.onDetectorUnavailable,
        log = config.log,
        postToMain = postToMain,
    )

    private val detectorHost = object : WakeDetector.Host {
        override val context: Context = context
        override val wakeWords: List<WakeWord> = config.wakeWords
        override val events: WakeDetector.Events = eventHandler
        override val handoffCooldown: WakeHandoffCooldown = eventHandler
    }

    private val detectors: List<WakeDetector> = config.detectors.map { it.create(detectorHost) }

    @Volatile private var pendingWakeWords: List<WakeWord>? = null

    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = AudioRecordPcmDevice(),
            onFrame = ::onFrame,
            onStarted = ::onStarted,
            onStopped = { if (session === built) postToMain { config.onStopped() } },
            onError = { if (session === built) postToMain { config.onError(it) } },
            log = config.log,
            threadName = "wake-capture",
            rebuildAfterReadFailures = MIC_READ_FAILURES_BEFORE_REBUILD,
            idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        )
        return built
    }

    fun start(): Boolean {
        config.beforeMicAcquire()
        if (session.start()) return true
        config.log("wake capture wedged — rebuilding session and retrying")
        session = buildSession()
        return session.start()
    }

    /** Hot-swap wake words without restarting capture. See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords = words
    }

    fun pause() {
        session.stop()
        config.log("mic paused (yielded slot)")
    }

    fun close() {
        session.stop()
        detectors.forEach { it.close() }
    }

    private fun onStarted() {
        eventHandler.reset()
        detectors.forEach { it.start() }
    }

    private fun onFrame(buf: ByteArray, n: Int) {
        applyPendingWakeWords()
        detectors.forEach { it.accept(buf, n) }
    }

    private fun applyPendingWakeWords() {
        val words = pendingWakeWords ?: return
        pendingWakeWords = null
        config.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        detectors.forEach { it.updateWakeWords(words) }
    }

    companion object {
        const val MIC_READ_FAILURES_BEFORE_REBUILD = 40
    }
}
