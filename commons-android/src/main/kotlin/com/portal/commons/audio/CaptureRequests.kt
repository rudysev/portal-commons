package com.portal.commons.audio

import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Owns the [WakeMicEngine]'s capture lifecycle: its **capture requests** (see `GLOSSARY.md`) and the capture session
 * of each, serialized access to the detectors, delivery of wakes and of main-thread callbacks, the engine's one closed
 * state, and the ordering of [begin], [end] and [close] against one deadline. The engine keeps wake policy (the
 * handoff cooldown, the wake-word hot-swap, event routing) and hands it in as a [Capture], with the session policies.
 *
 * How it upholds the engine's guarantees:
 * - **Current request only.** [begin] builds a fresh single-use [PcmCaptureSession] whose callbacks are bound to a new
 *   capture request. A callback from an older session (a capture thread that outlived [end]'s stop join) reaches no
 *   detector, and its loss is never delivered.
 * - **One detector call at a time.** [Capture.onStarted] and [Capture.onFrame] run under one detector lock, so a
 *   wedged older capture thread and a fresh one are never inside a detector at once: a Vosk recognizer run from two
 *   threads aborts the process.
 * - **Closed state.** One [Lifecycle] value, set first by [close]. It is checked before every detector call, before a
 *   wake is delivered and when a main-thread callback is delivered (not when posted).
 * - **At most one capture loss per request.** A loss is raised once, while its request is open, then posted and
 *   claimed when delivered: an [end] or a newer [begin] in between drops it.
 * - **Bounded close.** [close] stops the session, then closes the detectors, against one deadline,
 *   [PcmCaptureSession.STOP_DEADLINE_MS]. A thread inside a detector call (one close gave up waiting for, or the
 *   calling thread itself, from inside the wake callback) closes the detectors as it leaves that call.
 *
 * [createDetectors] runs once, during construction, and receives this module so the detectors' events can deliver
 * through it.
 */
internal class CaptureRequests(
    private val newDevice: () -> PcmDevice,
    private val postToMain: (Runnable) -> Unit,
    private val log: (String) -> Unit,
    private val rebuildAfterReadFailures: Int,
    private val capture: Capture,
    createDetectors: (CaptureRequests) -> List<WakeDetector>,
) {
    /** The engine's side of a capture request. */
    interface Capture {
        /** Caller thread of [begin], while open: before it begins a request or joins the open one. */
        fun beforeMicAcquire()

        /** Capture thread, under the detector lock: the current request's capture has started. */
        fun onStarted(detectors: List<WakeDetector>)

        /** Capture thread, under the detector lock: one frame of the current request's capture. */
        fun onFrame(detectors: List<WakeDetector>, buf: ByteArray, n: Int)

        /** Main thread: the current request's capture was lost, and nothing has overtaken it. */
        fun onLost(loss: CaptureLoss)
    }

    /** The engine's one closed state. Each step only moves forward. */
    private enum class Lifecycle {
        OPEN,

        // close() has begun and is stopping the session. Already closed to every caller and callback.
        STOPPING_CAPTURE,

        // close() has reached the detectors: whichever thread holds the detector lock closes them as it leaves. Not
        // armed while the session stops, so a capture thread leaving a frame then doesn't take the detectors' own
        // (slow) close onto itself and overrun the stop join.
        CLOSING_DETECTORS,

        // The detectors are closed. Set under the detector lock.
        CLOSED,
    }

    // Read lock-free on every frame. Leaves OPEN under requestLock, in close(); the later steps are written by the one
    // close() that got past that move (CLOSING_DETECTORS, under neither lock) and under the detector lock (CLOSED).
    @Volatile private var lifecycle = Lifecycle.OPEN

    private val isClosed: Boolean get() = lifecycle !== Lifecycle.OPEN

    // Guards open, session, each request's raised flag and the move out of OPEN, so a begin() racing close() either
    // builds a session close() then stops, or sees the engine closed.
    private val requestLock = Any()

    // The open request, if any: always the newest one, since only begin() sets it.
    private var open: CaptureRequest? = null

    // The newest request begun. Read on every frame, so volatile rather than under the lock.
    @Volatile private var current: CaptureRequest? = null

    // The newest request's session; null until the first begin().
    private var session: PcmCaptureSession? = null

    // Serializes every detector call, and their close. Never taken while holding requestLock. requestLock may be taken
    // under it: the consumer's onWake, delivered under it, can call start(), pause() or close().
    private val detectorLock = ReentrantLock()

    // Last, so everything the detectors' events deliver through is initialized when a detector raises one at creation.
    private val detectors: List<WakeDetector> = createDetectors(this)

    /**
     * Posts [callback] to the main thread, never running it inline. It runs only if the engine is still open when it
     * is delivered.
     */
    fun deliverOnMain(callback: () -> Unit) = postToMain { if (!isClosed) callback() }

    /**
     * Delivers a wake through [deliver] unless the engine is closed, and returns whether it did. Detectors raise a wake
     * only from inside `accept`, so the capture thread holds the detector lock here. [close] leaves OPEN before it
     * waits for that lock: a wake past this check is one close waits for (or gives up on, as already begun).
     */
    fun deliverWake(deliver: () -> Unit): Boolean {
        if (isClosed) return false
        deliver()
        return true
    }

    /**
     * [WakeMicEngine.start]: a logged no-op once closed. Otherwise runs [Capture.beforeMicAcquire], then keeps the
     * current capture while a request is open and has raised no loss (its capture is then still opening, running or
     * about to report how it ended), or else begins a new capture request on a fresh session.
     */
    fun begin() {
        // Lock-free, only to skip the hook once closed; the closed check that counts is the one under the lock.
        if (!isClosed) capture.beforeMicAcquire()
        // Outside the lock: a close() that stopped it first leaves this a no-op, as on any stopped session.
        beginRequestOrNull()?.start()
    }

    // Begins a capture request and returns its fresh session, or null when start() joins the open request or the
    // engine is closed (checked here, so a start() racing close() either builds a session close() stops or sees it
    // closed).
    private fun beginRequestOrNull(): PcmCaptureSession? = synchronized(requestLock) {
        val joined = open
        when {
            ignoredAfterClose("start()") -> {
                null
            }

            joined != null && !joined.raised -> {
                log("start() during an open capture request — keeping the current capture")
                null
            }

            else -> {
                val request = CaptureRequest()
                open = request
                current = request
                newSession(request).also { session = it }
            }
        }
    }

    /**
     * [WakeMicEngine.pause]: a logged no-op once closed. Otherwise ends the open request first, so whatever stops
     * capture from here on was asked for, and only then stops its session.
     */
    fun end() {
        if (ignoredAfterClose("pause()")) return
        val stopping = synchronized(requestLock) {
            open = null
            session
        }
        stopping?.stop()
        log("mic paused (yielded slot)")
    }

    /**
     * [WakeMicEngine.close]: terminal and idempotent. Closes the engine, then stops the session and closes the
     * detectors against one deadline, [PcmCaptureSession.STOP_DEADLINE_MS]. A detector call still running when the
     * time is up (or running on the calling thread) closes the detectors as it leaves them; the time running out is
     * logged loudly, since a detector call that never returns leaks the detectors.
     */
    fun close() {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PcmCaptureSession.STOP_DEADLINE_MS)
        val stopping = synchronized(requestLock) {
            if (isClosed) return
            lifecycle = Lifecycle.STOPPING_CAPTURE
            session
        }
        stopping?.stop()
        closeDetectors(timeoutMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()))
    }

    private fun ignoredAfterClose(call: String): Boolean = isClosed.also { if (it) log("$call after close() ignored") }

    private fun newSession(request: CaptureRequest) = PcmCaptureSession(
        device = newDevice(),
        onFrame = { buf, n -> withDetectors(request) { capture.onFrame(it, buf, n) } },
        onStarted = { withDetectors(request, capture::onStarted) },
        onLost = request::lost,
        log = log,
        threadName = "wake-capture",
        rebuildAfterReadFailures = rebuildAfterReadFailures,
        idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        silenceWindowMs = PcmCaptureSession.DEFAULT_SILENCE_WINDOW_MS,
    )

    /** Runs [block] on the detectors under the detector lock, only for the current [request] and only while open. */
    private fun withDetectors(request: CaptureRequest, block: (List<WakeDetector>) -> Unit) {
        detectorLock.lock()
        try {
            if (!isClosed && request.isCurrent) block(detectors)
        } finally {
            detectorLock.unlock()
        }
        // Checked after unlocking: a close() that gave up waiting armed this before it began to wait, so either it got
        // the lock or this sees it armed.
        if (lifecycle === Lifecycle.CLOSING_DETECTORS && detectorLock.tryLock()) closeDetectorsAndUnlock()
    }

    private fun closeDetectors(timeoutMs: Long) {
        lifecycle = Lifecycle.CLOSING_DETECTORS
        if (detectorLock.isHeldByCurrentThread) return
        val acquired = try {
            detectorLock.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            log("🔴 wake detector close deferred — a detector call is still running; it closes them when it returns")
            return
        }
        closeDetectorsAndUnlock()
    }

    private fun closeDetectorsAndUnlock() {
        try {
            if (lifecycle === Lifecycle.CLOSING_DETECTORS) {
                lifecycle = Lifecycle.CLOSED
                detectors.forEach { it.close() }
            }
        } finally {
            detectorLock.unlock()
        }
    }

    /** One capture request, and the handle its capture session's callbacks are bound to. */
    private inner class CaptureRequest {
        // Guarded by requestLock.
        var raised = false
            private set

        /** True while no newer request has begun. */
        val isCurrent: Boolean get() = current === this

        /** Capture thread: this request's session reported a capture loss. Raised at most once, while open. */
        fun lost(loss: CaptureLoss) {
            synchronized(requestLock) {
                if (open !== this || raised) return
                raised = true
            }
            deliverOnMain { if (claimDelivery()) capture.onLost(loss) }
        }

        // Delivering a loss ends the request: the engine is idle, as after a pause.
        private fun claimDelivery(): Boolean = synchronized(requestLock) {
            (open === this).also { if (it) open = null }
        }
    }
}
