package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit
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
 * **capture request**, which [CaptureRequests] tracks. Each capture request captures on a fresh single-use
 * session and device, whose callbacks are bound to that request: a wedged older session's frames reach no detector,
 * and its loss is never reported. A loss a [pause] or a newer [start] has overtaken reports nothing. After a loss
 * the engine is idle, as after [pause]; it never retries on its own, so retry policy stays with the consumer.
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
 *
 * The capture lifecycle (sessions, detector access, the closed state, the bounded close) lives in [CaptureRequests];
 * this class keeps the wake policy: the handoff cooldown, the wake-word hot-swap and the routing of detector events.
 */
class WakeMicEngine internal constructor(
    context: Context,
    private val config: WakeMicConfig,
    newDevice: () -> PcmDevice,
    postToMain: (Runnable) -> Unit,
    // Monotonic milliseconds, so a wall-clock jump can't hold the handoff cooldown open and leave the engine deaf.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    constructor(context: Context, config: WakeMicConfig) :
        this(context, config, ::AudioRecordPcmDevice, WakeCallbackThreads.mainThreadPoster())

    // The handoff cooldown is open while clock() is before this instant. Touched on the capture thread only: under
    // the detector lock in onStarted/onFrame, and in onWake, which detectors raise from inside accept.
    private var handoffCooldownUntilMs = Long.MIN_VALUE

    private fun inHandoffCooldown(nowMs: Long = clock()): Boolean = nowMs < handoffCooldownUntilMs

    // Set from any thread, taken on the capture thread. Read and cleared in one step, so an update that lands between
    // the read and the clear is never lost.
    private val pendingWakeWords = AtomicReference<List<WakeWord>?>(null)

    // The wake policy run on the capture thread (and the start() hook, on the caller's). CaptureRequests runs it only
    // for the current capture request, only while open, and never alongside another detector call.
    private val capture = object : CaptureRequests.Capture {
        override fun beforeMicAcquire() = config.beforeMicAcquire()

        override fun onStarted(detectors: List<WakeDetector>) {
            handoffCooldownUntilMs = Long.MIN_VALUE
            detectors.forEach { it.start() }
        }

        override fun onFrame(detectors: List<WakeDetector>, buf: ByteArray, n: Int) {
            applyPendingWakeWords(detectors)
            // The handoff cooldown: no detector hears these frames, including the rest of the frame a wake just
            // fired on.
            for (detector in detectors) {
                if (inHandoffCooldown()) return
                detector.accept(buf, n)
            }
        }

        override fun onLost(loss: CaptureLoss) {
            config.log("capture lost (${loss.javaClass.simpleName}): ${loss.reason}")
            config.onCaptureLost(loss)
        }
    }

    // Last among the fields its detectors' events read: a detector may raise one while it is being created.
    private val requests = CaptureRequests(
        newDevice = newDevice,
        postToMain = postToMain,
        log = config.log,
        rebuildAfterReadFailures = MIC_READ_FAILURES_BEFORE_REBUILD,
        capture = capture,
    ) { delivery ->
        val host = object : WakeDetector.Host {
            override val context: Context = context
            override val wakeWords: List<WakeWord> = config.wakeWords
            override val events: WakeDetector.Events = DetectorEvents(delivery)
        }
        config.detectors.map { it.create(host) }
    }

    /** Routes [WakeDetector.Events] to the consumer, delivering through [delivery], the engine's [requests]. */
    private inner class DetectorEvents(private val delivery: CaptureRequests) : WakeDetector.Events {
        override fun onReady(detectorId: String) {
            config.log("wake detector ready ($detectorId)")
            delivery.deliverOnMain { config.onDetectorReady(detectorId) }
        }

        override fun onUnavailable(detectorId: String) {
            config.log("wake unavailable ($detectorId) — detector idle")
            delivery.deliverOnMain { config.onDetectorUnavailable(detectorId) }
        }

        override fun onWake(event: WakeEvent) {
            val nowMs = clock()
            if (inHandoffCooldown(nowMs)) {
                config.log("wake dropped (handoff cooldown) → ${event.wakeId} [${event.transcript}]")
                return
            }
            handoffCooldownUntilMs = nowMs + config.wakeHandoffCooldownMs
            config.log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
            if (!delivery.deliverWake { config.onWake(event) }) {
                config.log("wake dropped (closed) → ${event.wakeId} [${event.transcript}]")
            }
        }

        override fun onDiagnostic(detectorId: String, message: String) = config.log("($detectorId) $message")
    }

    /**
     * Begins a capture request and acquires the mic on a fresh session and device, running
     * [WakeMicConfig.beforeMicAcquire] first. Returns nothing: a mic that can't be opened arrives later as a posted
     * [CaptureLoss]. A wedged previous capture (a thread that outlived [pause]'s stop join) doesn't block it, and
     * reports no loss. During an open capture request that has raised no loss it keeps the current capture (a loss
     * that capture raises later is still reported). A logged no-op after [close].
     */
    fun start() = requests.begin()

    /** Hot-swap wake words without restarting capture. See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords.set(words)
    }

    /**
     * Ends the capture request and yields the mic slot. Reports no capture loss, and cancels one already posted but
     * not yet delivered. A harmless no-op after a loss; a logged no-op after [close].
     */
    fun pause() = requests.end()

    /**
     * Terminal and idempotent: after it, [start] and [pause] are logged no-ops and no callback is delivered.
     *
     * Bounded waits: the session's stop join and the wait for a detector call to finish (which covers a wake already
     * being delivered, since a wake is delivered inside one) share one deadline, [PcmCaptureSession.STOP_DEADLINE_MS],
     * and the mic slot is free when it returns (unless a stuck capture thread's forced device release hangs too: see
     * [PcmCaptureSession.stop]). No wake delivery begins after it returns, even from a capture thread it gave up
     * waiting for. Detectors still busy then, or busy in the calling thread (a close from inside the wake callback),
     * are closed by the capture thread as it leaves them, never in the middle of a call; a close that runs out of time
     * logs a `🔴` line. If that call never returns, the detectors' native resources leak until the process dies.
     *
     * Not bounded: the detectors' own [WakeDetector.close]. When the wait succeeds it runs on the calling thread after
     * the deadline's waits, however long it takes. Freeing the Vosk model alone takes ~0.5 s on Portal+ gen1, so a
     * close from the main thread usually blocks it past the deadline.
     */
    fun close() = requests.close()

    private fun applyPendingWakeWords(all: List<WakeDetector>) {
        val words = pendingWakeWords.getAndSet(null) ?: return
        config.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        all.forEach { it.updateWakeWords(words) }
    }

    companion object {
        const val MIC_READ_FAILURES_BEFORE_REBUILD = 40
    }
}
