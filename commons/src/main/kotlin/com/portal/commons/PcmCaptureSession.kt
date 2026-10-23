package com.portal.commons

import java.util.concurrent.TimeUnit

/**
 * Owns the microphone-capture lifecycle shared by consuming apps: a single capture thread that opens a
 * [PcmDevice], assembles [PcmCaptureFormat]-sized frames from non-blocking reads, and tears down
 * **without ever hanging**. The Android `AudioRecord` lives behind [PcmDevice], so this concurrency
 * contract is pure JVM and unit-tested with a fake device.
 *
 * Callbacks ([onFrame], [onStarted], [onError], [onReadError], [onStopped]) and [log] all run on the
 * capture thread — keep them cheap and non-blocking. [start]/[stop] are driven from a controlling thread
 * (e.g. a service's main Handler); they synchronize internally, but a callback on the capture thread
 * should not call [stop] expecting to join itself (a self-[stop] just signals and returns).
 *
 * **Stop contract.** [PcmDevice.read] is non-blocking, so the loop observes `running == false` within one
 * poll interval and exits on its own; [stop] sets the flag and joins within a bounded [stopTimeoutMs]. On the
 * normal path the loop's `finally` releases the device, so the device is touched only on the capture thread.
 * If the thread doesn't exit in time (it overran the join inside a callback, or a native call hung), [stop]
 * force-releases the device as a last resort (on a helper thread, so [stop] stays bounded) so the mic slot can't
 * stay held, and keeps the thread reference set so a later [start] refuses (returns false) rather than running two
 * readers on the single mic slot.
 *
 * **One session = one capture.** This object is deliberately *not* self-recovering: in the rare case a
 * native call hangs so [stop]'s join times out, [start] just refuses (false). Recovering — so capture resumes —
 * is the caller's job and is trivial: **discard this session and build a fresh one** (a new device, a new
 * thread, no shared mutable state with the wedged one). Keeping recovery out here is what lets this class
 * stay a simple single-lifecycle mechanism; the wedged thread, once it unblocks, sees `running == false`
 * and exits delivering no further frame (it is never revived — the fresh capture is a new instance). Its
 * `finally` still fires [onStopped], so a caller that reuses callbacks across a discard+rebuild should drop
 * that stale stop (e.g. by gating on the current session). And if the thread overran the join *inside* a
 * callback, it is still running that callback when [stop] returns — a caller that shares a consumer across the
 * rebuild must serialize it, or the fresh thread runs alongside the wedged one.
 *
 * **Rebuild.** With [rebuildAfterReadFailures] set, the session rebuilds the device (`stop` → `release` →
 * `open`) after that many consecutive read errors (`< 0`). With [idleRebuildMs] set, it *also* rebuilds when no
 * full frame has been **delivered** for that long — catching a stolen / half-dead mic that returns a clean 0,
 * a sub-frame trickle, or partials interleaved with sub-threshold errors (none of which the `< 0` path sees),
 * so a consumer can't go silently deaf. The idle clock is monotonic ([System.nanoTime]) so a wall-clock jump
 * can't false-fire it. Both are transient mic-loss recovery (the always-listening apps opt in).
 */
// The constructor is the whole configuration surface: everything after device/onFrame is an optional callback or
// tuning knob with a default, and the apps pass them by name. A parameter object would only move the same list.
@Suppress("LongParameterList")
class PcmCaptureSession(
    private val device: PcmDevice,
    private val onFrame: (buf: ByteArray, n: Int) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onError: (message: String) -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val onReadError: (consecutiveFailures: Int) -> Unit = {},
    private val log: (message: String) -> Unit = {},
    private val threadName: String = "pcm-capture",
    private val rebuildAfterReadFailures: Int? = null,
    private val stopTimeoutMs: Long = STOP_DEADLINE_MS,
    private val readErrorBackoffMs: Long = DEFAULT_READ_ERROR_BACKOFF_MS,
    private val pollMs: Long = DEFAULT_READ_POLL_MS,
    private val idleRebuildMs: Long? = null,
) {
    // Guards the compound (running, thread) state across the controlling thread(s) and the capture thread's
    // finally. `running` is also @Volatile so the hot read loop can poll it without taking the lock.
    private val lock = Any()

    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * Start capturing. Returns **false only** when refused because a previous capture thread is still alive
     * (a zombie from a [stop] that timed out) — we never run two readers on the one mic slot. Otherwise
     * returns true, meaning the capture thread was started (or was already running): capture begins
     * asynchronously, and a failure to acquire the mic surfaces via [onError], not the return value.
     * Idempotent.
     */
    fun start(): Boolean = synchronized(lock) {
        if (running) return true
        val prev = thread
        if (prev != null && prev.isAlive) {
            log("pcm start refused — previous capture thread still alive")
            return false
        }
        running = true
        thread = Thread({ loop() }, threadName).also { it.start() }
        true
    }

    /**
     * Stop and free the mic, guaranteed not to hang: returns within [timeoutMs]. Sets `running = false` and joins the
     * capture thread; the loop observes the flag within one poll and its `finally` releases the device — so on the
     * normal path the device is touched only on the capture thread. If the thread is still alive near the end of
     * [timeoutMs] (it overran the join inside a callback, or a native open/stop/release hung), this force-releases the
     * device as a last resort so the mic slot can't stay held, and keeps the thread reference set so a later [start]
     * refuses rather than running two readers on the one slot. Safe from any thread; a self-[stop] from the capture
     * thread just signals and returns (the loop's own teardown releases and clears the thread reference).
     *
     * **Trade-off.** The force-release runs on a short-lived helper thread, and the join leaves it the last
     * [FORCE_RELEASE_GRACE_MS] of [timeoutMs] (at most half). So the mic slot is free when this returns whenever the
     * release itself is quick; if the native release hangs too, this still returns on time and the slot frees only
     * when that release returns, on the helper thread. A bounded stop is worth more than a held caller: on the main
     * thread an unbounded one is an ANR.
     */
    fun stop(timeoutMs: Long = stopTimeoutMs) {
        val t = synchronized(lock) {
            running = false
            thread
        }
        if (t == null || t == Thread.currentThread()) {
            // self-stop (or never started): leave `thread` set so a concurrent start() is refused until the
            // loop's finally clears it on the controlled path.
            return
        }
        val totalMs = timeoutMs.coerceAtLeast(1)
        val graceMs = minOf(FORCE_RELEASE_GRACE_MS, totalMs / 2)
        runCatching { t.join(totalMs - graceMs) }
        if (t.isAlive) {
            // The capture thread didn't exit in time — it overran the join inside a callback, or a native
            // open/stop/release hung. Force-release the device so the mic slot can't stay held, and keep
            // `thread = t` so start() refuses while it's alive (never two readers on the one slot).
            log("pcm stop timeout — force-releasing device; capture thread still alive")
            forceRelease(graceMs)
        }
        // if it exited, the loop's finally already cleared `thread` under the lock
    }

    // On a helper thread, so a release that hangs in native code holds that thread, not the caller.
    private fun forceRelease(waitMs: Long) {
        val helper = Thread({
            runCatching { device.stop() }
            runCatching { device.release() }
        }, "$threadName-release").apply {
            isDaemon = true
            start()
        }
        runCatching { helper.join(waitMs.coerceAtLeast(1)) }
        if (helper.isAlive) log("pcm force-release still running — the mic slot frees when it returns")
    }

    /**
     * Tear down and reopen the device after a fault (a read-error run, or the idle watchdog). A member
     * function, so it closes over none of the loop's mutable locals. Returns true if the device reopened;
     * false means [PcmDevice.open] failed and the caller should fire [onError] and break (the consumer
     * recovers by discarding this session). Callers guard `running` before calling so a stop() in flight
     * doesn't reopen a device that's about to be torn down.
     */
    private fun rebuildDevice(reason: String): Boolean {
        log("pcm $reason → rebuilding device")
        runCatching { device.stop() }
        runCatching { device.release() }
        return runCatching { device.open() }.getOrDefault(false)
    }

    private fun loop() {
        if (!runCatching { device.open() }.getOrDefault(false)) {
            log("pcm open failed")
            synchronized(lock) {
                running = false
                if (thread == Thread.currentThread()) thread = null
            }
            onError("microphone unavailable")
            return
        }
        // Built before onStarted(), so the idle clock starts when the device opened.
        val capture = CaptureLoop()
        log("pcm capture started")
        try {
            // A stop() that landed while open() was in flight must not start the consumer either: on gen2 a stopped
            // session's onStarted ran Vosk's reset + warm-up past the stop join, alongside the rebuilt session.
            if (running) onStarted()
            capture.run()
        } catch (
            // Last line of defence on the capture thread: anything escaping here would be an uncaught exception on
            // a background thread, which kills the host app's process. Log it; the finally still frees the mic.
            @Suppress("TooGenericExceptionCaught")
            e: Throwable,
        ) {
            log("pcm capture EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { device.stop() }
            runCatching { device.release() }
            synchronized(lock) {
                running = false
                // Only clear if we're still the active thread — never clobber a thread a newer start() set.
                if (thread == Thread.currentThread()) thread = null
            }
            log("pcm capture stopped")
            onStopped()
        }
    }

    /**
     * The read loop of one capture, with its frame-assembly and watchdog state. Created and run by [loop] on the
     * capture thread only, so its fields are confined to that thread.
     */
    private inner class CaptureLoop {
        private val buf = ByteArray(PcmCaptureFormat.FRAME_BYTES)
        private var off = 0 // bytes assembled into the current frame so far

        // monotonic time a full frame was last delivered — drives the idle watchdog
        private var lastFrameNs = System.nanoTime()
        private var readFails = 0

        /** Read until [stop] or a failed rebuild. */
        fun run() {
            var keepReading = true
            while (keepReading && running) keepReading = step()
        }

        /** One non-blocking read and its handling. False ends the capture. */
        private fun step(): Boolean {
            // Non-blocking read directly into the frame buffer at the current offset.
            val n = device.read(buf, off, PcmCaptureFormat.FRAME_BYTES - off)
            // Re-check running AFTER read() returns and BEFORE any callback: a stop() may have landed. A
            // stopping session must deliver no further callback — otherwise, if the caller has already
            // discarded this session and started a fresh one, this (old) thread's onFrame would run
            // concurrently with the fresh thread's, racing a non-thread-safe consumer (e.g. Vosk).
            return running &&
                when {
                    // Idle watchdog: if no FULL frame was delivered for idleRebuildMs, the mic is stolen/half-dead.
                    // Measuring *delivered frames* (not raw bytes read) catches a clean 0, a sub-frame trickle (n>0
                    // that never completes a frame), and partials interleaved with sub-threshold errors — none of
                    // which the <0 rebuild sees. Monotonic clock so a wall-clock jump can't false-fire/disable it.
                    // The read's result is dropped: the rebuild discards any partial frame anyway.
                    idleRebuildMs != null &&
                        System.nanoTime() - lastFrameNs >= TimeUnit.MILLISECONDS.toNanos(idleRebuildMs) ->
                        rebuild("no frame for ${idleRebuildMs}ms")

                    n > 0 -> {
                        onData(n)
                        true
                    }

                    n == 0 -> {
                        // No data right now — NORMAL for a non-blocking read, not a failure (does not touch
                        // readFails). Poll again after a short sleep; re-check running so a stop() landing here
                        // isn't delayed a full poll interval. Sustained no-data is handled by the idle watchdog above.
                        if (pollMs > 0 && running) Thread.sleep(pollMs)
                        true
                    }

                    else -> onDeviceError()
                }
        }

        private fun onData(n: Int) {
            readFails = 0
            off += n
            // length is capped at the remaining bytes, so off lands exactly here
            if (off == PcmCaptureFormat.FRAME_BYTES) {
                onFrame(buf, PcmCaptureFormat.FRAME_BYTES)
                off = 0
                lastFrameNs = System.nanoTime()
            }
            // else: partial frame — loop again immediately to drain whatever else is buffered
        }

        /** n < 0: a real device error. False ends the capture (a failed rebuild). */
        private fun onDeviceError(): Boolean {
            off = 0 // discard any partial frame to avoid splicing audio
            onReadError(++readFails)
            val rebuildAfter = rebuildAfterReadFailures
            if (rebuildAfter != null && readFails >= rebuildAfter) return rebuild("read failing")
            if (readErrorBackoffMs > 0 && running) Thread.sleep(readErrorBackoffMs)
            return true
        }

        /** Rebuild the device (see [rebuildDevice]) and restart the frame and idle state. False ends the capture. */
        private fun rebuild(reason: String): Boolean {
            if (!running) return false // a stop() landed — don't reopen a device we're about to tear down
            val reopened = rebuildDevice(reason)
            if (reopened) {
                off = 0
                readFails = 0
                lastFrameNs = System.nanoTime() // fresh device — restart the idle clock
            } else {
                onError("microphone unavailable on rebuild")
            }
            return reopened
        }
    }

    companion object {
        /**
         * The one bound on stopping capture: [stop]'s whole wait (join plus force-release) by default, and the
         * shared deadline of `WakeMicEngine.close()`.
         */
        const val STOP_DEADLINE_MS = 400L
        const val DEFAULT_READ_ERROR_BACKOFF_MS = 20L

        /** The part of [stop]'s timeout left for a force-release once the join gives up (at most half of it). */
        const val FORCE_RELEASE_GRACE_MS = 50L

        // Sleep between non-blocking reads when no data is buffered yet. At 32 B/ms this wakes ~50×/s
        // (negligible CPU), drains ≤ ~640 B per idle wake, adds ≤ this many ms to a 100 ms frame, and leaves
        // ~20× headroom under the ~400 ms AudioRecord buffer. When data is flowing the loop drains without
        // sleeping, so steady-state cadence stays ~100 ms/frame.
        const val DEFAULT_READ_POLL_MS = 20L

        // Recommended idleRebuildMs for an always-listening consumer: ~20× FRAME_MS, so healthy continuous
        // capture never idles this long — only a stolen/half-dead mic does.
        const val DEFAULT_IDLE_REBUILD_MS = 2_000L
    }
}
