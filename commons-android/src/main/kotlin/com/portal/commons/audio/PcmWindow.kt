package com.portal.commons.audio

/**
 * The most recent [capacityBytes] of PCM fed to it, in one preallocated circular array, so keeping the window costs
 * no allocation per frame on the capture thread. Single-threaded: owned by the wake decoder.
 */
internal class PcmWindow(private val capacityBytes: Int) {

    private val ring = ByteArray(capacityBytes)
    private var end = 0 // where the next byte goes
    private var size = 0

    /** Append the first [n] bytes of [buf], overwriting the oldest bytes once full. */
    fun add(buf: ByteArray, n: Int) {
        val skip = maxOf(0, n - capacityBytes) // a write longer than the window keeps only its tail
        var from = skip
        while (from < n) {
            val chunk = minOf(n - from, capacityBytes - end)
            System.arraycopy(buf, from, ring, end, chunk)
            end = (end + chunk) % capacityBytes
            from += chunk
        }
        size = minOf(capacityBytes, size + n - skip)
    }

    /** A fresh copy of the window, oldest byte first. */
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val start = (end - size + capacityBytes) % capacityBytes
        val first = minOf(size, capacityBytes - start)
        System.arraycopy(ring, start, out, 0, first)
        System.arraycopy(ring, 0, out, first, size - first)
        return out
    }

    fun clear() {
        end = 0
        size = 0
    }
}
