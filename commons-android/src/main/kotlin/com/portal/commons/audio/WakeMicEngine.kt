package com.portal.commons.audio

import android.content.Context
import com.portal.commons.CapturePolicy
import com.portal.commons.PcmCapture
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the mic through one [PcmCapture] and feeds each captured frame to its one **wake detector** (see
 * `GLOSSARY.md`), which loads the [WakeMicConfig.model] and turns frames into wakes.
 *
 * Owns the **handoff cooldown**: one window, opened when the wake detector returns a wake and lasting
 * [WakeMicConfig.wakeHandoffCooldownMs] on a monotonic clock. Inside it the wake detector is fed no frame, so no
 * wake of any wake id arises. This covers the ~1 frame between a match and capture actually pausing for the handoff.
 * (Re)starting capture closes the window.
 *
 * Delivers each wake to [WakeMicConfig.onWake] synchronously on the capture thread, and the wake detector's readiness
 * and unavailability to the consumer on the main thread. Each gets a log line, as does each diagnostic.
 *
 * Owns **capture loss** (see `GLOSSARY.md`), reported through [WakeMicConfig.onCaptureLost], at most once per
 * **capture request**, which [PcmCapture] tracks: a wedged older capture thread's frames reach no wake detector, and
 * its loss is never reported. Every [start] passes the same listener, so a [start] during an open capture request
 * joins it. A loss a [pause] or a newer [start] has overtaken reports nothing. After a loss
 * the engine is idle, as after [pause]; it never retries on its own, so retry policy stays with the consumer.
 *
 * **Posting rule.** Every main-thread callback ([WakeMicConfig.onCaptureLost], [WakeMicConfig.onReady],
 * [WakeMicConfig.onUnavailable]) is always posted to the main looper, never run inline, even when the event
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
 * The capture lifecycle (capture requests, serialized wake detector calls, the closed state, the bounded close) lives
 * in [PcmCapture], whose `onClosed` closes the wake detector; this class keeps the wake policy: the handoff cooldown,
 * the wake-word hot-swap and the routing of the wake detector's callbacks.
 *
 * **The wake detector's calls** follow the threading contract on [WakeDetector]. Its readiness and unavailability
 * reach the consumer on the main thread.
 */
class WakeMicEngine internal constructor(
    private val config: WakeMicConfig,
    newDevice: () -> PcmDevice,
    private val postToMain: (Runnable) -> Unit,
    // Where the Vosk fakes replace native libvosk in tests.
    newLoader: (WakeModel) -> VoskModelLoader,
    // Monotonic milliseconds, so a wall-clock jump can't hold the handoff cooldown open and leave the engine deaf.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    constructor(context: Context, config: WakeMicConfig) : this(
        config,
        ::AudioRecordPcmDevice,
        WakeCallbackThreads.mainThreadPoster(),
        newLoader = { LibVoskModelLoader(context, it) },
    )

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
            detector.start()
        }

        override fun onFrame(buf: ByteArray, n: Int) {
            applyPendingWakeWords()
            if (inHandoffCooldown()) return // the wake detector hears none of these frames
            detector.accept(buf, n)?.let(::deliver)
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
        onClosed = { detector.close() },
    )

    /** Posts [callback] to the main thread, never inline. It runs only if the engine is still open when delivered. */
    private fun deliverOnMain(callback: () -> Unit) = postToMain { if (!capture.isClosed) callback() }

    // Last among the fields its callbacks read: the wake detector may report unavailable while it is being created.
    private val detector: WakeDetector = WakeDetector(
        newLoader(config.model),
        config.wakeWords,
        onReady = {
            config.log("wake detector ready")
            deliverOnMain { config.onReady() }
        },
        onUnavailable = {
            config.log("wake detector unavailable")
            deliverOnMain { config.onUnavailable() }
        },
        diagnostic = config.log,
    )

    /**
     * Opens the handoff cooldown and delivers [event] to [WakeMicConfig.onWake]. Runs in onFrame, under PcmCapture's
     * callback lock. close() leaves the open state before it waits for that lock: a wake past the closed check is one
     * close() waits for (or gives up on, as already begun).
     */
    private fun deliver(event: WakeEvent) {
        handoffCooldownUntilMs = clock() + config.wakeHandoffCooldownMs
        config.log("wake detected → ${event.wakeId} [${event.transcript}]")
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

    /**
     * Hot-swap wake words without restarting capture: the wake detector rebuilds its grammar on the model it has,
     * before its next frame.
     */
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
     * Bounded waits: the capture stop and the wait for a wake detector call to finish (which covers a wake already
     * being delivered, since a wake is delivered inside one) share one deadline, [PcmCapture.STOP_DEADLINE_MS],
     * and the mic slot is free when it returns (unless a stuck capture thread's forced device release hangs too).
     * No wake delivery begins after it returns, even from a capture thread it gave up waiting for. A wake detector
     * still busy then, or busy in the calling thread (a close from inside the wake callback), is closed by the capture
     * thread as it leaves that call, never in the middle of it; a close that runs out of time logs a `🔴` line.
     * If that call never returns, the wake detector's native resources leak until the process dies.
     *
     * Not bounded: the wake detector's own close. When the wait succeeds it runs on the calling thread after
     * the deadline's waits, however long it takes. Freeing the Vosk model alone takes ~0.5 s on Portal+ gen1, so a
     * close from the main thread usually blocks it past the deadline.
     */
    fun close() = capture.close()

    private fun applyPendingWakeWords() {
        val words = pendingWakeWords.getAndSet(null) ?: return
        config.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        detector.updateWakeWords(words)
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
