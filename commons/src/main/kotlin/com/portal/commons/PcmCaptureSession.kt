package com.portal.commons

import com.portal.commons.audio.CaptureLoss
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the microphone-capture lifecycle shared by consuming apps: a single capture thread that opens a
 * [PcmDevice], assembles [PcmCaptureFormat]-sized frames from non-blocking reads, and tears down
 * **without ever hanging**. The Android `AudioRecord` lives behind [PcmDevice], so this concurrency
 * contract is pure JVM and unit-tested with a fake device.
 *
 * **Capture through [PcmCapture] instead.** It runs one of these per capture request and meets the duties this
 * session leaves its caller: binding callbacks to the current request, serializing a wedged older session against a
 * newer one, and closing the consumer only when no callback is running.
 *
 * Callbacks ([onFrame], [onStarted], [onLost]) and [log] all run on the capture thread — keep them
 * cheap and non-blocking. [start]/[stop] are driven from a controlling thread (e.g. a service's main Handler); they
 * synchronize internally, but a callback on the capture thread should not call [stop] expecting to join itself (a
 * self-[stop] just signals and returns).
 *
 * **Capture loss.** A capture that ends without being asked to reports why through [onLost], once per session: the
 * device would not open when capture started ([CaptureLoss.NotGranted]); a rebuild's reopen failed, or an exception
 * escaped the read loop ([CaptureLoss.Dropped]); the silence window filled ([CaptureLoss.Silenced]). A requested
 * [stop], including one that lands while the device is still opening, reports nothing: [onLost] never runs once
 * [stop] has been called. The check and the call run under the session lock, and [stop] waits for that lock only
 * within its deadline: past it, an [onLost] already in progress began before the stop, and [stop] returns without
 * waiting for it.
 *
 * **Stop contract.** [PcmDevice.read] is non-blocking, so the loop observes `running == false` within one
 * poll interval and exits on its own; [stop] sets the flag and joins within a bounded [STOP_DEADLINE_MS]. On the
 * normal path the loop's `finally` releases the device, so the device is touched only on the capture thread.
 * If the thread doesn't exit in time (it overran the join inside a callback, or a native call hung), [stop]
 * force-releases the device as a last resort (on a helper thread, so [stop] stays bounded) so the mic slot can't
 * stay held. The wedged thread, once it unblocks, sees `running == false` and exits delivering no further frame and
 * no [onLost]. If it overran the join *inside* a callback, it is still running that callback when [stop] returns: a
 * caller that shares a consumer with a newer session must serialize it.
 *
 * **Single-use.** A session runs at most one capture: a second [start], or a [start] after [stop], is a logged
 * no-op. To capture again, build a fresh session.
 *
 * **Rebuild.** With [rebuildAfterReadFailures] set, the session rebuilds the device (`stop` → `release` →
 * `open`) after that many consecutive read errors (`< 0`). With [idleRebuildMs] set, it *also* rebuilds when no
 * full frame has been **delivered** for that long — catching a stolen / half-dead mic that returns a clean 0,
 * a sub-frame trickle, or partials interleaved with sub-threshold errors (none of which the `< 0` path sees),
 * so a consumer can't go silently deaf. The idle clock is monotonic ([System.nanoTime]) so a wall-clock jump
 * can't false-fire it. Both are transient mic-loss recovery (the always-listening apps opt in).
 *
 * **Silence.** With [silenceWindowMs] set, a capture that delivers that much audio of exact-zero samples in a row
 * ends as [CaptureLoss.Silenced], with no rebuild: a denied app-op's silence survives a reopen (and on gen1 the
 * reopen fails). With [rebuildOnSilence] also set, a full window first rebuilds the device, once per silence:
 * Portal+ gen1 silences a client's open input when its app leaves the foreground, and a reopen is live again. Only a
 * window that follows that rebuild with no sample heard in between ends capture. Real mics never produce exact zeros
 * for that long, even in a quiet room (checked on Portal+ gen1), so there is no tolerance band. The window is counted
 * in samples, not on a clock, and starts again at every start, every rebuild and every non-zero sample.
 */
// The constructors are the whole configuration surface: the public one's parameters after device/onFrame are optional
// callbacks or policies with a default, and the apps pass them by name. A parameter object would only move the same
// list.
@Suppress("LongParameterList")
class PcmCaptureSession internal constructor(
    private val device: PcmDevice,
    private val onFrame: (buf: ByteArray, n: Int) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onLost: (CaptureLoss) -> Unit = {},
    private val log: (message: String) -> Unit = {},
    private val threadName: String = "pcm-capture",
    private val rebuildAfterReadFailures: Int? = null,
    private val idleRebuildMs: Long? = null,
    private val silenceWindowMs: Long? = null,
    private val rebuildOnSilence: Boolean = false,
    // Test timing, not policy: required here so a call that leaves it out picks the public constructor.
    private val pollMs: Long,
    private val readErrorBackoffMs: Long = DEFAULT_READ_ERROR_BACKOFF_MS,
    private val stopTimeoutMs: Long = STOP_DEADLINE_MS,
) {
    /**
     * The production surface: the device, the callbacks and the three capture policies. Read polling, read-error
     * backoff and the [stop] timeout use [DEFAULT_READ_POLL_MS], [DEFAULT_READ_ERROR_BACKOFF_MS] and
     * [STOP_DEADLINE_MS].
     */
    constructor(
        device: PcmDevice,
        onFrame: (buf: ByteArray, n: Int) -> Unit,
        onStarted: () -> Unit = {},
        onLost: (CaptureLoss) -> Unit = {},
        log: (message: String) -> Unit = {},
        threadName: String = "pcm-capture",
        rebuildAfterReadFailures: Int? = null,
        idleRebuildMs: Long? = null,
        silenceWindowMs: Long? = null,
        rebuildOnSilence: Boolean = false,
    ) : this(
        device = device,
        onFrame = onFrame,
        onStarted = onStarted,
        onLost = onLost,
        log = log,
        threadName = threadName,
        rebuildAfterReadFailures = rebuildAfterReadFailures,
        idleRebuildMs = idleRebuildMs,
        silenceWindowMs = silenceWindowMs,
        rebuildOnSilence = rebuildOnSilence,
        pollMs = DEFAULT_READ_POLL_MS,
    )

    // Guards the compound (used, running, thread) state across the controlling thread(s) and the capture thread's
    // end. `running` is also @Volatile so the hot read loop can poll it without taking the lock, and `thread` so a
    // stop() that gave up waiting for the lock still sees it.
    private val lock = ReentrantLock()

    private var used = false

    @Volatile private var running = false

    @Volatile private var thread: Thread? = null

    /**
     * Start capturing, on a capture thread of its own. Capture begins asynchronously, and a failure to acquire the
     * mic surfaces via [onLost]. Only the first call on a session starts anything, and only before [stop]: any other
     * is a logged no-op.
     */
    fun start() {
        lock.withLock {
            if (used) {
                log("pcm start ignored — a capture session is single-use")
                return
            }
            used = true
            running = true
            thread = Thread({ loop() }, threadName).also { it.start() }
        }
    }

    /**
     * Stop and free the mic, guaranteed not to hang: returns within [STOP_DEADLINE_MS]. Sets `running = false` and
     * joins the capture thread; the loop observes the flag within one poll and its `finally` releases the device — so
     * on the normal path the device is touched only on the capture thread. If the thread is still alive near the end
     * of that deadline (it overran the join inside a callback, or a native open/stop/release hung), this
     * force-releases the device as a last resort so the mic slot can't stay held. Safe from any thread; a self-[stop]
     * from the capture thread just signals and returns (the loop's own teardown releases the device). It ends the
     * session: a later [start] is a no-op. An [onLost] already in progress counts against the same deadline: a slow
     * one runs on past it, like any other callback that overruns the join.
     *
     * **Trade-off.** The force-release runs on a short-lived helper thread, and the join leaves it the last
     * [FORCE_RELEASE_GRACE_MS] of the deadline (at most half). So the mic slot is free when this returns whenever the
     * release itself is quick; if the native release hangs too, this still returns on time and the slot frees only
     * when that release returns, on the helper thread. A bounded stop is worth more than a held caller: on the main
     * thread an unbounded one is an ANR.
     */
    fun stop() {
        val totalMs = stopTimeoutMs.coerceAtLeast(1)
        val graceMs = minOf(FORCE_RELEASE_GRACE_MS, totalMs / 2)
        val joinDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs - graceMs)
        val t = if (lockBefore(joinDeadlineNs)) {
            try {
                used = true
                running = false
                thread
            } finally {
                lock.unlock()
            }
        } else {
            // The capture thread has held the lock all this time: it is inside onLost, which began before this stop.
            running = false
            thread
        }
        if (t == null || t == Thread.currentThread()) return // never started, or a self-stop
        val joinMs = TimeUnit.NANOSECONDS.toMillis(joinDeadlineNs - System.nanoTime())
        if (joinMs > 0) runCatching { t.join(joinMs) } // join(0) would wait forever
        if (t.isAlive) {
            // The capture thread didn't exit in time — it overran the join inside a callback, or a native
            // open/stop/release hung. Force-release the device so the mic slot can't stay held.
            log("pcm stop timeout — force-releasing device; capture thread still alive")
            forceRelease(graceMs)
        }
    }

    // Takes the lock if it frees up before deadlineNs. Not interruptible, like the join's bound: an interrupt is kept
    // for the caller.
    private fun lockBefore(deadlineNs: Long): Boolean {
        var interrupted = false
        try {
            while (true) {
                try {
                    return lock.tryLock(deadlineNs - System.nanoTime(), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
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
     * Tear down and reopen the device after a fault (a read-error run, the idle watchdog, or a silence window). A
     * member function, so it closes over none of the loop's mutable locals. Returns true if the device reopened;
     * false means [PcmDevice.open] failed and the capture ends as [CaptureLoss.Dropped]. Callers guard `running`
     * before calling so a stop() in flight doesn't reopen a device that's about to be torn down.
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
            end(CaptureLoss.NotGranted("microphone unavailable"))
            return
        }
        // Built before onStarted(), so the idle clock starts when the device opened.
        val capture = CaptureLoop()
        log("pcm capture started")
        var loss: CaptureLoss? = null
        try {
            // A stop() that landed while open() was in flight must not start the consumer either: on gen2 a stopped
            // session's onStarted ran Vosk's reset + warm-up past the stop join, alongside the fresh session.
            if (running) onStarted()
            loss = capture.run()
        } catch (
            // Last line of defence on the capture thread: anything escaping here would be an uncaught exception on
            // a background thread, which kills the host app's process. Log it and report it; the finally still
            // frees the mic.
            @Suppress("TooGenericExceptionCaught")
            e: Throwable,
        ) {
            log("pcm capture EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            loss = CaptureLoss.Dropped("capture ended unexpectedly: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { device.stop() }
            runCatching { device.release() }
            log("pcm capture stopped")
            end(loss)
        }
    }

    /**
     * Ends the capture: reports [loss] unless [stop] was called. Under the lock [stop] takes, so a [stop] lands either
     * before the check (and nothing is reported) or after [onLost] began.
     */
    private fun end(loss: CaptureLoss?) = lock.withLock {
        try {
            if (running && loss != null) onLost(loss)
        } finally {
            running = false
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

        // Consecutive exact-zero samples delivered, against the silence window counted in samples of audio.
        private var zeroSamples = 0L
        private val silenceSamples = silenceWindowMs?.let { it * PcmCaptureFormat.SAMPLE_RATE / MS_PER_SECOND }

        // With rebuildOnSilence: a silence window already rebuilt the device, and no sample has been heard since.
        private var silenceRebuilt = false

        // Why the capture is ending, once it knows: set where a step returns false for anything but a stop.
        private var loss: CaptureLoss? = null

        /** Read until [stop] or a capture loss. Returns that loss, or null for a stop. */
        fun run(): CaptureLoss? {
            var keepReading = true
            while (keepReading && running) keepReading = step()
            return loss
        }

        /** One non-blocking read and its handling. False ends the capture. */
        private fun step(): Boolean {
            // Non-blocking read directly into the frame buffer at the current offset.
            val n = device.read(buf, off, PcmCaptureFormat.FRAME_BYTES - off)
            // Re-check running AFTER read() returns and BEFORE any callback: a stop() may have landed. A
            // stopping session must deliver no further callback — otherwise, if the caller has already
            // started a fresh session, this (old) thread's onFrame would run concurrently with the fresh
            // thread's, racing a non-thread-safe consumer (e.g. Vosk).
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

                    n > 0 -> onData(n)

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

        /** n > 0: assemble, and deliver a completed frame. False ends the capture (the silence window ran out). */
        private fun onData(n: Int): Boolean {
            readFails = 0
            off += n
            // length is capped at the remaining bytes, so off lands exactly here
            if (off != PcmCaptureFormat.FRAME_BYTES) return true // partial frame — drain whatever else is buffered
            onFrame(buf, PcmCaptureFormat.FRAME_BYTES)
            off = 0
            lastFrameNs = System.nanoTime()
            return !silenced()
        }

        /**
         * Counts the frame just delivered against the silence window; true (and a [CaptureLoss.Silenced]) once it is
         * full. With [rebuildOnSilence], a full window first rebuilds the device, and only a window that follows
         * that rebuild with no sample heard in between ends capture. A stop that landed during the frame is no loss:
         * a stopping session delivers no further callback.
         */
        private fun silenced(): Boolean {
            val window = silenceSamples ?: return false
            val runBefore = zeroSamples
            zeroSamples = trailingZeroSamples(runBefore)
            if (zeroSamples < runBefore + FRAME_SAMPLES) silenceRebuilt = false // a sample heard: the stream is live
            val reason = "digital silence for ${silenceWindowMs}ms"
            return when {
                zeroSamples < window || !running -> false

                rebuildOnSilence && !silenceRebuilt -> {
                    silenceRebuilt = true
                    !rebuild(reason)
                }

                else -> {
                    log("pcm $reason → ending capture")
                    loss = CaptureLoss.Silenced(reason)
                    true
                }
            }
        }

        // The zero run after this frame: it grows by the whole frame if the frame is all zeros, else it is the
        // frame's own trailing zeros.
        private fun trailingZeroSamples(runBefore: Long): Long {
            var i = PcmCaptureFormat.FRAME_BYTES - PcmCaptureFormat.BYTES_PER_SAMPLE
            var trailing = 0L
            while (i >= 0 && buf[i] == ZERO && buf[i + 1] == ZERO) {
                trailing++
                i -= PcmCaptureFormat.BYTES_PER_SAMPLE
            }
            return if (i < 0) runBefore + trailing else trailing
        }

        /** n < 0: a real device error. False ends the capture (a failed rebuild). */
        private fun onDeviceError(): Boolean {
            off = 0 // discard any partial frame to avoid splicing audio
            readFails++
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
                zeroSamples = 0 // and the silence window
            } else {
                loss = CaptureLoss.Dropped("microphone unavailable on rebuild")
            }
            return reopened
        }
    }

    companion object {
        /**
         * The one bound on stopping capture: [stop]'s whole wait (join plus force-release), and the
         * shared deadline of [PcmCapture.close]'s waits (not of the `onClosed` that follows them).
         */
        const val STOP_DEADLINE_MS = 400L
        const val DEFAULT_READ_ERROR_BACKOFF_MS = 20L

        /** The part of [stop]'s deadline left for a force-release once the join gives up (at most half of it). */
        const val FORCE_RELEASE_GRACE_MS = 50L

        // Sleep between non-blocking reads when no data is buffered yet. At 32 B/ms this wakes ~50×/s
        // (negligible CPU), drains ≤ ~640 B per idle wake, adds ≤ this many ms to a 100 ms frame, and leaves
        // ~20× headroom under the ~400 ms AudioRecord buffer. When data is flowing the loop drains without
        // sleeping, so steady-state cadence stays ~100 ms/frame.
        const val DEFAULT_READ_POLL_MS = 20L

        // Recommended idleRebuildMs for an always-listening consumer: ~20× FRAME_MS, so healthy continuous
        // capture never idles this long — only a stolen/half-dead mic does.
        const val DEFAULT_IDLE_REBUILD_MS = 2_000L

        /**
         * The silence window an always-listening consumer uses: this much audio of exact-zero samples ends capture
         * as silenced. Equal to [DEFAULT_IDLE_REBUILD_MS], so no frames and only zeros both end within the same time.
         * A consumer that paces retries reads it to know how long a silenced retry runs before it can fail.
         */
        const val DEFAULT_SILENCE_WINDOW_MS = DEFAULT_IDLE_REBUILD_MS

        private const val MS_PER_SECOND = 1_000L
        private const val FRAME_SAMPLES = PcmCaptureFormat.FRAME_BYTES / PcmCaptureFormat.BYTES_PER_SAMPLE
        private const val ZERO: Byte = 0
    }
}
