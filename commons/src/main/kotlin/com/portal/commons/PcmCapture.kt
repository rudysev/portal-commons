package com.portal.commons

import com.portal.commons.audio.CaptureLoss
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The module every app captures the mic through, one **capture request** at a time (see `GLOSSARY.md`): [start] begins
 * a request (or joins the open one), [pause] ends it, [close] ends the module. Every request captures on a fresh
 * device from [newDevice], under the [policy] passed here once.
 *
 * What it guarantees a caller:
 * - **Current request only.** Each request's callbacks reach only the [Listener] it was started with, and only while
 *   it is the newest request: a capture thread that outlived a stop (inside a callback, past the stop deadline) reaches
 *   no listener again.
 * - **One callback at a time.** [Listener.onStarted] and [Listener.onFrame] run on the capture thread under one
 *   callback lock, so a wedged older capture thread and a fresh one are never inside a listener at once, even across
 *   requests and listeners.
 * - **At most one capture loss per request.** It is raised once, while its request is open, then posted to the main
 *   thread through [postToMain] (never run inline) and claimed when delivered: a [pause] or a newer [start] in between
 *   drops it. Delivering it ends its request. A requested [pause] reports no loss.
 * - **Closed state.** [close] is terminal and idempotent. It is checked before every listener call and when a posted
 *   callback is delivered (not when posted), so nothing reaches a listener after [close] returns. [start] and [pause]
 *   after it are logged no-ops.
 * - **Bounded close.** [close] stops capture, then waits for the callback lock, against one deadline,
 *   [STOP_DEADLINE_MS]; the mic slot is free when it returns. [onClosed] runs exactly once and never during a listener
 *   callback: on the thread calling [close] when the lock frees in time, otherwise on the thread leaving the callback
 *   [close] outlasted (or that called [close] itself).
 *
 * [start], [pause] and [close] are safe from any thread, including from inside a listener callback.
 */
class PcmCapture(
    private val newDevice: () -> PcmDevice,
    private val postToMain: (Runnable) -> Unit,
    private val log: (message: String) -> Unit = {},
    private val threadName: String = "pcm-capture",
    private val policy: CapturePolicy = CapturePolicy(),
    private val onClosed: () -> Unit = {},
) {
    /** A consumer of one capture request. Every method defaults to a no-op. */
    interface Listener {
        /** Capture thread, under the callback lock: this request's capture has started. */
        fun onStarted() = Unit

        /** Capture thread, under the callback lock: one frame of this request's capture. */
        fun onFrame(buf: ByteArray, n: Int) = Unit

        /** Main thread: this request's capture was lost, and nothing has overtaken it. Ends the request. */
        fun onLost(loss: CaptureLoss) = Unit
    }

    /** The one closed state. Each step only moves forward. */
    private enum class Lifecycle {
        OPEN,

        // close() has begun and is stopping the session. Already closed to every caller and callback.
        STOPPING_CAPTURE,

        // close() has reached onClosed: whichever thread holds the callback lock runs it as it leaves. Not armed
        // while the session stops, so a capture thread leaving a frame then doesn't take the consumer's own (slow)
        // close onto itself and overrun the stop join.
        CLOSING,

        // onClosed has run (or is running). Set under the callback lock.
        CLOSED,
    }

    // Read lock-free on every frame. Leaves OPEN under requestLock, in close(); the later steps are written by the one
    // close() that got past that move (CLOSING, under neither lock) and under the callback lock (CLOSED).
    @Volatile private var lifecycle = Lifecycle.OPEN

    /** True once [close] has begun. */
    val isClosed: Boolean get() = lifecycle !== Lifecycle.OPEN

    // Guards open, session, overtaken, each request's raised flag and the move out of OPEN, so a start() racing close()
    // either builds a session close() then stops, or sees the module closed.
    private val requestLock = Any()

    // The open request, if any: always the newest one, since only start() sets it.
    private var open: CaptureRequest? = null

    // The newest request begun. Read on every frame, so volatile rather than under the lock.
    @Volatile private var current: CaptureRequest? = null

    // The newest request's session; null until the first start().
    private var session: PcmCaptureSession? = null

    // Sessions a start() overtook and is still stopping, outside requestLock. pause() and close() stop them too, so a
    // capture the overtaking start() has yet to free still holds no mic slot when they return.
    private val overtaken = mutableListOf<PcmCaptureSession>()

    // Serializes every listener callback, and onClosed. Never taken while holding requestLock. requestLock may be
    // taken under it: a callback can call start(), pause() or close().
    private val callbackLock = ReentrantLock()

    /**
     * Begins a capture request delivering to [listener], on a fresh device. A logged no-op once closed. While a
     * request is open and has raised no loss (its capture is then still opening, running or about to report how it
     * ended), a [listener] that is the same one (by reference) joins it and keeps the running capture; a different
     * one ends it, as [pause] then [start] would, and begins a new request.
     */
    fun start(listener: Listener) {
        val begun = beginRequestOrNull(listener) ?: return
        // Outside the lock, the overtaken capture first, so the new one opens the mic only once the old one is told to
        // stop. From any thread but the old capture thread that stop frees the mic slot first; from inside one of its
        // callbacks it only signals, as pause() then start() there would. A pause() or close() that stopped the new
        // session first leaves its start a no-op, as on any stopped session.
        begun.overtaken?.let { old ->
            old.stop()
            synchronized(requestLock) { overtaken.remove(old) }
        }
        begun.session.start()
    }

    /** A capture request [start] began: its fresh [session], and the open request's session it ended, if any. */
    private class Begun(val session: PcmCaptureSession, val overtaken: PcmCaptureSession?)

    // Begins a capture request, or returns null when start() joins the open request or the module is closed (checked
    // here, so a start() racing close() either builds a session close() stops or sees it closed). An open request for
    // a different listener ends here, as pause() would end it: no loss is reported for it.
    private fun beginRequestOrNull(listener: Listener): Begun? = synchronized(requestLock) {
        val joined = open
        when {
            ignoredAfterClose("start()") -> {
                null
            }

            joined != null && !joined.raised && joined.listener === listener -> {
                log("start() during an open capture request — keeping the current capture")
                null
            }

            else -> {
                val ended = if (joined != null && !joined.raised) session else null
                if (ended != null) {
                    log("start() with a different listener — ending the open capture request")
                    overtaken.add(ended)
                }
                val request = CaptureRequest(listener)
                open = request
                current = request
                Begun(newSession(request).also { session = it }, ended)
            }
        }
    }

    /**
     * Ends the open capture request, reporting no loss for it, and frees the mic slot before it returns. A logged
     * no-op once closed.
     */
    fun pause() {
        if (ignoredAfterClose("pause()")) return
        val stopping = synchronized(requestLock) {
            open = null
            stopOrder()
        }
        stopping.forEach { it.stop() }
        log("mic paused (yielded slot)")
    }

    /**
     * Terminal and idempotent. Closes the module, then stops capture and runs [onClosed] against one deadline,
     * [STOP_DEADLINE_MS]. A listener callback still running when the time is up (or running on the calling thread)
     * runs [onClosed] as it leaves; the time running out is logged loudly, since a callback that never returns leaks
     * whatever [onClosed] frees.
     */
    fun close() {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_DEADLINE_MS)
        val stopping = synchronized(requestLock) {
            if (isClosed) return
            lifecycle = Lifecycle.STOPPING_CAPTURE
            stopOrder()
        }
        stopping.forEach { it.stop() }
        runOnClosed(timeoutMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()))
    }

    // Under requestLock: every session that may still hold the mic, the newest first. A start() starts its own session
    // only once it has stopped the one it overtook, so stopping the newest first turns a pending start into a no-op
    // rather than a capture that opens the mic behind the stop.
    private fun stopOrder(): List<PcmCaptureSession> = listOfNotNull(session) + overtaken.asReversed()

    private fun ignoredAfterClose(call: String): Boolean = isClosed.also { if (it) log("$call after close() ignored") }

    private fun newSession(request: CaptureRequest) = PcmCaptureSession(
        device = newDevice(),
        onFrame = { buf, n -> withListener(request) { it.onFrame(buf, n) } },
        onStarted = { withListener(request) { it.onStarted() } },
        onLost = request::lost,
        log = log,
        threadName = threadName,
        policy = policy,
    )

    /** Runs [block] on [request]'s listener under the callback lock, only while it is current and only while open. */
    private fun withListener(request: CaptureRequest, block: (Listener) -> Unit) {
        callbackLock.lock()
        try {
            if (!isClosed && request.isCurrent) block(request.listener)
        } finally {
            callbackLock.unlock()
        }
        // Checked after unlocking: a close() that gave up waiting armed this before it began to wait, so either it got
        // the lock or this sees it armed.
        if (lifecycle === Lifecycle.CLOSING && callbackLock.tryLock()) runOnClosedAndUnlock()
    }

    private fun runOnClosed(timeoutMs: Long) {
        lifecycle = Lifecycle.CLOSING
        if (callbackLock.isHeldByCurrentThread) return
        val acquired = try {
            callbackLock.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            log("🔴 capture close deferred — a listener callback is still running; onClosed runs when it returns")
            return
        }
        runOnClosedAndUnlock()
    }

    private fun runOnClosedAndUnlock() {
        try {
            if (lifecycle === Lifecycle.CLOSING) {
                lifecycle = Lifecycle.CLOSED
                onClosed()
            }
        } finally {
            callbackLock.unlock()
        }
    }

    /** One capture request, and the handle its capture session's callbacks are bound to. */
    private inner class CaptureRequest(val listener: Listener) {
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
            postToMain { if (!isClosed && claimDelivery()) listener.onLost(loss) }
        }

        // Delivering a loss ends the request: the module is idle, as after a pause.
        private fun claimDelivery(): Boolean = synchronized(requestLock) {
            (open === this).also { if (it) open = null }
        }
    }

    companion object {
        /** The one bound on stopping capture: [pause]'s stop, and the shared deadline of [close]'s waits. */
        const val STOP_DEADLINE_MS = PcmCaptureSession.STOP_DEADLINE_MS
    }
}
