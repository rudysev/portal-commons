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
 * Owns **capture loss** (see `GLOSSARY.md`), reported through [WakeMicConfig.onCaptureLost], at most once per
 * **capture request**. Each capture request captures on a fresh session and device. A loss a [pause] or a newer
 * [start] has overtaken reports nothing. After a loss the engine is idle, as after [pause]; it never retries on its
 * own, so retry policy stays with the consumer.
 *
 * **Posting rule.** Every main-thread callback ([WakeMicConfig.onCaptureLost], [WakeMicConfig.onDetectorReady],
 * [WakeMicConfig.onDetectorUnavailable]) is always posted to the main looper, never run inline, even when the event
 * happens on the main thread, so none runs inside a call the consumer is making to the engine (its constructor
 * included). The closed and capture request checks run when the callback is delivered, not when it is posted.
 *
 * **Closed state.** [close] is terminal and idempotent: after it no consumer callback is delivered (including ones
 * already posted), and [start] and [pause] are logged no-ops. Its waits are bounded, and it is safe from any thread,
 * including from inside the wake callback; see [close].
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

    private val detectors = SerializedDetectors(config.detectors.map { it.create(detectorHost) }, config.log)

    // Set from any thread, taken on the capture thread. Read and cleared in one step, so an update that lands between
    // the read and the clear is never lost.
    private val pendingWakeWords = AtomicReference<List<WakeWord>?>(null)

    private val requests = CaptureRequests(post = ::deliverOnMain) { loss ->
        config.log("capture lost (${loss.javaClass.simpleName}): ${loss.reason}")
        config.onCaptureLost(loss)
    }

    // The session of the newest capture request; null until the first start().
    @Volatile private var session: PcmCaptureSession? = null

    private fun newSession(request: CaptureRequests.CaptureRequest) = PcmCaptureSession(
        device = newDevice(),
        onFrame = { buf, n -> onFrame(request, buf, n) },
        onStarted = { onStarted(request) },
        onLost = request::lost,
        log = config.log,
        threadName = "wake-capture",
        rebuildAfterReadFailures = MIC_READ_FAILURES_BEFORE_REBUILD,
        idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        silenceWindowMs = PcmCaptureSession.DEFAULT_SILENCE_WINDOW_MS,
    )

    /**
     * Begins a capture request and acquires the mic on a fresh session and device, running
     * [WakeMicConfig.beforeMicAcquire] first. Returns nothing: a mic that can't be opened arrives later as a posted
     * [CaptureLoss]. A wedged previous capture (a thread that outlived [pause]'s stop join) doesn't block it, and
     * reports no loss. During an open capture request that has raised no loss it keeps the current capture (a loss
     * that capture raises later is still reported). A logged no-op after [close].
     */
    fun start() {
        if (ignoredAfterClose("start()")) return
        config.beforeMicAcquire()
        val request = requests.begin()
        if (request == null) {
            config.log("start() during an open capture request — keeping the current capture")
            return
        }
        session = newSession(request).also { it.start() }
    }

    /** Hot-swap wake words without restarting capture. See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords.set(words)
    }

    /**
     * Ends the capture request and yields the mic slot. Reports no capture loss, and cancels one already posted but
     * not yet delivered. A harmless no-op after a loss; a logged no-op after [close].
     */
    fun pause() {
        if (ignoredAfterClose("pause()")) return
        requests.end()
        session?.stop()
        config.log("mic paused (yielded slot)")
    }

    private fun ignoredAfterClose(call: String): Boolean =
        closed.get().also { if (it) config.log("$call after close() ignored") }

    /**
     * Terminal and idempotent: after it, [start] and [pause] are logged no-ops and no callback is delivered.
     *
     * Bounded waits: the session's stop join, the wait for a detector call to finish and the wait for a wake already
     * being delivered share one deadline, [PcmCaptureSession.STOP_DEADLINE_MS], and the mic slot is free when it
     * returns (unless a stuck capture thread's forced device release hangs too: see [PcmCaptureSession.stop]). No
     * wake delivery begins after it returns, even from a capture thread it gave up waiting for. Detectors still busy
     * then, or busy in the calling thread (a close from inside the wake callback), are closed by the capture thread as
     * it leaves them, never in the middle of a call; a close that runs out of time logs a `🔴` line. If that call
     * never returns, the detectors' native resources leak until the process dies.
     *
     * Not bounded: the detectors' own [WakeDetector.close]. When the wait succeeds it runs on the calling thread after
     * the deadline's waits, however long it takes. Freeing the Vosk model alone takes ~0.5 s on Portal+ gen1, so a
     * close from the main thread usually blocks it past the deadline.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PcmCaptureSession.STOP_DEADLINE_MS)
        val leftMs = { TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()) }
        session?.stop(timeoutMs = PcmCaptureSession.STOP_DEADLINE_MS)
        detectors.close(timeoutMs = leftMs())
        wakeGate.close(timeoutMs = leftMs())
    }

    // A wedged session's thread may still call back once it unblocks; only the current capture request's session
    // reaches the detectors.
    private fun onStarted(request: CaptureRequests.CaptureRequest) = detectors.use { all ->
        if (!request.isCurrent) return@use
        handoffCooldownUntilMs = Long.MIN_VALUE
        all.forEach { it.start() }
    }

    private fun onFrame(request: CaptureRequests.CaptureRequest, buf: ByteArray, n: Int) = detectors.use { all ->
        if (!request.isCurrent) return@use
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
    }
}
