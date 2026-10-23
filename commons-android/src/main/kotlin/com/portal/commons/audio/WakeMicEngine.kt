package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice

/**
 * Owns the **mic + capture thread + frame assembly** over the shared [PcmCaptureSession], and fans each
 * captured frame to every configured [WakeDetector]. Recognition policy lives behind the [WakeDetector] seam.
 *
 * Owns the **handoff cooldown**: after a wake fires, no detector is fed a frame until
 * [WakeMicConfig.wakeHandoffCooldownMs] has elapsed for every wake id that fired, and a repeat of the same wake
 * id inside its window is dropped before it reaches [WakeMicConfig.onWake]. This covers the ~1 frame between a
 * match and capture actually pausing for the handoff. (Re)starting capture clears every cooldown.
 *
 * Routes [WakeDetector.Events] to the consumer: [WakeMicConfig.onWake] on the capture thread, readiness on the
 * main thread via the poster, and a log line for each.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** — the consumer drives [pause]/[start] for handoff
 * and call stand-down. [pause] yields the slot; [start] reacquires it, running [WakeMicConfig.beforeMicAcquire]
 * first.
 *
 * Caller must hold RECORD_AUDIO. Consumer callback threading is documented on [WakeMicConfig].
 */
class WakeMicEngine internal constructor(
    context: Context,
    private val config: WakeMicConfig,
    private val newDevice: () -> PcmDevice,
    private val postToMain: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, config: WakeMicConfig) :
        this(context, config, ::AudioRecordPcmDevice, WakeCallbackThreads.mainThreadPoster())

    private val fireCooldown = FireCooldown(config.wakeHandoffCooldownMs)

    private val detectorEvents = object : WakeDetector.Events {
        override fun onReady(detectorId: String) {
            config.log("wake detector ready ($detectorId)")
            postToMain { config.onDetectorReady(detectorId) }
        }

        override fun onUnavailable(detectorId: String) {
            config.log("wake unavailable ($detectorId) — detector idle")
            postToMain { config.onDetectorUnavailable(detectorId) }
        }

        override fun onWake(event: WakeEvent) {
            if (!fireCooldown.tryFire(event.wakeId, clock())) return
            config.log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
            config.onWake(event)
        }

        override fun onDiagnostic(detectorId: String, message: String) = config.log("($detectorId) $message")
    }

    private val detectorHost = object : WakeDetector.Host {
        override val context: Context = context
        override val wakeWords: List<WakeWord> = config.wakeWords
        override val events: WakeDetector.Events = detectorEvents
    }

    private val detectors: List<WakeDetector> = config.detectors.map { it.create(detectorHost) }

    // Serializes every detector call. A capture thread that overran pause()'s stop join can still be inside a
    // detector when start() rebuilds the session, and a Vosk recognizer run from two threads aborts the process.
    private val detectorLock = Any()

    @Volatile private var pendingWakeWords: List<WakeWord>? = null

    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = newDevice(),
            onFrame = { buf, n -> onFrame(built, buf, n) },
            onStarted = { onStarted(built) },
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
        synchronized(detectorLock) { detectors.forEach { it.close() } }
    }

    // A discarded (wedged) session's thread may still call back once it unblocks; only the current one reaches
    // the detectors.
    private fun onStarted(from: PcmCaptureSession): Unit = synchronized(detectorLock) {
        if (from !== session) return
        fireCooldown.reset()
        detectors.forEach { it.start() }
    }

    private fun onFrame(from: PcmCaptureSession, buf: ByteArray, n: Int): Unit = synchronized(detectorLock) {
        if (from !== session) return
        applyPendingWakeWords()
        // The handoff cooldown: no detector hears these frames, including the rest of the frame a wake just fired on.
        for (detector in detectors) {
            if (fireCooldown.isAnyCoolingDown(clock())) return
            detector.accept(buf, n)
        }
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
