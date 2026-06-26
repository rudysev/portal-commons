package com.portal.commons

/**
 * Owns the microphone-capture lifecycle shared by consuming apps: a single capture thread that opens a
 * [PcmDevice], reads [PcmCaptureFormat]-sized frames in a blocking
 * loop, and tears down **without ever hanging**. The Android `AudioRecord` lives behind [PcmDevice], so
 * this concurrency contract is pure JVM and unit-tested with a fake device.
 *
 * Callbacks ([onFrame], [onStarted], [onError], [onReadError], [onStopped]) and [log] all run on the
 * capture thread — keep them cheap and non-blocking. [start]/[stop] are driven from a controlling thread
 * (e.g. a service's main Handler); they synchronize internally, but a callback on the capture thread
 * should not call [stop] expecting to join itself (a self-[stop] just signals and returns).
 *
 * **Stop contract (the reason this class exists).** [PcmDevice.read] blocks, so [stop] calls
 * [PcmDevice.stop] to unblock a parked read, then joins the thread with a bounded [joinTimeoutMs]. If the
 * thread is still alive after the timeout it force-[release]s the device as a last resort so the mic can't
 * stay held, and keeps the thread reference so a later [start] refuses (returns false) rather than running
 * two readers on the single mic slot.
 *
 * **One session = one capture.** This object is deliberately *not* self-recovering: if a native call wedges
 * the thread so [stop]'s join times out, [start] just refuses (false). Recovering — so capture resumes —
 * is the caller's job and is trivial: **discard this session and build a fresh one** (a new device, a new
 * thread, no shared mutable state with the wedged one). Keeping recovery out here is what lets this class
 * stay a simple single-lifecycle mechanism; the wedged thread, once it unblocks, sees `running == false`
 * and exits delivering no further frame (it is never revived — the fresh capture is a new instance). Its
 * `finally` still fires [onStopped], so a caller that reuses callbacks across a discard+rebuild should drop
 * that stale stop (e.g. by gating on the current session).
 *
 * **Rebuild.** With [rebuildAfterReadFailures] set, the session rebuilds the device
 * (`stop` → `release` → `open`) after that many consecutive failed reads — transient mic-loss recovery
 * (the wake app opts in; the assistant leaves it null).
 */
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
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    private val readErrorBackoffMs: Long = DEFAULT_READ_ERROR_BACKOFF_MS,
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
     * Stop and free the mic, guaranteed not to hang. Signals the loop, unblocks a parked read via
     * [PcmDevice.stop], and joins with [joinTimeoutMs]; on timeout, force-releases the device. Safe from
     * any thread; if called from the capture thread itself it just signals and returns (the loop's own
     * teardown releases and clears the thread reference).
     */
    fun stop() {
        val t = synchronized(lock) {
            running = false
            thread
        }
        runCatching { device.stop() } // unblock a parked read() so the loop exits and its finally releases
        if (t == null || t == Thread.currentThread()) {
            // self-stop (or never started): leave `thread` set so a concurrent start() is refused until the
            // loop's finally clears it on the controlled path.
            return
        }
        runCatching { t.join(joinTimeoutMs) }
        if (t.isAlive) {
            log("pcm stop timeout — force-releasing device; capture thread still alive")
            runCatching { device.stop() }
            runCatching { device.release() }
            // keep `thread = t` so start() refuses while the zombie is alive
        }
        // if it exited, the loop's finally already cleared `thread` under the lock
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
        val buf = ByteArray(PcmCaptureFormat.FRAME_BYTES)
        log("pcm capture started")
        onStarted()
        var readFails = 0
        try {
            while (running) {
                val n = device.read(buf)
                // Re-check running AFTER read() returns and BEFORE any callback: a stop() may have landed
                // while we were parked. A stopping session must deliver no further callback — otherwise, if
                // the caller has already discarded this session and started a fresh one to recover from a
                // wedge, this (old) thread's onFrame would run concurrently with the fresh thread's, racing a
                // non-thread-safe consumer (e.g. Vosk acceptWaveForm). running is set false by stop() before it
                // unblocks the read, and never set true again on this instance, so this gate is sufficient.
                if (!running) break
                if (n > 0) {
                    readFails = 0
                    onFrame(buf, n)
                    continue
                }
                onReadError(++readFails)
                if (rebuildAfterReadFailures != null && readFails >= rebuildAfterReadFailures) {
                    log("pcm read failing → rebuilding device")
                    runCatching { device.stop() }
                    runCatching { device.release() }
                    readFails = 0
                    if (!runCatching { device.open() }.getOrDefault(false)) {
                        log("pcm rebuild open failed")
                        onError("microphone unavailable on rebuild")
                        break
                    }
                    continue
                }
                if (readErrorBackoffMs > 0) Thread.sleep(readErrorBackoffMs)
            }
        } catch (e: Throwable) {
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

    companion object {
        const val DEFAULT_JOIN_TIMEOUT_MS = 400L
        const val DEFAULT_READ_ERROR_BACKOFF_MS = 20L
    }
}
