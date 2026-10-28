package com.portal.commons.audio

import android.content.Context
import com.portal.commons.CapturePolicy
import com.portal.commons.PcmCapture
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the mic through one [PcmCapture] and fans each captured frame to every configured [WakeDetector].
 * Recognition policy lives behind the [WakeDetector] seam.
 *
 * Owns the **handoff cooldown**: one window, opened when any detector returns a wake from [WakeDetector.accept] and
 * lasting [WakeMicConfig.wakeHandoffCooldownMs] on a monotonic clock. Inside it no detector is fed a frame, so no
 * wake of any wake id arises; nor is the rest of the frame that woke fed to the other detectors. This
 * covers the ~1 frame between a match and capture actually pausing for the handoff. (Re)starting capture closes the
 * window.
 *
 * Delivers each wake a detector returns to [WakeMicConfig.onWake] synchronously on the capture thread, and routes
 * [WakeDetector.Events] to the consumer: readiness and unavailability on the main thread. Each gets a log line.
 *
 * Owns **capture loss** (see `GLOSSARY.md`), reported through [WakeMicConfig.onCaptureLost], at most once per
 * **capture request**, which [PcmCapture] tracks: a wedged older capture thread's frames reach no detector, and
 * its loss is never reported. Every [start] passes the same listener, so a [start] during an open capture request
 * joins it. A loss a [pause] or a newer [start] has overtaken reports nothing. After a loss
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
 * The capture lifecycle (capture requests, serialized detector access, the closed state, the bounded close) lives in
 * [PcmCapture], whose `onClosed` closes the detectors; this class keeps the wake policy: the handoff cooldown, the
 * wake-word hot-swap and the routing of detector events.
 */
class WakeMicEngine internal constructor(
    context: Context,
    private val config: WakeMicConfig,
    newDevice: () -> PcmDevice,
    private val postToMain: (Runnable) -> Unit,
    // Monotonic milliseconds, so a wall-clock jump can't hold the handoff cooldown open and leave the engine deaf.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    constructor(context: Context, config: WakeMicConfig) :
        this(context, config, ::AudioRecordPcmDevice, WakeCallbackThreads.mainThreadPoster())

    // The handoff cooldown is open while clock() is before this instant. Touched on the capture thread only, under
    // PcmCapture's callback lock in onStarted/onFrame.
    private var handoffCooldownUntilMs = Long.MIN_VALUE

    private fun inHandoffCooldown(): Boolean = clock() < handoffCooldownUntilMs

    // Set from any thread, taken on the capture thread. Read and cleared in one step, so an update that lands between
    // the read and the clear is never lost.
    private val pendingWakeWords = AtomicReference<List<WakeWord>?>(null)

    // The wake policy, passed on every start() so a start() during an open request joins it. PcmCapture runs its
    // capture-thread callbacks only for the current capture request, only while open, and never two at once.
    private val listener = object : PcmCapture.Listener {
        override fun onStarted() {
            handoffCooldownUntilMs = Long.MIN_VALUE
            detectors.forEach { it.start() }
        }

        override fun onFrame(buf: ByteArray, n: Int) {
            applyPendingWakeWords(detectors)
            if (inHandoffCooldown()) return // no detector hears these frames
            for (detector in detectors) {
                val wake = detector.accept(buf, n) ?: continue
                deliver(wake) // opens the cooldown: no other detector hears the rest of this frame
                return
            }
        }

        override fun onLost(loss: CaptureLoss) {
            config.log("capture lost (${loss.javaClass.simpleName}): ${loss.reason}")
            config.onCaptureLost(loss)
        }
    }

    private val capture = PcmCapture(
        newDevice = newDevice,
        postToMain = postToMain,
        log = config.log,
        threadName = "wake-capture",
        policy = CapturePolicy(
            rebuildAfterReadFailures = MIC_READ_FAILURES_BEFORE_REBUILD,
            idleRebuildMs = CapturePolicy.DEFAULT_IDLE_REBUILD_MS,
            silence = CapturePolicy.Silence(SILENCE_WINDOW_MS),
        ),
        onClosed = { detectors.forEach { it.close() } },
    )

    // Last among the fields its detectors' events read: a detector may raise one while it is being created.
    private val detectors: List<WakeDetector> = run {
        val host = object : WakeDetector.Host {
            override val context: Context = context
            override val wakeWords: List<WakeWord> = config.wakeWords
            override val events: WakeDetector.Events = DetectorEvents()
        }
        config.detectors.map { it.create(host) }
    }

    /** Posts [callback] to the main thread, never inline. It runs only if the engine is still open when delivered. */
    private fun deliverOnMain(callback: () -> Unit) = postToMain { if (!capture.isClosed) callback() }

    /** Routes [WakeDetector.Events] to the consumer. */
    private inner class DetectorEvents : WakeDetector.Events {
        override fun onReady(detectorId: String) {
            config.log("wake detector ready ($detectorId)")
            deliverOnMain { config.onDetectorReady(detectorId) }
        }

        override fun onUnavailable(detectorId: String) {
            config.log("wake unavailable ($detectorId) — detector idle")
            deliverOnMain { config.onDetectorUnavailable(detectorId) }
        }

        override fun onDiagnostic(detectorId: String, message: String) = config.log("($detectorId) $message")
    }

    /**
     * Opens the handoff cooldown and delivers [event] to [WakeMicConfig.onWake]. Runs in onFrame, under PcmCapture's
     * callback lock. close() leaves the open state before it waits for that lock: a wake past the closed check is one
     * close() waits for (or gives up on, as already begun).
     */
    private fun deliver(event: WakeEvent) {
        handoffCooldownUntilMs = clock() + config.wakeHandoffCooldownMs
        config.log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
        if (capture.isClosed) {
            config.log("wake dropped (closed) → ${event.wakeId} [${event.transcript}]")
            return
        }
        config.onWake(event)
    }

    /**
     * Begins a capture request and acquires the mic on a fresh session and device, running
     * [WakeMicConfig.beforeMicAcquire] first. Returns nothing: a mic that can't be opened arrives later as a posted
     * [CaptureLoss]. A wedged previous capture (a thread that outlived [pause]'s stop join) doesn't block it, and
     * reports no loss. During an open capture request that has raised no loss it keeps the current capture (a loss
     * that capture raises later is still reported). A logged no-op after [close].
     */
    fun start() {
        // Lock-free, only to skip the hook once closed; PcmCapture's own closed check is the one that counts.
        if (!capture.isClosed) config.beforeMicAcquire()
        capture.start(listener)
    }

    /** Hot-swap wake words without restarting capture. See [WakeDetector.updateWakeWords]. */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords.set(words)
    }

    /**
     * Ends the capture request and yields the mic slot. Reports no capture loss, and cancels one already posted but
     * not yet delivered. A harmless no-op after a loss; a logged no-op after [close].
     */
    fun pause() = capture.pause()

    /**
     * Terminal and idempotent: after it, [start] and [pause] are logged no-ops and no callback is delivered.
     *
     * Bounded waits: the capture stop and the wait for a detector call to finish (which covers a wake already
     * being delivered, since a wake is delivered inside one) share one deadline, [PcmCapture.STOP_DEADLINE_MS],
     * and the mic slot is free when it returns (unless a stuck capture thread's forced device release hangs too).
     * No wake delivery begins after it returns, even from a capture thread it gave up waiting for. Detectors still
     * busy then, or busy in the calling thread (a close from inside the wake callback), are closed by the capture
     * thread as it leaves them, never in the middle of a call; a close that runs out of time logs a `🔴` line.
     * If that call never returns, the detectors' native resources leak until the process dies.
     *
     * Not bounded: the detectors' own [WakeDetector.close]. When the wait succeeds it runs on the calling thread after
     * the deadline's waits, however long it takes. Freeing the Vosk model alone takes ~0.5 s on Portal+ gen1, so a
     * close from the main thread usually blocks it past the deadline.
     */
    fun close() = capture.close()

    private fun applyPendingWakeWords(all: List<WakeDetector>) {
        val words = pendingWakeWords.getAndSet(null) ?: return
        config.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        all.forEach { it.updateWakeWords(words) }
    }

    companion object {
        const val MIC_READ_FAILURES_BEFORE_REBUILD = 40

        /**
         * The engine's silence window: this much audio of exact-zero samples ends capture as silenced, with no rebuild
         * (a wake consumer retries on its own). Equal to the idle rebuild window, so no frames and only zeros both end
         * capture within the same time. A consumer that paces retries reads it to know how long a silenced retry runs
         * before it can fail.
         */
        const val SILENCE_WINDOW_MS = CapturePolicy.DEFAULT_IDLE_REBUILD_MS
    }
}
