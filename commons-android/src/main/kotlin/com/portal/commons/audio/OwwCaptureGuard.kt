package com.portal.commons.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight capture-thread inference and defers ONNX classifier session close until the capture
 * thread is idle — prevents use-after-close when [OpenWakeWordDetector] hot-swaps classifiers mid-[accept]
 * or tears down shared mel/embedding sessions in [OpenWakeWordDetector.close].
 */
internal class OwwCaptureGuard(private val modelThread: OwwModelThread) {

    private val captureInFlight = AtomicInteger(0)
    private val idleLock = Object()
    private val retired = mutableListOf<OpenWakeWordDetector.LoadedClassifierHandle>()

    fun enterCapture(): Int = captureInFlight.incrementAndGet()

    fun exitCapture() {
        if (captureInFlight.decrementAndGet() == 0) {
            modelThread.submit(::closeRetiredOnModelThread)
            synchronized(idleLock) { idleLock.notifyAll() }
        }
    }

    /**
     * Block until no capture-thread [accept] is in flight, or [timeoutMs] elapses.
     * Used by [OpenWakeWordDetector.close] before tearing down OrtSessions.
     * @return true if idle, false on timeout
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(idleLock) {
            while (captureInFlight.get() > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                idleLock.wait(remaining)
            }
        }
        return true
    }

    /** Queue [classifiers] for close on the model thread once capture is idle. Model thread only. */
    fun retireOnModelThread(classifiers: List<OpenWakeWordDetector.LoadedClassifierHandle>) {
        retired.addAll(classifiers)
        if (captureInFlight.get() == 0) {
            closeRetiredOnModelThread()
        }
    }

    fun inFlightCount(): Int = captureInFlight.get()

    fun pendingRetiredCount(): Int = retired.size

    /** Close all retired sessions immediately — only when capture has stopped. Model thread only. */
    fun forceCloseRetiredOnModelThread() {
        closeRetiredOnModelThread()
    }

    private fun closeRetiredOnModelThread() {
        val toClose = retired.toList()
        retired.clear()
        for (handle in toClose) {
            handle.close()
        }
    }
}
