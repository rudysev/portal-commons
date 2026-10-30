package com.portal.commons.audio

import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The [WakeAudit] that writes each clip to [dir] as a WAV named `<kind>_<word>_<epochMs>.wav`, e.g.
 * `wake_jarvis_1793330671218.wav`. The epoch milliseconds are taken when [save] is called, within a few ms of the
 * `debug.txt` line for the same decision (which carries the same clock), so a clip joins its decode by timestamp.
 *
 * **Off the capture thread.** [save] only timestamps the snapshot and offers it to a bounded queue drained by one
 * daemon thread, the same shape as [DebugLog]. Under a flood faster than the disk drains, clips are dropped rather
 * than blocking capture (logged once). Each file is written as `.part` and renamed, so a pull never sees half a WAV.
 *
 * **Capped per kind.** Near-misses arrive every minute or two and wakes far less often, so one shared cap would let a
 * talkative day crowd out the wake clips that settle a question. Counts are seeded from [dir], so a restart resumes
 * the cap; a kind at its cap is recounted before refusing, so clips pulled off the device free their room.
 */
class WakeClipRecorder(
    private val dir: File,
    private val capPerKind: Int = DEFAULT_CAP_PER_KIND,
    private val log: (String) -> Unit = DebugLog::log,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : WakeAudit {

    private sealed interface Cmd

    private class Clip(val kind: WakeAudit.Clip, val word: String, val atMs: Long, val pcm: ByteArray) : Cmd

    private class Barrier(val done: CountDownLatch) : Cmd

    private val queue = ArrayBlockingQueue<Cmd>(QUEUE_CAPACITY)

    // Capture-thread only: one line when the queue first overflows, not one per dropped clip.
    private var dropLogged = false

    // Writer-thread state: only runWriter touches these.
    private val counts = HashMap<WakeAudit.Clip, Int>()
    private val capLogged = HashSet<WakeAudit.Clip>()

    private val writer = Thread(::runWriter, "wake-clips").apply {
        isDaemon = true // diagnostics: never holds the process up
        start()
    }

    /** Queue the clip for writing. Capture thread; no I/O, no lock. */
    override fun save(clip: WakeAudit.Clip, word: String, pcm: ByteArray) {
        if (queue.offer(Clip(clip, word, wallClock(), pcm))) return
        if (!dropLogged) {
            dropLogged = true
            log("wake clips: queue full, dropping clips (disk slower than capture)")
        }
    }

    /** Stop the writer. Pending clips are abandoned: this is teardown, not a flush. */
    fun close() = writer.interrupt()

    /** Block until every clip queued before this call is written. **Tests only**: capture never waits on the disk. */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = CountDownLatch(1)
        check(queue.offer(Barrier(done))) { "queue full" }
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "clip writer did not drain in ${timeoutMs}ms" }
    }

    private fun runWriter() {
        WakeAudit.Clip.entries.forEach(::recount)
        while (true) {
            // The interrupt is close(): leaving the loop is the handling.
            val cmd = try {
                queue.take()
            } catch (
                @Suppress("SwallowedException")
                _: InterruptedException,
            ) {
                return
            }
            when (cmd) {
                is Barrier -> cmd.done.countDown()
                is Clip -> write(cmd)
            }
        }
    }

    /** Count [kind]'s clips in [dir], so the cap bounds the directory rather than one service lifetime. */
    private fun recount(kind: WakeAudit.Clip) {
        runCatching {
            dir.mkdirs()
            val names = dir.list().orEmpty()
            counts[kind] = names.count { it.startsWith("${kind.prefix}_") && it.endsWith(".wav") }
        }.onFailure { log("wake clips: could not read $dir (${it.message})") }
    }

    private fun write(c: Clip) {
        if ((counts[c.kind] ?: 0) >= capPerKind) recount(c.kind)
        if ((counts[c.kind] ?: 0) >= capPerKind) {
            if (capLogged.add(c.kind)) log("wake clips: cap $capPerKind reached for '${c.kind.prefix}', skipping")
            return
        }
        capLogged.remove(c.kind)
        val name = "${c.kind.prefix}_${c.word}_${c.atMs}.wav"
        runCatching {
            val part = File(dir, "$name.part")
            part.writeBytes(wavHeader(c.pcm.size) + c.pcm)
            check(part.renameTo(File(dir, name))) { "rename failed" }
            counts[c.kind] = (counts[c.kind] ?: 0) + 1
        }.onFailure { log("wake clips: save $name failed (${it.message})") }
    }

    companion object {
        /** Clips kept per kind: at ~160 KB each (5 s), both kinds full cost ~320 MB. Bounded, not small. */
        const val DEFAULT_CAP_PER_KIND = 1_000

        // Pending clips before save() drops: far more than the decoder finalizes in the time one write takes.
        private const val QUEUE_CAPACITY = 16

        private const val WAV_HEADER_BYTES = 44
        private const val RIFF_SIZE_OFFSET = 36 // RIFF chunk size = header after the size field + data
        private const val FMT_CHUNK_BYTES = 16
        private const val PCM_FORMAT: Short = 1
        private const val BITS_PER_BYTE = 8

        /** The canonical 44-byte RIFF/WAVE header for [dataBytes] of [PcmCaptureFormat] audio. */
        internal fun wavHeader(dataBytes: Int): ByteArray {
            val blockAlign = PcmCaptureFormat.CHANNELS * PcmCaptureFormat.BYTES_PER_SAMPLE
            return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(RIFF_SIZE_OFFSET + dataBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(FMT_CHUNK_BYTES)
                putShort(PCM_FORMAT)
                putShort(PcmCaptureFormat.CHANNELS.toShort())
                putInt(PcmCaptureFormat.SAMPLE_RATE)
                putInt(PcmCaptureFormat.SAMPLE_RATE * blockAlign)
                putShort(blockAlign.toShort())
                putShort((PcmCaptureFormat.BYTES_PER_SAMPLE * BITS_PER_BYTE).toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
            }.array()
        }
    }
}
