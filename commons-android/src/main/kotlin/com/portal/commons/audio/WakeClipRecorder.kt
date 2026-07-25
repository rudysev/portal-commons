package com.portal.commons.audio

import com.portal.commons.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The [WakeAudit] that writes clips to disk as WAVs — one file per decision, named so a directory listing
 * is already a summary:
 *
 * ```
 * wake_jarvis_0724-153012_p047.wav     the cascade fired
 * rej_jarvis_0724-153355_p046.wav      stage 1 proposed, stage 2 said no
 * near_jarvis_0724-153401_p028.wav     stage 1 scored just under its threshold
 * ```
 *
 * `p` is the stage-1 score ×100, so the on-device score travels with the audio and can be reproduced
 * offline (`hey-jarvis/scripts/score_clips.py`).
 *
 * **Off the capture thread, deliberately.** [save] only timestamps the snapshot and hands it to a bounded
 * queue drained by one private daemon thread — the same shape as [DebugLog], and for a stronger reason
 * here. A clip is ~64 KB, and a `rej` clip is written immediately after a ~900 ms stage-2 decode that is
 * already spending the capture thread's [TwoStageTuning.VERIFY_BUDGET_MS] budget. Writing inline would both
 * risk dropping audio and inflate the very decode timings the budget line exists to measure. Under a flood
 * faster than the disk can drain, clips are dropped rather than blocking capture (logged once).
 *
 * **Capped per kind, not in total.** Near-misses are rate-limited to two per second per phrase and wakes
 * are far rarer, so a single shared cap would let a talkative afternoon of `near` clips crowd out the
 * `wake` and `rej` clips that actually settle a question. Each kind gets its own budget. Counts are
 * **seeded from the directory at startup**, so a service restart resumes the cap instead of resetting it —
 * a day-long run survives several restarts and must not fill the device.
 */
class WakeClipRecorder @JvmOverloads constructor(
    private val dir: File,
    private val capPerKind: Int = DEFAULT_CAP_PER_KIND,
    private val log: (String) -> Unit = { DebugLog.log(it) },
) : WakeAudit {

    private sealed interface Cmd
    private class Clip(val kind: WakeAudit.Clip, val wakeId: String, val score: Float, val atMs: Long, val pcm: ShortArray) : Cmd
    private class Barrier(val done: CountDownLatch) : Cmd

    private val queue = ArrayBlockingQueue<Cmd>(QUEUE_CAPACITY)

    /** Capture-thread only: one line when the queue first overflows, never a line per dropped clip. */
    private var dropLogged = false

    // ---- writer-thread-owned state (no locks: only runWriter touches these) ------------------------------

    private val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US)
    private val counts = HashMap<WakeAudit.Clip, Int>()
    private val capLogged = HashSet<WakeAudit.Clip>()

    private val writer = Thread({ runWriter() }, "wake-clips").apply {
        isDaemon = true // must never hold the process up: this is diagnostics, not work
        start()
    }

    /**
     * Queue the window for writing. Called on the capture thread; does no I/O and takes no lock.
     *
     * The timestamp is taken **here**, not at write time, so a backed-up queue produces filenames that
     * still line up with the `debug.txt` line for the same event.
     */
    override fun save(clip: WakeAudit.Clip, wakeId: String, score: Float, pcm: ShortArray) {
        if (queue.offer(Clip(clip, wakeId, score, System.currentTimeMillis(), pcm))) return
        if (!dropLogged) {
            dropLogged = true
            log("audit clips: queue full — dropping clips (disk slower than capture)")
        }
    }

    /** Stop the writer. Pending clips are abandoned — this is teardown, not a flush. */
    fun close() {
        writer.interrupt()
    }

    /**
     * Block until every clip queued before this call has been written. **Tests only** — the capture thread
     * must never wait on the disk.
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = CountDownLatch(1)
        if (!queue.offer(Barrier(done))) throw IllegalStateException("queue full")
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "clip writer did not drain in ${timeoutMs}ms" }
    }

    // ---- the writer thread -------------------------------------------------------------------------------

    private fun runWriter() {
        seedCountsFromDisk()
        while (true) {
            val cmd = try {
                queue.take()
            } catch (_: InterruptedException) {
                return // close()
            }
            when (cmd) {
                is Barrier -> cmd.done.countDown()
                is Clip -> write(cmd)
            }
        }
    }

    /**
     * Resume the per-kind counts from whatever a previous run left behind, so the cap bounds the
     * *directory* rather than one service lifetime. Runs on the writer thread — the directory can hold
     * thousands of entries and this must not sit on the caller's thread.
     */
    private fun seedCountsFromDisk() {
        runCatching {
            dir.mkdirs()
            val existing = dir.list() ?: return@runCatching
            for (kind in WakeAudit.Clip.entries) {
                counts[kind] = existing.count { it.startsWith("${kind.prefix}_") && it.endsWith(".wav") }
            }
        }.onFailure { log("audit clips: could not read $dir (${it.message})") }
    }

    private fun write(c: Clip) {
        val n = counts[c.kind] ?: 0
        if (n >= capPerKind) {
            if (capLogged.add(c.kind)) log("audit clips: cap $capPerKind reached for '${c.kind.prefix}' — no more of that kind")
            return
        }
        try {
            // Score is clamped, not wrapped: a stage 1 that ever reports outside [0, 1] must still produce a
            // parseable name rather than a negative or 4-digit field.
            val p = (c.score * 100).toInt().coerceIn(0, 999)
            val name = "%s_%s_%s_p%03d.wav".format(c.kind.prefix, c.wakeId, stamp.format(Date(c.atMs)), p)
            WavRecorder.write(nonClashing(name), c.pcm)
            counts[c.kind] = n + 1
        } catch (e: Exception) {
            log("audit clips: save failed (${e.message})")
        }
    }

    /**
     * The timestamp is second-resolution, and near-misses can arrive twice a second, so two clips of the
     * same kind can collide. Suffix rather than overwrite — the second one is as much evidence as the first.
     */
    private fun nonClashing(name: String): File {
        val f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.removeSuffix(".wav")
        for (i in 1..MAX_NAME_CLASH) {
            val alt = File(dir, "$stem-$i.wav")
            if (!alt.exists()) return alt
        }
        return f // give up and overwrite: MAX_NAME_CLASH in one second is not a real case
    }

    companion object {
        /**
         * Clips kept per kind. At ~64 KB each (2 s of 16 kHz mono s16) three full kinds cost ~190 MB, which
         * a multi-day run on a Portal can afford; the point of the cap is that it cannot grow without
         * bound, not that it is small.
         */
        const val DEFAULT_CAP_PER_KIND = 1_000

        /** Pending clips (~64 KB each) before [save] starts dropping. One second of worst-case near-misses. */
        private const val QUEUE_CAPACITY = 16

        private const val MAX_NAME_CLASH = 9
    }
}
