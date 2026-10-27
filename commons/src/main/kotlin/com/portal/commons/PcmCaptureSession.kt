package com.portal.commons

import com.portal.commons.audio.CaptureLoss
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns one capture behind [PcmCapture]: a single capture thread that opens a
 * [PcmDevice], carries out its [CaptureLoop]'s actions (non-blocking reads, frames, rebuilds), and tears down
 * **without ever hanging**. The Android `AudioRecord` lives behind [PcmDevice], so this concurrency
 * contract is pure JVM and unit-tested with a fake device.
 *
 * [PcmCapture] runs one of these per capture request and meets the duties this session leaves its caller: binding
 * callbacks to the current request, serializing a wedged older session against a newer one, and closing the consumer
 * only when no callback is running.
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
 * poll interval and exits on its own: it checks `running` before carrying out every action, so a stop that lands
 * during a frame's callback carries out neither the read, rebuild nor loss that follows it. [stop] sets the flag and
 * joins within a bounded [STOP_DEADLINE_MS]. On the normal path the loop's `finally` releases the device, so the
 * device is touched only on the capture thread.
 * If the thread doesn't exit in time (it overran the join inside a callback, or a native call hung), [stop]
 * force-releases the device as a last resort (on a helper thread, so [stop] stays bounded) so the mic slot can't
 * stay held. The wedged thread, once it unblocks, sees `running == false` and exits delivering no further frame and
 * no [onLost]. If it overran the join *inside* a callback, it is still running that callback when [stop] returns, which
 * is why [PcmCapture] serializes every callback under one lock.
 *
 * **Single-use.** A session runs at most one capture: a second [start], or a [start] after [stop], is a logged
 * no-op. To capture again, build a fresh session.
 *
 * **Rebuild and silence.** [rebuildAfterReadFailures], [idleRebuildMs], [silenceWindowMs] and [rebuildOnSilence] are
 * the capture policies; [CaptureLoop] holds the rules they set, and this session carries out the rebuilds and losses
 * those rules decide. A rebuild is `stop` → `release` → `open` of the device.
 */
// The constructor is the whole configuration surface: the parameters after device/onFrame are optional callbacks or
// policies with a default, and PcmCapture passes them by name. A parameter object would only move the same list.
@Suppress("LongParameterList")
internal class PcmCaptureSession(
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
    // Test timing, not policy.
    private val stopTimeoutMs: Long = STOP_DEADLINE_MS,
) {
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

    private fun loop() {
        if (!runCatching { device.open() }.getOrDefault(false)) {
            log("pcm open failed")
            end(CaptureLoss.NotGranted("microphone unavailable"))
            return
        }
        // Built before onStarted(), so the idle clock starts when the device opened.
        val capture = CaptureLoop(
            rebuildAfterReadFailures = rebuildAfterReadFailures,
            idleRebuildMs = idleRebuildMs,
            silenceWindowMs = silenceWindowMs,
            rebuildOnSilence = rebuildOnSilence,
            openedAtNs = System.nanoTime(),
        )
        log("pcm capture started")
        var loss: CaptureLoss? = null
        try {
            // A stop() that landed while open() was in flight must not start the consumer either: on gen2 a stopped
            // session's onStarted ran Vosk's reset + warm-up past the stop join, alongside the fresh session.
            if (running) onStarted()
            loss = run(capture)
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
     * Carries out [capture]'s actions until [stop] or a capture loss, and returns that loss (null for a stop).
     * `running` is checked before every action, so a stopping session delivers no further callback (otherwise this
     * old thread's onFrame could run alongside a fresh session's, racing a non-thread-safe consumer such as Vosk),
     * and reopens no device that is about to be torn down.
     */
    private fun run(capture: CaptureLoop): CaptureLoss? {
        val scratch = ByteArray(PcmCaptureFormat.FRAME_BYTES)
        var action = capture.first()
        while (running) {
            action = when (val now = action) {
                is CaptureLoop.Action.Read -> if (now.afterMs > 0) {
                    Thread.sleep(now.afterMs)
                    now.copy(afterMs = 0) // checks running again before the read
                } else {
                    val n = device.read(scratch, 0, now.length)
                    capture.onRead(scratch, n, System.nanoTime())
                }

                is CaptureLoop.Action.Deliver -> {
                    onFrame(now.frame, now.frame.size)
                    now.then
                }

                is CaptureLoop.Action.Rebuild -> capture.onRebuilt(rebuildDevice(now.reason), System.nanoTime())

                is CaptureLoop.Action.End -> {
                    log("pcm ${now.loss.reason} → ending capture")
                    return now.loss
                }
            }
        }
        return null
    }

    /** Tears down and reopens the device. True if it reopened. */
    private fun rebuildDevice(reason: String): Boolean {
        log("pcm $reason → rebuilding device")
        runCatching { device.stop() }
        runCatching { device.release() }
        return runCatching { device.open() }.getOrDefault(false)
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

    companion object {
        /**
         * The one bound on stopping capture: [stop]'s whole wait (join plus force-release), and the
         * shared deadline of [PcmCapture.close]'s waits (not of the `onClosed` that follows them).
         */
        const val STOP_DEADLINE_MS = 400L

        /** The part of [stop]'s deadline left for a force-release once the join gives up (at most half of it). */
        const val FORCE_RELEASE_GRACE_MS = 50L

        // Recommended idleRebuildMs for an always-listening consumer: ~20× FRAME_MS, so healthy continuous
        // capture never idles this long — only a stolen/half-dead mic does.
        const val DEFAULT_IDLE_REBUILD_MS = 2_000L

        /**
         * The silence window an always-listening consumer uses: this much audio of exact-zero samples ends capture
         * as silenced. Equal to [DEFAULT_IDLE_REBUILD_MS], so no frames and only zeros both end within the same time.
         * A consumer that paces retries reads it to know how long a silenced retry runs before it can fail.
         */
        const val DEFAULT_SILENCE_WINDOW_MS = DEFAULT_IDLE_REBUILD_MS
    }
}
