package com.portal.commons.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Single-thread executor for all openWakeWord ONNX session lifecycle work (initial load, hot-swap, teardown).
 * Capture-thread inference only reads volatile snapshots produced here — it never opens or closes sessions.
 */
internal class OwwModelThread(private val threadName: String = "oww-model") {

    private val queue = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var running = true

    private val thread = Thread({
        while (running) {
            try {
                val task = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                task()
            } catch (_: InterruptedException) {
                if (!running) break
            }
        }
    }, threadName)

    init {
        thread.start()
    }

    fun submit(task: () -> Unit) {
        if (!running) return
        queue.put(task)
    }

    /** Run [task] on the model thread and block the caller until it finishes or [timeoutMs] elapses. */
    fun submitAndJoin(timeoutMs: Long, task: () -> Unit) {
        if (!running) return
        val latch = CountDownLatch(1)
        queue.put {
            try {
                task()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown(joinMs: Long) {
        running = false
        thread.interrupt()
        thread.join(joinMs)
    }
}
