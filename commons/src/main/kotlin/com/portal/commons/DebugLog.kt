package com.portal.commons

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * File-based debug log shared by the Portal apps.
 *
 * The Portal restricts `logcat` for non-root shells (it returns almost nothing), so diagnostics are
 * appended to a file under the app's external files dir, which can be read with:
 *
 *   hzdb adb shell "cat /sdcard/Android/data/<your.package>/files/debug.txt"
 *
 * Set [file] once at startup (typically in your service's `onCreate`). Until [file] is set, [log] is a
 * no-op.
 *
 * **Off the caller's thread.** All disk work — write, flush, rotation — happens on one private daemon
 * thread that exclusively owns the writer. [log] only timestamps the message and hands it to a bounded
 * queue: it takes no lock, performs no filesystem `stat`, and never blocks on I/O, so logging is safe
 * from a real-time audio capture thread and a logging failure can never take down audio. Under a sustained
 * flood faster than the disk can drain, surplus lines are dropped rather than blocking the caller
 * (best-effort). Because the writer thread does the only I/O, the write path needs no lock at all.
 *
 * Writes go through a single append-mode buffered writer (no open/close per line) and are flushed in
 * batches — once a burst has drained — instead of once per line. When the active file exceeds
 * [DEFAULT_MAX_FILE_BYTES] (tracked with an in-memory byte counter, not a per-line `stat`), it is renamed
 * to `<name>.1` and a fresh file is started. Call [close] on teardown to flush and release the handle.
 */
object DebugLog {

    private sealed interface Cmd
    private class WriteLine(val line: String) : Cmd
    private class SwitchFile(val file: File?) : Cmd
    private class Barrier(val done: CountDownLatch) : Cmd

    private val queue = LinkedBlockingQueue<Cmd>(MAX_QUEUE)
    private val workerLock = Any()

    @Volatile private var worker: Thread? = null

    /** Active log size (bytes) before rotating to `<name>.1`. Tests may lower this; production uses [DEFAULT_MAX_FILE_BYTES]. */
    @Volatile
    internal var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
        set(value) {
            require(value > 0) { "maxFileBytes must be positive, was $value" }
            field = value
        }

    @Volatile
    var file: File? = null
        // Synchronized so (field write + SwitchFile enqueue) is atomic: concurrent switches can't end with
        // the worker on one file while the public getter reads another, nor reorder the SwitchFile commands.
        set(value) = synchronized(workerLock) {
            if (field == value) return@synchronized
            field = value
            ensureWorker()
            offerControl(SwitchFile(value))
        }

    fun log(message: String) {
        if (file == null) return // unconfigured: cheap no-op, don't grow the queue
        // Keep CONTROL_HEADROOM slots free so a log flood can never crowd out a control command: dropping a
        // line under sustained load is fine, dropping a close()/file-switch (handle leak) is not. Never blocks.
        if (queue.remainingCapacity() <= CONTROL_HEADROOM) return
        queue.offer(WriteLine("${System.currentTimeMillis()} $message"))
    }

    /**
     * Release the active writer/file handle. Call from a service's `onDestroy` so the handle is freed instead
     * of leaking until process death. Best-effort and never throws.
     *
     * **Asynchronous:** like [log], this only enqueues — the worker thread performs the actual flush + close
     * shortly after. A caller that deletes or renames the log file immediately after [close] may race the
     * still-pending close; if you need the file fully settled first, drain via [awaitIdle].
     *
     * Resets to the unset state: after [close], [file] reads null and [log] is a no-op until [file] is set
     * again (assigning any path, including the previous one, re-opens the writer). Idempotent.
     */
    fun close() {
        file = null // enqueues a SwitchFile(null); the worker flushes and closes the active writer
    }

    /**
     * Test-only barrier: block until the worker has drained every command queued so far and flushed the
     * writer, so a test can read the log file deterministically despite the async write path. Returns false
     * if the worker isn't draining within [timeoutMs].
     */
    internal fun awaitIdle(timeoutMs: Long = 2_000): Boolean {
        worker ?: return true
        val latch = CountDownLatch(1)
        if (!offerControl(Barrier(latch))) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Enqueue a control command (SwitchFile/Barrier) into the headroom [log] keeps free; non-blocking. */
    private fun offerControl(cmd: Cmd): Boolean = queue.offer(cmd)

    private fun ensureWorker() {
        if (worker != null) return
        synchronized(workerLock) {
            if (worker != null) return
            worker = Thread(::runLoop, "debug-log").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** The one thread that touches the writer — so the write path takes no lock and the caller never blocks on I/O. */
    private fun runLoop() {
        var writer: BufferedWriter? = null
        var current: File? = null
        var bytes = 0L
        while (true) {
            when (val cmd = runCatching { queue.take() }.getOrNull() ?: continue) {
                is SwitchFile -> {
                    runCatching { writer?.close() }
                    writer = null
                    current = cmd.file
                    bytes = 0
                    val f = current
                    if (f != null) {
                        runCatching {
                            bytes = f.length() // one stat at open time, not per line
                            writer = openWriter(f)
                        }
                    }
                }

                is WriteLine -> runCatching {
                    val f = current ?: return@runCatching
                    var out = writer ?: return@runCatching
                    if (bytes > maxFileBytes) {
                        val (rotatedWriter, rotatedBytes) = rotate(out, f)
                        writer = rotatedWriter
                        bytes = rotatedBytes
                        // Reopen failed: stay in the clean "not ready" state (writer == null) rather than
                        // holding the closed pre-rotation writer; a later SwitchFile re-opens.
                        out = rotatedWriter ?: return@runCatching
                    }
                    out.write(cmd.line)
                    out.newLine()
                    bytes += cmd.line.toByteArray().size + 1 // UTF-8 byte length, consistent with the f.length() seed
                    if (queue.isEmpty()) out.flush() // batch: flush once the burst has drained
                }

                is Barrier -> {
                    runCatching { writer?.flush() }
                    cmd.done.countDown()
                }
            }
        }
    }

    private fun openWriter(f: File): BufferedWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true))) // append = true: never truncate on (re)open

    /**
     * Close [w], rename [f] to `<name>.1`, and reopen a fresh [f]. Returns the new writer and its byte count,
     * or `(null, 0)` if the reopen fails — the caller then drops to the clean "not ready" state instead of
     * holding the closed pre-rotation writer.
     */
    private fun rotate(w: BufferedWriter, f: File): Pair<BufferedWriter?, Long> {
        runCatching { w.close() }
        val rotated = File(f.parentFile, "${f.name}.1")
        rotated.delete()
        val renamed = f.renameTo(rotated)
        val fresh = runCatching { openWriter(f) }.getOrNull() ?: return null to 0L
        // On a rename failure the original file stays in place (and still oversized); reflect its real size
        // so we don't reset the counter and silently let it grow unbounded.
        return fresh to if (renamed) 0L else f.length()
    }

    const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_QUEUE = 1024
    private const val CONTROL_HEADROOM = 16 // queue slots kept free for control commands so a log flood can't starve them
}
