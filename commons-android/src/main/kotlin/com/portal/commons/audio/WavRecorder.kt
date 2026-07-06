package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal WAV writer for dumping the raw captured PCM to disk for inspection (diagnostic/bench only —
 * there is no production use). Writes a 44-byte canonical RIFF/WAVE header for
 * [PcmCaptureFormat] (16 kHz / mono / 16-bit LE) then the raw frame bytes as-is; [close] back-patches the
 * two size fields. Not thread-safe — call [write]/[close] from the single capture thread.
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
        h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt(36 + dataLen)
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII)); h.putInt(16); h.putShort(1) // PCM
        h.putShort(ch.toShort()); h.putInt(sr); h.putInt(byteRate)
        h.putShort(blockAlign.toShort()); h.putShort(bits.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(dataLen)
        return h.array()
    }
}
