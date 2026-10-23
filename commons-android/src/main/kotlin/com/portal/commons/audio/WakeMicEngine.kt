package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * Routes [WakeDetector.Events] to the consumer: [WakeMicConfig.onWake] synchronously on the capture thread,
 * readiness and unavailability on the main thread, and a log line for each.
 *
 * Owns **capture loss** (see `GLOSSARY.md`): from [start] until the next [pause] or [close] capture is wanted, and the
 * first way it fails to run is reported once as [WakeMicConfig.onCaptureLost] with a [CaptureLoss] cause:
 * [CaptureLoss.NotGranted] (the mic could not be opened, including a start refused because a previous capture is
 * wedged) or [CaptureLoss.Dropped] (capture was running and died, including a failed rebuild). A requested pause, a
 * stale or discarded capture session, and a loss a newer [start] has overtaken report nothing. After a loss the engine
 * is idle, as after [pause]; it never retries on its own, so retry policy stays with the consumer.
 *
 * **Posting rule.** Every main-thread callback ([WakeMicConfig.onCaptureLost], [WakeMicConfig.onDetectorReady],
 * [WakeMicConfig.onDetectorUnavailable]) is always posted to the main looper, never run inline, even when the event
 * happens on the main thread, so none runs inside a call the consumer is making to the engine (its constructor
 * included). The closed and capture-generation checks run when the callback is delivered, not when it is posted.
 *
 * **Closed state.** [close] is terminal and idempotent: after it no consumer callback is delivered (including ones
 * already posted), and [start] and [pause] are logged no-ops. It is bounded and safe from any thread, including from
 * inside the wake callback; see [close].
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
    // the detector lock in onStarted/onFrame, and in onWake, which detectors raise from inside accept.
    private var handoffCooldownUntilMs = Long.MIN_VALUE

    private fun inHandoffCooldown(nowMs: Long = clock()): Boolean = nowMs < handoffCooldownUntilMs

    // Terminal: set once by close(). Checked when a posted callback runs, not when it is posted, so a callback
    // already queued when close() runs never reaches the consumer.
    private val closed = AtomicBoolean(false)

    private fun deliverOnMain(callback: () -> Unit) = postToMain { if (!closed.get()) callback() }

    private val detectorEvents = object : WakeDetector.Events {
        override fun onReady(detectorId: String) {
            config.log("wake detector ready ($detectorId)")
            deliverOnMain { config.onDetectorReady(detectorId) }
        }

        override fun onUnavailable(detectorId: String) {
            config.log("wake unavailable ($detectorId) — detector idle")
            deliverOnMain { config.onDetectorUnavailable(detectorId) }
        }

        override fun onWake(event: WakeEvent) {
            val nowMs = clock()
            if (inHandoffCooldown(nowMs)) {
                config.log("wake dropped (handoff cooldown) → ${event.wakeId} [${event.transcript}]")
                return
            }
            handoffCooldownUntilMs = nowMs + config.wakeHandoffCooldownMs
            config.log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
            wakeGate.deliver(
                onWake = { config.onWake(event) },
                onDropped = { config.log("wake dropped (closed) → ${event.wakeId} [${event.transcript}]") },
            )
        }

        override fun onDiagnostic(detectorId: String, message: String) = config.log("($detectorId) $message")
    }

    private val wakeGate = WakeGate()

    private val detectorHost = object : WakeDetector.Host {
        override val context: Context = context
        override val wakeWords: List<WakeWord> = config.wakeWords
        override val events: WakeDetector.Events = detectorEvents
    }

    private val detectors = SerializedDetectors(config.detectors.map { it.create(detectorHost) })

    // Set from any thread, taken on the capture thread. Read and cleared in one step, so an update that lands between
    // the read and the clear is never lost.
    private val pendingWakeWords = AtomicReference<List<WakeWord>?>(null)

    private val losses = CaptureLossTracker(post = ::deliverOnMain) { loss ->
        config.log("capture lost (${loss.javaClass.simpleName}): ${loss.reason}")
        config.onCaptureLost(loss)
    }

    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = newDevice(),
            onFrame = { buf, n -> onFrame(built, buf, n) },
            onStarted = {
                if (session === built) losses.runStarted()
                onStarted(built)
            },
            onError = { if (session === built) losses.runError(it) },
            onStopped = { if (session === built) losses.runStopped() },
            log = config.log,
            threadName = "wake-capture",
            rebuildAfterReadFailures = MIC_READ_FAILURES_BEFORE_REBUILD,
            idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        )
        return built
    }

    /**
     * Wants capture and (re)acquires the mic, running [WakeMicConfig.beforeMicAcquire] first. Returns nothing: a mic
     * that can't be opened, or a start refused because a previous capture thread is wedged, arrives later as a posted
     * [CaptureLoss.NotGranted]. While capture is still wanted and no loss has been raised it keeps the current capture
     * (a loss that capture raises later is still reported). A logged no-op after [close].
     */
    fun start() {
        if (ignoredAfterClose("start()")) return
        config.beforeMicAcquire()
        if (!losses.want()) {
            config.log("start() while capture is already wanted — keeping the current capture")
            return
        }
        if (!session.start()) {
            config.log("wake capture wedged — rebuilding session and retrying")
            session = buildSession()
            if (!session.start()) losses.refused("previous capture thread still alive")
        }
    }

    /** Hot-swap wake words without restarting capture. See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords.set(words)
    }

    /**
     * Stops wanting capture and yields the mic slot. Reports no capture loss, and cancels one already posted but not
     * yet delivered. A harmless no-op after a loss; a logged no-op after [close].
     */
    fun pause() {
        if (ignoredAfterClose("pause()")) return
        losses.unwant()
        session.stop()
        config.log("mic paused (yielded slot)")
    }

    private fun ignoredAfterClose(call: String): Boolean =
        closed.get().also { if (it) config.log("$call after close() ignored") }

    /**
     * Terminal and idempotent: after it, [start] and [pause] are logged no-ops and no callback is delivered.
     *
     * Bounded: the session's stop join, the wait for a detector call to finish and the wait for a wake already
     * being delivered share one [CLOSE_DEADLINE_MS], and the mic slot is free when it returns. No wake delivery
     * begins after it returns, even from a capture thread it gave up waiting for. Detectors still busy then, or busy
     * in the calling thread (a close from inside the wake callback), are closed by the capture thread as it leaves
     * them, never in the middle of a call; a close that runs out of time logs a `🔴` line. If that call never
     * returns, the detectors' native resources leak until the process dies.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DEADLINE_MS)
        val leftMs = { TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()) }
        session.stop(timeoutMs = CLOSE_DEADLINE_MS)
        detectors.close(timeoutMs = leftMs(), log = config.log)
        wakeGate.close(timeoutMs = leftMs())
    }

    // A discarded (wedged) session's thread may still call back once it unblocks; only the current one reaches
    // the detectors.
    private fun onStarted(from: PcmCaptureSession) = detectors.use { all ->
        if (from !== session) return@use
        handoffCooldownUntilMs = Long.MIN_VALUE
        all.forEach { it.start() }
    }

    private fun onFrame(from: PcmCaptureSession, buf: ByteArray, n: Int) = detectors.use { all ->
        if (from !== session) return@use
        applyPendingWakeWords(all)
        // The handoff cooldown: no detector hears these frames, including the rest of the frame a wake just fired on.
        for (detector in all) {
            if (inHandoffCooldown()) return@use
            detector.accept(buf, n)
        }
    }

    private fun applyPendingWakeWords(all: List<WakeDetector>) {
        val words = pendingWakeWords.getAndSet(null) ?: return
        config.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        all.forEach { it.updateWakeWords(words) }
    }

    companion object {
        const val MIC_READ_FAILURES_BEFORE_REBUILD = 40

        /** The whole of [close]'s wait, matching the capture session's stop join. */
        const val CLOSE_DEADLINE_MS = PcmCaptureSession.DEFAULT_JOIN_TIMEOUT_MS
    }
}
