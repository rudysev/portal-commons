package com.portal.commons.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Lets a wake reach the consumer only while the [WakeMicEngine] is open, and lets [close] wait for one already on its
 * way, so no wake delivery begins once [close] has returned.
 *
 * [deliver] holds a read lock from its open check until the consumer call returns; [close] marks the gate shut, then
 * takes the write lock, so a wake that gets in after that sees it shut.
 */
internal class WakeGate {
    private val lock = ReentrantReadWriteLock()

    @Volatile private var shut = false

    /** Runs [onWake] unless the gate is shut, in which case it runs [onDropped]. */
    fun deliver(onWake: () -> Unit, onDropped: () -> Unit) {
        val read = lock.readLock()
        // A failed tryLock means close() holds the gate, so it is already shut.
        val entered = read.tryLock()
        try {
            if (entered && !shut) onWake() else onDropped()
        } finally {
            if (entered) read.unlock()
        }
    }

    /**
     * Shuts the gate and waits at most [timeoutMs] for a wake already past the check to finish. Skipped from inside
     * [deliver] (that wake is the one in flight). One that outlasts the wait was already being delivered before this
     * returned; none begins after.
     */
    fun close(timeoutMs: Long) {
        shut = true
        if (lock.readHoldCount > 0) return
        val write = lock.writeLock()
        val acquired = try {
            write.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (acquired) write.unlock()
    }
}
