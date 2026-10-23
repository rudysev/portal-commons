package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit

/**
 * Owns the **mic + capture thread + frame assembly** over the shared [PcmCaptureSession], and fans each
 * captured frame to every configured [WakeDetector]. Recognition policy lives behind the [WakeDetector] seam.
 *
 * Owns the **handoff cooldown**: one window, opened when any wake is delivered and lasting
 * [WakeMicConfig.wakeHandoffCooldownMs] on a monotonic clock. Inside it no detector is fed a frame and every
 * further wake, whatever its wake id, is dropped (and logged) before it reaches [WakeMicConfig.onWake]. This
 * covers the ~1 frame between a match and capture actually pausing for the handoff. (Re)starting capture closes
 * the window.
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
    // Monotonic milliseconds, so a wall-clock jump can't hold the handoff cooldown open and leave the engine deaf.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    constructor(context: Context, config: WakeMicConfig) :
        this(context, config, ::AudioRecordPcmDevice, WakeCallbackThreads.mainThreadPoster())

    // The handoff cooldown is open while clock() is before this instant. Touched on the capture thread only: under
    // detectorLock in onStarted/onFrame, and in onWake, which detectors raise from inside accept.
    private var handoffCooldownUntilMs = Long.MIN_VALUE

    private fun inHandoffCooldown(nowMs: Long = clock()): Boolean = nowMs < handoffCooldownUntilMs

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
            val nowMs = clock()
            if (inHandoffCooldown(nowMs)) {
                config.log("wake dropped (handoff cooldown) → ${event.wakeId} [${event.transcript}]")
                return
            }
            handoffCooldownUntilMs = nowMs + config.wakeHandoffCooldownMs
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
        handoffCooldownUntilMs = Long.MIN_VALUE
        detectors.forEach { it.start() }
    }

    private fun onFrame(from: PcmCaptureSession, buf: ByteArray, n: Int): Unit = synchronized(detectorLock) {
        if (from !== session) return
        applyPendingWakeWords()
        // The handoff cooldown: no detector hears these frames, including the rest of the frame a wake just fired on.
        for (detector in detectors) {
            if (inHandoffCooldown()) return
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
