package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal WAV writer for dumping the raw captured PCM to disk for inspection. Writes a 44-byte canonical
 * RIFF/WAVE header for [PcmCaptureFormat] (16 kHz / mono / 16-bit LE) then the raw frame bytes as-is;
 * [close] back-patches the two size fields.
 *
 * Two forms:
 *  - **streaming** — construct, [write] frames as they arrive, [close]. Used by benches that dump a whole
 *    capture session.
 *  - **one-shot** — [Companion.write], for a window already held in memory. This is what
 *    [WakeClipRecorder] uses to persist the 2 s window behind a wake or a near-miss.
 *
 * Not thread-safe: the streaming form must be driven from one thread (the capture thread), and the
 * one-shot form owns its file for the duration of the call.
 */
class WavRecorder(file: File) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0

    init {
        raf.setLength(0)
        raf.write(header(0)) // placeholder sizes, patched in close()
    }

    /** Append the first [n] bytes of [buf] (16-bit LE PCM) to the data chunk. */
    fun write(buf: ByteArray, n: Int) {
        raf.write(buf, 0, n)
        dataBytes += n
    }

    /** Back-patch the RIFF/data sizes and close. Safe to call once. */
    fun close() {
        runCatching {
            raf.seek(0)
            raf.write(header(dataBytes))
        }
        runCatching { raf.close() }
    }

    private fun header(dataLen: Int): ByteArray {
        val sr = PcmCaptureFormat.SAMPLE_RATE
        val ch = PcmCaptureFormat.CHANNELS
        val bits = PcmCaptureFormat.BYTES_PER_SAMPLE * 8
        val byteRate = sr * ch * PcmCaptureFormat.BYTES_PER_SAMPLE
        val blockAlign = ch * PcmCaptureFormat.BYTES_PER_SAMPLE
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt(36 + dataLen)
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(ch.toShort())
        h.putInt(sr)
        h.putInt(byteRate)
        h.putShort(blockAlign.toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataLen)
        return h.array()
    }

    companion object {
        /**
         * Write [pcm] (16 kHz mono signed 16-bit samples, oldest first) to [file] as one complete WAV.
         *
         * The samples are serialised little-endian by hand rather than via a `ShortBuffer` view: this is
         * called with a [PcmWindowBuffer] snapshot, which is already a fresh array, and one explicit pass
         * avoids a second copy.
         */
        fun write(file: File, pcm: ShortArray) {
            val bytes = ByteArray(pcm.size * PcmCaptureFormat.BYTES_PER_SAMPLE)
            var i = 0
            for (s in pcm) {
                val v = s.toInt()
                bytes[i++] = (v and 0xFF).toByte()
                bytes[i++] = ((v shr 8) and 0xFF).toByte()
            }
            val w = WavRecorder(file)
            try {
                w.write(bytes, bytes.size)
            } finally {
                w.close() // back-patches the sizes; a partial write still leaves a playable file
            }
        }
    }
}
