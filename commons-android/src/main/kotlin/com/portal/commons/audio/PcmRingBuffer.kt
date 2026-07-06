package com.portal.commons.audio

/**
 * Fixed-capacity FIFO of recent PCM frames. Used to retain speech spoken while the wake models are still
 * loading so it can be fed to the recognizer once ready (instead of being discarded).
 */
internal class PcmRingBuffer(private val maxFrames: Int) {

    private val frames = ArrayDeque<ByteArray>()

    /** Append a frame (copied). Drops the oldest frame when at capacity. */
    @Synchronized
    fun add(data: ByteArray, n: Int) {
        val frame = if (n == data.size) data.copyOf() else data.copyOf(n)
        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** Remove and return all buffered frames in insertion order. */
    @Synchronized
    fun drain(): List<ByteArray> {
        if (frames.isEmpty()) return emptyList()
        val copy = frames.toList()
        frames.clear()
        return copy
    }

    /** Discard all buffered frames without returning them (e.g. on (re)start, so pre-pause audio can't linger). */
    @Synchronized
    fun clear() = frames.clear()

    @Synchronized
    fun isEmpty(): Boolean = frames.isEmpty()
}
