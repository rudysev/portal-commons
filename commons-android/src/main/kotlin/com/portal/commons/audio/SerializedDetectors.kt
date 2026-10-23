package com.portal.commons.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The [WakeMicEngine]'s detectors behind one lock, so no two threads are ever inside a detector at once, and their
 * close.
 *
 * Closing is never done under a thread that is inside a detector call. A [close] from inside one (the capture thread,
 * e.g. from the wake callback) only marks the detectors "close pending"; the call that holds the lock closes them as
 * it leaves, and no detector call starts after that.
 */
internal class SerializedDetectors(private val detectors: List<WakeDetector>, private val log: (String) -> Unit) {
    // Serializes every detector call. A capture thread that overran pause()'s stop join can still be inside a
    // detector when start() rebuilds the session, and a Vosk recognizer run from two threads aborts the process.
    private val lock = ReentrantLock()

    @Volatile private var closePending = false

    // Guarded by lock.
    private var closed = false

    /** Runs [block] on the detectors under the lock; a no-op once a close is requested. */
    fun use(block: (List<WakeDetector>) -> Unit) {
        lock.lock()
        try {
            if (!closePending) block(detectors)
        } finally {
            lock.unlock()
        }
        // Checked after unlocking: a close() that gave up waiting set the flag before it began to wait, so either it
        // got the lock or this sees the flag.
        if (closePending && lock.tryLock()) closeAndUnlock()
    }

    /**
     * Closes the detectors, waiting at most [timeoutMs] for a thread inside one to leave it. Called from inside
     * [use], or when the wait runs out, it returns at once and the thread inside closes them as it leaves; a wait that
     * runs out is logged loudly, since a detector call that never returns leaks the detectors. Idempotent.
     */
    fun close(timeoutMs: Long) {
        closePending = true
        if (lock.isHeldByCurrentThread) return
        val acquired = try {
            lock.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            log("🔴 wake detector close deferred — a detector call is still running; it closes them when it returns")
            return
        }
        closeAndUnlock()
    }

    private fun closeAndUnlock() {
        try {
            if (!closed) {
                closed = true
                detectors.forEach { it.close() }
            }
        } finally {
            lock.unlock()
        }
    }
}
