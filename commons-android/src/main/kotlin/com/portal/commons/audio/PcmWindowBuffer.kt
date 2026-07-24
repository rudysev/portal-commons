package com.portal.commons.audio

/**
 * A rolling window of the most recent raw PCM samples — what [TwoStageWakeDetector] hands to stage 2 when
 * stage 1 flags a candidate.
 *
 * Distinct from [PcmRingBuffer], which retains whole *frames* to replay later (speech captured while a
 * model loads). This one keeps a fixed span of *samples* and is only ever read as "the last N samples,
 * oldest first" — the same 2 s that openWakeWord's classifier window covers, so stage 2 re-decodes exactly
 * the audio stage 1 scored.
 *
 * Decodes little-endian signed 16-bit PCM straight into the ring: no intermediate `ShortArray` per frame,
 * because this runs on the capture thread for every frame whether or not a candidate ever appears.
 *
 * NOT thread-safe, by contract: written and read on the engine's capture thread only (see [WakeDetector]).
 */
internal class PcmWindowBuffer(val capacitySamples: Int) {

    private val ring = ShortArray(capacitySamples)
    private var writePos = 0
    private var filled = 0

    /** True once a full window has been captured — before that there is nothing meaningful to verify. */
    val isFull: Boolean get() = filled >= capacitySamples

    /** Append [n] bytes of little-endian s16 PCM from [buf]. A partial trailing byte is ignored. */
    fun add(buf: ByteArray, n: Int) {
        var i = 0
        val limit = n - 1 // need two bytes per sample
        while (i < limit) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt() // signed: carries the sign bit
            ring[writePos] = ((hi shl 8) or lo).toShort()
            writePos = (writePos + 1) % capacitySamples
            if (filled < capacitySamples) filled++
            i += 2
        }
    }

    /**
     * The window, oldest sample first, newest last. Returns a fresh array each call — stage 2 hands it to
     * native code, so it must not alias the ring that the capture thread keeps writing.
     *
     * Before the ring has filled, returns only the samples actually captured (so an early candidate is
     * verified against real audio rather than leading zeros).
     */
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        // Oldest retained sample: the write cursor once wrapped, else index 0.
        val start = if (isFull) writePos else 0
        for (i in 0 until filled) out[i] = ring[(start + i) % capacitySamples]
        return out
    }

    /** Drop everything, so audio from before a pause can't leak into the next session's verification. */
    fun clear() {
        writePos = 0
        filled = 0
    }
}
